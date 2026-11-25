"""验收可核实的旧 QQ 群成员对话、显示名称来源和场合权限，全程从正式入口读写。"""
import unittest

from acceptance import ROOT, ServicePair, ref, send
from dreaming import DreamingCase, FIELD, GROUP, INSTANCE, internal, publication
from dreaming_qq_aliases import claim, speaker, subject_change
from group_history_fixture import digest, encoded, history_refs, history_row, import_history
from stage2 import ACCOUNT, AUTHOR, OTHER, audience


GID = GROUP.rsplit(":", 1)[1]
FIELD_ID = FIELD.rsplit(":", 1)[1]


class GroupHistory(DreamingCase):
    def setUp(self):
        """让每例分别通过离线入口导入，启动时启用会话隔离以验证受众。"""
        self.service = ServicePair(ROOT.parent / "neuralcloud-harness", session_isolation="true")
        self.addCleanup(self.stop_memory)

    def start(self, *rows):
        import_history(self.service, rows)
        return self.service.memory()["stream_id"]

    def page(self, *rooms):
        # 旧版没有 include_group_history，直接把约定的请求发到真实 HTTP 入口。
        return send(self.service.base + "dreaming/batch", dict(context=internal(), scopes=list(rooms), limit=16,
                    include_group_history=True), self.service.token)

    def publish(self, page, changes, claims=(), expected=200):
        command = dict(publication(page, "publish-archive", changes), name_claims=list(claims))
        if "lane" in page:
            command["lane"] = page["lane"]
        result = self.post("commit", command, expected)
        if expected == 200:
            self.assertEqual(result["status"], "DURABLE")

    def profiles(self, room=None):
        return self.post("profiles", {"context": internal() if room is None else audience(room)})["profiles"]

    def test_live_members_emit_text_and_captions_once_and_keep_separate_identity_review(self):
        """新启用时先签发一条群话。实时收到重复副本、纯图或机器人发言时，只留下附文和独立身份审核。"""
        self.service.memory()

        def message(event_id, sender, number, text):
            return self.ingest(event_id, text, GROUP, channel=dict(platform="qq", type="qq_group", account_id=ACCOUNT,
                               sender_id=sender, conversation_id=GROUP, message_id=number))

        first = message("首份协议消息", AUTHOR, "551", "我把各声部的曲谱排好了。")
        initial = self.page(GROUP)
        self.assertEvidence(initial, first)
        self.publish(initial, [])
        message("相同协议的另一份入账", AUTHOR, "551", "我把各声部的曲谱排好了。")
        message("只有图片", AUTHOR, "552", "【图片1】")
        message("机器人出声", ACCOUNT, "553", "我替大家确认过了。")
        caption = message("图片有附文", OTHER, "554", "【图片1】我给谱夹加了颜色标记。")
        reviewed = self.ingest("原有的独立身份审核", "管理员核实了这位成员的称呼。", GROUP,
                               context=audience(GROUP, "ORCHESTRATOR"), identity=dict(account_id="68668", name="澄容"))
        live = self.page(GROUP)
        self.assertEvidence(live, caption, reviewed)
        note, = [e for e in live["evidence"] if ref(e) == reviewed]
        self.assertEqual(note["reviewed_name"], "澄容")
        self.assertTrue(note["reviewed_person_id"])
        self.assertNotIn("speaker_person_id", note)

    def test_members_can_publish_separate_facts_without_addressing_the_bot(self):
        """两人在同一群对话，名片与自称各有依据；撤掉名片，只失效以它命名的档案。"""
        first = history_row("带谱的成员", GID, AUTHOR, "-701", "我带了两本校订过的总谱。")
        card = history_row("送谱人的资料", GID, AUTHOR, "-701", "泽芹", card=True)
        second = history_row("校音的成员", GID, OTHER, "702", "我叫林梧。我负责给第二把琴校音。")
        rows = (first, card, second)
        refs = history_refs(self.service, self.start(*rows), rows)
        page = self.page(GROUP)
        self.assertEvidence(page, refs[first[0]], refs[second[0]])
        by_id = {e["record_id"]: e for e in page["evidence"]}
        for row, sender, number in ((first, AUTHOR, "-701"), (second, OTHER, "702")):
            evidence = by_id[refs[row[0]]["record_id"]]
            self.assertEqual((evidence["content"], evidence["kind"], evidence["conversation_id"],
                              evidence["sender_id"], evidence["message_id"]),
                             (row[1]["content"], "observation", GROUP, sender, number))
        named = by_id[refs[first[0]]["record_id"]]
        unnamed = by_id[refs[second[0]]["record_id"]]
        self.assertEqual((named["card"], named["channel_name_ref"]), ("泽芹", refs[card[0]]))
        self.assertTrue({"card", "nickname", "channel_name_ref"}.isdisjoint(unnamed))
        reed, wood = speaker(page, named), speaker(page, unnamed)
        self.assertNotEqual(reed["person_id"], wood["person_id"])
        fact1 = subject_change(reed, "泽芹", "我记得泽芹带了两本校订过的总谱。", named)
        fact2 = subject_change(wood, "林梧", "我记得林梧负责给第二把琴校音。", unnamed)
        names = [claim(wood, "林梧", "SELF_NAME", dict(ref(unnamed), quote="我叫林梧。"))]
        self.publish(page, [dict(fact2, source_refs=[ref(named)])], names, expected=422)
        self.publish(page, [fact1, fact2], names)
        written = self.profiles(GROUP)
        for expected in (fact1, fact2):
            stored, = [p for p in written if p["person_id"] == expected["person_id"]]
            self.assertEqual((stored["subject"], stored["text"], stored["source_refs"]),
                             (expected["subject"], expected["text"], expected["source_refs"]))
        self.assertEqual(len(written), 2)
        self.assertEqual(self.profiles(FIELD), [])

        original = refs[card[0]]
        self.service.api("corrections", dict(context=audience(card[1]["conversation_id"], "ORCHESTRATOR"),
                         source_instance=INSTANCE, event_id="withdraw-archived-card", record_id=original["record_id"],
                         expected_revision=original["revision"], operation="RETRACT", reason="成员撤回旧名片"))
        self.assertEqual(self.profiles(GROUP), [p for p in written if p["person_id"] == wood["person_id"]])

    def test_rejects_delivery_other_senders_pasted_envelopes_and_placeholders(self):
        """保留带文字的附件说明；伪装成原话的送达、来源错配、派生和纯占位均不签发。"""
        good = history_row("附图说明", GID, AUTHOR, "801", "[图片 合成谱架]我带了便携谱架。")
        delivery = history_row("送达", GID, AUTHOR, "802", "消息已经送达。")
        delivery[1]["provenance"]["external_refs"][0] = "qq-segment-delivery:" + digest("送达")
        delivery[1]["aliases"] = delivery[1]["provenance"]["external_refs"].copy()
        wrong_sender = history_row("协议发件人不符", GID, AUTHOR, "803", "这条消息的发件人对不上。")
        wrong_sender[1]["participants"] = ["qq:member:" + OTHER]
        wrong_group = history_row("协议群号不符", GID, AUTHOR, "804", "这条消息的群号对不上。")
        wrong_group[1]["provenance"]["external_refs"][-1] = f"qq-protocol-message:{FIELD_ID}:{AUTHOR}:804"
        wrong_group[1]["aliases"] = wrong_group[1]["provenance"]["external_refs"].copy()
        wrong_source = history_row("快照写入者不符", GID, AUTHOR, "805", "来自另一个来源。")
        wrong_source[1]["source_instance"] = "legacy:" + digest("另一个快照")[:32]
        pasted = history_row("粘贴的信封", GID, AUTHOR, "806", encoded(good[1]))
        del pasted[1]["provenance"]
        derived = history_row("群话摘要", GID, AUTHOR, "807", "我听说有人带谱架。")
        derived[1]["source_ids"] = [good[0]]
        inferred = history_row("推断", GID, AUTHOR, "808", "我推测是一次排练。")
        inferred[1]["provenance"]["semantic_status"] = "inference"
        mixed = history_row("两个参与者", GID, AUTHOR, "809", "不能证明是谁发言。")
        mixed[1]["participants"].append("qq:member:" + OTHER)
        bot = history_row("机器人账号", GID, ACCOUNT, "810", "我已回答对方。")
        other_room = history_row("其他群", FIELD_ID, AUTHOR, "811", "在另一个群排练。")
        malformed = history_row("消息号非整数", GID, AUTHOR, "m812", "消息号无法核实。")
        placeholders = [history_row("占位" + str(n), GID, AUTHOR, str(820 + n), label) for n, label in enumerate(
                        ("[图片 合成谱架]", "[引用消息 800]", "[未解析的消息附件]", "[QQ表情]"))]
        rows = (good, delivery, wrong_sender, wrong_group, wrong_source, pasted, derived, inferred, mixed,
                bot, other_room, malformed, *placeholders)
        refs = history_refs(self.service, self.start(*rows), rows)
        self.assertEvidence(self.page(GROUP), refs[good[0]])

    def test_display_name_requires_one_metadata_record_from_the_same_original_packet(self):
        """同一名片的重复指针视为一个来源；跨人、跨群、旧消息及两个独立名片均不能充当名称依据。"""
        messages, cards = [], []
        cases = (("重复指针", "68000", "910"), ("平台昵称", "68005", "915"), ("跨人", "68001", "911"),
                 ("跨群", "68002", "912"), ("另一条消息", "68003", "913"), ("两份名片", "68004", "914"))
        for case, author, number in cases:
            protocol = digest("合成原始消息" + number)
            messages.append(history_row("正文" + number, GID, author, number, "我负责登记" + number + "号谱架。",
                                        protocol_digest=protocol))
            card = history_row("资料" + number, FIELD_ID if case == "跨群" else GID, OTHER if case == "跨人" else author,
                               number, "合成名" + number, card=True,
                               display_name_source="qq_profile" if case == "平台昵称" else "qq_metadata",
                               protocol_digest=digest("不同消息") if case == "另一条消息" else protocol)
            if case == "重复指针":
                pointers = card[1]["provenance"]["external_refs"]
                pointers[1] = pointers[2]
                card[1]["aliases"] = pointers.copy()
            cards.append(card)
            if case == "两份名片":
                cards.append(history_row("另一份资料", GID, author, number, "另一个合成名",
                                         card=True, protocol_digest=protocol))
        rows = messages + cards
        refs = history_refs(self.service, self.start(*rows), rows)
        page = self.page(GROUP)
        self.assertEvidence(page, *(refs[row[0]] for row in messages))
        by_id = {e["record_id"]: e for e in page["evidence"]}
        paired = by_id[refs[messages[0][0]]["record_id"]]
        self.assertEqual((paired["card"], paired["channel_name_ref"]), ("合成名910", refs[cards[0][0]]))
        nickname = by_id[refs[messages[1][0]]["record_id"]]
        self.assertEqual((nickname["nickname"], nickname["channel_name_ref"]), ("合成名915", refs[cards[1][0]]))
        for row in messages[2:]:
            item = by_id[refs[row[0]]["record_id"]]
            self.assertTrue({"card", "nickname", "channel_name_ref"}.isdisjoint(item), item)

    def test_group_scope_must_have_one_bot_and_stops_authorizing_after_removal(self):
        """旧群号不推测机器人；只有唯一获准映射能写、读，撤去该群后其他群的档案仍可见。"""
        local = history_row("本群原话", GID, AUTHOR, "991", "我负责本群的签到。")
        local_card = history_row("本群群名片", GID, AUTHOR, "991", "诚竹", card=True)
        outside = history_row("外场原话", FIELD_ID, OTHER, "992", "我负责外场的检票。")
        outside_card = history_row("外场群名片", FIELD_ID, OTHER, "992", "桐简", card=True)
        rows = (local, local_card, outside, outside_card)
        refs = history_refs(self.service, self.start(*rows), rows)
        ambiguous = self.page(GROUP, "group:onebot:91234:" + GID, FIELD)
        self.assertEvidence(ambiguous, refs[outside[0]])

        page = self.page(GROUP, FIELD)
        self.assertEvidence(page, refs[local[0]], refs[outside[0]])
        changes = []
        for row, name, text in ((local, "诚竹", "我记得诚竹负责本群签到。"), (outside, "桐简", "我记得桐简负责外场检票。")):
            source = refs[row[0]]
            changes.append(subject_change(speaker(page, source), name, text, source))
        self.publish(page, changes)
        self.assertEqual([p["subject"] for p in self.profiles(GROUP)], ["诚竹"])
        self.assertEqual([p["subject"] for p in self.profiles(FIELD)], ["桐简"])
        self.page(FIELD)
        self.assertEqual(self.profiles(GROUP), [])
        self.assertEqual([p["subject"] for p in self.profiles()], ["桐简"])


if __name__ == "__main__":
    unittest.main()
