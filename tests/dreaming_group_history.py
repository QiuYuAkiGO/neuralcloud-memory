"""用真实旧版 Memory 导入合成 v1 快照，经 HTTP 验证格式 8 群原话回扫的升级与隔离。"""
import os
from pathlib import Path
import unittest

from acceptance import ROOT, ServicePair, ref, send
from dreaming import DreamingCase, FIELD, internal, publication
from group_history_fixture import history_row, import_history
from stage2 import ACCOUNT, AUTHOR, GROUP, OTHER


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
        self.scope = GROUP

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
        page = self.batch(self.scope)
        self.assertGreater(page["through"], page["after"])
        cmd = publication(page, "covered-before-format-eight", [])
        saved = self.post("commit", cmd)
        empty = self.batch(self.scope)
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
        return self.post("batch", dict(context=internal(), scopes=[self.scope], limit=limit,
                                       include_group_history=True))

    def confirm(self, page, key):
        cmd = dict(publication(page, key, []), lane=page["lane"], name_claims=[])
        saved = self.post("commit", cmd)
        self.assertEqual((saved["status"], saved["lane"], saved["after"], saved["through"]),
                         ("DURABLE", page["lane"], page["after"], page["through"]))
        return cmd, saved

    def native(self, sender, number, text, room=GROUP):
        """经 v7 正式渠道入账；没有 @ 机器人或回复关系的发言仍属于群成员原话。"""
        channel = dict(platform="qq", type="qq_group", account_id=ACCOUNT, sender_id=sender,
                       conversation_id=room, message_id=number)
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

    def test_replay_verified_group_history_after_v7_coverage_and_restart(self):
        """旧正常流已扫完；开启后仅有该群两条可核实原话，重启后从首条回执的位置续上。"""
        command, covered = self.legacy(self.v7)
        self.restart(self.current)
        # 旧调用方省略开关时，已覆盖账本仍返回正常水位。
        previous_client = self.batch(self.scope)
        self.assertNotEqual(previous_client.get("lane"), GROUP_HISTORY)
        self.assertEqual((previous_client["after"], previous_client["through"], previous_client["evidence"]),
                         (covered["through"], covered["through"], []))

        first = self.opted_in()
        self.assertEqual(first.get("lane"), GROUP_HISTORY)
        evidence, = first["evidence"]
        self.assertEqual((evidence["conversation_id"], evidence["content"]), (self.scope, FIRST))
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
                self.assertEqual(item["conversation_id"], self.scope)
                texts.append(item["content"])
            _, saved = self.confirm(page, "continue-group-" + str(cursor))
            cursor = saved["through"]
        self.assertCountEqual(texts, [FIRST, SECOND])
        self.assertEqual((page["after"], page["through"], page["evidence"]),
                         (covered["through"], covered["through"], []))

    def test_new_group_lane_cannot_acknowledge_or_discard_pending_v7_identity(self):
        """未结的身份回扫从 v7 回执续传，误用群历史通道的旧页不能越权确认。"""
        v6 = Path(os.environ["NC_DREAMING_V6_JAR"]).resolve(strict=True)
        _, covered = self.legacy(v6)
        self.restart(self.v7)
        first = self.batch(self.scope, limit=1)
        self.assertEqual(first.get("lane"), IDENTITY)
        old_command, saved = self.confirm(first, "v7-first-identity-page")
        self.assertLess(saved["through"], covered["through"])
        self.restart(self.current)

        # 先以旧版请求领取未结身份页；不能将相同位置按新通道确认。
        pending = self.batch(self.scope, limit=1)
        self.assertEqual((pending.get("lane"), pending["after"]), (IDENTITY, saved["through"]))
        wrong = dict(publication(pending, "wrong-group-lane", []), lane=GROUP_HISTORY, name_claims=[])
        self.post("commit", wrong, 409)
        self.assertEqual(self.receipt(wrong["event_id"])["status"], "NOT_FOUND")
        self.assertEqual(self.batch(self.scope, limit=1), pending)
        self.assertEqual(self.post("commit", old_command), saved)

        positions = {IDENTITY: saved["through"]}
        group_texts = []
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
            _, receipt = self.confirm(page, lane + "-" + str(page["after"]))
            positions[lane] = receipt["through"]
        self.assertEqual(positions[IDENTITY], covered["through"])
        self.assertCountEqual(group_texts, [FIRST, SECOND])
        self.assertEqual((page["after"], page["through"]), (covered["through"], covered["through"]))


if __name__ == "__main__":
    unittest.main()
