"""阶段 3 冻结中文多轮语料的成对消融回放与发布门禁脚本。

在固定候选预算和上下文预算下，依次用历史阶段 1 基线 JAR（P1）及当前候选 JAR
（LEXICAL / CELLS / TEMPORAL / HYBRID / HYBRID+THINKING）执行同一组语料回放，
汇总按题型分组的 Recall@5 命中、来源归属正确率、过期证据率、P95 检索延迟及聊天首字延迟，
并在 `--gate` 模式下自动判定最终应启用的默认检索模式。
"""
import argparse
import hashlib
import http.client
import json
import math
import statistics
import subprocess
import time
from pathlib import Path

from acceptance import ROOT, Run, context, dump, encode, event, query, ref, send

FIXTURE = ROOT / "tests/fixtures/stage34-zh.json"
DATA = json.loads(FIXTURE.read_text(encoding="utf-8"))
P = DATA["protocol"]
BASE = ROOT / "target/stage34-baseline"


def p95(values):
    """按向上取整下标计算列表的 P95 延迟（单位 ms，保留两位小数）。"""
    return round(sorted(values)[math.ceil(len(values) * 0.95) - 1], 2)


def seed(run, legacy=False):
    """将冻结中文语料按序写入 Memory 账本；legacy=True 时剥离阶段 1 尚不支持的 topic/reply_to/valid_until 字段。"""
    receipts = {}
    for row in DATA["events"]:
        r = dict(row)
        key, scope, text = (r.pop(k) for k in ("event_id", "scope", "content"))
        if legacy:
            r.pop("topic")
            r.pop("reply_to", None)
            if "assertion" in r:
                r["assertion"] = {k: v for k, v in r["assertion"].items() if k != "valid_until"}
        rec = send(run.base + "ingest", event(key, text, scope=scope, **r), run.token)
        receipts[key] = rec
    return receipts


def chat(run, prompts):
    """测量端到端聊天首字延迟：向 ConsoleMain 发请求并逐字节流式读取，直到解码出 JSON 中 `data.text` 的首个 UTF-8 字符。
    计时涵盖 HTTP 请求、记忆检索、模型决策/回复与交付前复核全过程。
    """
    from urllib.parse import urlsplit

    u = urlsplit(run.console)
    latencies = []
    marker = b'"text":"'
    for i, question in enumerate(prompts):
        body = encode(dict(chain="timing-" + str(i), text=question + "？"))
        with_connection = http.client.HTTPConnection(u.hostname, u.port, timeout=20)
        try:
            start = time.perf_counter_ns()
            with_connection.request(
                "POST", "/api/nodes/chat-worker/messages", body, {"Content-Type": "application/json"}
            )
            res = with_connection.getresponse()
            assert res.status == 200, res.read()
            prefix = bytearray()
            while not prefix.endswith(marker):
                b = res.read(1)
                assert b, bytes(prefix)
                prefix += b
            first = res.read(1)
            while True:
                try:
                    first.decode("utf-8")
                    break
                except UnicodeDecodeError:
                    first += res.read(1)
            latencies.append((time.perf_counter_ns() - start) / 1e6)
            result = json.loads(bytes(prefix) + first + res.read())
            assert result["type"] == "chat_worker_reply" and result["data"]["text"]
        finally:
            with_connection.close()
    return latencies


