"""通过真实 Memory v2 与 OpenAPI 验证同群通知、换版及私聊受众边界。"""
from pathlib import Path
import argparse

from acceptance import ROOT, ServicePair, context, event, ref, stop


def verify(run):
    """用两组具有相同本地成员编号的实例验证群隔离，并检查派生回复随旧版失效。"""
    assert "mobile_member_notices" in run.memory()["capabilities"]
    group = "group:mistyai:field"
    member = {**context("c2c:mistyai:field:2"), "mobile_member": {"instance": "field", "member_id": "2"}}
    channel = dict(platform="mistyai", type="mobile_group_notice", account_id="field", sender_id="1",
                   conversation_id=group, message_id="1")
    first = run.api("ingest", event("notice-1", "群通知第一版", scope=group, channel=channel))
    plain = run.api("ingest", event("ordinary", "普通聊天", scope=group))
    secret = run.api("ingest", event("sensitive", "受限通知", scope=group, privacy="sensitive",
                                     channel={**channel, "message_id": "2"}))
    def read(ctx, *targets, use="CITE"):
        return run.api("read", dict(context=ctx, kind="HISTORY", use=use, targets=list(targets)))["items"]

    assert [i["status"] for i in read(member, ref(first), ref(plain), ref(secret))] == ["ONLINE", "NOT_FOUND", "NOT_FOUND"]
    outside = {**context("c2c:mistyai:elsewhere:2"), "mobile_member": {"instance": "elsewhere", "member_id": "2"}}
    assert read(outside, ref(first))[0]["status"] == "NOT_FOUND"
    for ctx in (context(member["conversation_id"]), {**member, "conversation_id": "c2c:mistyai:field:3"}):
        run.api("read", dict(context=ctx, kind="HISTORY", use="CITE", targets=[ref(first)]), expected=403)
    derived = run.api("ingest", event("private-reply", "B 收到第一版", kind="self_expression",
                                      context=member, source_refs=[ref(first)]))
    assert read(member, ref(derived))[0]["grant_dependent"]
    command = dict(context=context(group), source_instance="neuralcloud-java", event_id="notice-3",
                   record_id=first["record_id"], expected_revision=1, operation="REPLACE",
                   reason="原作者在群内发送变更通知", content="群通知第二版", channel={**channel, "message_id":"3"})
    run.api("corrections", {**command, "channel": {**command["channel"], "sender_id": "2"}}, expected=403)
    second = run.api("corrections", command)
    assert ref(second) == {**ref(first), "revision": 2}
    assert run.api("corrections", command) == second
    items = read(member, ref(first), ref(second), {"record_id": derived["record_id"]})
    assert [i["status"] for i in items] == ["NOT_FOUND", "ONLINE", "SOURCE_CHANGED"]
    assert items[1]["text"] == "群通知第二版" and "text" not in items[2]
    assert read(outside, ref(second))[0]["status"] == "NOT_FOUND"
    assert read(member, ref(second), use="READ")[0]["status"] == "ONLINE"
    retract = {**command, "event_id": "notice-4", "expected_revision": 2, "operation": "RETRACT",
               "channel": {**channel, "message_id": "4"}}
    del retract["content"]
    run.api("corrections", retract)
    assert read(member, {"record_id": first["record_id"]})[0]["status"] == "NOT_FOUND"
    print("PASS 同群通知现版、群外隔离、凭据绑定、普通与敏感消息隔离、作者、重试、更正与撤回")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, default=ROOT.parent / "neuralcloud-harness")
    # 在隔离模式单独验证同群成员凭据授予的通知读取权。
    run = ServicePair(parser.parse_args().harness, session_isolation="true")
    try:
        verify(run)
    finally:
        if run.mproc:
            stop(run.mproc)
