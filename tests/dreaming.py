"""从正式 HTTP 入口验收 Dreaming 的证据、受众、修订和独立进度。"""
import secrets
import unittest

from acceptance import ROOT, ServicePair, context, event, ref, send, stop
from stage2 import ACCOUNT, AUTHOR, GROUP, audience, private


INSTANCE = "neuralcloud-java"
DREAM = "dreaming:misty-dev"
STUDIO = "console:排练室"
ARCHIVE = "console:排练室-档案"
FIELD = "group:onebot:12345:34567"


def internal(role="ORCHESTRATOR"):
    """内部会话和作者由服务上下文标识，调度实例保留自己的 session_id。"""
    return {**context(DREAM, role), "actor_id": "DREAMING", "session_id": "night-shift-01"}


def change(kind, subject, text, refs, version=0):
    """每条提案携带模型所见的来源修订和认识版本。"""
    return dict(kind=kind, subject=subject, expected_version=version, text=text, scope="本次排练",
                reason="依据排练原始记录", source_refs=refs)


def publication(page, key, changes):
    """回传所领批次的身份及边界，空列表也显式提交。"""
    return {**{k: page[k] for k in ("stream_id", "policy_version", "after", "through")},
            "context": internal("ROUTER"), "source_instance": INSTANCE, "event_id": key, "changes": changes}


