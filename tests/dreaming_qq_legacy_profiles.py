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

    def retract_bridge(self, source):
        """仅撤回本人认领别称的那条原话，各旧档用于描述生活习惯的来源仍然有效。"""
        self.service.api("corrections", dict(context=context(GROUP), source_instance=INSTANCE,
                         event_id="undo-" + source["record_id"], record_id=source["record_id"],
                         expected_revision=source["revision"], operation="RETRACT", reason="发言人撤回别称说明"))

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

        self.retract_bridge(bridge)
        restored = {p["subject"]: p for p in self.profiles()}
        self.assertEqual(set(restored), {"柳舟", "青笺"})
        for original in (primary, former):
            current = restored[original["subject"]]
            self.assertEqual(current["text"], original["text"])
            self.assertEqual(current["source_refs"], original["source_refs"])
        self.assertEqual(restored["柳舟"]["person_id"], pid)
        self.assertNotEqual(restored["青笺"].get("person_id"), pid)

    def test_direct_qq_habit_stays_with_its_speaker_when_the_alias_is_withdrawn(self):
        """旧称只出现在文件标题。本人 QQ 对自己习惯的直述可独立归属，称呼撤回后仍在主档。"""
        first = self.qq("821", "我总把镜头布放在摄影包左边。")
        habit = self.qq("822", "我每次上山拍照前，都会给备用电池贴上日期。")
        primary = change("person", "柳舟", "我记得柳舟收纳镜头布的位置。", [first])
        former = change("person", "野渡", "我记得对方拍照前会给备用电池贴上日期。", [habit])
        self.upgrade_profiles(primary, former)

        pid, bridge = self.acknowledge("823", "野渡")

        def check_owner():
            owned = [p for p in self.profiles() if p.get("person_id") == pid]
            self.assertEqual([p["subject"] for p in owned], ["柳舟"])
            main, = owned
            self.assertIn(primary["text"], main["text"])
            self.assertIn(former["text"], main["text"])
            self.assertCountEqual(main["source_refs"], [first, habit])

        check_owner()
        self.retract_bridge(bridge)
        check_owner()

    def test_a_forwarded_self_description_and_another_speaker_do_not_become_the_qq_owners_habits(self):
        """分别检验本人转述第三人的第一人称、其他 QQ 自述。撤回后两种习惯各留其旧档。"""
        first = self.qq("831", "我出门前会检查雨量计。")
        forwarded = self.qq("832", "我给你转述小满的原话：“我习惯把票根夹在末页。”这是她的习惯。")
        other = self.qq("833", "我自己一直用浅色布袋收纳胶卷。", sender=OTHER)
        primary = change("person", "柳舟", "我记得柳舟会在出发前检查雨量计。", [first])
        clipping = change("person", "潮影", "我读到那个人把票根夹在末页的习惯。", [forwarded])
        bag = change("person", "晚渡", "我记得对方用浅色布袋收纳胶卷。", [other])
        self.upgrade_profiles(primary, clipping, bag)

        pid, first_bridge = self.acknowledge("834", "潮影")
        _, second_bridge = self.acknowledge("835", "晚渡")
        self.retract_bridge(first_bridge)
        self.retract_bridge(second_bridge)
        profiles = {p["subject"]: p for p in self.profiles()}
        self.assertEqual(set(profiles), {"柳舟", "潮影", "晚渡"})
        self.assertEqual(profiles["柳舟"]["person_id"], pid)
        self.assertNotEqual(profiles["潮影"].get("person_id"), pid)
        self.assertNotEqual(profiles["晚渡"].get("person_id"), pid)
        for original in (primary, clipping, bag):
            current = profiles[original["subject"]]
            self.assertEqual(current["text"], original["text"])
            self.assertEqual(current["source_refs"], original["source_refs"])

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
