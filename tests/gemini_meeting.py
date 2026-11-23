"""经由 Harness ConsoleMain 与官方真实 Gemini Chat Worker 运行完整的会议改期端到端验收。

测试覆盖：原始会议通知与改期原因、跨轮复盘先后顺序、原安排与现安排溯源、提醒工具尝试（未确认）与
外部执行器回执（已确认）、渠道正文编辑与下游摘要失效，以及撤回最新通知后恢复到唯一仍有效原安排。
"""

import argparse
import json
import os
import re
import sqlite3
import time
import unicodedata
from pathlib import Path
from urllib.parse import urlsplit

from acceptance import (
    ROOT,
    ServicePair,
    apply_reminder,
    context,
    dump,
    event,
    meeting_source,
    props,
    query,
    ref,
    send,
    stop,
)


def previous_services(manifest):
    """解析 `--resume-demo` 指向的旧批次 demo.json，复用其本地端口、记忆账本与 Harness 审计目录。"""
    if manifest is None:
        return {}
    data = json.loads(manifest.read_text(encoding="utf-8"))
    memory, console = (urlsplit(data[name]) for name in ("memory", "console"))
    if any(u.hostname != "127.0.0.1" or u.scheme != "http" or not u.port for u in (memory, console)):
        raise ValueError("The demo must have local Memory and Harness endpoints.")
    ledger = Path(data.get("ledger", manifest.parent / "ledger")).resolve()
    audit = Path(data.get("audit", manifest.parent / "audit")).resolve()
    if not (ledger / "memory.sqlite").is_file() or not (audit / "memory-v2-misty-dev.sqlite").is_file():
        raise ValueError("The selected demo is missing its ledger or its Harness journal.")
    return dict(ports=(memory.port, console.port), ledger=ledger, audit_dir=audit)


