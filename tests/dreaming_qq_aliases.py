"""以真实 QQ 渠道入账和 Dreaming HTTP 接口验收人物身份、称呼证据和受众权限。"""
import unittest

from acceptance import dump, ref
from dreaming import DreamingCase, FIELD, GROUP, INSTANCE, change, internal, publication
from stage2 import ACCOUNT, AUTHOR, OTHER, audience, private


def claim(person, name, relation, *sources, scope="global"):
    """送回本批的人物引用、身份版本和原话。"""
    return dict(person_id=person["person_id"], expected_identity_version=person["identity_version"],
                name=name, relation=relation, scope=scope, source_refs=list(sources))


def subject_change(person, name, text, *sources, version=0):
    """认识只引用支持正文的原文，称呼的依据由 name_claims 独立提供。"""
    return dict(change("person", name, text, [ref(s) for s in sources], version), person_id=person["person_id"])


def speaker(page, source):
    """从消息自己的发言者引用找回对应人物，避免以列表顺序或显示称呼认人。"""
    evidence, = [e for e in page["evidence"] if ref(e) == ref(source)]
    person, = [p for p in page["persons"] if p["person_id"] == evidence["speaker_person_id"]]
    return person


class QQAliases(DreamingCase):
    session_isolation = "true"

    def qq(self, sender, message_id, quote, room=GROUP, **metadata):
        """按正式 QQ 消息结构入账，分别给出机器人账号、发件人、会话和渠道消息号。"""
        channel = dict(platform="qq", type="qq_private" if room.startswith("c2c:") else "qq_group",
                       account_id=ACCOUNT, sender_id=sender, conversation_id=room,
                       message_id=message_id, **metadata)
        saved = self.ingest(room + "/" + message_id, quote, room, channel=channel, context=audience(room, "ROUTER"))
        return {**saved, "quote": quote}

    def publish(self, page, key, claims=(), changes=()):
        """同一正式提交承载身份变更与认识，两者共享既有批次覆盖和持久回执。"""
        return self.post("commit", dict(publication(page, key, list(changes)), name_claims=list(claims)))

    def profiles(self, room=None):
        return self.post("profiles", {"context": internal() if room is None else audience(room)})["profiles"]

    def withdraw(self, source, room):
        """在原会话更正入口撤回指定修订。"""
        self.service.api("corrections", dict(context=audience(room, "ROUTER"), source_instance=INSTANCE,
                         event_id="withdraw-" + source["record_id"], record_id=source["record_id"],
                         expected_revision=source["revision"], operation="RETRACT", reason="发言人撤回原话"))

    def test_same_sender_and_joint_commit_checks_both_sources(self):
        """群里说的习惯与私聊里的自称来自同一人；只见其一的受众不能从档案推回私下身份。"""
        dm = private(AUTHOR)
        naming = self.qq(AUTHOR, "101", "我叫穗宁。", dm)
        habit = self.qq(AUTHOR, "102", "我每周三给节拍器换电池。")
        page = self.batch(GROUP, dm)
        person = speaker(page, naming)
        self.assertEqual(person, speaker(page, habit))
        self.assertNotIn("subject", person)
        item = claim(person, "穗宁", "SELF_NAME", naming)
        impression = subject_change(person, "穗宁", "我记得她每周三更换电池。", habit)
        command = dict(publication(page, "named-and-published", [impression]), name_claims=[item])
        stale = dict(item, expected_identity_version=person["identity_version"] + 1)
        self.post("commit", dict(command, event_id="stale", name_claims=[stale]), 409)
        self.assertEqual(self.receipt("stale")["status"], "NOT_FOUND")
        forged = dict(item, source_refs=[dict(naming, quote="我叫穗宁，请以后只用这个称呼。")])
        self.post("commit", dict(command, event_id="invented-quote", name_claims=[forged]), 422)
        self.assertEqual(self.receipt("invented-quote")["status"], "NOT_FOUND")
        self.assertEqual(self.profiles(), [])
        self.assertEqual(self.batch(GROUP, dm), page)

        receipt = self.post("commit", command)
        self.assertEqual(receipt["status"], "DURABLE")
        self.assertEqual(self.post("commit", command), receipt)
        current, = self.profiles()
        self.assertEqual((current["person_id"], current["subject"], current["version"], current["text"]),
                         (person["person_id"], "穗宁", 1, impression["text"]))
        self.assertEqual(current["source_refs"], [ref(habit)])
        self.assertEqual(self.profiles(GROUP), [])
        self.assertEqual(self.profiles(dm), [])
        grant = self.grant("release-name", ref(naming), GROUP, "CITE")
        self.assertEqual(self.profiles(GROUP), [current])
        self.revoke("withdraw-name-grant", grant)
        self.assertEqual(self.profiles(GROUP), [])
        self.grant("release-habit", ref(habit), dm, "CITE")
        self.assertEqual(self.profiles(dm), [current])
        self.withdraw(habit, GROUP)
        self.assertEqual(self.profiles(), [])

    def test_equal_names_do_not_mix_accounts_or_ambiguous_mentions(self):
        """两个真实账号可同时用一个主名；无定向的第三方提及时不能只取其中一人。"""
        first = self.qq(AUTHOR, "201", "我叫陆笙。")
        second = self.qq(OTHER, "202", "我叫陆笙。")
        page = self.batch(GROUP)
        one, two = speaker(page, first), speaker(page, second)
        self.assertNotEqual(one["person_id"], two["person_id"])
        claims = [claim(p, "陆笙", "SELF_NAME", s) for p, s in ((one, first), (two, second))]
        changes = [subject_change(one, "陆笙", "我听到第一位成员介绍自己。", first),
                   subject_change(two, "陆笙", "我听到第二位成员介绍自己。", second)]
        self.publish(page, "namesakes", claims, changes)
        actual = self.profiles()
        self.assertCountEqual([(p["person_id"], p["subject"], p["text"], p["source_refs"]) for p in actual],
                              [(p["person_id"], "陆笙", p["text"], p["source_refs"]) for p in changes])
        witness = self.qq("45678", "203", "陆笙今天到了。")
        ambiguous = self.batch(GROUP)
        ids = {one["person_id"], two["person_id"]}
        self.assertNotIn(speaker(ambiguous, witness)["person_id"], ids)
        self.assertNotEqual(len({p["person_id"] for p in ambiguous["persons"]} & ids), 1)

    def test_third_party_can_describe_someone_without_confirming_their_alias(self):
        """证据的发言者和印象描述的人可以不同；第三方给对方起的称呼仍未确认。"""
        intro = self.qq(AUTHOR, "211", "我叫林舟。")
        first = self.batch(GROUP)
        target = speaker(first, intro)
        self.publish(first, "origin", [claim(target, "林舟", "SELF_NAME", intro)])
        report = self.qq(OTHER, "212", "林舟帮忙翻了乐谱，大家都叫他纸鹤。", mention_ids=[AUTHOR])
        page = self.batch(GROUP)
        described, = [p for p in page["persons"] if p["person_id"] == target["person_id"]]
        self.assertNotEqual(described["person_id"], speaker(page, report)["person_id"])
        fact = subject_change(described, "林舟", "我听到群友说林舟帮忙翻了乐谱。", report)
        self.publish(page, "report", [claim(described, "纸鹤", "MENTION", report, scope=GROUP)], [fact])
        saved, = self.profiles()
        self.assertEqual((saved["person_id"], saved["subject"], saved["source_refs"]),
                         (target["person_id"], "林舟", [ref(report)]))
        neutral = self.qq(AUTHOR, "213", "排练结束。")
        self.assertNotIn("纸鹤", speaker(self.batch(GROUP), neutral)["aliases"])

    def test_later_direct_answer_recalls_the_question_and_confirms(self):
        """真实提及及同群回复可连接分属两批的问答；单独问句不会给目标立别称。"""
        intro = self.qq(AUTHOR, "301", "我叫叶汀。")
        first = self.batch(GROUP)
        who = speaker(first, intro)
        self.publish(first, "intro", [claim(who, "叶汀", "SELF_NAME", intro)])
        question = self.qq(OTHER, "302", "你也叫曲竹吗？", mention_ids=[AUTHOR])
        asked = self.batch(GROUP)
        target, = [p for p in asked["persons"] if p["person_id"] == who["person_id"]]
        self.publish(asked, "question-only", [claim(target, "曲竹", "MENTION", question, scope=GROUP)])
        answer = self.qq(AUTHOR, "303", "对，是我。", reply_message_id="302")
        answered = self.batch(GROUP)
        responding = speaker(answered, answer)
        self.assertNotIn("曲竹", responding["aliases"])
        self.assertIn(ref(question), [ref(e) for e in answered["evidence"]])
        self.publish(answered, "answered", [claim(responding, "曲竹", "DIRECT_CONFIRMATION",
                                                  question, answer, scope=GROUP)])
        next_word = self.qq(AUTHOR, "304", "我会按时到场。")
        current = speaker(self.batch(GROUP), next_word)
        self.assertEqual((current["person_id"], current["subject"]), (who["person_id"], "叶汀"))
        self.assertIn("曲竹", current["aliases"])

    def test_another_question_answer_does_not_confirm_a_third_party_name(self):
        """问题都确实问过本人，但本人答的是座位问题。第三方的别称推断仍只能作为线索。"""
        suggestion = self.qq(OTHER, "321", "听说你也叫云舸，对吗？", mention_ids=[AUTHOR])
        self.qq(OTHER, "322", "你坐第三排吗？", mention_ids=[AUTHOR])
        answer = self.qq(AUTHOR, "323", "对，是的。", reply_message_id="322")
        offered = self.batch(GROUP)
        target = speaker(offered, answer)
        self.publish(offered, "wrong-question", [claim(target, "云舸", "DIRECT_CONFIRMATION",
                                                      suggestion, answer, scope=GROUP)])
        later = self.qq(AUTHOR, "324", "我收到了。")
        person = speaker(self.batch(GROUP), later)
        self.assertNotEqual(person.get("subject"), "云舸")
        self.assertNotIn("云舸", person["aliases"])

    def test_group_question_must_address_the_replying_account(self):
        """群里可以围观并回复。只有原问题指向答话账号时，回答才能确认被问到的别称。"""
        remark = self.qq(AUTHOR, "331", "我在收拾乐器。")
        open_question = self.qq(OTHER, "332", "你也叫桦弦吗？")
        bystander = self.qq(AUTHOR, "333", "是的。", reply_message_id="332")
        page = self.batch(GROUP)
        who = speaker(page, bystander)
        self.publish(page, "bystander", [claim(who, "桦弦", "DIRECT_CONFIRMATION", open_question, bystander, scope=GROUP)])
        # 询问者现在明确回复该成员先前的消息。两条相同的文字因此获得可核对的指向。
        directed = self.qq(OTHER, "334", "你也叫桦弦吗？", reply_message_id="331")
        reply = self.qq(AUTHOR, "335", "是的。", reply_message_id="334")
        page = self.batch(GROUP)
        who = speaker(page, reply)
        self.assertNotIn("桦弦", [who.get("subject"), *who["aliases"]])
        self.assertIn(ref(remark), [ref(e) for e in page["evidence"]])
        self.publish(page, "addressed", [claim(who, "桦弦", "DIRECT_CONFIRMATION", directed, reply, scope=GROUP)])
        probe = self.qq(AUTHOR, "336", "我听到了。")
        who = speaker(self.batch(GROUP), probe)
        self.assertIn("桦弦", [who.get("subject"), *who["aliases"]])

    def test_negative_quoted_conditional_or_joking_self_claim_stays_unconfirmed(self):
        """模型即使签成 SELF_NAME，原句里的否定、引用、假设和玩笑都不能变成这个账号的称呼。"""
        phrases = [(AUTHOR, "鹭羽", "我不叫鹭羽。"), (OTHER, "渡音", "他说：“我叫渡音。”"),
                   ("45678", "谷风", "如果我叫谷风，你会记得吗？"), ("56789", "栗芒", "开玩笑，我叫栗芒。")]
        sources = [self.qq(account, str(400 + i), phrase) for i, (account, _, phrase) in enumerate(phrases)]
        offered = self.batch(GROUP)
        self.publish(offered, "uncertain", [claim(speaker(offered, s), name, "SELF_NAME", s)
                                            for s, (_, name, _) in zip(sources, phrases)])
        later = [self.qq(account, str(410 + i), "我来了。") for i, (account, _, _) in enumerate(phrases)]
        verified = self.batch(GROUP)
        for source, (_, name, _) in zip(later, phrases):
            person = speaker(verified, source)
            self.assertNotEqual(person.get("subject"), name)
            self.assertNotIn(name, person["aliases"])

    def test_clipped_quote_cannot_remove_a_multisentence_qualifier(self):
        """截下来的句子都像自称，但完整原话分别是跨句引语、假设和事后明确点出的玩笑。"""
        items = [(AUTHOR, "赤笙", "朋友转述了一段话：“今天下雨了。我叫赤笙。”"),
                 (OTHER, "青鹭", "这是一段假设。我叫青鹭。"),
                 ("45678", "叶莺", "我叫叶莺。刚才是开玩笑的。")]
        words = [self.qq(account, str(420 + i), body) for i, (account, _, body) in enumerate(items)]
        page = self.batch(GROUP)
        self.publish(page, "clipped", [claim(speaker(page, s), name, "SELF_NAME", dict(s, quote="我叫" + name))
                                        for s, (_, name, _) in zip(words, items)])
        probes = [self.qq(account, str(430 + i), "我收到了。") for i, (account, _, _) in enumerate(items)]
        page = self.batch(GROUP)
        for s, (_, name, _) in zip(probes, items):
            who = speaker(page, s)
            self.assertNotIn(name, [who.get("subject"), *who["aliases"]])

    def test_ordinary_alias_survives_one_withdrawal_but_not_both(self):
        """两份独立的本人原话都支持别称；先后撤回时，只在最后一份失效后移出已确认称呼。"""
        dm = private(AUTHOR)
        base = self.qq(AUTHOR, "501", "我叫芷禾。")
        page = self.batch(GROUP, dm)
        who = speaker(page, base)
        self.publish(page, "base", [claim(who, "芷禾", "SELF_NAME", base)])
        supports = []
        for number, room in (("502", dm), ("503", GROUP)):
            source = self.qq(AUTHOR, number, "我也叫紫笛。", room)
            page = self.batch(GROUP, dm)
            same = speaker(page, source)
            self.assertEqual(same["person_id"], who["person_id"])
            self.publish(page, number, [claim(same, "紫笛", "SELF_NAME", source)])
            supports.append((source, room))
        for i, (source, room) in enumerate(supports):
            self.withdraw(source, room)
            probe = self.qq(AUTHOR, str(504 + i), "我到了。")
            page = self.batch(GROUP, dm)
            same = speaker(page, probe)
            self.assertEqual(same["subject"], "芷禾")
            self.assertEqual("紫笛" in same["aliases"], i == 0)
            if i == 0:
                self.publish(page, "one-left")

    def test_group_card_is_searchable_only_in_its_group(self):
        """甲群名片可在甲群按名找到该账号；乙群第三方复述同一个名字不会定位到甲群成员。"""
        original = self.qq(AUTHOR, "601", "借用一把谱夹。", nickname="北山", card="晚潮")
        first = self.batch(GROUP, FIELD)
        person_id = speaker(first, original)["person_id"]
        self.publish(first, "saw-card")
        self.qq(OTHER, "602", "晚潮说谱夹归还了。", FIELD)
        elsewhere = self.batch(GROUP, FIELD)
        self.assertNotIn(person_id, [p["person_id"] for p in elsewhere["persons"]])
        self.publish(elsewhere, "other-room")
        self.qq(OTHER, "603", "晚潮说谱夹归还了。")
        local = self.batch(GROUP, FIELD)
        found, = [p for p in local["persons"] if p["person_id"] == person_id]
        self.assertIn("晚潮", [found.get("subject"), *found["aliases"]])

    def test_unique_initial_display_can_publish_now_and_survive_an_ordinary_alias(self):
        """首次签发的唯一渠道昵称就可作主名；普通别称只在该昵称唯一来源撤回后接替它。"""
        display = self.qq(AUTHOR, "611", "我喜欢整理演出说明。", nickname="千屿")
        first = self.batch(GROUP)
        who = speaker(first, display)
        self.assertEqual(who.get("subject"), "千屿")
        self.publish(first, "display-and-note", changes=[subject_change(who, "千屿", "我记得其喜欢整理说明。", display)])
        alternate = self.qq(AUTHOR, "612", "我也叫晓渠。")
        page = self.batch(GROUP)
        self.publish(page, "ordinary-alias", [claim(speaker(page, alternate), "晓渠", "SELF_NAME", alternate)])
        self.assertEqual([p["subject"] for p in self.profiles()], ["千屿"])
        self.withdraw(display, GROUP)
        probe = self.qq(AUTHOR, "613", "资料已整理。")
        after = speaker(self.batch(GROUP), probe)
        self.assertEqual((after["person_id"], after.get("subject")), (who["person_id"], "晓渠"))

    def test_equal_display_candidates_have_no_primary_until_a_clear_self_name(self):
        """两个同级渠道显示名同时首次出现，缺少唯一首选时等本人给出明确称呼。"""
        self.qq(AUTHOR, "621", "第一场排练。", nickname="岑北")
        again = self.qq(AUTHOR, "622", "第二场排练。", nickname="岑南")
        initial = self.batch(GROUP)
        self.assertNotIn("subject", speaker(initial, again))
        self.publish(initial, "two-displays")
        explicit = self.qq(AUTHOR, "623", "我叫小岑。")
        page = self.batch(GROUP)
        who = speaker(page, explicit)
        self.assertNotIn("subject", who)
        self.publish(page, "explicit-name", [claim(who, "小岑", "SELF_NAME", explicit)])
        later = self.qq(AUTHOR, "624", "第三场排练。")
        self.assertEqual(speaker(self.batch(GROUP), later)["subject"], "小岑")

    def test_global_preference_renames_only_automatically_chosen_primary(self):
        """同样的明确全局改称能更改自动主名，已经人工指定主名的另一人仍沿用原名。"""
        a = self.qq(AUTHOR, "701", "我叫遥音。")
        b = self.qq(OTHER, "702", "我叫程雨。")
        initial = self.batch(GROUP)
        auto, fixed = speaker(initial, a), speaker(initial, b)
        self.publish(initial, "original", [claim(auto, "遥音", "SELF_NAME", a), claim(fixed, "程雨", "SELF_NAME", b)],
                     [subject_change(auto, "遥音", "我听到她自我介绍。", a),
                      subject_change(fixed, "程雨", "我听到她自我介绍。", b)])

        # 下次启动由维护者确认其中一人的主名，已有 QQ、档案和来源继续用原身份。
        self.stop_memory()
        dump(self.service.out / "people.json", {"character_id": "misty-dev", "people": [
             {"subject": "程雨", "aliases": ["雨铃"], "qq_sender_id": OTHER}]})
        with (self.service.out / "memory.properties").open("a", encoding="utf-8") as settings:
            settings.write("dreamingPeopleFile=people.json\n")
        self.service.memory()
        ar = self.qq(AUTHOR, "703", "以后无论在哪里都请叫我晚晴。")
        br = self.qq(OTHER, "704", "以后无论在哪里都请叫我苇竹。")
        rename = self.batch(GROUP)
        self.publish(rename, "preferred", [claim(speaker(rename, ar), "晚晴", "PREFERRED_NAME", ar),
                                           claim(speaker(rename, br), "苇竹", "PREFERRED_NAME", br)])
        self.assertCountEqual([(p["person_id"], p["subject"]) for p in self.profiles()],
                              [(auto["person_id"], "晚晴"), (fixed["person_id"], "程雨")])


if __name__ == "__main__":
    unittest.main(verbosity=2)
