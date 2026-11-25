"""通过 Memory v2 的公开入口核对 QQ 私聊按作者引用历史原话。"""
import unittest

from acceptance import ROOT, ServicePair, context, event, query, ref
from session_isolation import close_memory


BOT, AUTHOR, PEER, GROUP = "12345", "54321", "67890", "23456"
AUDIENCE = context(f"c2c:onebot:{BOT}:{PEER}", "CHAT_WORKER",
                   dict(account_id=BOT, peer_id=PEER, member_group_ids=[GROUP]))
CUTOFF = "2026-11-25T10:00:00Z"


def group_event(key, hour, words, *, bot=BOT, sender=AUTHOR, kind="qq_group"):
    """构造渠道见证的发言；假名只会出现在干扰消息正文里。"""
    scope = f"group:onebot:{bot}:{GROUP}"
    channel = dict(platform="qq", type=kind, account_id=bot, sender_id=sender,
                   conversation_id=scope, message_id=key)
    return event(key, words, scope=scope, channel=channel, source_venue="合成档案组",
                 occurred_at=f"2026-11-25T{hour:02d}:00:00Z")


def author_query(topic="", **fields):
    """作者仅是筛选条件，引用受众始终由提问者的 QQ 私聊上下文给出。"""
    return query(topic, context=AUDIENCE, use="CITE", before=CUTOFF, qq_sender_id=AUTHOR, **fields)


class QqAuthorHistory(unittest.TestCase):
    def service(self, isolation):
        run = ServicePair(ROOT.parent / "neuralcloud-harness", session_isolation=isolation)
        health = run.memory()
        self.addCleanup(close_memory, run)
        return run, health

    def test_author_utterances_with_or_without_topic(self):
        """正例。从共享历史中按发生时间取原话，给定话题时能命中较早的本人发言。"""
        run, health = self.service("false")
        latest = group_event("latest", 9, "我在整理车票。")
        earliest = group_event("earliest", 7, "我把 CONIFER 写在封面上。")
        middle = group_event("middle", 8, "我来给箱子编号。")
        latest_ref, earliest_ref, middle_ref = [
            ref(run.api("ingest", row)) for row in (latest, earliest, middle)
        ]
        # 同一 QQ 号在另一机器人处的更新发言，以及与本轮同时发生的发言，都不能占据名额。
        run.api("ingest", group_event("other-bot", 9, "CONIFER 在另一处。", bot="34567"))
        run.api("ingest", group_event("at-cutoff", 10, "CONIFER 刚才才有更新。"))

        self.assertIn("qq_author_history", health["capabilities"])
        for term, expected in (("", [(latest_ref, latest), (middle_ref, middle)]),
                               ("CONIFER", [(earliest_ref, earliest)])):
            with self.subTest(query=term):
                items = run.api("recall", author_query(term, limit=2))["items"]
                self.assertEqual([item["ref"] for item in items], [record for record, _ in expected])
                for item, (record, original) in zip(items, expected):
                    self.assertEqual(item["source_refs"], [record])
                    self.assertEqual(item["text"], original["content"])
                    self.assertEqual(item["source_venue"], original["source_venue"])
                    self.assertEqual(item["citation_scope"], AUDIENCE["conversation_id"])

    def test_sender_does_not_grant_access_or_match_mentions(self):
        """反例。原作者未对外开放的原话、可见的他人转述及不合法的参数组合均不交付。"""
        run, _ = self.service("true")
        # 共同群的显式通知可向成员引用，普通发言需要授权。这两条分别只满足一个条件。
        run.api("ingest", group_event("hidden", 8, "我在查 CONIFER 的索引。"))
        run.api("ingest", group_event("mention", 9, "青檀聊过 CONIFER。", sender="65432",
                                      kind="qq_group_notice"))
        hits = run.api("recall", author_query("CONIFER"))
        self.assertEqual(hits["items"], [])
        self.assertEqual(hits["assessment"]["state"], "NOT_FOUND")

        for invalid in (query("", context=AUDIENCE, use="CITE", before=CUTOFF),
                        query("CONIFER", context=AUDIENCE, kind="SELF_HISTORY",
                              before=CUTOFF, qq_sender_id=AUTHOR)):
            with self.subTest(kind=invalid["kind"]):
                run.api("recall", invalid, expected=422)


if __name__ == "__main__":
    unittest.main()
