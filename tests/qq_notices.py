"""用两个已接入的 QQ 群验证通知、在线版本以及私聊的成员受众边界。"""
from acceptance import ROOT, ServicePair, context, event, ref, stop


def verify(run):
    assert "qq_member_notices" in run.memory()["capabilities"]
    group = "group:onebot:12345:23456"
    channel = dict(type="qq_group_notice", platform="qq", account_id="12345", sender_id="54321",
                   conversation_id=group, message_id="10")
    member = context("c2c:onebot:12345:67890")
    member["qq"] = dict(account_id="12345", peer_id="67890", member_group_ids=["34567", "23456"])
    outside = {**member, "qq": {**member["qq"], "member_group_ids": ["34567"]}}
    first = run.api("ingest", event("first", "南门", scope=group, channel=channel))
    ordinary = run.api("ingest", event("chatter", "旁听", scope=group, channel={**channel, "type": "qq_group"}))
    secret = run.api("ingest", event("secret", "内部", scope=group, channel=channel, privacy="sensitive"))

    def read(ctx, *targets):
        return run.api("read", dict(context=ctx, kind="HISTORY", use="CITE", targets=list(targets)))["items"]

    visible, hidden, restricted = read(member, ref(first), ref(ordinary), ref(secret))
    assert visible["text"] == "南门" and visible["member_notice"]
    assert hidden["status"] == restricted["status"] == read(outside, ref(first))[0]["status"] == "NOT_FOUND"
    derived = run.api("ingest", event("private", "南门", kind="self_expression", context=member, source_refs=[ref(first)]))
    assert read(outside, ref(derived))[0]["status"] == "NOT_FOUND"
    update = dict(context=context(group), source_instance="neuralcloud-java", event_id="change", operation="REPLACE",
                  record_id=first["record_id"], expected_revision=1, reason="作者变更", content="北门",
                  channel={**channel, "message_id": "11"})
    run.api("corrections", {**update, "channel": {**update["channel"], "sender_id": "67890"}}, expected=403)
    second = run.api("corrections", update)
    assert ref(second) == {**ref(first), "revision": 2} and run.api("corrections", update) == second
    assert [i["status"] for i in read(member, ref(first), ref(second), {"record_id": derived["record_id"]})] == [
        "NOT_FOUND", "ONLINE", "SOURCE_CHANGED"]
    assert read(member, ref(second))[0]["text"] == "北门"
    print("PASS QQ 成员名单、普通消息隔离、原作者修订、旧版及派生回复失效")


if __name__ == "__main__":
    run = ServicePair(ROOT.parent / "neuralcloud-harness")
    try:
        verify(run)
    finally:
        if run.mproc:
            stop(run.mproc)