class GeminiMeeting:
    """管理单次真实 Gemini 会议改期验收会话，逐轮核对审计链、SQLite 交付登记与回执证据。"""

    def __init__(self, pair, endpoint, timeout):
        self.pair = pair
        self.timeout = timeout
        self.chain = "gemini-meeting-" + pair.out.name
        self.scope = "console:" + self.chain
        self.events = []
        self.report = dict(
            status="RUNNING",
            chain=self.chain,
            provider_endpoint=endpoint,
            jar_sha256=pair.jar_hashes,
            turns=[],
            checks=[],
            receipts={},
        )

    def save(self):
        """将当前轮次报告与完整审计链落盘至本批次证据目录。"""
        dump(self.pair.out / "gemini-case.json", self.report)
        dump(self.pair.out / "meeting-audit.json", self.events)

    def turn(self, step, message, observation=None):
        """发送一轮控制台消息并完成端到端取证：
        校验本轮审计闭环（入站 -> 真实 Gemini 规划用量 -> 回复 -> 控制台写出）、回读 Memory 确认双向原文已持久化，
        并只读检查 Harness 本地 SQLite 中 `inputs`/`input_evidence` 的实际交付状态与版本绑定。
        """
        offset = len(self.events)
        answer = self.pair.msg(self.chain, message, observation, timeout=self.timeout)
        self.events = self.pair.audit(self.chain)
        turn = self.events[offset:]

        def only(kind):
            matches = [e for e in turn if e["type"] == kind]
            assert len(matches) == 1, (step, kind, len(matches))
            return matches[0]

        incoming, plan, reply, sent = (
            only(t)
            for t in (
                "operator_message",
                "chat_worker_turn_plan",
                "chat_worker_reply",
                "console_response_written",
            )
        )
        assert reply["data"]["text"] == answer and sent["causedBy"] == reply["eventId"]
        assert plan["data"]["usage"].get("promptTokenCount", 0) > 0, (step, "missing Gemini usage", plan)
        receipts = []
        for source in (incoming, sent):
            write = next(
                e
                for e in turn
                if e["type"] == "router_memory_write" and e["data"]["sourceEventId"] == source["eventId"]
            )
            durable = next(
                e["data"] for e in turn if e["type"] == "memory_durable" and e["causedBy"] == write["eventId"]
            )
            receipts.append(
                dict(record_id=durable["recordId"], revision=durable["revision"], seq=durable["sequence"])
            )
        stored = self.pair.api("read", dict(context=context(self.scope), targets=[ref(r) for r in receipts]))
        assert stored["stream_id"] == self.report["stream_id"]
        assert [(i["ref"], i["kind"], i["text"]) for i in stored["items"]] == [
            (ref(receipts[0]), "observation", message),
            (ref(receipts[1]), "self_expression", answer),
        ]

        recalls = [e["data"] for e in turn if e["type"] == "memory_recall"]
        results = [e["data"] for e in turn if e["type"] == "memory_call_result"]
        resets = [e["data"] for e in turn if e["type"] == "memory_context_reset"]
        delivery = []
        with sqlite3.connect(
            (self.pair.audit_dir / "memory-v2-misty-dev.sqlite").as_uri() + "?mode=ro", uri=True
        ) as db:
            for recall in recalls:
                for binding in recall["bindings"]:
                    saved = db.execute(
                        """
                        SELECT inputs.status, input_evidence.stream, input_evidence.seq, input_evidence.item
                        FROM inputs JOIN input_evidence USING(input_id)
                        WHERE input_id=? AND id=? AND kind=?""",
                        (binding["input"], binding["id"], binding["kind"]),
                    ).fetchone()
                    assert saved and saved[:3] == ("DELIVERED", recall["stream_id"], recall["read_seq"])
                    item = json.loads(saved[3])
                    assert item == binding["item"]
                    delivery.append(
                        dict(
                            ref=item["ref"],
                            sources=item["source_refs"],
                            text=item["text"],
                            input_id=binding["input"],
                            read_seq=recall["read_seq"],
                        )
                    )
            for reset in resets:
                row = db.execute(
                    "SELECT status,material FROM inputs WHERE input_id=?", (reset["input_id"],)
                ).fetchone()
                assert row and row[0] == "DELIVERED" and json.loads(row[1]) == reset["materials"]

        evidence = dict(
            step=step,
            question=message,
            answer=answer,
            plan=plan["data"]["kind"],
            planning_usage=plan["data"]["usage"],
            reply_event=reply["eventId"],
            harness_latency_ms=reply["data"]["latencyMs"],
            incoming=receipts[0],
            outgoing=receipts[1],
            delivered=delivery,
            retrieved_material=[s for result in results for s in result["sentences"]],
            first_input_updates=[s for reset in resets for s in reset["materials"]],
        )
        self.report["turns"].append(evidence)
        self.save()
        print(step, answer, flush=True)
        return evidence

    def check(self, name, condition):
        """断言单个语义或证据检查点通过，并将其追加到报告中。"""
        if not condition:
            raise AssertionError(name)
        self.report["checks"].append(name)
        self.save()

    def ingest(self, suffix, text, **fields):
        """以当前会话链为前缀直接向 Memory 提交一条观察或工具记录。"""
        return self.pair.api("ingest", event(self.chain + "-" + suffix, text, scope=self.scope, **fields))

    def correct(self, target, suffix, operation, **fields):
        """按乐观锁版本向 Memory 提交渠道修订（REPLACE）或撤回（RETRACT）。"""
        return self.pair.api(
            "corrections",
            dict(
                context=context(self.scope),
                source_instance="neuralcloud-java",
                event_id=self.chain + "-" + suffix,
                record_id=target["record_id"],
                expected_revision=target["revision"],
                operation=operation,
                reason="原消息的渠道修订",
                **fields,
            ),
        )

    @staticmethod
    def cited(turn, target):
        """判断目标记录的精确版本是否既作为交付项本身、又作为来源引用送达了本轮模型输入。"""
        return any(row["ref"] == ref(target) and ref(target) in row["sources"] for row in turn["delivered"])

    def run(self):
        """执行完整会议改期验收剧本：两轮通知 -> 改期复盘 -> 核对原/现安排 -> 提醒未决/已决 -> 编辑失效 -> 撤回回退。"""
        old = self.turn(
            "original-message",
            "例会定在周五 15 点。",
            meeting_source("2026-11-20T15:00:00+08:00", "2026-11-18T09:00:00+08:00"),
        )["incoming"]
        new = self.turn(
            "reschedule",
            "周五会议室要检修，所以例会改到周六 10 点。",
            meeting_source("2026-11-21T10:00:00+08:00", "2026-11-19T10:00:00+08:00", old),
        )["incoming"]
        self.check(
            "distinct original observations", old["record_id"] != new["record_id"] and old["seq"] < new["seq"]
        )
        recap = self.turn("ask-for-recap", "所以可以讲下这次例会前后改期发生了什么吗")
        first = schedule_mention(recap["answer"], "friday")
        second = schedule_mention(recap["answer"], "saturday")
        self.check(
            "recap delivers both source notices in chronological order",
            recap["plan"] == "HISTORY"
            and self.cited(recap, old) and self.cited(recap, new)
            and first is not None and second is not None and first.start() < second.start()
            and "小林" in recap["answer"] and "项目群" in recap["answer"],
        )
        self.check("recap explains the attested reason to the user", "会议室" in recap["answer"]
                   and any(word in recap["answer"] for word in ("检修", "维修", "维护")))
        original = self.turn("ask-original", "请查一下例会的原始发言。最初约在星期几几点，是谁在哪儿说的？")
        current = self.turn(
            "ask-current", "请核对例会改期的那条原话。最新安排在星期几几点？请带上发言人和场合。"
        )
        for name, turn, target, when in (
            ("original", original, old, "friday"),
            ("current", current, new, "saturday"),
        ):
            self.check(
                name + " time and provenance",
                states_arrangement(turn["answer"], when, historical=(name == "original"))
                and "小林" in turn["answer"]
                and "项目群" in turn["answer"],
            )
            self.check(name + " source delivered", self.cited(turn, target))

        action = self.chain + "-reminder"
        attempted = self.ingest(
            "attempt",
            "已请求按例会的最新安排更新提醒清单，等待执行回执。",
            kind="tool_attempt",
            action_id=action,
        )
        self.ingest("other", "打印任务已执行。", kind="tool_result", action_id=action + "-other")
        waiting = self.pair.api("recall", query("提醒清单", scope=self.scope, action_id=action))
        unknown = self.turn(
            "ask-before-receipt", "请查本会话里例会提醒清单的执行记录。根据已有回执，能确定清单更新了吗？"
        )
        self.check(
            "unconfirmed attempt stays unknown",
            waiting["assessment"]["state"] == "INCOMPLETE"
            and self.cited(unknown, attempted)
            and uncertain(unknown["answer"])
            and not confirmed_update(unknown["answer"]),
        )

        latest = self.pair.api("recall", query("例会", scope=self.scope, subject_id="weekly-review"))
        assert [r["ref"] for r in latest["items"]] == [ref(new)]
        external = apply_reminder(
            self.pair.out / "controlled-reminders.sqlite",
            dict(action_id=action, title="例会", scheduled_for=latest["items"][0]["assertion"]["value"]),
        )
        completed = self.ingest(
            "completed",
            "例会提醒清单已更新，会议时间为 " + external["scheduled_for"],
            kind="tool_result",
            action_id=external["action_id"],
        )
        self.report["receipts"].update(executor=external, attempt=attempted, result=completed)
        done = self.turn(
            "ask-after-receipt",
            "再查一次例会提醒清单的执行回执。这次更新结果是什么？清单上的时间是星期几几点？",
        )
        self.check(
            "actual tool receipt changes the answer",
            self.cited(done, completed)
            and describes(done["answer"], "saturday")
            and confirmed_update(done["answer"]),
        )

        candidate = event(
            self.chain + "-summary",
            "例会定于周六上午十点。",
            scope=self.scope,
            kind="summary",
            author="MASTER",
            source_refs=[ref(new)],
        )
        summary = self.pair.api("candidates", candidate)
        revised = self.correct(
            new,
            "edit",
            "REPLACE",
            content="例会改到周日 14 点。",
            assertion=meeting_source("2026-11-22T14:00:00+08:00", "2026-11-19T10:00:00+08:00", old)[
                "assertion"
            ],
        )
        invalid = self.pair.api("read", dict(context=context(self.scope), targets=[ref(summary)]))["items"][0]
        late = self.pair.api("candidates", dict(candidate, event_id=self.chain + "-late-summary"), 409)
        self.check(
            "previous derivation invalidated",
            revised["revision"] == 2
            and invalid["status"] == "SOURCE_CHANGED"
            and "text" not in invalid
            and late["code"] == "SOURCE_CHANGED",
        )
        edited = self.turn("ask-after-edit", "请查最新修订的例会发言。现在该按哪天几点参加？请引用原话。")
        self.check(
            "edited time reaches the next model turn",
            states_arrangement(edited["answer"], "sunday")
            and any("周日 14 点" in s for s in edited["first_input_updates"]),
        )
        withdrawn = self.correct(revised, "withdraw", "RETRACT")
        hidden = self.pair.api("read", dict(context=context(self.scope), targets=[ref(new)]))["items"][0]
        self.check("withdrawal hides old revision", hidden["status"] == "RETRACTED" and "text" not in hidden)
        restored = self.turn(
            "ask-after-withdrawal", "请按还有效的例会原话核对一下。还剩哪一条安排，星期几几点？"
        )
        self.check(
            "remaining arrangement after withdrawal",
            states_arrangement(restored["answer"], "friday")
            and any("失效" in s for s in restored["first_input_updates"]),
        )
        self.report["receipts"].update(original=old, reschedule=new, revised=revised, withdrawn=withdrawn)


