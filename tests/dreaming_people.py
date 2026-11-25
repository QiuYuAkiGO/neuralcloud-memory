"""从角色配置及正式 HTTP 入口验收 Dreaming 人物称呼、旧档合并和引用权限。"""
import unittest

from acceptance import ROOT, ServicePair, context, dump, wait_ready
from dreaming import DreamingCase, GROUP, STUDIO, change, internal, publication
from stage2 import ACCOUNT, AUTHOR, OTHER


PERSON = {"subject": "林枝", "aliases": ["梧音"], "qq_sender_id": AUTHOR}
SELF = {"subject": "舟月", "aliases": ["Lune"], "self": True}


class DreamingPeople(DreamingCase):
    def setUp(self):
        """每例独占角色库；配置放在工作目录的子目录，以检验相对路径的解析基准。"""
        self.service = ServicePair(ROOT.parent / "neuralcloud-harness", session_isolation="true")
        self.addCleanup(self.stop_memory)
        self.base_config = (self.service.out / "memory.properties").read_text(encoding="utf-8")
        self.config = self.service.out / "settings" / "memory.properties"
        self.config.parent.mkdir()

    def start(self, people=None):
        """同一目录重启正式服务。省略清单时只能使用上次成功载入的身份。"""
        self.stop_memory()
        settings = self.base_config
        if people is not None:
            dump(self.config.with_name("人物.json"), {"character_id": "misty-dev", "people": people})
            settings += "dreamingPeopleFile=人物.json\n"
        self.config.write_text(settings, encoding="utf-8")
        self.service.mproc, log = self.service.launch("memory", [
            "-jar", str(self.service.memjar), "--config", str(self.config), "--stop-on-eof"])
        wait_ready(self.service.mproc, lambda: self.service.api("health"), log)

    def profiles(self, viewer=None):
        """保留列表，确保重复发布相同 kind/subject 也能被断言发现。"""
        return self.post("profiles", {"context": internal() if viewer is None else viewer})["profiles"]

    def test_merge_current_profiles_with_history_and_visibility(self):
        """两份旧人物档合并后延续主档版本和共同引用，重放、重启及撤回都沿用同一份认识。"""
        self.start()
        shared = self.ingest("duet", "两处都安排了合奏。")
        local = self.ingest("strings", "林枝负责调弦。")
        away = self.ingest("turn", "梧音在换页时打拍子。", GROUP)
        main = dict(change("person", "林枝", "我记得林枝在排练前调弦。", [local, shared]),
                    scope="排练前", reason="调弦安排单")
        alias = dict(change("person", "梧音", "我记得梧音在换页时打拍子。", [away, shared]),
                     scope="换页时", reason="换页实录")
        topic = change("topic", "梧音", "我听到了同名曲目。", [local])
        similar = change("person", "梧音子", "我记得另一位成员也参加合奏。", [local])
        old = publication(self.batch(STUDIO, GROUP), "before-names", [main, alias, topic, similar])
        old_receipt = self.post("commit", old)

        self.start([PERSON])
        current = self.profiles()
        self.assertCountEqual([(p["kind"], p["subject"]) for p in current],
                              [("person", "林枝"), ("topic", "梧音"), ("person", "梧音子")])
        merged = next(p for p in current if p["subject"] == "林枝")
        self.assertEqual((merged["version"], merged["author"]), (2, "DREAMING"))
        self.assertCountEqual(merged["source_refs"], [local, away, shared])
        for field in ("text", "scope", "reason"):
            for original in (main, alias):
                self.assertIn(original[field], merged[field])
        # 只持本会话依据的受众不能获知合并结果；原命令重放仍然得到它当时的回执。
        local_views = self.profiles(context(STUDIO, "MASTER"))
        self.assertCountEqual([(p["kind"], p["subject"]) for p in local_views],
                              [("topic", "梧音"), ("person", "梧音子")])
        self.assertEqual(self.post("commit", old), old_receipt)
        self.start([PERSON])
        self.assertCountEqual(self.profiles(), current)
        self.start()
        self.assertCountEqual(self.profiles(), current)
        self.correct("withdraw-shared", shared, "RETRACT")
        self.assertProfiles(topic, similar)

    def test_alias_only_and_retired_content(self):
        """旧库只写过别称时也归入主称呼；已撤回的另一档不参与有效认识。"""
        self.start()
        live = self.ingest("alive", "Lune 负责翻谱，林枝负责调弦。")
        gone = self.ingest("gone", "梧音最早准备了纸谱。")
        mine = change("person", "Lune", "我负责翻谱。", [live])
        main = change("person", "林枝", "我记得林枝负责调弦。", [live])
        retired = change("person", "梧音", "我记得梧音准备了纸谱。", [gone])
        self.post("commit", publication(self.batch(STUDIO), "old", [mine, main, retired]))
        self.correct("withdraw", gone, "RETRACT")
        self.start([SELF, PERSON])
        current = self.profiles()
        self.assertCountEqual([p["subject"] for p in current], ["舟月", "林枝"])
        for p in current:
            self.assertIn(mine["text"] if p["subject"] == "舟月" else main["text"], p["text"])
            self.assertEqual(p["source_refs"], [live])
            self.assertNotIn("纸谱", p["text"])

    def test_alias_evidence_revises_primary_and_refuses_alias_writes(self):
        """只用别称出现的新原话能修订主档；试图另立别称人物档时整批拒绝。"""
        self.start([PERSON, SELF])
        previous = self.ingest("first", "林枝借走了调音器。")
        person = change("person", "林枝", "我记得林枝借走了调音器。", [previous])
        self.post("commit", publication(self.batch(STUDIO), "first", [person]))
        # 省略配置再重启：这次必须通过已登记的别称找回主档及其旧依据。
        self.start()
        fresh = self.ingest("later", "我归还了调音器。", speaker="梧音")
        page = self.batch(STUDIO)
        self.assertEvidence(page, previous, fresh)
        self.assertEqual(page["person_names"], [{"subject": "林枝", "aliases": ["梧音"]}])
        self.assertEqual([(p["kind"], p["subject"], p["version"]) for p in page["profiles"]],
                         [("person", "林枝", 1)])
        topic = change("topic", "梧音", "我听到了同名曲目。", [fresh])
        split = change("person", "梧音", "我记得她归还了调音器。", [fresh])
        self.post("commit", publication(page, "split", [topic, split]), 422)
        self.assertEqual(self.receipt("split")["status"], "NOT_FOUND")
        self.assertEqual(self.batch(STUDIO), page)
        updated = change("person", "林枝", "我记得林枝借走后已经归还了调音器。", [previous, fresh], version=1)
        self.post("commit", publication(page, "revised", [topic, updated]))
        self.assertProfiles(topic, updated)

    def test_only_matching_sender_or_self_supplies_binding(self):
        """新库里没有旧档案和称呼文本，仍能从真实消息发件人及角色自己的发言给出各自身份。"""
        self.start([PERSON, SELF])

        def channel(sender, message):
            return dict(platform="qq", type="qq_group", account_id=ACCOUNT, sender_id=sender,
                        conversation_id=GROUP, message_id=message)

        self.ingest("member", "铜铃修好了。", GROUP, channel=channel(AUTHOR, "501"))
        member = self.batch(GROUP)
        self.assertEqual(member["person_names"], [{"subject": "林枝", "aliases": ["梧音"]}])
        self.post("commit", publication(member, "seen", []))
        self.ingest("other", "谢幕定在周三。", GROUP, channel=channel(OTHER, "502"))
        self.ingest("myself", "我来记录次序。", GROUP, kind="self_expression")
        self.assertEqual(self.batch(GROUP)["person_names"], [{"subject": "舟月", "aliases": ["Lune"]}])

    def test_conflicting_bindings_refuse_start_without_partial_import(self):
        """两个主称呼指向同一旧称时启动失败，同库下次读取仍须得到原档。"""
        self.start()
        src = self.ingest("before", "梧音完成了排练。")
        alias = change("person", "梧音", "我记得梧音完成排练。", [src])
        self.post("commit", publication(self.batch(STUDIO), "old-name", [alias]))
        conflict = {"subject": "李栎", "aliases": ["梧音"]}
        with self.assertRaisesRegex(RuntimeError, "梧音|称呼|别名|aliases"):
            self.start([PERSON, conflict])
        self.start()
        self.assertProfiles(alias)


if __name__ == "__main__":
    unittest.main(verbosity=2)
