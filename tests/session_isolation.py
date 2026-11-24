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


def identity_event(key, account, name, privacy):
    """确认备注与称呼取不同的合成文本，以便检验两种读取目的的输出。"""
    return event(key, "测试用核验备注 " + key, context=context(QQ_GROUP, "ORCHESTRATOR"), privacy=privacy,
                 identity=dict(account_id=account, name=name))


def read(run, audience, *targets, kind="HISTORY", **fields):
    """按原读取目的和本次受众一次核对多条记录及其指定修订。"""
    return run.api("read", dict(context=audience, kind=kind, targets=list(targets), **fields))["items"]


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

    def assert_identities(self, items, expected, audience):
        """逐个账号核对已确认称呼、确认修订和原请求受众，正文不能混入核验备注。"""
        self.assertCountEqual([(item["ref"], item["identity_account_id"], item["text"]) for item in items], expected)
        for item in items:
            self.assertEqual([ref(source) for source in item["source_refs"]], [item["ref"]])
            self.assertEqual(item["citation_scope"], audience["conversation_id"])
            self.assertIs(item["grant_dependent"], True)

    def test_default_shares_all_privacy_levels(self):
        """正例。三种隐私等级均能通过原会话及跨渠道读取，QQ 本人历史仍只交付本人发言。"""
        run = self.service()
        health = run.memory()
        group = group_message("group-observation", "银杏样册使用蓝色封面。", "public")
        mobile = event("mobile-observation", "银杏样册附有勘误页。", context=MOBILE_PRIVATE, topic=TOPIC,
                       speaker="小秋", source_venue="合成移动私聊", privacy="private")
        peer = QQ_PRIVATE["qq"]
        private_channel = dict(platform="qq", type="qq_private", account_id=peer["account_id"], sender_id=peer["peer_id"],
                               conversation_id=QQ_PRIVATE["conversation_id"], message_id="synthetic-private")
        sensitive = event("private-observation", "银杏样册还有合成的批注。", topic=TOPIC, privacy="sensitive",
                          context=dict(QQ_PRIVATE, actor_id="ROUTER", role="ROUTER"), channel=private_channel,
                          speaker="合成发言人", source_venue="合成 QQ 私聊")
        originals = [(ref(run.api("ingest", source)), source) for source in (group, mobile, sensitive)]
        records = [record for record, _ in originals]

        # QQ 私聊在原会话引用自己的敏感原话。移动群以另一受众的身份回读这三份证据。
        hits = run.api("recall", query(TOPIC, context=QQ_PRIVATE, use="CITE"))["items"]
        self.assert_sources(hits, originals, QQ_PRIVATE["conversation_id"])
        self.assertIs(health.get("session_isolation"), False)
        self.assert_sources(read(run, MOBILE_GROUP, *records, use="READ"), originals, None)
        own = query(TOPIC, context=QQ_PRIVATE, kind="SELF_HISTORY", before="2099-01-01T00:00:00Z")
        self.assertEqual([item["ref"] for item in run.api("recall", own)["items"]], [records[2]])
        personal, third_party = read(run, QQ_PRIVATE, records[2], records[0], kind="SELF_HISTORY")
        self.assertEqual((personal["ref"], personal["text"]), (records[2], sensitive["content"]))
        self.assertEqual(third_party, dict(record_id=records[0]["record_id"], status="NOT_FOUND"))

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
        visible, sensitive, replaced, withdrawn, invalid = read(shared, QQ_PRIVATE, current, secret, first, removed, old_reply)
        self.assertEqual((visible["ref"], visible["text"], visible["citation_scope"]),
                         (current, "银杏样册改用新编号。", QQ_PRIVATE["conversation_id"]))
        self.assertEqual(sensitive["status"], "ONLINE", sensitive)
        self.assertEqual((sensitive["ref"], sensitive["text"], sensitive["citation_scope"]),
                         (secret, "银杏样册附有限阅清单。", QQ_PRIVATE["conversation_id"]))
        for item, target in ((replaced, first), (withdrawn, removed), (invalid, old_reply)):
            self.assertNotIn("text", item)
            self.assertEqual(item["record_id"] if item["status"] == "NOT_FOUND" else item["ref"]["record_id"],
                             target["record_id"])
        hits = shared.api("recall", query(TOPIC, context=QQ_PRIVATE))["items"]
        self.assertCountEqual([item["ref"] for item in hits], [current, secret])
        details = dict(context=QQ_PRIVATE, view="L1", axis="SUBJECT", title=TOPIC, limit=16)
        cards = shared.api("cards", details)["items"]
        self.assertCountEqual([item["ref"] for row in cards for item in row["items"]], [current, secret])

        own = query(TOPIC, context=QQ_PRIVATE, kind="SELF_HISTORY", before="2099-01-01T00:00:00Z")
        self.assertEqual(shared.api("recall", own)["items"], [])
        denied = event("denied", "样册摘要", kind="summary", context=QQ_PRIVATE, author="MASTER", source_refs=[secret])
        self.assertEqual(shared.api("candidates", denied, 403)["code"], "FORBIDDEN")
        # 回复保留精确的外会话来源，回切时也必须按当前受众重新核对。
        reply = ref(shared.api("ingest", event("reply", "我已收到新编号。", kind="self_expression",
                        context=dict(QQ_PRIVATE, actor_id="ROUTER", role="ROUTER"), source_refs=[current])))
        delivered, = read(shared, QQ_PRIVATE, reply, use="CITE")
        self.assertEqual((delivered["ref"], delivered["source_refs"]), (reply, [current]))
        # 在公开写入口由 Router 交付 Master 摘要。其敏感来源须随摘要一起复核。
        summary = ref(shared.api("candidates", dict(denied, event_id="summary",
                               context=dict(QQ_PRIVATE, actor_id="ROUTER", role="ROUTER"))))
        delivered, = read(shared, QQ_PRIVATE, summary, use="CITE")
        self.assertEqual((delivered["ref"], delivered["source_refs"]), (summary, [secret]))
        close_memory(shared)

        restored = self.service("true", shared.ledger, token)
        self.assertIs(restored.memory().get("session_isolation"), True)
        hidden = [current, reply, secret, summary]
        self.assertEqual(read(restored, QQ_PRIVATE, *hidden, use="CITE"),
                         [dict(record_id=record["record_id"], status="NOT_FOUND") for record in hidden])
        self.assertEqual(restored.api("recall", query(TOPIC, context=QQ_PRIVATE, use="CITE"))["items"], [])
        self.assertEqual(restored.api("cards", details)["items"], [])
        authorized, = read(restored, MOBILE_GROUP, current, use="CITE")
        self.assertEqual((authorized["ref"], authorized["citation_scope"]), (current, MOBILE_GROUP["conversation_id"]))
        print("隔离回切", dict(ungranted=hidden, still_granted=current, sensitive=secret,
                           replaced=first, withdrawn=removed, invalid=old_reply), flush=True)

    def test_confirmed_identity_directory(self):
        """正例。群及私聊按已确认账号、完整称呼或记录 ID 取证，多个账号可以各有自己的称呼。"""
        run = self.service("false")
        health = run.memory()
        first_account = QQ_PRIVATE["qq"]["peer_id"]
        other_account = QQ_AUTHOR["qq"]["peer_id"]
        first_name, other_name = "合成纸鸢甲", "合成蓝页乙"
        first = ref(run.api("ingest", identity_event("identity-first", first_account, first_name, "sensitive")))
        other = ref(run.api("ingest", identity_event("identity-other", other_account, other_name, "private")))
        both = [(first, first_account, first_name), (other, other_account, other_name)]

        # 公开接口能力随服务版本发布。目录可列举不同账号，也可由别的渠道逐个精确查找。
        self.assertIn("identity_lookup", health["capabilities"])
        listed = run.api("recall", query("", context=MOBILE_GROUP, kind="IDENTITY"))
        self.assert_identities(listed["items"], both, MOBILE_GROUP)
        self.assertNotEqual(listed["assessment"]["state"], "CONFLICT")
        self.assertIs(listed["assessment"]["sufficient"], True)
        for term, expected in ((other_account, both[1:]), (first_name, both[:1]), ("纸鸢", [])):
            matched = run.api("recall", query(term, context=QQ_PRIVATE, kind="IDENTITY"))
            self.assert_identities(matched["items"], expected, QQ_PRIVATE)
        self.assert_identities(read(run, MOBILE_GROUP, first, other, kind="IDENTITY"), both, MOBILE_GROUP)
        own = run.api("recall", query("", context=QQ_PRIVATE, kind="SELF_IDENTITY"))["items"]
        self.assertEqual([(item["ref"], item["text"]) for item in own], [(first, first_name)])
        print("已确认的合成账号及来源", both, flush=True)

    def test_identity_confirmation_conflict_and_restore(self):
        """反例。冒名确认、无确认来源、称呼冲突和回切后的目录引用都不能成为确定身份。"""
        shared = self.service("false")
        shared.memory()
        account = QQ_PRIVATE["qq"]["peer_id"]
        initial = identity_event("identity-live", account, "合成青瓷", "public")
        alternate = identity_event("identity-alternate", account, "合成红笺", "private")
        denied = dict(initial, event_id="identity-denied", context=context(QQ_GROUP, "ROUTER"))
        self.assertEqual(shared.api("ingest", denied, 403)["code"], "FORBIDDEN")
        declared = ref(shared.api("ingest", group_message("identity-unconfirmed", "随口自称合成青瓷。", "public")))
        live = ref(shared.api("ingest", initial))
        conflict = ref(shared.api("ingest", alternate))
        lookup = query(account, context=MOBILE_GROUP, kind="IDENTITY", limit=1)
        # 按账号或按称呼查找时，都须在截取第一条之前检查这个账号的矛盾确认。
        for term in (account, "合成青瓷"):
            self.assertEqual(shared.api("recall", dict(lookup, query=term))["assessment"],
                             dict(state="CONFLICT", sufficient=False, absence_confirmed=False))
        shared.api("corrections", dict(context=context(QQ_GROUP, "ORCHESTRATOR"), source_instance="neuralcloud-java",
                                       event_id="identity-retract", reason="撤销合成的冲突确认", operation="RETRACT",
                                       record_id=conflict["record_id"], expected_revision=conflict["revision"]))
        self.assert_identities(shared.api("recall", lookup)["items"], [(live, account, "合成青瓷")], MOBILE_GROUP)
        unconfirmed, withdrawn = read(shared, MOBILE_GROUP, declared, conflict, kind="IDENTITY")
        for item, source in ((unconfirmed, declared), (withdrawn, conflict)):
            self.assertNotIn("text", item)
            self.assertEqual(item["record_id"] if item["status"] == "NOT_FOUND" else item["ref"]["record_id"],
                             source["record_id"])
        wrong_character = dict(MOBILE_GROUP, character_id="synthetic-other-character")
        self.assertEqual(shared.api("recall", dict(lookup, context=wrong_character), 403)["code"], "FORBIDDEN")

        # 在确认来源自己的会话里发表身份引用，再派生一层，确保回切测试只改变目的权限。
        group = context(QQ_GROUP, "CHAT_WORKER")
        echo = ref(shared.api("ingest", event("identity-echo", "本次目录给出合成青瓷。", scope=QQ_GROUP,
                   kind="self_expression", source_refs=[dict(live, purpose="IDENTITY")])))
        summary = ref(shared.api("candidates", event("identity-summary", "摘要采用该称呼。", scope=QQ_GROUP,
                      kind="summary", author="MASTER", source_refs=[echo])))
        self.assertEqual([item["ref"] for item in read(shared, group, echo, summary, use="CITE")], [echo, summary])
        close_memory(shared)

        isolated = self.service("true", shared.ledger)
        self.assertIn("identity_lookup", isolated.memory()["capabilities"])
        self.assertEqual(isolated.api("recall", query("", context=QQ_PRIVATE, kind="IDENTITY"))["items"], [])
        self.assertEqual(read(isolated, QQ_PRIVATE, live, kind="IDENTITY"),
                         [dict(record_id=live["record_id"], status="NOT_FOUND")])
        own = isolated.api("recall", query("", context=QQ_PRIVATE, kind="SELF_IDENTITY"))["items"]
        self.assertEqual([(item["ref"], item["text"]) for item in own], [(live, "合成青瓷")])
        ordinary, *hidden = read(isolated, group, live, echo, summary, use="CITE")
        self.assertEqual((ordinary["ref"], ordinary["text"]), (live, initial["content"]))
        self.assertEqual(hidden, [dict(record_id=record["record_id"], status="NOT_FOUND") for record in (echo, summary)])
        print("身份回切及来源复核", dict(live=live, withdrawn=conflict, hidden=[echo, summary]), flush=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