def schedule_mention(text, slot):
    """在自然语言中文回答中匹配指定会议档期（星期/日期 + 对应小时），容忍常见口语表述差异。"""
    # 归一化全角/空格/Markdown 符号后，在同一子句窗口内同时匹配日期与小时。
    compact = re.sub(r"[\s*_\x60]", "", unicodedata.normalize("NFKC", text))
    day, hour = {
        "friday": (
            r"(?:(?:周|星期|礼拜)五|11月20[日号]|2026-11-20)",
            r"(?:15|十五|下午[3三])(?:[:点时]00?|[点时])",
        ),
        "saturday": (r"(?:(?:周|星期|礼拜)六|11月21[日号]|2026-11-21)", r"(?:10|十)(?:[:点时]00?|[点时])"),
        "sunday": (
            r"(?:(?:周|星期|礼拜)[日天]|11月22[日号]|2026-11-22)",
            r"(?:14|十四|下午[2两二])(?:[:点时]00?|[点时])",
        ),
    }[slot]
    return re.search(day + r"[^。！？\n]{0,24}?" + hour, compact)


def describes(text, slot):
    return schedule_mention(text, slot) is not None


def states_arrangement(answer, slot, *, historical=False):
    """判断回答是否准确将 `slot` 归类为“原安排（historical=True）”或“当前有效安排（historical=False）”。"""
    if not describes(answer, slot):
        return False
    if not any(describes(answer, other) for other in ("friday", "saturday", "sunday") if other != slot):
        return True
    # 当回答同时提及两个时间点时，需在分句粒度核对哪个时间被标定为历史安排或当前有效安排。
    marker = (
        r"原[来先本]|最初|起初|一开始|历史"
        if historical
        else r"当前|目前|现在|最新|改[到成为]|还有效|仍然有效|只剩"
    )
    clauses = re.split(r"[，。！？；,;\n]|不过|但是|后来(?=改)", answer)
    clauses = [c.strip(' “”。「」') for c in clauses if c.strip(' “”。「」')]
    for i, clause in enumerate(clauses):
        if not describes(clause, slot):
            continue
        if re.search(marker, clause):
            return True
        # 兼容跨分句回指写法（例如“‘周五 15 点。’不过那是历史安排。”），指示代词修饰紧邻的前一句通知。
        if historical and i + 1 < len(clauses) and re.match(
                r"(?:这|那|该)(?:条|个|项|则)?(?:是|属于|只是)(?:当时的|之前的)?历史安排", clauses[i + 1]):
            return True
    return False