def replay(mode, baseline, do_chat):
    """在隔离运行目录中执行单个检索模式的完整回放：灌入语料、冷/热轮召回计时、在线对照组与可选首字延迟测量。"""
    run = Run(ROOT.parent / "neuralcloud-harness")
    try:
        if mode == "P1":
            # P1 基线强制校验两个历史 worktree 的精确 Git 提交哈希，保证跨阶段对照实验可复现。
            for field, project in (("memjar", baseline / "memory"), ("hjar", baseline / "harness/java")):
                root = project.parent if field == "hjar" else project
                expected = (
                    "94bffbcccb30745e4d3b4839b61b03cf8403e531"
                    if field == "hjar"
                    else "711768ccc3da2243139ea0a72d45d894e247ff43"
                )
                assert (
                    subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
                    == expected
                )
                path = project / "target" / getattr(run, field).name
                if not path.is_file():
                    raise ValueError("Build the two recorded P1 commits before replay: " + str(path))
                setattr(run, field, path)
        else:
            with (run.out / "memory.properties").open("a", encoding="utf-8") as f:
                f.write("organizeIntervalSeconds=0\nretrievalMode=" + mode.split("+")[0] + "\n")
        run.jar_hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (run.memjar, run.hjar)}
        health = run.memory()
        rcs = seed(run, mode == "P1")
        sources = {rcs[e["event_id"]]["record_id"]: e for e in DATA["events"]}
        requests, samples, first = [], [], []
        for case in DATA["questions"]:
            where = {k: case[k] for k in ("subject_id", "predicate") if k in case}
            if "known_event" in case:
                where["as_of_known_seq"] = rcs["zh34-" + case["known_event"]]["seq"]
            requests.append(
                query(
                    case["query"],
                    case.get("scope", "console:review34"),
                    as_of_valid=case["at"],
                    profile="THINKING" if "+" in mode else "QUICK",
                    limit=P["candidate_limit"],
                    **where,
                )
            )
        details = []
        by_case = [[] for _ in requests]
        for n in range(P["warm_rounds"] + 1):
            # 每轮轮换起始题目下标，避免固定题目总是落在相同的 SQLite 页缓存热度位置。
            for j in range(len(requests)):
                i = (j + n) % len(requests)
                start = time.perf_counter_ns()
                answer = send(run.base + "recall", requests[i], run.token)
                elapsed = (time.perf_counter_ns() - start) / 1e6
                (first if n == 0 else samples).append(elapsed)
                if n:
                    by_case[i].append(elapsed)
                    continue
                case = DATA["questions"][i]
                items = answer["items"][: P["context_items"]]
                assert all(len(x["text"]) <= P["excerpt_codepoints"] for x in items)
                gold = case["gold"]
                hits = [x["ref"] for x in items]
                for x in items:
                    src = sources[x["ref"]["record_id"]]
                    assert src["scope"] == requests[i]["context"]["conversation_id"]
                correct = sum(x["ref"] in gold for x in items)
                attributed = sum(
                    x["speaker"] == sources[x["ref"]["record_id"]]["speaker"]
                    and x["source_venue"] == sources[x["ref"]["record_id"]]["source_venue"]
                    for x in items
                )
                details.append(
                    dict(
                        group=case["group"],
                        q=case["query"],
                        top5=hits,
                        hit=correct,
                        gold=len(gold),
                        attribution=[attributed, len(items)],
                        expired=sum(h in case.get("expired", []) for h in hits),
                        correct_empty=not hits if not gold else None,
                    )
                )
        # 在线对照组（不带 as_of_valid 历史时间参数）：验证同等文本在 P1 常规实时 FTS 路径下的表现。
        online = []
        for i, case in enumerate(DATA["questions"]):
            if case["group"] not in ("字面与归属", "事件与指代", "相对日期", "受众"):
                continue
            live = dict(requests[i])
            live.pop("as_of_valid")
            items = send(run.base + "recall", live, run.token)["items"][: P["context_items"]]
            online.append(dict(q=case["query"], hit=sum(x["ref"] in case["gold"] for x in items)))
        chats = []
        if do_chat:
            profile = "THINKING" if "+" in mode else "QUICK"
            run.harness_start(**({} if mode == "P1" else {"historyProfile": profile}))
            if mode != "P1":
                assert run.audit("memory-startup")[-1]["data"]["historyProfile"] == profile

            # 将同一份原话按相同引用拓扑复制到各独立计时会话，模型规划严格沿用冻结查询，绝不参考 gold 标注。
            def model(body):
                if "contents" not in body:
                    return None
                parts = body["contents"][-1]["parts"]
                question = parts[0]["text"]
                if "responseSchema" in body["generationConfig"]:
                    text = json.dumps(
                        dict(kind="HISTORY", query=question.rstrip("？")[:256]), ensure_ascii=False
                    )
                else:
                    text = parts[-1]["text"][:100] if len(parts) > 1 else "材料已读。"
                return dict(candidates=[dict(finishReason="STOP", content=dict(parts=[dict(text=text)]))])

            run.proxy.model_response = model
            from copy import deepcopy

            for i in range(P["chat_repeats"] + 1):
                case = DATA["questions"][i % len(requests)]
                scoped = [e for e in DATA["events"] if e["scope"] == case.get("scope", "console:review34")]
                mapping = {}
                for original in scoped:
                    e = deepcopy(original)
                    k = e.pop("event_id")
                    text = e.pop("content")
                    e.pop("scope")
                    if mode == "P1":
                        e.pop("reply_to", None)
                        e.pop("topic")
                        if "assertion" in e:
                            e["assertion"].pop("valid_until", None)
                    for parent, field in ((e, "reply_to"), (e.get("assertion", {}), "supersedes_ref")):
                        if field in parent:
                            parent[field] = mapping[parent[field]["record_id"]]
                    receipt = send(
                        run.base + "ingest",
                        event(f"timing-{i}-{k}", text, scope=f"console:timing-{i}", **e),
                        run.token,
                    )
                    mapping[rcs[k]["record_id"]] = ref(receipt)
            chats = chat(
                run, [DATA["questions"][i % len(requests)]["query"] for i in range(P["chat_repeats"] + 1)]
            )
        # 为每个分题型 P95 保留实际样本量（预先约定的 25 轮聊天中部分细分题型样本较少）。
        by_group = {}
        for group in dict.fromkeys(case["group"] for case in DATA["questions"]):
            qids = [i for i, c in enumerate(DATA["questions"]) if c["group"] == group]
            milliseconds = [v for i in qids for v in by_case[i]]
            characters = [v for i, v in enumerate(chats) if i and i % len(requests) in qids]
            by_group[group] = dict(
                recall_n=len(milliseconds),
                recall_p95_ms=p95(milliseconds),
                chat_n=len(characters),
                chat_p95_ms=p95(characters) if characters else None,
            )
        for i, row in enumerate(details):
            row["warm_ms"] = [round(v, 3) for v in by_case[i]]
        return dict(
            mode=mode,
            health_mode=health.get("retrieval_mode", "P1"),
            jars=run.jar_hashes,
            checks=details,
            by_group=by_group,
            online_control=online,
            first_pass_p95_ms=p95(first),
            warm_p95_ms=p95(samples),
            warm_median_ms=round(statistics.median(samples), 2),
            warm_n=len(samples),
            first_chat_ms=round(chats[0], 2) if chats else None,
            chat_p95_ms=p95(chats[1:]) if chats else None,
            chat_samples_ms=[round(t, 2) for t in chats[1:]],
            output=run.out.name,
        )
    finally:
        run.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--modes", nargs="+", default=["P1", "LEXICAL", "CELLS", "TEMPORAL", "HYBRID", "HYBRID+THINKING"]
    )
    parser.add_argument("--baseline", type=Path, default=BASE)
    parser.add_argument("--chat", action="store_true")
    parser.add_argument(
        "--gate",
        action="store_true",
        help="Require release gates on the full paired run including first-character timing.",
    )
    parser.add_argument("--output", type=Path, default=ROOT / "target/stage3-measurements.json")
    args = parser.parse_args()
    if args.gate and (
        not args.chat or args.modes != ["P1", "LEXICAL", "CELLS", "TEMPORAL", "HYBRID", "HYBRID+THINKING"]
    ):
        parser.error("--gate requires --chat and the complete ordered ablation")
    runs = []
    for m in args.modes:
        r = replay(m, args.baseline, args.chat)
        runs.append(r)
        print(m, sum(x["hit"] for x in r["checks"]), r["warm_p95_ms"], r["chat_p95_ms"], flush=True)
        dump(args.output, dict(corpus_sha256=hashlib.sha256(FIXTURE.read_bytes()).hexdigest(), modes=runs))

    if args.gate:
        base = runs[0]

        def score(result):
            return sum(q["hit"] for q in result["checks"] if q["group"] in ("事件与指代", "相对日期", "限期状态", "双时态"))

        best = base
        decisions = []
        for r in runs[1:]:
            correct = all(
                x["attribution"][0] == x["attribution"][1]
                and x["expired"] == 0
                and x["correct_empty"] is not False
                for x in r["checks"]
            )
            latency = (
                r["warm_p95_ms"] <= P["recall_p95_ms"]
                and r["chat_p95_ms"] - base["chat_p95_ms"] <= P["chat_p95_delta_ms"]
            )
            gain = score(r) - score(best)
            # LEXICAL 仅作向后兼容对照；CELLS 起才作为候选增强组件，只有同时满足正确性、延迟预算且难例净增益 > 0 才启用。
            accept = r["mode"] != "LEXICAL" and correct and latency and gain > 0
            if accept:
                best = r
            decisions.append(
                dict(mode=r["mode"], correct=correct, within_latency=latency, hard_gain=gain, enable=accept)
            )
        dump(
            args.output,
            dict(
                corpus_sha256=hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),
                modes=runs,
                decisions=decisions,
                selected=best["mode"],
            ),
        )
        assert best["mode"] == "CELLS", decisions
        print("Release gates passed:", best["mode"])
