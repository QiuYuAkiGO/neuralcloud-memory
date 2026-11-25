"""用已知来源 ID 的 HISTORY/CITE 批量回读验收查询时限和传递依赖的可见性。"""
import time
import unittest

from acceptance import ROOT, ServicePair, context, event, ref, send
from qq_author_history import AUDIENCE, group_event
from session_isolation import close_memory


def private_event(key, text, **fields):
    """由 Router 在提问者的私聊中登记发言及其原始引用。"""
    writer = dict(AUDIENCE, actor_id="ROUTER", role="ROUTER")
    return event(key, text, context=writer, **fields)


def read(run, targets, audience=AUDIENCE):
    """计时覆盖与 Harness 相同的批量 HTTP 请求和响应正文。"""
    body = dict(context=audience, kind="HISTORY", use="CITE", targets=targets)
    started = time.perf_counter()
    items = send(run.base + "read", body, run.token)["items"]
    return items, time.perf_counter() - started


class KnownRecordReads(unittest.TestCase):
    def service(self, isolation):
        run = ServicePair(ROOT.parent / "neuralcloud-harness", session_isolation=isolation)
        with (run.out / "memory.properties").open("a", encoding="utf-8") as config:
            config.write("organizeIntervalSeconds=0\n")
        self.addCleanup(close_memory, run)
        run.memory()
        return run

    def test_batch_with_unrelated_history_meets_client_deadline(self):
        """正例。用合成原话及其转述构成 38 个已知 ID，请求在现有客户端五秒时限内交付。"""
        run = self.service("false")
        targets = []
        for i in range(19):
            source = ref(run.api("ingest", group_event(f"item-{i}", 8, f"我在给样片 {i} 编号。")))
            echo = private_event(f"echo-{i}", f"已记录样片 {i}。", kind="self_expression", source_refs=[source])
            targets.extend([source, ref(run.api("ingest", echo))])
        original, earlier = read(run, targets)
        self.assertEqual([(item["ref"], item["status"]) for item in original],
                         [(target, "ONLINE") for target in targets])

        # 每次公开写入仅新增一个无关引用。前后两次回读固定受众、目标、版本和返回正文。
        scope = "console:other-catalog"
        tip = ref(run.api("ingest", event("other-root", "单独编制的目录。", scope=scope)))
        for i in range(250):
            entry = event(f"other-{i}", f"续记目录第 {i} 项。", scope=scope,
                          kind="self_expression", source_refs=[tip])
            tip = ref(send(run.base + "ingest", entry, run.token))
        current, elapsed = read(run, targets)
        self.assertEqual(current, original)
        print(f"38 条已知记录回读：无关链增长前 {earlier:.3f}s，增长后 {elapsed:.3f}s", flush=True)
        self.assertLess(elapsed, 5.0, "批量回读超过 Harness 五秒请求时限")

    def test_transitive_source_changes_and_audience(self):
        """反例。两层引用下的来源失权、更正或撤回均隐藏其转述，批内独立原话继续可见。"""
        run = self.service("true")
        changes, targets = [], []
        for operation in ("REPLACE", "RETRACT"):
            notice = group_event(operation, 8, "本组的新通知。", kind="qq_group_notice")
            source = ref(run.api("ingest", notice))
            middle = private_event("quote-" + operation, "我记下了群通知。",
                                   kind="self_expression", source_refs=[source])
            outer = private_event("retell-" + operation, "这份记录源自前次转述。",
                                  kind="self_expression", source_refs=[ref(run.api("ingest", middle))])
            targets.append(ref(run.api("ingest", outer)))
            channel = dict(notice["channel"], message_id="change-" + operation)
            change = dict(context=context(channel["conversation_id"]), source_instance="neuralcloud-java",
                          event_id="change-" + operation, operation=operation, record_id=source["record_id"],
                          expected_revision=source["revision"], reason="发布者更正或撤回群通知", channel=channel)
            if operation == "REPLACE":
                change["content"] = "更正后的新通知。"
            changes.append(change)
        targets.append(ref(run.api("ingest", private_event("independent", "单独编号的原话。"))))
        initial, _ = read(run, targets)
        self.assertEqual([(item["status"], item["ref"]) for item in initial],
                         [("ONLINE", target) for target in targets])

        outside = dict(AUDIENCE, qq=dict(AUDIENCE["qq"], member_group_ids=["34567"]))
        denied, _ = read(run, targets, outside)
        self.assertEqual(denied[:-1], [dict(record_id=t["record_id"], status="NOT_FOUND") for t in targets[:-1]])
        self.assertEqual(denied[-1], initial[-1])
        for change in changes:
            run.api("corrections", change)
        invalid, _ = read(run, targets)
        self.assertEqual(invalid[:-1], [dict(ref=t, status="SOURCE_CHANGED") for t in targets[:-1]])
        self.assertEqual(invalid[-1], initial[-1])


if __name__ == "__main__":
    unittest.main()
