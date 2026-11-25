"""用 NC_DREAMING_V6_JAR 建立分开的真实旧档，从公开出口验收有据可撤销的归并。"""
import unittest

from acceptance import context, ref
from dreaming import GROUP, INSTANCE, STUDIO, change, internal, publication
from dreaming_qq_aliases import claim
from dreaming_qq_backfill import LANE, LegacyPeopleCase
from stage2 import OTHER


class LegacyProfileMerge(LegacyPeopleCase):
    def upgrade_profiles(self, *profiles):
        """两边的认识先由旧版收据确认。升级后看完旧批，再引入唯一的新身份依据。"""
        old = publication(self.batch(GROUP, STUDIO), "old-profiles", list(profiles))
        self.assertEqual(self.post("commit", old)["status"], "DURABLE")
        self.restart()
        history = self.batch(GROUP, STUDIO)
        self.assertEqual(history.get("lane"), LANE)
        self.post("commit", dict(publication(history, "covered-history", []), lane=LANE, name_claims=[]))

    def acknowledge(self, number, name):
        """真实发件人柳舟明确自称别名；这条原话专门支持身份关系。"""
        quote = "我也叫" + name + "。"
        source = self.qq(number, quote)
        page = self.batch(GROUP, STUDIO)
        person = self.subject_for(page, source)
        item = claim(person, name, "SELF_NAME", dict(source, quote=quote))
        command = dict(publication(page, "confirm-" + number, []), name_claims=[item])
        self.assertEqual(self.post("commit", command)["status"], "DURABLE")
        return person["person_id"], source

    def profiles(self):
        return self.post("profiles", {"context": internal()})["profiles"]

    def test_confirmed_name_unites_old_self_descriptions_until_its_only_bridge_is_withdrawn(self):
        """别称档有本人的明确旧自述但没有可信账号。撤回唯一桥接后两份有据认识各自恢复。"""
        timing = self.qq("801", "我会在排练前校准节拍器。")
        archive = self.ingest("keeper", "我叫青笺。我会把老底片分袋保存。", STUDIO, speaker="青笺")
        primary = change("person", "柳舟", "我记得柳舟会在排练前校准节拍器。", [timing])
        former = dict(change("person", "青笺", "我记得青笺会把老底片分袋保存。", [archive]),
                      scope="保存旧照片", reason="本人说明存放底片的习惯")
        self.upgrade_profiles(primary, former)
        pid, bridge = self.acknowledge("802", "青笺")
        merged, = self.profiles()
        self.assertEqual((merged["person_id"], merged["subject"]), (pid, "柳舟"))
        for original in (primary, former):
            for part in ("text", "scope", "reason"):
                self.assertIn(original[part], merged[part])
        self.assertTrue(all(r in merged["source_refs"] for r in (timing, archive)))

        self.service.api("corrections", dict(context=context(GROUP), source_instance=INSTANCE, event_id="undo-bridge",
                         record_id=bridge["record_id"], expected_revision=bridge["revision"], operation="RETRACT",
                         reason="原发件人撤回别称说明"))
        restored = {p["subject"]: p for p in self.profiles()}
        self.assertEqual(set(restored), {"柳舟", "青笺"})
        for original in (primary, former):
            current = restored[original["subject"]]
            self.assertEqual(current["text"], original["text"])
            self.assertEqual(current["source_refs"], original["source_refs"])
        self.assertEqual(restored["柳舟"]["person_id"], pid)
        self.assertNotEqual(restored["青笺"].get("person_id"), pid)

    def test_foreign_quotation_and_oversized_merge_preserve_the_old_profiles(self):
        """异账号转引和无关说话人的旧档不得误归；即使另一旧档可归属，超长时也须完整保留。"""
        setup = self.qq("811", "我会提前校准乐器。")
        foreign = self.qq("812", "我在转述别人的话：“我叫云笺。”我自己不叫这个名字。", OTHER)
        listener = self.ingest("listener", "我收到的鼓槌是黑色的。", STUDIO, speaker="外场记录员")
        archive = self.ingest("long-archive", "我叫溪笺。我记录了排练变奏。", STUDIO, speaker="溪笺")
        primary = change("person", "柳舟", "我记得柳舟的校准步骤。" + "排练开始之前逐一核对。" * 110, [setup])
        mixed = change("person", "云笺", "我记得云笺那次转引和现场记录的黑色鼓槌。", [foreign, listener])
        large = change("person", "溪笺", "我记得溪笺的变奏记录。" + "每次合奏以后记录细节。" * 110, [archive])
        self.assertGreater(len(primary["text"]) + len(large["text"]), 2048)
        self.upgrade_profiles(primary, mixed, large)
        self.acknowledge("813", "云笺")
        self.acknowledge("814", "溪笺")
        current = {p["subject"]: p for p in self.profiles()}
        self.assertEqual(set(current), {"柳舟", "云笺", "溪笺"})
        for profile in (primary, mixed, large):
            kept = current[profile["subject"]]
            self.assertEqual(kept["text"], profile["text"])
            self.assertCountEqual(kept["source_refs"], [ref(s) for s in profile["source_refs"]])


if __name__ == "__main__":
    unittest.main()
