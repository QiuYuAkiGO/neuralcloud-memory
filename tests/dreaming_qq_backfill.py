"""由 NC_DREAMING_V6_JAR 指定正式格式 6 Memory，原地升级后只从 HTTP 核对历史回扫和持久回执。"""
import os
import unittest
from pathlib import Path

from acceptance import ROOT, ServicePair, dump, ref, send
from dreaming import DreamingCase, GROUP, change, internal, publication
from stage2 import ACCOUNT, AUTHOR


LANE = "identity_backfill"
FIRST = "我也叫青笺。"
SECOND = "今天先排练第二段。"


class LegacyPeopleCase(DreamingCase):
    def setUp(self):
        """正式旧版从指定 JAR 启动，配置里仅人工确认柳舟对应的 QQ。"""
        self.service = ServicePair(ROOT.parent / "neuralcloud-harness", session_isolation="true")
        self.addCleanup(self.stop_memory)
        self.current_jar = self.service.memjar
        self.service.memjar = Path(os.environ["NC_DREAMING_V6_JAR"]).resolve(strict=True)
        dump(self.service.out / "people.json", {"character_id": "misty-dev", "people": [
            {"subject": "柳舟", "aliases": ["小柳"], "qq_sender_id": AUTHOR}]})
        with (self.service.out / "memory.properties").open("a", encoding="utf-8") as settings:
            settings.write("dreamingPeopleFile=people.json\n")
        self.assertNotIn("dreaming_qq_aliases", self.service.memory()["capabilities"])

    def post(self, route, body, expected=200):
        """透传各版本各自的公开 JSON，包括未声明格式 7 扩展字段的真实旧版命令。"""
        return send(self.service.base + "dreaming/" + route, body, self.service.token, expected)

    def restart(self):
        self.stop_memory()
        self.service.memjar = self.current_jar
        self.assertIn("dreaming_qq_aliases", self.service.memory()["capabilities"])

    def qq(self, number, content, sender=AUTHOR):
        return self.ingest("group-" + number, content, GROUP, channel=dict(platform="qq", type="qq_group",
                           account_id=ACCOUNT, sender_id=sender, conversation_id=GROUP, message_id=number))

    def subject_for(self, page, source):
        evidence, = [e for e in page["evidence"] if ref(e) == source]
        person, = [p for p in page["persons"] if p["person_id"] == evidence["speaker_person_id"]]
        return person


class IdentityBackfill(LegacyPeopleCase):
    def setUp(self):
        """旧版先覆盖两个 QQ 事件并发布主档，留存原命令和回执后原地升级。"""
        super().setUp()
        self.first = self.qq("701", FIRST)
        self.second = self.qq("702", SECOND)
        old_batch = self.batch(GROUP)
        self.old_profile = change("person", "柳舟", "我记得柳舟先排练第二段。", [self.second])
        self.old_command = publication(old_batch, "v6-profile", [self.old_profile])
        self.old_receipt = self.post("commit", self.old_command)
        self.restart()

    def test_backfill_persists_across_restart_without_rewinding_normal_progress(self):
        """中断后延续独立回扫页；回放两代回执既不重发首条学习也不改变主档和实时水位。"""
        first_page = self.batch(GROUP, limit=1)
        self.assertEqual(first_page.get("lane"), LANE)
        person = self.subject_for(first_page, self.first)
        self.assertEqual(person["subject"], "柳舟")
        claim = dict(person_id=person["person_id"], expected_identity_version=person["identity_version"],
                     name="青笺", relation="SELF_NAME", scope="global", source_refs=[dict(self.first, quote=FIRST)])
        command = dict(publication(first_page, "learn-old-name", []), lane=LANE, name_claims=[claim])
        saved = self.post("commit", command)
        self.assertEqual((saved["status"], saved["lane"], saved["after"], saved["through"]),
                         ("DURABLE", LANE, first_page["after"], first_page["through"]))

        self.restart()
        self.assertEqual(self.receipt("learn-old-name"), saved)
        self.assertEqual(self.post("commit", command), saved)
        self.assertEqual(self.receipt("v6-profile"), self.old_receipt)
        self.assertEqual(self.post("commit", self.old_command), self.old_receipt)
        second_page = self.batch(GROUP, limit=1)
        self.assertEqual((second_page.get("lane"), second_page["after"]), (LANE, saved["through"]))
        restored = self.subject_for(second_page, self.second)
        self.assertEqual(restored["person_id"], person["person_id"])
        self.assertIn("青笺", restored["aliases"])
        self.assertGreater(restored["identity_version"], person["identity_version"])
        self.assertProfiles(self.old_profile)
        self.post("commit", dict(publication(second_page, "finish-history", []), lane=LANE, name_claims=[]))

        fresh = self.qq("703", "我带了新的谱夹。")
        live = self.batch(GROUP, limit=1)
        self.assertNotEqual(live.get("lane"), LANE)
        self.assertEqual(live["after"], self.old_receipt["through"])
        current = self.subject_for(live, fresh)
        self.assertEqual((current["person_id"], current["identity_version"]),
                         (restored["person_id"], restored["identity_version"]))

    def test_backfill_positions_cannot_acknowledge_a_normal_batch(self):
        """回扫第一页的数字不能冒充实时位置，拒绝后仍可领取该历史页和原始回执。"""
        page = self.batch(GROUP, limit=1)
        self.assertEqual(page.get("lane"), LANE)
        self.assertLess(page["after"], self.old_receipt["through"])
        wrong = dict(publication(page, "wrong-lane", []), name_claims=[])
        self.post("commit", wrong, 409)
        self.assertEqual(self.receipt("wrong-lane")["status"], "NOT_FOUND")
        self.assertEqual(self.batch(GROUP, limit=1), page)
        self.assertEqual(self.receipt("v6-profile"), self.old_receipt)


if __name__ == "__main__":
    unittest.main()