def uncertain(answer):
    """判断回答是否如实表达了“仅有工具尝试、尚无执行回执因此无法确认已更新”的不确定性。"""
    return (
        re.search(
            r"未知|不确定|待(?:核实|确认)|(?:等待|还在等|等候).{0,6}回执|(?:不能|无法|没法|不足以|还不能).{0,10}(?:确认|确定|断定|证明)|"
            r"(?:尚无|未见|尚未|没有|没看到|没找到|还没|缺少|缺乏|未收到).{0,14}(?:回执|确认|结果|证据|完成|成功)",
            answer,
        )
        is not None
    )


def confirmed_update(answer):
    """判断回答中是否存在明确肯定“提醒清单已更新”的分句（兼容先对比未决尝试再转折确认结果的句式）。"""
    # 回答可能先回顾先前待确认状态，再转折说明现已取得回执；按分句拆分以避免被前半句的否定词误杀。
    clauses = re.split(r"[。！？；;\n]|(?:但是|不过|现在则)", answer)
    return any(
        not uncertain(c)
        and re.search(
            r"(?:已(?:经|成功|完成)?|成功)(?:按[^，。；]{1,8})?(?:更新|调整|改[成为到]|设置)|"
            r"(?:更新|调整|修改)(?:成功|完成|好了)|(?:更新|调整|改)(?:为|到了|成了)",
            c,
        )
        for c in clauses
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--harness", type=Path, default=ROOT.parent / "neuralcloud-harness")
    parser.add_argument(
        "--resume-demo", type=Path, help="Reuse ports, ledger and audit from a stopped demo.json."
    )
    parser.add_argument(
        "--serve", action="store_true", help="Keep this real Gemini console up after the case."
    )
    parser.add_argument("--max-output-tokens", type=int, help="Override the official worker output budget in this isolated run.")
    args = parser.parse_args()
    config = args.harness.resolve() / "java/src/main/resources/config/chat-worker-gemini.properties"
    cfg = dict(
        line.split("=", 1)
        for line in config.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith(("#", "!"))
    )
    endpoint = urlsplit(cfg["endpoint"])
    if (
        endpoint.scheme != "https"
        or endpoint.netloc != "generativelanguage.googleapis.com"
        or endpoint.query
        or endpoint.fragment
    ):
        raise ValueError("The live case expects the official Google Gemini HTTPS endpoint.")
    key_env = cfg["apiKeyEnv"]
    if not os.environ.get(key_env):
        parser.error("Set " + key_env + " in the launch environment.")
    pair = ServicePair(args.harness, **previous_services(args.resume_demo))
    if args.max_output_tokens is not None:
        if args.max_output_tokens < 1:
            parser.error("--max-output-tokens must be positive")
        cfg["maxOutputTokens"] = str(args.max_output_tokens)
        config = pair.out / "gemini-worker.properties"
        props(config, cfg)
    # 隔离凭据边界：只把 Gemini API Key 注入 Harness 进程环境，Memory 进程不持有外部模型密钥。
    pair.harness_env = pair.env.copy()
    pair.env.pop(key_env)
    # JVM 沿用系统默认 TLS 根证书库以及 Harness 配置中声明的官方代理。
    props(
        pair.console_config,
        dict(
            listenHost="127.0.0.1",
            listenPort=pair.console_port,
            auditDirectory=pair.audit_dir.as_posix(),
            memoryConfig=pair.client.as_posix(),
            chatWorkerConfig=config.as_posix(),
            chatWorkerRolePrompt="classpath:/config/chat-worker-misty-prompt.txt",
            routerConfig="classpath:/config/router.properties",
            routerRolePrompt="classpath:/config/router-role-prompt.txt",
            routerMetrics="http://127.0.0.1:18082/metrics",
            routerMetricsFormat="VLLM",
            masterConfig="classpath:/config/local-master-qwen38.properties",
            masterMetrics="http://127.0.0.1:18081/metrics",
            masterMetricsFormat="LLAMA_CPP",
            masterMaxTokens=256,
            masterTemperature=0,
            gpuMetrics="NONE",
            metricsIntervalSeconds=60,
            metricsTimeoutSeconds=2,
        ),
    )
    case = GeminiMeeting(pair, cfg["endpoint"], 3 * int(cfg["requestTimeoutSeconds"]) + 10)
    print("Evidence:", pair.out, flush=True)
    try:
        health = pair.memory()
        pair.harness_start()
        node = next(n for n in send(pair.console + "nodes")["nodes"] if n["id"] == "chat-worker")
        assert node["endpoint"] == cfg["endpoint"]
        case.report.update(stream_id=health["stream_id"], retrieval_mode=health.get("retrieval_mode"), max_output_tokens=int(cfg["maxOutputTokens"]), processes=pair.pids)
        manifest = dict(
            console=pair.console.removesuffix("api/"),
            chat_url=pair.console.removesuffix("api/") + "?chain=" + case.chain,
            memory=pair.base,
            chain=case.chain,
            ledger=str(pair.ledger),
            audit=str(pair.audit_dir),
            memory_pid=pair.mproc.pid,
            harness_pid=pair.hproc.pid,
            stop_file=str(pair.out / "STOP"),
            chat_worker=node["endpoint"],
        )
        dump(pair.out / "demo.json", manifest)
        case.run()
        case.report["status"] = "PASSED"
        case.save()
        print("PASS real Gemini meeting case;", len(case.report["turns"]), "turns", flush=True)
        print("Chat:", manifest["chat_url"], flush=True)
    except Exception as exc:
        case.report.update(status="FAILED", error=str(exc))
        case.save()
        if pair.hproc is not None and pair.hproc.poll() is None:
            dump(pair.out / "meeting-audit.json", pair.audit(case.chain))
        raise
    else:
        if args.serve:
            while not (pair.out / "STOP").exists():
                if pair.mproc.poll() is not None or pair.hproc.poll() is not None:
                    raise RuntimeError("A demo JVM exited")
                time.sleep(1)
    finally:
        stop(pair.hproc)
        if pair.mproc is not None and pair.mproc.poll() is None:
            pair.mproc.stdin.close()
            pair.mproc.wait(timeout=10)


if __name__ == "__main__":
    main()
