"""通过 Memory v2 的配置入口、健康检查和业务 HTTP 接口验收临时跨会话共享。"""
import secrets
import unittest

from acceptance import ROOT, ServicePair, context, event, query, ref, send


QQ_GROUP = "group:onebot:12345:23456"
QQ_PRIVATE = context("c2c:onebot:12345:67890", "CHAT_WORKER", dict(account_id="12345", peer_id="67890"))
QQ_AUTHOR = context("c2c:onebot:12345:54321", "ORCHESTRATOR", dict(account_id="12345", peer_id="54321"))
MOBILE_PRIVATE = dict(context("c2c:mistyai:field:2"), mobile_member=dict(instance="field", member_id="2"))
MOBILE_GROUP = context("group:mistyai:field", "MASTER")
TOPIC = "银杏样册"


def group_message(key, content, privacy):
    """构造普通 QQ 群发言，保留发送者、来源会话和消息 ID。"""
    channel = dict(platform="qq", type="qq_group", account_id="12345", sender_id="54321",
                   conversation_id=QQ_GROUP, message_id=key)
    return event(key, content, scope=QQ_GROUP, channel=channel, topic=TOPIC, speaker="阿岚",
                 source_venue="合成评审群", privacy=privacy)


def read(run, audience, *targets, **fields):
    """用同一个受众一次核对多条记录及其指定修订。"""
    return run.api("read", dict(context=audience, kind="HISTORY", targets=list(targets), **fields))["items"]


def organize(run):
    """通过正式整理入口发布这批合成资料的目录。"""
    run.api("organize", dict(context=context(QQ_GROUP, "ORCHESTRATOR"), limit=256))


def close_memory(run):
    """正式启动参数 --stop-on-eof 使服务在下次进程复用数据前完成正常退出。"""
    if run.mproc is not None:
        run.mproc.stdin.close()
        run.mproc.wait(timeout=15)


