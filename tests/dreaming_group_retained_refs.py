"""以真实 v7 已授权的私聊人物档，验收升级后群历史批次的依据保留与拒绝边界。"""
import os
import secrets
import unittest
from pathlib import Path

from acceptance import ROOT, ServicePair
from dreaming import DREAM, GROUP, DreamingCase, change, internal, publication
from dreaming_qq_aliases import speaker, subject_change
from stage2 import ACCOUNT, AUTHOR, OTHER, audience, private


class GroupRetainedRefs(DreamingCase):
    def setUp(self):
        """原作者只给整理器 CITE。v7 把一条私聊存入人物档，另一条同人私聊只用作主题依据。"""
        self.service = ServicePair(ROOT.parent / "neuralcloud-harness", session_isolation="true")
        self.addCleanup(self.stop_memory)
        current = self.service.memjar
        self.service.memjar = Path(os.environ["NC_DREAMING_V7_JAR"]).resolve(strict=True)
        self.author_token = secrets.token_urlsafe(32)
        self.service.env["NC_DREAM_AUTHOR_TOKEN"] = self.author_token
        with (self.service.out / "memory.properties").open("a", encoding="utf-8") as settings:
            settings.write(f"grantAccountId={ACCOUNT}\ngrantAccessTokenEnv=NC_DREAM_AUTHOR_TOKEN\n")
        self.service.memory()
        dm = private(AUTHOR)
        self.earlier = self.qq(dm, AUTHOR, "931", "我每次入场会先核对总谱页码。", nickname="徽音")
        self.grant_id = self.grant("allow-person-source", self.earlier, DREAM, "CITE")
        self.topic_ref = self.qq(dm, AUTHOR, "932", "我单独清点了备用铅笔的库存。")
        self.grant("allow-topic-source", self.topic_ref, DREAM, "CITE")
        self.member = self.qq(GROUP, AUTHOR, "933", "我给第一排的谱架贴了绿色标记。")
        self.peer = self.qq(GROUP, OTHER, "934", "我把计时表放到了入口。", card="朔禾")
        old = self.batch(GROUP)
        self.previous = subject_change(speaker(old, self.earlier), "徽音", "我记得徽音入场前会核对总谱页码。", self.earlier)
        topic = change("topic", "铅笔库存", "我记得备用铅笔已单独清点。", [self.topic_ref])
        self.post("commit", dict(publication(old, "two-prior-profiles", [self.previous, topic]), name_claims=[]))
        self.stop_memory()
        self.service.memjar = current
        self.service.memory()
        self.page = self.post("batch", dict(context=internal(), scopes=[GROUP], include_group_history=True, limit=16))
        self.assertEqual(self.page["lane"], "group_history")
        self.owner, self.other = speaker(self.page, self.member), speaker(self.page, self.peer)

    def qq(self, room, sender, number, text, **metadata):
        return self.ingest(number, text, room, context=audience(room, "ROUTER"), channel=dict(platform="qq",
                           type="qq_private" if room.startswith("c2c:") else "qq_group", account_id=ACCOUNT,
                           sender_id=sender, conversation_id=room, message_id=number, **metadata))

    def command(self, event_id, proposal):
        return dict(publication(self.page, event_id, [proposal]), lane="group_history", name_claims=[])

    def update(self, *sources):
        return subject_change(self.owner, "徽音", "我记得徽音会核对总谱页码，这次还给第一排谱架做了标记。", *sources, version=1)

    def test_same_member_can_keep_their_authorized_private_reference(self):
        """批内只给本页两条群原话，现版人物档可携旧引用续写，两种材料仍各守原受众。"""
        self.assertEvidence(self.page, self.member, self.peer)
        self.assertEqual(self.owner["person_id"], self.previous["person_id"])
        prior, = [p for p in self.page["profiles"] if p.get("person_id") == self.owner["person_id"]]
        self.assertEqual((prior["version"], prior["text"], prior["source_refs"]), (1, self.previous["text"], [self.earlier]))
        proposal = self.update(self.earlier, self.member)
        self.assertEqual(self.post("commit", self.command("carry-one-private-ref", proposal))["status"], "DURABLE")
        stored, = [p for p in self.post("profiles", {"context": internal()})["profiles"] if p["kind"] == "person"]
        self.assertEqual((stored["person_id"], stored["version"], stored["text"]), (self.owner["person_id"], 2, proposal["text"]))
        self.assertCountEqual(stored["source_refs"], [self.earlier, self.member])
        self.assertEqual(self.post("profiles", {"context": audience(GROUP)})["profiles"], [])

    def test_old_sources_cannot_be_reassigned_or_used_without_a_new_group_fact(self):
        """当前人的旧引用须有本页本人原话才能沿用，逐项核验原档归属、精确版本和仍有效的 CITE。"""
        transfer = subject_change(self.other, "朔禾", "我记得朔禾带了计时表。", self.peer, self.earlier)
        wrong_version = dict(self.earlier, revision=self.earlier["revision"] + 1)
        for event_id, proposal, status, code in (
                ("other-person-carry", transfer, 409, "SOURCE_CHANGED"),
                ("borrow-from-topic", self.update(self.member, self.topic_ref), 409, "SOURCE_CHANGED"),
                ("wrong-earlier-version", self.update(self.member, wrong_version), 409, "SOURCE_CHANGED"),
                ("different-current-speaker", self.update(self.earlier, self.peer), 422, "INVALID_FIELD"),
                ("only-retained-source", self.update(self.earlier), 422, "INVALID_FIELD")):
            with self.subTest(proposal=event_id):
                self.assertEqual(self.post("commit", self.command(event_id, proposal), status)["code"], code)
                self.assertEqual(self.receipt(event_id)["status"], "NOT_FOUND")
        self.revoke("withdraw-private-source", self.grant_id)
        stale = self.command("revoked-after-issue", self.update(self.earlier, self.member))
        self.assertEqual(self.post("commit", stale, 409)["code"], "DREAM_BATCH_CHANGED")
        self.assertEqual(self.receipt("revoked-after-issue")["status"], "NOT_FOUND")
        self.assertFalse(any(p["kind"] == "person" for p in self.post("profiles", {"context": internal()})["profiles"]))


if __name__ == "__main__":
    unittest.main()
