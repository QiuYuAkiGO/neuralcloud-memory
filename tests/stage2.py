"""Replay versioned author grants through two JVMs, OneBot and the actual Worker model interfaces."""

import argparse
import json
import os
import re
import secrets
import sqlite3
import subprocess
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from itertools import count
from pathlib import Path
from urllib.parse import urlencode

from jsonschema import Draft202012Validator
from acceptance import ROOT, HIDDEN, Run, context, dump, event, props, query, ref, send, stop

ACCOUNT, AUTHOR, OTHER = "12345", "67890", "78901"
GROUP = "group:onebot:12345:23456"
TEAM = "console:p2-team"
PLACE, REASON = "雪松厅", "靛青样机晚到"
QUESTION = "请查例会的地点和改期原因，记录里分别怎么说？"


def private(peer):
    return "c2c:onebot:" + ACCOUNT + ":" + peer


def audience(scope, role="MASTER"):
    qq = dict(account_id=ACCOUNT, peer_id=scope.split(":")[-1]) if scope.startswith("c2c:") else None
    return context(scope, role, qq)


def serialized(value):
    return json.dumps(value, ensure_ascii=False)


class Case:
    def __init__(self, run):
        self.run = run
        self.ids = count(81000)
        self.token = secrets.token_urlsafe(36)
        self.first_request = threading.Event()
        self.release_model = threading.Event()
        self.pause_model = False
        self.before_action = None
        self.run.env["NC_AUTHOR_BRIDGE"] = self.token
        self.run.client_config["grantAccessTokenEnv"] = "NC_AUTHOR_BRIDGE"
        with (run.out / "memory.properties").open("a", encoding="utf-8") as f:
            f.write("grantAccountId=12345\ngrantAccessTokenEnv=NC_AUTHOR_BRIDGE\n")
        run.http.model_response = run.proxy.model_response = self.model

    def model(self, body):
        if self.pause_model:
            self.pause_model = False
            self.first_request.set()
            self.release_model.wait(12)
            return None
        if "contents" in body:
            parts = body["contents"][-1]["parts"]
            if "responseSchema" in body["generationConfig"]:
                text = serialized(dict(kind="HISTORY", query="例会"))
            else:
                text = parts[-1]["text"] if len(parts) > 1 else "已记录。"
            return dict(candidates=[dict(finishReason="STOP", content=dict(parts=[dict(text=text)]))])
        task = body["model"] == "fixture-task"
        if body.get("tools"):
            name = (
                "memory_call"
                if any(t["function"]["name"] == "memory_call" for t in body["tools"])
                else "read_server_status"
            )
            if task and name == "read_server_status" and self.before_action is not None:
                assert PLACE in serialized(body["messages"])
                self.before_action()
            args = dict(kind="HISTORY", query="例会") if name == "memory_call" else {}
            call = dict(id="read-1", type="function", function=dict(name=name, arguments=serialized(args)))
            message, reason = dict(role="assistant", content="", tool_calls=[call]), "tool_calls"
        else:
            message, reason = dict(role="assistant", content=body["messages"][-1]["content"]), "stop"
        return dict(model=body["model"], choices=[dict(finish_reason=reason, message=message)])

    def harness(self, peer=AUTHOR):
        run = self.run
        if run.hproc and run.hproc.poll() is None:
            stop(run.hproc)
        run.onebot.peer = int(peer)
        props(
            run.out / "onebot.properties",
            dict(
                endpoint=f"ws://127.0.0.1:{run.onebot.port}/",
                accountId=ACCOUNT,
                allowedUserId=peer,
                allowedGroupId=23456,
                accessTokenEnv="NC_FIXTURE_QQ",
            ),
        )
        run.harness_start()
        assert run.onebot.ready.wait(4)

    def ask(self, text, **kwargs):
        return self.run.onebot.ask(text, next(self.ids), **kwargs)

    def source(self, chain, text):
        for _ in range(80):
            events = self.run.audit(chain)
            entry = next(e for e in events if e["type"] == "qq_message" and e["data"]["text"] == text)
            write = next(
                (
                    e
                    for e in events
                    if e["type"] == "router_memory_write"
                    and e["runId"] == entry["runId"]
                    and e["data"]["sourceEventId"] == entry["eventId"]
                ),
                None,
            )
            if write:
                saved = next(
                    (
                        e["data"]
                        for e in events
                        if e["type"] == "memory_durable"
                        and e["runId"] == entry["runId"]
                        and e["causedBy"] == write["eventId"]
                    ),
                    None,
                )
                if saved:
                    return dict(record_id=saved["recordId"], revision=saved["revision"])
            time.sleep(0.03)
        raise AssertionError("inbound receipt missing")

    def decision(self, record, target, use="CITE"):
        answer = self.ask(
            f"/memory grant {record['record_id']}@{record['revision']} {target} {use} 验证会议范围"
        )
        match = re.fullmatch(r"已授予授权 ([0-9a-f]{64})", answer)
        assert match, answer
        return match.group(1)

    def revoke(self, grant):
        answer = self.ask("/memory revoke " + grant + " 会议范围已变更")
        assert answer == "已撤销授权 " + grant, answer

    def read(self, record, scope, use="CITE"):
        args = dict(context=audience(scope), targets=[record])
        if use is not None:
            args["use"] = use
        return self.run.api("read", args)["items"][0]

    def lookup(self, scope, **more):
        return self.run.api("recall", dict(query("例会", scope), context=audience(scope), use="CITE", **more))

    def master(self):
        return send(self.run.console + "nodes/master/messages", dict(chain="p2-team", text=QUESTION))["data"][
            "text"
        ]

    def exercise(self):
        r = self.run
        # Reopen a populated P1 ledger so migration also covers its stream, sequence and durable receipt.
        r.ledger.mkdir()
        stream = str(uuid.uuid4())
        old = event("stage-one-event", "旧库的原通知。", scope="console:legacy")
        with sqlite3.connect(r.ledger / "memory.sqlite") as db:
            db.executescript((ROOT / "src/main/resources/schema.sql").read_text(encoding="utf-8"))
            db.execute("INSERT INTO meta VALUES(?,?)", ("misty-dev", stream))
            key = "a" * 64
            db.execute(
                "INSERT INTO records VALUES(?,?,?,?,?,1,'ONLINE')",
                (key, "console:legacy", "ROUTER", "neuralcloud-java", "observation"),
            )
            db.execute("INSERT INTO changes VALUES(1,'INGEST',?,1,?)", (key, old["occurred_at"]))
            body = dict(
                content=old["content"], kind="observation", occurred_at=old["occurred_at"], privacy="private"
            )
            db.execute("INSERT INTO revisions VALUES(?,1,1,?,?)", (key, old["occurred_at"], serialized(body)))
            db.execute(
                "INSERT INTO receipts VALUES(?,?,'ingest',?,?,1,1)",
                ("neuralcloud-java", old["event_id"], serialized(old), key),
            )
        health = r.memory()
        assert health["stream_id"] == stream and health["head_seq"] == 1
        legacy = r.api("receipts?source_instance=neuralcloud-java&event_id=stage-one-event")
        assert self.read(ref(legacy), "console:legacy", None)["text"] == old["content"]
        r.check("stage-one-ledger-migration-preserves-stream-receipt-and-record")
        assert {"audience_reads", "author_grants"} <= set(health["capabilities"])
        self.harness()
        notice = "例会改到周六 10 点，在雪松厅开。"
        reason = "我私下说明，例会改期的原因是靛青样机晚到。"
        self.ask(notice, group=True)
        self.ask(reason)
        group = self.source("qq-group-12345-23456", notice)
        private_reason = self.source("qq-private-12345-67890", reason)
        target = private(OTHER)
        assert self.read(group, target)["status"] == "NOT_FOUND"
        before = len(r.proxy.calls)
        self.harness(OTHER)
        self.ask(QUESTION)
        assert PLACE not in serialized(r.proxy.calls[before:]) and REASON not in serialized(
            r.proxy.calls[before:]
        )
        self.harness()
        called = len(r.proxy.calls)
        read_grant = self.decision(group, target, "READ")
        assert len(r.proxy.calls) == called
        assert self.read(group, target)["status"] == self.read(group, target, None)["status"] == "NOT_FOUND"
        readonly = self.read(group, target, "READ")
        assert readonly["text"] == notice and readonly["use"] == "READ" and "citation_scope" not in readonly
        r.check("authenticated-onebot-author-and-read-only-grant", source=group, grant=read_grant)

        self.verify_author(group, target)
        wrong = self.ask(f"/memory grant {group['record_id']}@1 {target} CITE 他人代授", sender=int(OTHER))
        assert "FORBIDDEN" in wrong
        approved = self.decision(group, target)
        console_grant = self.decision(group, TEAM)
        assert self.read(group, target)["citation_scope"] == target
        for i in range(6):
            r.api("ingest", event("hidden-" + str(i), "雪松厅 雪松厅 例会。", scope="console:elsewhere"))
        assert [i["ref"] for i in self.lookup(target, query=PLACE, limit=1)["items"]] == [group]
        assert self.read(private_reason, GROUP)["status"] == "NOT_FOUND"
        assert (
            REASON not in serialized(self.lookup(GROUP))
            and self.read(private_reason, target)["status"] == "NOT_FOUND"
        )
        start = len(r.proxy.calls)
        assert PLACE in self.ask(QUESTION, group=True)
        assert REASON not in serialized(r.proxy.calls[start:])
        self.harness(OTHER)
        start = len(r.proxy.calls)
        assert PLACE in self.ask(QUESTION) and REASON not in serialized(r.proxy.calls[start:])
        self.harness("89012")
        start = len(r.proxy.calls)
        self.ask(QUESTION)
        assert PLACE not in serialized(r.proxy.calls[start:]) and REASON not in serialized(
            r.proxy.calls[start:]
        )
        self.harness()
        r.check("group-approved-and-unapproved-private-first-model-inputs", approved_grant=approved)

        local = r.api("ingest", event("team-local", "例会签到。", scope=TEAM))
        card = event(
            "team-card",
            "例会卡片：雪松厅。",
            topic="雪松会场卡",
            scope=TEAM,
            kind="summary",
            author="MASTER",
            source_refs=[group, ref(local)],
        )
        published = ref(r.api("candidates", card))
        assert self.read(published, TEAM)["text"] == card["content"]
        r.api("organize",dict(context=audience(TEAM,"ORCHESTRATOR"),limit=256))
        directory=dict(context=audience(TEAM),view="L0",limit=32)
        detail=dict(context=audience(TEAM),view="L1",axis="SUBJECT",title="雪松会场卡",limit=4)
        assert "雪松会场卡" in serialized(r.api("cards",directory))
        assert r.api("cards",detail)["items"][0]["items"][0]["grant_dependent"]
        assert (
            r.api("candidates", dict(card, event_id="private-card", source_refs=[group, private_reason]), 409)[
                "code"
            ]
            == "SOURCE_CHANGED"
        )
        assert PLACE in r.msg("p2-team", QUESTION) and PLACE in self.master()
        held = next(
            e["data"]
            for e in reversed(r.audit("p2-team"))
            if e["type"] == "memory_durable" and e["data"]["kind"] == "SELF_EXPRESSION"
        )
        echoed = dict(record_id=held["recordId"], revision=held["revision"])
        assert self.read(echoed, TEAM)["grant_dependent"]
        self.revoke(console_grant)
        assert "雪松会场卡" not in serialized(r.api("cards",directory)) and not r.api("cards",detail)["items"]
        for candidate in (group, published, echoed):
            assert self.read(candidate, TEAM)["status"] == "NOT_FOUND"
        assert self.read(group, target)["text"] == notice
        assert PLACE not in serialized(self.lookup(TEAM, as_of_known_seq=health["head_seq"] + 12))
        assert r.api("candidates", dict(card, event_id="late-card"), 409)["code"] == "SOURCE_CHANGED"
        r.check("revoke-hides-unchanged-card-reply-and-historical-window", card=published, echoed=echoed)
        self.restart_pending(health["stream_id"])
        self.late_registration(group)
        self.action_gate(group)

        revised = r.api(
            "corrections",
            dict(
                context=audience(GROUP, "ORCHESTRATOR"),
                source_instance="neuralcloud-java",
                event_id="edit-notice",
                record_id=group["record_id"],
                expected_revision=1,
                operation="REPLACE",
                reason="发件人编辑",
                content="例会换到银杏厅。",
            ),
        )
        assert (
            self.read(ref(revised), target)["status"]
            == self.read(group, target, "READ")["status"]
            == "NOT_FOUND"
        )
        new_grant = self.decision(ref(revised), target)
        assert self.read(ref(revised), target)["text"] == "例会换到银杏厅。"
        r.api(
            "corrections",
            dict(
                context=audience(GROUP, "ORCHESTRATOR"),
                source_instance="neuralcloud-java",
                event_id="withdraw-notice",
                record_id=group["record_id"],
                expected_revision=2,
                operation="RETRACT",
                reason="撤回",
            ),
        )
        assert self.read(ref(revised), target)["status"] == "NOT_FOUND"
        r.check("edition-and-retraction-expire-exact-version-grants", revised=ref(revised), grant=new_grant)

    def verify_author(self, record, target):
        r = self.run
        route = "visibility-grants"
        cmd = dict(
            context=audience(private(AUTHOR), "ORCHESTRATOR"),
            source_instance="neuralcloud-java",
            event_id="author-proof",
            source_ref=record,
            target_conversation_id=target,
            use="READ",
            reason="身份校验",
        )
        assert send(r.base + route, cmd, r.token, 403)["code"] == "FORBIDDEN"
        for scope in (private(OTHER), "console:author"):
            assert (
                send(r.base + route, dict(cmd, context=audience(scope, "ORCHESTRATOR")), self.token, 403)[
                    "code"
                ]
                == "FORBIDDEN"
            )
        assert (
            send(r.base + route, dict(cmd, source_ref=dict(record, revision=2)), self.token, 409)["code"]
            == "SOURCE_CHANGED"
        )
        cmd.update(target_conversation_id="console:p2-check")

        def call(path, body, status=200):
            uri = "/v2/visibility-grants" + ("/{grant_id}/revoke" if "/revoke" in path else "")
            op = r.spec["paths"][uri]["post"]

            def validate(sch, data):
                Draft202012Validator(dict(sch, components=r.spec["components"])).validate(data)

            validate(op["requestBody"]["content"]["application/json"]["schema"], body)
            result = send(r.base + path, body, self.token, status)
            validate(op["responses"][str(status)]["content"]["application/json"]["schema"], result)
            return result

        grant = call(route, cmd)
        assert call(route, cmd) == grant
        assert (
            r.api("receipts?" + urlencode(dict(source_instance="neuralcloud-java", event_id=cmd["event_id"])))
            == grant
        )
        path = route + "/" + grant["grant_id"] + "/revoke"
        revoke = dict(
            context=cmd["context"],
            source_instance="neuralcloud-java",
            event_id="revoke-proof",
            reason="验证结束",
        )
        assert (
            send(
                r.base + path, dict(revoke, context=audience(private(OTHER), "ORCHESTRATOR")), self.token, 403
            )["code"]
            == "FORBIDDEN"
        )
        receipt = call(path, revoke)
        assert call(path, revoke) == receipt and receipt["seq"] > grant["seq"]
        r.check("author-only-decisions-idempotent-command-and-durable-receipts", grant=grant, revoke=receipt)

    def restart_pending(self, stream):
        r = self.run
        count_before = len(r.onebot.deliveries)
        self.pause_model = True
        with ThreadPoolExecutor(1) as pool:
            turn = pool.submit(r.msg, "p2-team", "继续。")
            assert self.first_request.wait(6)
            with sqlite3.connect(r.audit_dir / "memory-v2-misty-dev.sqlite") as db:
                assert (
                    db.execute(
                        "SELECT count(*) FROM inputs WHERE scope=? AND status='PREPARED' AND material LIKE '%失效%'",
                        (TEAM,),
                    ).fetchone()[0]
                    > 0
                )
            r.hproc.kill()
            r.hproc.wait(timeout=8)
            self.release_model.set()
            assert turn.exception(timeout=10) is not None
        r.mproc.stdin.close()
        assert r.mproc.wait(timeout=10) == 0
        assert r.memory()["stream_id"] == stream
        self.harness()
        for worker in (lambda: r.msg("p2-team", "继续。"), self.master):
            start = len(r.proxy.calls)
            worker()
            requests = r.proxy.calls[start:]
            assert (
                "失效" in serialized(requests[0])
                and PLACE not in serialized(requests)
                and REASON not in serialized(requests)
            )
        start = len(r.proxy.calls)
        self.master()
        assert "记忆更正" not in serialized(r.proxy.calls[start]) and len(r.onebot.deliveries) == count_before
        r.check("prepared-correction-survives-process-loss-for-two-workers-and-clears-prefix")

    def late_registration(self, record):
        r = self.run
        grant = self.decision(record, TEAM)
        self.harness()
        stop(r.hproc)
        r.harness_start(endpoint=f"http://127.0.0.1:{r.relay.server_port}/")
        r.relay.pause_next = True
        with ThreadPoolExecutor(1) as pool:
            old = pool.submit(r.msg, "p2-team", QUESTION)
            assert r.relay.snapshot_taken.wait(5)
            self.revoke(grant)
            self.master()
            offset = len(r.proxy.calls)
            r.relay.release.set()
            assert old.exception(timeout=10) is not None
            assert PLACE not in serialized(r.proxy.calls[offset:])
        start = len(r.proxy.calls)
        r.msg("p2-team", "继续。")
        assert "失效" in serialized(r.proxy.calls[start]) and PLACE not in serialized(r.proxy.calls[start:])
        r.check("late-read-registration-cannot-publish-revoked-source")

    def action_gate(self, record):
        r = self.run
        grant = self.decision(record, "console:p2-actions")
        cp = os.pathsep.join([str(r.hjar), str(r.harness / "java/target/test-classes")])
        for mode, samples in (("allowed", 1), ("revoked", 0)):
            self.before_action = (lambda: self.revoke(grant)) if mode == "revoked" else None
            args = [
                "java",
                *r.native_args,
                "-cp",
                cp,
                "neuralcloud.controlplane.memory.StageTwoActionProbe",
                r.base[:-3],
                r.model_base + "/v1/chat/completions",
                str(r.out / "task-probe"),
                mode,
            ]
            proc = subprocess.run(
                args,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                env=r.env,
                timeout=25,
                creationflags=HIDDEN,
            )
            assert proc.returncode == 0 and f"samples={samples}" in proc.stdout, (proc.stdout, proc.stderr)
        self.before_action = None
        r.check(
            "task-worker-checks-current-grant-immediately-before-action",
            authorized_samples=1,
            revoked_samples=0,
        )

    def live_gemini(self):
        r = self.run
        config = r.harness / "java/src/main/resources/config/chat-worker-gemini.properties"
        settings = dict(
            line.split("=", 1)
            for line in config.read_text(encoding="utf-8").splitlines()
            if line and not line.startswith("#")
        )
        from urllib.parse import urlsplit

        endpoint = urlsplit(settings["endpoint"])
        if endpoint.scheme != "https" or endpoint.netloc != "generativelanguage.googleapis.com":
            raise ValueError("Expected the official Google Gemini model endpoint")
        key = settings["apiKeyEnv"]
        if not r.env.get(key):
            raise ValueError("Missing " + key)
        r.harness_env = r.env.copy()
        r.env.pop(key)
        r.harness_vm_args = []
        console = r.console_config.read_text(encoding="utf-8")
        r.console_config.write_text(
            re.sub(r"(?m)^chatWorkerConfig=.*$", "chatWorkerConfig=" + config.as_posix(), console),
            encoding="utf-8",
        )
        r.memory()
        self.harness()
        notice = "项目例会改到周六上午十点，地点是雪松厅。"
        self.ask(notice, group=True, timeout=120)
        source = self.source("qq-group-12345-23456", notice)
        self.ask("请记下，我只在这个私聊说明：例会改期是因为靛青样机晚到。", timeout=120)
        question = "请核对例会原始通知。通知里约在哪个厅、几点开始，有写改期原因吗？"
        steps = []

        def turn(label):
            chain = "qq-private-12345-" + OTHER
            chains = send(r.console + "audit/chains")["chains"]
            offset = len(r.audit(chain)) if any(c["chain"] == chain for c in chains) else 0
            answer = self.ask(question, timeout=120)
            for _ in range(60):
                events = r.audit(chain)[offset:]
                if events[-1]["type"] == "memory_durable":
                    break
                time.sleep(0.05)
            plan = next(e["data"] for e in events if e["type"] == "chat_worker_turn_plan")
            assert (
                plan["usage"]["promptTokenCount"] > 0
                and plan["kind"] == "HISTORY"
                and any(e["type"] == "qq_delivery" for e in events)
            )
            recalls = [b for e in events if e["type"] == "memory_recall" for b in e["data"]["bindings"]]
            assert REASON not in serialized(events) and REASON not in answer
            steps.append(dict(step=label, answer=answer, plan=plan, events=events))
            dump(r.out / "gemini-grants.json", steps)
            print(label, answer, flush=True)
            return answer, recalls, events

        self.harness(OTHER)
        answer, items, _ = turn("授权前")
        assert PLACE not in answer and all(b["id"] != source["record_id"] for b in items)
        self.harness()
        grant = self.decision(source, private(OTHER))
        self.harness(OTHER)
        answer, items, _ = turn("授权后")
        assert (
            PLACE in answer
            and re.search(r"(10|十)(?:[:：]00|点|時|时)", answer)
            and any(b["id"] == source["record_id"] for b in items)
        )
        self.harness()
        self.revoke(grant)
        self.harness(OTHER)
        answer, items, events = turn("撤销后")
        assert PLACE not in answer and all(b["id"] != source["record_id"] for b in items)
        assert any(e["type"] == "memory_context_reset" and "失效" in serialized(e["data"]) for e in events)
        r.check("real-gemini-before-grant-after-grant-and-after-revocation", grant=grant, source=source)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--harness", type=Path, default=ROOT.parent / "neuralcloud-harness")
    p.add_argument(
        "--gemini",
        action="store_true",
        help="Use the official Gemini Chat Worker for the three disclosure states.",
    )
    args = p.parse_args()
    run = Run(args.harness)
    print("Evidence:", run.out, flush=True)
    try:
        case = Case(run)
        if args.gemini:
            case.live_gemini()
        else:
            case.exercise()
        print("Passed", len(run.results), "stage 2 groups.", flush=True)
    finally:
        run.close()


if __name__ == "__main__":
    main()
