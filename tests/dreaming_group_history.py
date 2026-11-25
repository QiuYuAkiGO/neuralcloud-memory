"""用真实旧版 Memory 导入合成 v1 快照，经 HTTP 验证格式 8 群原话回扫的升级与隔离。"""
import json
import os
from pathlib import Path
import unittest

from acceptance import ROOT, ServicePair, ref, send
from dreaming import DreamingCase, FIELD, change, internal, publication
from dreaming_qq_aliases import speaker, subject_change
from group_history_fixture import history_row, import_history
from stage2 import ACCOUNT, AUTHOR, GROUP, OTHER, audience, private


GROUP_HISTORY = "group_history"
IDENTITY = "identity_backfill"
FIRST, SECOND = "我先从左边开始摆谱架。", "好，我来把右边的也摆好。"
HIDDEN, UNVERIFIED = "未授权群只在蓝幕下合唱。", "这句来源不明的群聊不能当成原话。"


class GroupHistoryUpgrade(DreamingCase):
    def setUp(self):
        """每个用例隔离数据目录，所有版本依次打开同一份正式导入的账本。"""
        self.service = ServicePair(ROOT.parent / "neuralcloud-harness", session_isolation="true")
        self.current = self.service.memjar
        self.v7 = Path(os.environ["NC_DREAMING_V7_JAR"]).resolve(strict=True)
        self.addCleanup(self.stop_memory)
        self.rows = [history_row("先摆左边", "23456", AUTHOR, "701", FIRST),
                     history_row("另群原话", "34567", OTHER, "702", HIDDEN),
                     history_row("缺来源", "23456", AUTHOR, "703", UNVERIFIED),
                     history_row("答应同伴", "23456", OTHER, "704", SECOND)]
        self.rows[2][1].pop("provenance")
        self.scopes = [GROUP]

    def post(self, route, body, expected=200):
        """通过各真实版本的 HTTP 契约提交同一组历史命令。"""
        return send(self.service.base + "dreaming/" + route, body, self.service.token, expected)

    def start_legacy(self, jar, rows=()):
        self.service.memjar = jar
        if rows:
            import_history(self.service, rows)
        self.service.memory()

    def cover(self):
        """用旧版正常批次确认当前账本的尾部，并保留原命令以便升级后重放。"""
        page = self.batch(*self.scopes)
        self.assertGreater(page["through"], page["after"])
        cmd = publication(page, "covered-before-format-eight", [])
        saved = self.post("commit", cmd)
        empty = self.batch(*self.scopes)
        self.assertEqual((empty["after"], empty["through"]), (saved["through"], saved["through"]))
        return cmd, saved

    def legacy(self, jar):
        self.start_legacy(jar, self.rows)
        return self.cover()

    def restart(self, jar):
        self.stop_memory()
        self.service.memjar = jar
        self.service.memory()

    def opted_in(self, limit=1):
        return self.post("batch", dict(context=internal(), scopes=self.scopes, limit=limit,
                                       include_group_history=True))

    def confirm(self, page, key):
        cmd = dict(publication(page, key, []), lane=page["lane"], name_claims=[])
        saved = self.post("commit", cmd)
        self.assertEqual((saved["status"], saved["lane"], saved["after"], saved["through"]),
                         ("DURABLE", page["lane"], page["after"], page["through"]))
        return cmd, saved

    def native(self, sender, number, text, room=GROUP, **metadata):
        """经 v7 正式渠道入账；没有 @ 机器人或回复关系的发言仍属于群成员原话。"""
        channel = dict(platform="qq", type="qq_group", account_id=ACCOUNT, sender_id=sender,
                       conversation_id=room, message_id=number, **metadata)
        return self.ingest("native-" + number, text, room, channel=channel)

    def group_evidence_after_upgrade(self):
        _, covered = self.cover()
        self.restart(self.current)
        result = []
        page = self.opted_in(limit=16)
        while page.get("lane") == GROUP_HISTORY:
            self.assertLess(page["after"], page["through"])
            result.extend(page["evidence"])
            self.confirm(page, "group-page-" + str(page["after"]))
            after = page["through"]
            page = self.opted_in(limit=16)
            if page.get("lane") == GROUP_HISTORY:
                self.assertEqual(page["after"], after)
        self.assertEqual((page["after"], page["through"]), (covered["through"], covered["through"]))
        return result

    def test_v7_native_member_message_covered_by_live_is_issued_again_for_group_history(self):
        """旧提示词已经看过的原生成员发言，在新的群历史通道仍能以同一准确修订签发。"""
        self.start_legacy(self.v7)
        source = self.native(OTHER, "711", "我来归还今天排练的谱夹。")
        message, = self.group_evidence_after_upgrade()
        self.assertEqual((ref(message), message["conversation_id"], message["sender_id"], message["message_id"]),
                         (source, GROUP, OTHER, "711"))

    def test_native_protocol_duplicate_wins_and_nonmember_material_stays_out(self):
        """同一协议消息只签原生修订；机器人、带来源的转述、未获准群均无签发记录。"""
        self.start_legacy(self.v7, [self.rows[0]])
        source = self.native(AUTHOR, "701", FIRST)
        self.native(ACCOUNT, "712", "机器人说它已经完成。")
        self.native(OTHER, "713", "另一个群已收回谱夹。", FIELD)
        self.ingest("derived-native-member", "转述别人摆谱架。", GROUP, "self_expression", source_refs=[source])
        self.assertEqual([ref(item) for item in self.group_evidence_after_upgrade()], [source])

    def test_group_member_update_can_retain_valid_earlier_source_from_the_same_group(self):
        """同一成员的旧档可合并本页原话，已合法入档的本群依据仍可保留。"""
        self.start_legacy(self.v7, [self.rows[0]])
        earlier = self.native(AUTHOR, "720", "我每次收齐谱夹后会数一遍。", card="原杉")
        old = self.batch(*self.scopes)
        previous = subject_change(speaker(old, earlier), "原杉", "我记得原杉收齐谱夹后会清点。", earlier)
        self.post("commit", dict(publication(old, "old-member-profile", [previous]), name_claims=[]))
        stored, = self.visible_profiles()
        self.restart(self.current)

        page = self.opted_in()
        self.assertEqual(page.get("lane"), GROUP_HISTORY)
        first, = [e for e in page["evidence"] if e["content"] == FIRST]
        owner = speaker(page, first)
        self.assertEqual(owner["person_id"], stored["person_id"])
        update = subject_change(owner, "原杉", "我记得原杉会先摆左边的谱架，也会清点谱夹。", earlier, first,
                                version=stored["version"])
        cmd = dict(publication(page, "member-both-sources", [update]), lane=GROUP_HISTORY, name_claims=[])
        self.post("commit", cmd)
        current, = self.visible_profiles()
        self.assertEqual((current["person_id"], current["text"]), (stored["person_id"], update["text"]))
        self.assertCountEqual(current["source_refs"], [earlier, ref(first)])

    def test_historical_group_topic_mention_cannot_disclose_private_or_bot_context(self):
        """普通旧主题可以持有自己的合法依据，但群员仅仅提到主题不会获得私聊和机器人内容。"""
        direct = private(AUTHOR)
        self.scopes.append(direct)
        self.start_legacy(self.v7, [self.rows[0]])
        private_text, bot_text = "排练谱架背面的专属储物约定。", "机器人此前答应去领谱架。"
        secret = self.ingest("private-stand", private_text, direct, context=audience(direct, "ROUTER"),
                             channel=dict(platform="qq", type="qq_private", account_id=ACCOUNT,
                                          sender_id=AUTHOR, conversation_id=direct, message_id="721"))
        bot = self.ingest("bot-stand", bot_text, GROUP, "self_expression")
        topic = change("topic", "谱架", "我知道仅在私聊谈过这个储物约定。", [secret, bot])
        self.post("commit", publication(self.batch(*self.scopes), "prior-stand-topic", [topic]))
        self.restart(self.current)

        page = self.opted_in(limit=16)
        self.assertEqual(page.get("lane"), GROUP_HISTORY)
        self.assertEqual([e["content"] for e in page["evidence"]], [FIRST])
        delivered = json.dumps(page, ensure_ascii=False)
        for confidential in (private_text, bot_text, topic["text"], secret["record_id"], bot["record_id"]):
            self.assertNotIn(confidential, delivered)

    def historic_profile(self):
        """让 v7 确认旧观察之后，用 v8 真正签发的原话与配对名片建立一份人物档案。"""
        card = history_row("左侧谱架名片", "23456", AUTHOR, "701", "原杉", card=True)
        self.start_legacy(self.v7, [self.rows[0], card, self.rows[3]])
        _, covered = self.cover()
        self.restart(self.current)
        page = self.opted_in()
        evidence, = page["evidence"]
        self.assertEqual((page["lane"], evidence["content"], evidence["card"]), (GROUP_HISTORY, FIRST, "原杉"))
        item = subject_change(speaker(page, evidence), "原杉", "我记得原杉说自己先摆左边的谱架。", evidence)
        cmd = dict(publication(page, "historical-music-stand", [item]), lane=GROUP_HISTORY, name_claims=[])
        self.post("commit", cmd)
        return covered["through"], item

    def visible_profiles(self):
        return self.post("profiles", {"context": audience(GROUP)})["profiles"]

    def test_historical_profile_is_visible_to_its_group_while_enabled(self):
        """同一授权保持开启时，档案绑定准确原话，剩余消息接续签发。"""
        _, item = self.historic_profile()
        stored, = self.visible_profiles()
        self.assertEqual((stored["person_id"], stored["text"], stored["source_refs"]),
                         (item["person_id"], item["text"], item["source_refs"]))
        second, = self.opted_in()["evidence"]
        self.assertEqual(second["content"], SECOND)

    def test_disabling_group_history_hides_profile_and_reenabling_can_restore_it(self):
        """中途关闭收回已发原话与档案；授权恢复时首条可重建，尚未确认的第二条仍可领取。"""
        covered, item = self.historic_profile()
        off = self.batch(*self.scopes)
        self.assertEqual((off["after"], off["through"], off["evidence"]), (covered, covered, []))
        self.assertEqual(self.visible_profiles(), [])

        again = self.opted_in(limit=16)
        self.assertEqual(again.get("lane"), GROUP_HISTORY)
        self.assertCountEqual([e["content"] for e in again["evidence"]], [FIRST, SECOND])
        prior = next((p["version"] for p in again["profiles"] if p.get("person_id") == item["person_id"]), 0)
        restored = dict(item, expected_version=prior)
        cmd = dict(publication(again, "restore-historical-stand", [restored]), lane=GROUP_HISTORY, name_claims=[])
        self.post("commit", cmd)
        profile, = self.visible_profiles()
        self.assertEqual((profile["person_id"], profile["text"], profile["source_refs"]),
                         (item["person_id"], item["text"], item["source_refs"]))

    def test_replay_verified_group_history_after_v7_coverage_and_restart(self):
        """旧正常流已扫完；开启后仅有该群两条可核实原话，重启后从首条回执的位置续上。"""
        command, covered = self.legacy(self.v7)
        self.restart(self.current)
        # 旧调用方省略开关时，已覆盖账本仍返回正常水位。
        previous_client = self.batch(*self.scopes)
        self.assertNotEqual(previous_client.get("lane"), GROUP_HISTORY)
        self.assertEqual((previous_client["after"], previous_client["through"], previous_client["evidence"]),
                         (covered["through"], covered["through"], []))

        first = self.opted_in()
        self.assertEqual(first.get("lane"), GROUP_HISTORY)
        evidence, = first["evidence"]
        self.assertEqual((evidence["conversation_id"], evidence["content"]), (GROUP, FIRST))
        group_command, group_receipt = self.confirm(first, "old-first-group-message")
        self.restart(self.current)
        for cmd, saved in ((command, covered), (group_command, group_receipt)):
            self.assertEqual(self.receipt(cmd["event_id"]), saved)
            self.assertEqual(self.post("commit", cmd), saved)

        cursor = group_receipt["through"]
        texts = [evidence["content"]]
        while True:
            page = self.opted_in()
            if page.get("lane") != GROUP_HISTORY:
                break
            self.assertEqual(page["after"], cursor)
            self.assertLess(cursor, page["through"])
            self.assertLessEqual(page["through"], covered["through"])
            for item in page["evidence"]:
                self.assertEqual(item["conversation_id"], GROUP)
                texts.append(item["content"])
            _, saved = self.confirm(page, "continue-group-" + str(cursor))
            cursor = saved["through"]
        self.assertCountEqual(texts, [FIRST, SECOND])
        self.assertEqual((page["after"], page["through"], page["evidence"]),
                         (covered["through"], covered["through"], []))

    def test_new_group_lane_cannot_acknowledge_or_discard_pending_v7_identity(self):
        """未结的身份回扫从 v7 回执续传，误用群历史通道的旧页不能越权确认。"""
        v6 = Path(os.environ["NC_DREAMING_V6_JAR"]).resolve(strict=True)
        direct = private(AUTHOR)
        self.scopes.append(direct)
        self.start_legacy(v6, self.rows)
        native_text = "我来收拾低声部的架子。"
        native_source = self.native(OTHER, "714", native_text)
        private_source = self.ingest("private-v6-identity", "我叫谷乔。", direct, context=audience(direct, "ROUTER"),
                                     channel=dict(platform="qq", type="qq_private", account_id=ACCOUNT,
                                                  sender_id=AUTHOR, conversation_id=direct, message_id="715"))
        _, covered = self.cover()
        self.restart(self.v7)
        first = self.batch(*self.scopes, limit=1)
        self.assertEqual(first.get("lane"), IDENTITY)
        old_command, saved = self.confirm(first, "v7-first-identity-page")
        self.assertLess(saved["through"], covered["through"])
        self.restart(self.current)

        # 先以旧版请求领取未结身份页；不能将相同位置按新通道确认。
        pending = self.batch(*self.scopes, limit=1)
        self.assertEqual((pending.get("lane"), pending["after"]), (IDENTITY, saved["through"]))
        wrong = dict(publication(pending, "wrong-group-lane", []), lane=GROUP_HISTORY, name_claims=[])
        self.post("commit", wrong, 409)
        self.assertEqual(self.receipt(wrong["event_id"])["status"], "NOT_FOUND")
        self.assertEqual(self.batch(*self.scopes, limit=1), pending)
        self.assertEqual(self.post("commit", old_command), saved)

        positions = {IDENTITY: saved["through"]}
        group_texts, identity_refs = [], []
        while True:
            page = self.opted_in()
            lane = page.get("lane", "live")
            if lane == "live":
                break
            self.assertIn(lane, (IDENTITY, GROUP_HISTORY))
            if lane in positions:
                self.assertEqual(page["after"], positions[lane])
            self.assertLess(page["after"], page["through"])
            self.assertLessEqual(page["through"], covered["through"])
            if lane == GROUP_HISTORY:
                group_texts.extend(item["content"] for item in page["evidence"])
            else:
                identity_refs.extend(ref(item) for item in page["evidence"])
            _, receipt = self.confirm(page, lane + "-" + str(page["after"]))
            positions[lane] = receipt["through"]
        self.assertEqual(positions[IDENTITY], covered["through"])
        self.assertCountEqual(group_texts, [FIRST, SECOND, native_text])
        self.assertIn(private_source, identity_refs)
        self.assertNotIn(native_source, identity_refs)
        self.assertEqual((page["after"], page["through"]), (covered["through"], covered["through"]))


if __name__ == "__main__":
    unittest.main()