class Dreaming(unittest.TestCase):
    """每个场景独占一个服务数据目录，凭据、修订和水位只从公开出口观察。"""

    def setUp(self):
        self.service = ServicePair(ROOT.parent / "neuralcloud-harness")
        self.author_token = secrets.token_urlsafe(32)
        self.service.env["NC_DREAM_AUTHOR_TOKEN"] = self.author_token
        with (self.service.out / "memory.properties").open("a", encoding="utf-8") as f:
            f.write(f"grantAccountId={ACCOUNT}\ngrantAccessTokenEnv=NC_DREAM_AUTHOR_TOKEN\n")
        self.addCleanup(self.stop_memory)
        self.service.memory()

    def stop_memory(self):
        process = self.service.mproc
        if process:
            stop(process)
            process.stdin.close()

    def post(self, route, body, expected=200):
        return self.service.api("dreaming/" + route, body, expected)

    def batch(self, *scopes, limit=16):
        return self.post("batch", dict(context=internal(), scopes=list(scopes), limit=limit))

    def receipt(self, key):
        return self.post("receipt", dict(context=internal(), source_instance=INSTANCE, event_id=key))

    def ingest(self, key, text, scope=STUDIO, kind="observation", **fields):
        return ref(self.service.api("ingest", event(key, text, kind, scope, **fields)))

    def correct(self, key, source, operation, **fields):
        return ref(self.service.api("corrections", dict(context=context(STUDIO), source_instance=INSTANCE,
                   event_id=key, record_id=source["record_id"], expected_revision=source["revision"],
                   operation=operation, reason="当事人更正排练安排", **fields)))

    def grant(self, key, source, target, use):
        """使用渠道专用凭据代表原作者授予精确版本的权限。"""
        body = dict(context=audience(private(AUTHOR), "ORCHESTRATOR"), source_instance=INSTANCE,
                    event_id=key, source_ref=source, target_conversation_id=target, use=use, reason="排练授权")
        return send(self.service.base + "visibility-grants", body, self.author_token)["grant_id"]

    def revoke(self, key, grant_id):
        body = dict(context=audience(private(AUTHOR), "ORCHESTRATOR"), source_instance=INSTANCE,
                    event_id=key, reason="取消本次授权")
        send(self.service.base + "visibility-grants/" + grant_id + "/revoke", body, self.author_token)

    def assertEvidence(self, page, *expected):
        """按精确修订比对，既检查漏项也检查重复或越界交付。"""
        self.assertCountEqual([ref(item) for item in page["evidence"]], expected)

    def assertProfiles(self, *expected, ctx=None):
        """同一认识的全部字段均须来自发布内容；来源与档案列表不要求排列顺序。"""
        actual = self.post("profiles", {"context": internal() if ctx is None else ctx})["profiles"]
        keys = ("kind", "subject", "text", "scope", "reason", "author", "version", "source_refs")

        def fields(profile):
            return {**{k: profile[k] for k in keys}, "source_refs": sorted(
                    profile["source_refs"], key=lambda r: (r["record_id"], r["revision"]))}

        self.assertCountEqual([fields(p) for p in actual], [fields({**p, "author": "DREAMING",
                              "version": p["expected_version"] + 1}) for p in expected])

    def test_firsthand_input_and_master_authorship(self):
        """正例跨会话收三类原文；反例排除派生、尝试、送达回执及相似会话名。"""
        obs = self.ingest("obs", "小程说愿意排练。")
        expr = self.ingest("mine", "我能参加周六的合奏。", GROUP, "self_expression")
        result = self.ingest("done", "场地预订已生效。", GROUP, "tool_result", action_id="booking")
        self.ingest("echo", "转述小程的话。", kind="self_expression", source_refs=[obs])
        self.ingest("try", "正在订场地。", kind="tool_attempt", action_id="booking")
        self.ingest("sent", "已发送消息。", kind="delivery_receipt", action_id="message")
        self.ingest("outside", "另一个会话的安排。", ARCHIVE)
        master = event("master", "小程对音乐认真。", "impression", STUDIO, topic="小程", author="MASTER",
                       source_refs=[obs])
        impression = ref(self.service.api("candidates", master))

        page = self.batch(STUDIO, GROUP)
        self.assertEvidence(page, obs, expr, result)
        self.assertCountEqual([(e["conversation_id"], e["kind"], e["content"]) for e in page["evidence"]],
                              [(STUDIO, "observation", "小程说愿意排练。"), (GROUP, "self_expression", "我能参加周六的合奏。"),
                               (GROUP, "tool_result", "场地预订已生效。")])
        self.assertEqual(page["profiles"], [])
        person = change("person", "小程", "小程愿意排练。", [obs])
        self.post("commit", publication(page, "publish", [person]))
        self.assertProfiles(person)
        # Master 的既有印象保留自己的引用和修订入口；同名 Dreaming 人物仍独立存在。
        revised = {**master, "context": context(STUDIO, "ORCHESTRATOR"), "event_id": "revise-master",
                   "content": "小程愿意参加这次排练。", "replaces_impression": impression}
        self.assertEqual(self.service.api("candidates", revised)["status"], "DURABLE")
        self.assertProfiles(person)

    def test_page_claims_restart_and_receipts(self):
        """正例逐页确认、重启续跑；反例重复领取不前进、同键异体不覆盖原回执。"""
        first = self.ingest("one", "演出第一条记录。")
        second = self.ingest("two", "演出第二条记录。")
        page = self.batch(STUDIO, limit=1)
        self.assertEvidence(page, first)
        self.assertGreater(page["through"], page["after"])
        self.assertEqual(page, self.batch(STUDIO, limit=1))
        self.post("commit", publication(page, "no-change", []))
        next_page = self.batch(STUDIO, limit=1)
        self.assertEqual(next_page["after"], page["through"])
        self.assertEvidence(next_page, second)
        self.post("commit", publication(page, "already-consumed", []), 409)
        topic = change("topic", "演出", "已有第二条演出记录。", [second])
        command = publication(next_page, "publish-page", [topic])
        saved = self.post("commit", command)
        self.assertEqual((saved["status"], saved["through"]), ("DURABLE", next_page["through"]))
        fresh = self.ingest("three", "新增一条演出记录。")
        pending = self.batch(STUDIO)
        self.assertEqual(pending["after"], next_page["through"])
        self.assertIn(fresh, [ref(e) for e in pending["evidence"]])
        self.assertNotIn(first, [ref(e) for e in pending["evidence"]])

        self.stop_memory()
        self.service.memory()
        self.assertEqual(self.receipt("publish-page"), saved)
        self.assertEqual(self.post("commit", command), saved)
        self.post("commit", {**command, "changes": []}, 409)
        self.assertProfiles(topic)
        self.assertEqual(self.batch(STUDIO), pending)
        self.post("commit", publication(pending, "resumed", []))
        idle = self.batch(STUDIO)
        self.assertEqual(idle["after"], idle["through"])
        self.assertEvidence(idle)

    def test_atomic_batch_and_profile_versions(self):
        """反例在第二项触发批外证据和旧版冲突；正例修正后两档案共同发布、推进。"""
        a = self.ingest("arrival", "小程到场并领取钥匙。")
        b = self.ingest("return", "小程已归还钥匙。")
        page = self.batch(STUDIO, limit=1)
        self.assertEvidence(page, a)
        person = change("person", "小程", "小程已到场。", [a])
        topic = change("topic", "钥匙", "钥匙已领取。", [a])
        outside = change("topic", "钥匙", "钥匙已归还。", [b])
        self.post("commit", publication(page, "outside-batch", [person, outside]), 409)
        self.assertEqual(self.receipt("outside-batch")["status"], "NOT_FOUND")
        self.assertProfiles()
        self.assertEqual(self.batch(STUDIO, limit=1), page)
        self.post("commit", publication(page, "create", [person, topic]))

        update = self.batch(STUDIO)
        next_person = change("person", "小程", "小程归还过钥匙。", [b], version=1)
        next_topic = change("topic", "钥匙", "钥匙已归还。", [b], version=1)
        stale = {**next_topic, "expected_version": 0}
        self.post("commit", publication(update, "stale-version", [next_person, stale]), 409)
        self.assertEqual(self.receipt("stale-version")["status"], "NOT_FOUND")
        self.assertProfiles(person, topic)
        self.assertEqual(self.batch(STUDIO)["after"], page["through"])
        self.post("commit", publication(update, "update", [next_person, next_topic]))
        self.assertProfiles(next_person, next_topic)

    def test_correction_retraction_and_survivors(self):
        """正例按保留版本重评幸存原文；反例更正或撤回立即隐藏旧认识，迟到提交无回执。"""
        notice = self.ingest("notice", "周五排练。")
        backup = self.ingest("backup", "小程准备了替补曲目。")
        profile = change("topic", "彩排", "周五排练，有替补曲目。", [notice, backup])
        self.post("commit", publication(self.batch(STUDIO), "before", [profile]))
        saturday = self.correct("edit", notice, "REPLACE", content="周六排练。")
        self.assertProfiles()
        page = self.batch(STUDIO)
        self.assertEvidence(page, saturday, backup)
        self.assertEqual(page["profiles"], [{"kind": "topic", "subject": "彩排", "version": 1}])
        changed = change("topic", "彩排", "周六排练，有替补曲目。", [saturday, backup], version=1)

        # 领取后原作者再次编辑，旧批次内曾经合法的修订也必须在持久写入前复核。
        sunday = self.correct("edit-again", saturday, "REPLACE", content="周日排练。")
        self.post("commit", publication(page, "late", [changed]), 409)
        self.assertEqual(self.receipt("late")["status"], "NOT_FOUND")
        retry = self.batch(STUDIO)
        self.assertEqual(retry["after"], page["after"])
        self.assertEvidence(retry, sunday, backup)
        current = change("topic", "彩排", "周日排练，有替补曲目。", [sunday, backup], version=1)
        self.post("commit", publication(retry, "corrected", [current]))
        self.assertProfiles(current)
        self.correct("withdraw", sunday, "RETRACT")
        self.assertProfiles()
        reduced = self.batch(STUDIO)
        self.assertEvidence(reduced, backup)
        self.assertEqual(reduced["profiles"], [{"kind": "topic", "subject": "彩排", "version": 2}])
        surviving = change("topic", "彩排", "只确认已准备替补曲目。", [backup], version=2)
        self.post("commit", publication(reduced, "surviving", [surviving]))
        self.assertProfiles(surviving)

    def test_author_grants_and_all_source_audience(self):
        """正例原作者可授权范围外的原文；反例 READ、部分 CITE、撤销任一必要授权均隐藏。"""
        channel = dict(platform="qq", type="qq_group", account_id=ACCOUNT, sender_id=AUTHOR,
                       conversation_id=FIELD, message_id="315")
        outside = self.ingest("location", "礼堂允许彩排。", FIELD, channel=channel)
        self.grant("read-dream", outside, DREAM, "READ")
        held = self.batch(GROUP)
        self.assertEvidence(held)
        if held["through"] > held["after"]:
            self.post("commit", publication(held, "skip-read", []))

        internal_grant = self.grant("cite-dream", outside, DREAM, "CITE")
        local = self.ingest("conductor", "指挥确认参加。", GROUP)
        page = self.batch(GROUP)
        self.assertEvidence(page, local, outside)
        profile = change("topic", "联演", "指挥确认参加礼堂彩排。", [local, outside])
        self.post("commit", publication(page, "joint", [profile]))
        self.assertProfiles(profile)
        audience_context = context(GROUP, "MASTER")
        self.grant("read-room", outside, GROUP, "READ")
        self.assertProfiles(ctx=audience_context)
        audience_grant = self.grant("cite-room", outside, GROUP, "CITE")
        self.assertProfiles(profile, ctx=audience_context)
        self.revoke("withdraw-room", audience_grant)
        self.assertProfiles(ctx=audience_context)
        self.assertProfiles(profile)
        self.revoke("withdraw-dream", internal_grant)
        self.assertProfiles()
        self.assertEvidence(self.batch(GROUP), local)

    def test_scope_expansion_and_contraction(self):
        """正例新增范围回扫此前历史；反例范围收缩立即退役旧认识和原策略下在途的提交。"""
        archived = self.ingest("archive", "档案室有旧谱。", ARCHIVE)
        studio = self.ingest("studio", "排练室已登记借谱。")
        single = self.batch(STUDIO)
        self.assertEvidence(single, studio)
        self.post("commit", publication(single, "initial", []))
        expanded = self.batch(STUDIO, ARCHIVE)
        self.assertNotEqual(expanded["policy_version"], single["policy_version"])
        self.assertEvidence(expanded, studio, archived)
        profile = change("topic", "借谱", "排练室借用档案室的旧谱。", [studio, archived])
        self.post("commit", publication(expanded, "two-scopes", [profile]))
        self.assertProfiles(profile)

        fresh = self.ingest("new", "排练室归还谱本。")
        pending = self.batch(STUDIO, ARCHIVE)
        narrowed = self.batch(STUDIO)
        self.assertNotEqual(narrowed["policy_version"], expanded["policy_version"])
        self.assertEvidence(narrowed, studio, fresh)
        self.assertProfiles()
        self.post("commit", publication(pending, "stale-policy", []), 409)

    def test_service_identity_and_batch_bounds(self):
        """正例由编排端领取、Router 提交；反例无凭据、内部身份错误或超限不能执行。"""
        src = self.ingest("door", "排练室开门。")
        request = {"context": internal(), "scopes": [STUDIO], "limit": 1}
        for limit in (0, 17):
            self.post("batch", {**request, "limit": limit}, 422)
        for ctx in (internal("MASTER"), {**internal(), "actor_id": "MASTER"},
                    {**internal(), "conversation_id": STUDIO}):
            self.post("batch", {**request, "context": ctx}, 403)
        page = self.post("batch", request)
        self.assertEvidence(page, src)
        command = publication(page, "authorized", [])
        lookup = {"context": internal(), "source_instance": INSTANCE, "event_id": "authorized"}
        for route, body in (("batch", request), ("commit", command), ("receipt", lookup),
                            ("profiles", {"context": internal()})):
            with self.subTest(route=route):
                send(self.service.base + "dreaming/" + route, body, expected=401)
        self.post("receipt", {**lookup, "context": internal("ROUTER")}, 403)
        self.post("commit", {**command, "context": internal()}, 403)
        self.assertEqual(self.post("receipt", lookup)["status"], "NOT_FOUND")
        self.assertEqual(self.post("commit", command)["status"], "DURABLE")


if __name__ == "__main__":
    unittest.main(verbosity=2)
