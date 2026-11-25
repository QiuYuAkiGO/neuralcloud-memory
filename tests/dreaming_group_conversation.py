"""通过正式 QQ 入账和 Dreaming 出口验收群成员自述、呼语和追问的归属。"""
import unittest

from dreaming import DreamingCase, GROUP, internal, publication
from dreaming_qq_aliases import speaker, subject_change
from stage2 import ACCOUNT, AUTHOR, OTHER, audience


class GroupConversation(DreamingCase):
    session_isolation = "true"

    def say(self, uid, name, number, text, **metadata):
        """每条消息只由自己的已认证账号发出，昵称和真实提及通过 QQ 渠道字段提供。"""
        return self.ingest(number, text, GROUP, context=audience(GROUP, "ROUTER"),
                           channel=dict(platform="qq", type="qq_group", account_id=ACCOUNT, sender_id=uid,
                                        conversation_id=GROUP, message_id=number, nickname=name, **metadata))

    def page(self):
        return self.post("batch", dict(context=internal(), scopes=[GROUP], limit=16, include_group_history=True))

    def test_addressing_a_peer_and_asking_a_follow_up_preserves_both_speakers_own_facts(self):
        """前段给出本人事实，末句单独问另一个人，两份认识仍各有明确的发件人与来源。"""
        first = self.say(AUTHOR, "月衡", "1001", "北芦，我每次给底片测光都会把数值写在方格本上，这个习惯已经保持了六年。你怎么记？")
        second = self.say(OTHER, "北芦", "1002", "我习惯把每卷胶卷的曝光参数写在牛皮纸袋上，拍完这些年一直这么做。",
                          mention_ids=[AUTHOR])
        batch = self.page()
        facts = []
        for source, name, text in ((first, "月衡", "我记得月衡每次都用方格本记测光数值，已经坚持六年。"),
                                   (second, "北芦", "我记得北芦习惯把胶卷曝光参数写在牛皮纸袋上。")):
            person = speaker(batch, source)
            self.assertEqual(person["subject"], name)
            facts.append(subject_change(person, name, text, source))
        self.assertNotEqual(facts[0]["person_id"], facts[1]["person_id"])
        command = dict(publication(batch, "members-own-words", facts), name_claims=[])
        self.assertEqual(self.post("commit", command)["status"], "DURABLE")
        current = self.post("profiles", {"context": internal()})["profiles"]
        self.assertCountEqual([(p["person_id"], p["subject"], p["text"], p["source_refs"]) for p in current],
                              [(p["person_id"], p["subject"], p["text"], p["source_refs"]) for p in facts])

    def test_a_question_or_quotation_alone_is_not_the_speakers_own_habit(self):
        """只有向别人发问或转述他人的第一人称时，单靠发件人身份不能建立其本人的习惯。"""
        question = self.say(AUTHOR, "月衡", "1011", "北芦，你习惯把测光数值写在方格本上吗？")
        quoted = self.say(OTHER, "北芦", "1012", "我转述小满的原话：“我习惯把曝光参数写在牛皮纸袋上。”")
        batch = self.page()
        for source, text in ((question, "我记得月衡习惯把测光数值写在方格本上。"),
                             (quoted, "我记得北芦习惯把曝光参数写在牛皮纸袋上。")):
            person = speaker(batch, source)
            fact = subject_change(person, person["subject"], text, source)
            command = dict(publication(batch, "unsupported-" + source["record_id"], [fact]), name_claims=[])
            with self.subTest(subject=person["subject"], fact=text):
                self.assertEqual(self.post("commit", command, 422)["code"], "INVALID_FIELD")
        self.assertEqual(self.post("profiles", {"context": internal()})["profiles"], [])


if __name__ == "__main__":
    unittest.main()