class SessionIsolation(unittest.TestCase):
    def service(self, setting=None, ledger=None, grant_token=None):
        """每次启动独立进程。指定相同目录时使用上一进程的原数据。"""
        run = ServicePair(ROOT.parent / "neuralcloud-harness", ledger=ledger, session_isolation=setting)
        self.addCleanup(close_memory, run)
        if grant_token:
            run.env["NC_TEST_GRANT_TOKEN"] = grant_token
            with (run.out / "memory.properties").open("a", encoding="utf-8") as stream:
                stream.write("grantAccountId=12345\ngrantAccessTokenEnv=NC_TEST_GRANT_TOKEN\n")
        return run

    def assert_sources(self, items, originals, citation_scope):
        """逐条用写入回执核对版本、原话、发言人和场合，引用权归本次请求受众。"""
        self.assertCountEqual([item.get("ref") for item in items], [record for record, _ in originals])
        by_id = {item["ref"]["record_id"]: item for item in items}
        for record, source in originals:
            item = by_id[record["record_id"]]
            self.assertEqual(item["source_refs"], [record])
            self.assertEqual(item["speaker"], source["speaker"])
            self.assertEqual(item["source_venue"], source["source_venue"])
            self.assertEqual(item["text"], source["content"])
            if citation_scope is None:
                self.assertEqual(item["use"], "READ")
                self.assertNotIn("citation_scope", item)
            else:
                self.assertEqual(item["citation_scope"], citation_scope)

    def test_default_shares_ordinary_channel_records(self):
        """正例。未配置开关，两个没有来源授权的受众可查阅 QQ 群及移动私聊原文。"""
        run = self.service()
        health = run.memory()
        group = group_message("group-observation", "银杏样册使用蓝色封面。", "public")
        mobile = event("mobile-observation", "银杏样册附有勘误页。", context=MOBILE_PRIVATE, topic=TOPIC,
                       speaker="小秋", source_venue="合成移动私聊", privacy="private")
        originals = [(ref(run.api("ingest", source)), source) for source in (group, mobile)]
        records = [record for record, _ in originals]

        # 私聊使用可引用召回，移动群按 ID 读取原文，两份数据都来自其他会话。
        hits = run.api("recall", query(TOPIC, context=QQ_PRIVATE, use="CITE"))["items"]
        self.assert_sources(hits, originals, QQ_PRIVATE["conversation_id"])
        self.assertIs(health.get("session_isolation"), False)
        self.assert_sources(read(run, MOBILE_GROUP, *records, use="READ"), originals, None)

        organize(run)
        directory = run.api("cards", dict(context=MOBILE_GROUP, view="L0", axis="SUBJECT", limit=16))["items"]
        self.assertTrue(any(row["title"] == TOPIC and row["ref"] in records for row in directory), directory)
        details = run.api("cards", dict(context=MOBILE_GROUP, view="L1", axis="SUBJECT", title=TOPIC, limit=16))
        self.assert_sources([item for row in details["items"] for item in row["items"]],
                            originals, MOBILE_GROUP["conversation_id"])
        print("默认共享的来源版本", records, flush=True)

    def test_boundaries_and_restore_on_same_data(self):
        """反例。共享限于可用原文，恢复隔离后只有原有授权可继续跨会话使用。"""
        with self.subTest(setting="TRUE"):
            # 大写配置必须因开关取值错误退出；进程一直运行也属于失败。
            bad = self.service("TRUE")
            bad.mproc, log = bad.launch("memory", ["-jar", str(bad.memjar), "--config", "memory.properties",
                                                   "--stop-on-eof"])
            self.assertNotEqual(bad.mproc.wait(timeout=8), 0)
            self.assertIn("sessionIsolation", log.read_text(encoding="utf-8"))

        token = secrets.token_urlsafe(36)
        initial = self.service("true", grant_token=token)
        initial.memory()
        first = ref(initial.api("ingest", group_message("first", "银杏样册使用旧编号。", "private")))
        secret = ref(initial.api("ingest", group_message("secret", "银杏样册附有限阅清单。", "sensitive")))
        removed = ref(initial.api("ingest", group_message("removed", "银杏样册另有插页。", "public")))
        old_reply = ref(initial.api("ingest", event("old-reply", "银杏样册按旧编号编排。", scope=QQ_GROUP,
                          kind="self_expression", source_refs=[first])))
        command = dict(context=context(QQ_GROUP, "ORCHESTRATOR"), source_instance="neuralcloud-java",
                       reason="原作者校正样册资料")
        current = ref(initial.api("corrections", dict(command, event_id="replace", record_id=first["record_id"],
                          expected_revision=first["revision"], operation="REPLACE", topic=TOPIC, content="银杏样册改用新编号。")))
        initial.api("corrections", dict(command, event_id="withdraw", record_id=removed["record_id"],
                                        expected_revision=removed["revision"], operation="RETRACT"))
        # 只把现版正式授权给移动群。QQ 私聊之后的访问完全依靠临时共享。
        grant = dict(command, context=QQ_AUTHOR, event_id="grant", source_ref=current,
                     target_conversation_id=MOBILE_GROUP["conversation_id"], use="CITE")
        send(initial.base + "visibility-grants", grant, token)
        organize(initial)
        close_memory(initial)

        shared = self.service("false", initial.ledger, token)
        self.assertIs(shared.memory().get("session_isolation"), False)
        visible, restricted, replaced, withdrawn, invalid = read(shared, QQ_PRIVATE, current, secret, first, removed, old_reply)
        self.assertEqual((visible["ref"], visible["text"], visible["citation_scope"]),
                         (current, "银杏样册改用新编号。", QQ_PRIVATE["conversation_id"]))
        self.assertEqual(restricted, dict(record_id=secret["record_id"], status="NOT_FOUND"))
        for item, target in ((replaced, first), (withdrawn, removed), (invalid, old_reply)):
            self.assertNotIn("text", item)
            self.assertEqual(item["record_id"] if item["status"] == "NOT_FOUND" else item["ref"]["record_id"],
                             target["record_id"])
        hits = shared.api("recall", query(TOPIC, context=QQ_PRIVATE))["items"]
        self.assertEqual([item["ref"] for item in hits], [current])
        details = dict(context=QQ_PRIVATE, view="L1", axis="SUBJECT", title=TOPIC, limit=16)
        cards = shared.api("cards", details)["items"]
        self.assertEqual([item["ref"] for row in cards for item in row["items"]], [current])

        own = query(TOPIC, context=QQ_PRIVATE, kind="SELF_HISTORY", before="2099-01-01T00:00:00Z")
        self.assertEqual(shared.api("recall", own)["items"], [])
        denied = event("denied", "样册摘要", kind="summary", context=QQ_PRIVATE, author="MASTER", source_refs=[current])
        self.assertEqual(shared.api("candidates", denied, 403)["code"], "FORBIDDEN")
        # 回复保留精确的外会话来源，回切时也必须按当前受众重新核对。
        reply = ref(shared.api("ingest", event("reply", "我已收到新编号。", kind="self_expression",
                        context=dict(QQ_PRIVATE, actor_id="ROUTER", role="ROUTER"), source_refs=[current])))
        delivered, = read(shared, QQ_PRIVATE, reply, use="CITE")
        self.assertEqual((delivered["ref"], delivered["source_refs"]), (reply, [current]))
        close_memory(shared)

        restored = self.service("true", shared.ledger, token)
        self.assertIs(restored.memory().get("session_isolation"), True)
        self.assertEqual(read(restored, QQ_PRIVATE, current, reply, use="CITE"),
                         [dict(record_id=record["record_id"], status="NOT_FOUND") for record in (current, reply)])
        self.assertEqual(restored.api("recall", query(TOPIC, context=QQ_PRIVATE, use="CITE"))["items"], [])
        self.assertEqual(restored.api("cards", details)["items"], [])
        authorized, = read(restored, MOBILE_GROUP, current, use="CITE")
        self.assertEqual((authorized["ref"], authorized["citation_scope"]), (current, MOBILE_GROUP["conversation_id"]))
        print("隔离回切", dict(ungranted=[current, reply], still_granted=current, sensitive=secret,
                           replaced=first, withdrawn=removed, invalid=old_reply), flush=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
