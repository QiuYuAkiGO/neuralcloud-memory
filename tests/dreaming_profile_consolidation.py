"""用两个正式版本和合成原话，黑盒验收旧人物档的精简、历史和一次性归档清单。"""
import unittest

import dreaming_qq_legacy_profiles
from acceptance import dump, ref
from dreaming import GROUP, STUDIO, change, internal, publication
from dreaming_qq_aliases import claim, subject_change
from dreaming_qq_backfill import LANE, LegacyPeopleCase
from stage2 import ACCOUNT, AUTHOR, OTHER, audience


PRIMARY = {"subject": "柳舟", "aliases": ["小柳"], "qq_sender_id": AUTHOR}
OTHER_PERSON = {"subject": "枫禾", "aliases": ["小禾"], "qq_sender_id": OTHER}


class ProfileConsolidation(LegacyPeopleCase):
    """旧 JAR 建档并签发回执，候选 JAR 在同库升级。只观察配置启动和公开 HTTP 出口。"""

    def load_people(self, people):
        """部署方以 UTF-8 文件载入本次清单，路径沿用旧服务的正式配置。"""
        dump(self.service.out / "people.json", {"character_id": "misty-dev", "people": people})
        self.restart()

    def profiles(self, room=None):
        return self.post("profiles", {"context": internal() if room is None else audience(room)})["profiles"]

    def publish_old(self, key, *changes):
        """保留旧命令及其公开回执，以便升级后逐字段比对。"""
        command = publication(self.batch(GROUP, STUDIO), key, list(changes))
        receipt = self.post("commit", command)
        self.assertEqual(receipt["status"], "DURABLE")
        return command, receipt

    def archive_pair(self):
        """每份档都符合单批限制，合在一起是 17 条；每条短原话只描述一枚可识别的谱签。"""
        copper = {}
        for i in range(1, 10):
            label = f"铜签{i:02}号"
            copper[label] = self.qq(str(900 + i), f"我收好了{label}。")
        main = change("person", "柳舟", "我记得柳舟曾整理谱签，这页旧评叫橙皮簿。", list(copper.values()))
        first = self.publish_old("old-copper", main)

        silver = {}
        for i in range(1, 9):
            label = f"银签{i:02}号"
            silver[label] = self.ingest("silver-" + str(i), f"我叫青笺，我收好了{label}。", STUDIO, speaker="青笺")
        alias = change("person", "青笺", "我记得青笺分别登记谱签，这页旧评叫紫皮簿。", list(silver.values()))
        second = self.publish_old("old-silver", alias)
        return (main, alias), (copper, silver), (first, second)

    def assert_compact(self, profile, groups):
        """只约束代表性、原话与引用的一致性；具体入选哪一条由服务决定。"""
        self.assertEqual((profile["kind"], profile["subject"], profile["author"]), ("person", "柳舟", "DREAMING"))
        self.assertTrue(profile["text"].startswith("我"))
        for key, limit in (("text", 2048), ("scope", 512), ("reason", 1024)):
            self.assertIn(len(profile[key]), range(1, limit + 1))
        refs = profile["source_refs"]
        self.assertIn(len(refs), range(2, 17))
        self.assertEqual(len(refs), len({(r["record_id"], r["revision"]) for r in refs}))
        known = [source for group in groups for source in group.values()]
        self.assertTrue(all(r in known for r in refs), refs)
        for group in groups:
            mentioned = [source for label, source in group.items() if label in profile["text"]]
            self.assertTrue(mentioned, profile["text"])
            self.assertTrue(all(source in refs for source in mentioned), (mentioned, refs))
        for old_annotation in ("橙皮簿", "紫皮簿"):
            self.assertNotIn(old_annotation, profile["text"])

    def page_for(self, source):
        """需要实时输入时，按回扫批的独立水位确认先出现的历史页。"""
        while True:
            page = self.batch(GROUP, STUDIO)
            if any(ref(item) == source for item in page["evidence"]):
                return page
            self.assertEqual(page.get("lane"), LANE, page)
            self.assertGreater(page["through"], page["after"])
            command = publication(page, "backfill-" + str(page["through"]), [])
            self.post("commit", dict(command, lane=LANE, name_claims=[]))

    def test_configured_alias_compacts_both_archives_and_checks_audience(self):
        """受信别称合并 17 条原话，单一会话看不到跨会话现版，重新导入与重读沿用同一版。"""
        _, groups, history = self.archive_pair()
        people = [dict(PRIMARY, aliases=["小柳", "青笺"])]
        self.load_people(people)
        merged, = self.profiles()
        self.assert_compact(merged, groups)
        self.assertEqual(merged["version"], 2)
        self.assertEqual(self.profiles(STUDIO), [])
        self.assertEqual(self.profiles(GROUP), [])
        self.assertEqual(self.profiles(), [merged])
        self.load_people(people)
        self.assertEqual(self.profiles(), [merged])
        for command, receipt in history:
            self.assertEqual(self.post("commit", command), receipt)

    def test_self_name_compacts_both_archives_until_the_bridge_is_withdrawn(self):
        """独立的本人自称可连接两份旧档；撤回唯一依据后，原文和全部旧引用仍可还原。"""
        originals, groups, history = self.archive_pair()
        self.restart()
        quote = "我也叫青笺。"
        bridge = self.qq("950", quote)
        page = self.page_for(bridge)
        person = self.subject_for(page, bridge)
        naming = claim(person, "青笺", "SELF_NAME", dict(bridge, quote=quote))
        command = dict(publication(page, "self-name", []), name_claims=[naming])
        receipt = self.post("commit", command)
        merged, = self.profiles()
        self.assertEqual(merged["person_id"], person["person_id"])
        self.assert_compact(merged, groups)
        self.restart()
        self.assertEqual(self.profiles(), [merged])
        self.assertEqual(self.post("commit", command), receipt)

        dreaming_qq_legacy_profiles.LegacyProfileMerge.retract_bridge(self, bridge)
        restored = self.profiles()
        self.assertCountEqual([p["subject"] for p in restored], [p["subject"] for p in originals])
        for original in originals:
            current, = [p for p in restored if p["subject"] == original["subject"]]
            for field in ("text", "reason", "scope"):
                self.assertEqual(current[field], original[field])
            self.assertCountEqual(current["source_refs"], original["source_refs"])
        for old_command, old_receipt in history:
            self.assertEqual(self.post("commit", old_command), old_receipt)

    def test_same_name_on_another_account_keeps_its_own_profile(self):
        """受信主档和另一 QQ 的显示主名相同，后来的别称归并只涉及被认领人的旧档。"""
        _, groups, _ = self.archive_pair()
        self.restart()
        intro = "我叫柳舟。"
        source = self.qq("951", intro, sender=OTHER)
        page = self.page_for(source)
        namesake = self.subject_for(page, source)
        own = subject_change(namesake, "柳舟", "我听到另一位成员介绍自己。", source)
        naming = claim(namesake, "柳舟", "SELF_NAME", dict(source, quote=intro))
        self.post("commit", dict(publication(page, "other-liuzhou", [own]), name_claims=[naming]))

        quote = "我也叫青笺。"
        bridge = self.qq("952", quote)
        page = self.page_for(bridge)
        owner = self.subject_for(page, bridge)
        self.assertNotEqual(owner["person_id"], namesake["person_id"])
        alias = claim(owner, "青笺", "SELF_NAME", dict(bridge, quote=quote))
        self.post("commit", dict(publication(page, "owners-alias", []), name_claims=[alias]))
        profiles = self.profiles()
        self.assertCountEqual([(p["subject"], p.get("person_id")) for p in profiles],
                              [("柳舟", owner["person_id"]), ("柳舟", namesake["person_id"])])
        merged, = [p for p in profiles if p["person_id"] == owner["person_id"]]
        self.assert_compact(merged, groups)
        other, = [p for p in profiles if p["person_id"] == namesake["person_id"]]
        self.assertEqual((other["text"], other["source_refs"]), (own["text"], [source]))

    def test_one_time_import_joins_the_version_but_does_not_register_the_title(self):
        """人工清单只纠正所指人物档的归属，普通别称仍有效，机器人账号同名主题与其他旧档独立。"""
        previous = self.qq("960", "我以前把备用簧片放在纸袋里。")
        first = change("person", ACCOUNT, "我记得对方把备用簧片放在纸袋里。", [previous])
        first_command, first_receipt = self.publish_old("previous-label", first)
        direct = self.qq("961", "我把备用簧片放进了木盒。")
        mistaken = change("person", ACCOUNT, "我记得对方把备用簧片放进木盒。", [direct], version=1)
        topic = change("topic", ACCOUNT, "我听到有人说起备用簧片。", [direct])
        local = self.ingest("unlisted", "我把旧票根收进盒里。", speaker="旁听者")
        unlisted = change("person", ACCOUNT + "0", "我记得旁听者收藏票根。", [local])
        command, receipt = self.publish_old("misfiled", mistaken, topic, unlisted)
        people = [dict(PRIMARY, aliases=["小柳", "夜舟"], legacy_profiles=[{"subject": ACCOUNT, "expected_version": 2}])]
        self.load_people(people)
        current = self.profiles()
        self.assertCountEqual([(p["kind"], p["subject"]) for p in current],
                              [("person", "柳舟"), ("topic", ACCOUNT), ("person", unlisted["subject"])])
        for old, kind, subject in ((mistaken, "person", "柳舟"), (topic, "topic", ACCOUNT),
                                   (unlisted, "person", unlisted["subject"])):
            kept, = [p for p in current if (p["kind"], p["subject"]) == (kind, subject)]
            self.assertEqual((kept["text"], kept["source_refs"]), (old["text"], old["source_refs"]))
        self.load_people(people)
        self.assertCountEqual(self.profiles(), current)
        self.assertEqual(self.post("commit", first_command), first_receipt)
        self.assertEqual(self.post("commit", command), receipt)

        probe = self.qq("962", "下一次排练定在周三。")
        page = self.page_for(probe)
        who = self.subject_for(page, probe)
        self.assertEqual(who["subject"], "柳舟")
        self.assertIn("夜舟", who["aliases"])
        self.assertNotIn(ACCOUNT, who["aliases"])
        names, = [p for p in page["person_names"] if p["subject"] == "柳舟"]
        self.assertIn("夜舟", names["aliases"])
        self.assertNotIn(ACCOUNT, names["aliases"])

    def test_legacy_import_rejects_invalid_versions_and_conflicting_owners_atomically(self):
        """同一旧档已有第二版；缺版本、旧版本以及企图改写他人归属的清单均须整批回滚。"""
        original = self.qq("971", "我用灰布包好了节拍器。")
        main = change("person", "柳舟", "我记得柳舟使用灰布。", [original])
        old = change("person", ACCOUNT, "我记得当时用灰布包节拍器。", [original])
        self.publish_old("old-label", main, old)
        newer = self.qq("972", "我现已用蓝布包节拍器。")
        corrected = change("person", ACCOUNT, "我记得当事人换成了蓝布。", [newer], version=1)
        command, receipt = self.publish_old("new-label", corrected)
        self.restart()
        baseline = self.profiles()

        exact = {"subject": ACCOUNT, "expected_version": 2}
        owner = dict(PRIMARY, legacy_profiles=[exact])
        cases = [
            ("缺少版本", [dict(PRIMARY, legacy_profiles=[{"subject": ACCOUNT}])]),
            ("不是正整数", [dict(PRIMARY, legacy_profiles=[dict(exact, expected_version=0)])]),
            ("版本写成字符串", [dict(PRIMARY, legacy_profiles=[dict(exact, expected_version="2")])]),
            ("版本过期", [dict(PRIMARY, legacy_profiles=[dict(exact, expected_version=1)])]),
            ("找不到旧档", [dict(PRIMARY, legacy_profiles=[exact, {"subject": "未建档", "expected_version": 1}])]),
            ("同档归给两人", [owner, dict(OTHER_PERSON, legacy_profiles=[exact])]),
            ("已有其他归属", [PRIMARY, dict(OTHER_PERSON, legacy_profiles=[{"subject": "柳舟", "expected_version": 1}])]),
        ]
        for label, people in cases:
            with self.subTest(label=label):
                with self.assertRaisesRegex(RuntimeError, "legacy_profiles|expected_version|归属|版本|档案|旧档|绑定|称呼"):
                    self.load_people(people)
                self.load_people([PRIMARY])
                self.assertCountEqual(self.profiles(), baseline)
                self.assertEqual(self.receipt(command["event_id"]), receipt)


if __name__ == "__main__":
    unittest.main(verbosity=2)
