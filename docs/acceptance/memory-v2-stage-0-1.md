# NeuralCloud Memory v2 阶段 0 和 1 验收报告

2026-11-22，Windows x64，Zulu JDK 21.0.3。Memory 和 Harness 分别构建并启动。正式入口 `POST /api/nodes/chat-worker/messages` 上的会议回放及故障回放 **20 组全部通过**。Harness 的 Maven 回归运行 11 个测试类，32 项通过，失败、错误和跳过均为 0。Harness 配套提交为 `7ff6607`。[可执行回放](../../tests/acceptance.py) · [构建与复跑](../../README.md)

## 回放环境

Memory 使用独立数据目录、JDBC、SQLite WAL 和 FTS5。Harness 使用 `ConsoleMain`、真实 HTTP 客户端、正式 Worker 和本地持久日志。首轮正向握手的 Memory PID 为 `42408`，Harness PID 为 `43072`。双方确认角色 `misty-dev`、协议 `2` 和流 `f287771e-cea6-4fd2-be6d-bf325a4566ca`。

会议发言通过本地控制台录入 `console:project`。操作员随原文提供发言者、原始场合、原始时间及明确的事项关系。可控模型按 Harness 实际发出的材料选取原话，回放同时断言回复与模型输入。提醒执行器在独立测试 SQLite 表中写入清单后返回 `APPLIED`。其尝试和实际回执由测试适配器提交给真实 Memory。QQ 本人读取另经 Harness 正式 OpenQQ 适配器及回环 OneBot 端点验证。模型质量、真实 QQ 群接入和线上提醒投递属于其他验证环境。

## 阶段 0

| 验收条件 | 实际结果 |
| --- | --- |
| Java 独立进程、角色及协议握手 | 两个不同 PID 同时存活，审计 `memory_ready` 中的角色、流与 Memory 健康响应一致。两端分别持有自己的数据库。 |
| 四种错误启动 | 错误令牌得到 HTTP 401，错误角色被 Harness 拒绝。对照健康响应分别降低协议主版本、清空能力后，两个 Harness 启动也各自非零退出并记录原因。 |
| 正常重启继承历史 | 主管进程关闭 Memory 的标准输入，触发正常停机，退出码为 0。原目录重启后 `stream_id` 相同，`head_seq=100`。按每页 9 条读取的变更严格递增，无重复。 |
| 兼容旧身份、回执、码点 | 原 Python 实现 `1c44702` 冻结的样本与 Java 返回相同的完整 ID、`revision=1`、`seq=1` 及 `DURABLE`。按码点取 `start=1,length=2` 均得“林🗓”。[v1 冻结样本](../../contracts/v2/examples/v1-replay.json) |
| 契约可供两端复用 | 回放覆盖全部八个已发布路径，用 JSON Schema 校验成功写读请求和实际响应，包括错误响应、失效项及分页。保留了本次两个入账请求、各自回执和当前事项查询。[v2 契约](../../contracts/v2/openapi.json) · [会议请求与响应](../../contracts/v2/examples/meeting.json) |

## 阶段 1

| 验收条件 | 实际结果 |
| --- | --- |
| 两条观察、可追溯的原文 | 周三“小林在项目群说例会定在周五 15 点”入账为 `423f0ebb2899…@1`，`seq=2`。周四改到周六的另一条消息为 `c9edf2c3d891…@1`，`seq=7`。二者分别从自身 `operator_message → router_memory_write → memory_durable` 取证。完整 ID 见会议样例。 |
| 查原安排、查现安排 | 正式入口分别回出 **周五 15 点** 和 **周六 10 点**，各自附小林、项目群和相应原话。只看过旧安排的 Worker 收到新发言后，其首个模型请求已把周五列为历史、周六列为当前。 |
| 业务时间与知悉时间 | 在 11 月 18 日的业务时间取到周五。固定 11 月 20 日的业务时间，将知悉水位由 `2` 推至 `7`，结果从周五变为周六。并行且无明确替代关系的两种安排返回 `CONFLICT`。零命中返回 `NOT_FOUND`、`absence_confirmed=false`。 |
| 提醒尝试与执行结果 | 只有 `rem-1` 的尝试，另有 `rem-2` 的结果时，`rem-1` 评估为 `INCOMPLETE`，会话回答“提醒状态未知”。执行器提交 `2026-11-21T10:00:00+08:00` 后，匹配回执在 `seq=17` 入账，会话才引用清单已更新。 |
| 重试幂等 | 同键同内容重放得到同一回执，水位保持原值。相同键不同内容返回 `409 EVENT_ID_CONTENT_CONFLICT`。另一次重放原更正也得到了原更正回执。 |
| 编辑、撤回与派生发布 | 改期消息编辑为周日 14 点，沿用原 ID，升至修订 2，`seq=89`。过期编辑返回 `REVISION_CONFLICT`，迟到的旧版派生返回 `SOURCE_CHANGED`。已发布摘要仍为修订 1，重读仅得失效状态。两个来源中有一项过期即整笔拒绝且无回执。撤回后原文升至修订 3，`seq=93`，显式读旧版也无正文。 |
| 先限会话再取前 K 条 | 写入 65 条其他会话的高相似文本，`limit=1` 仍取回本会话唯一合法原文。另一个会话凭 ID 回读只得 `NOT_FOUND`。检查捕获的模型请求，私聊哨兵出现 0 次。 |
| 实际登记、首个输入修正 | 模型输入与审计用同一份 `ref/source_refs/stream_id/read_seq` 登记。更正后的首个请求（捕获下标 `14`）含周日 14 点、无旧周六正文，随后查询水位为 `90`。撤回后独立重启 Harness，首个模型输入仍给出失效通知。 |
| 更正先消费、旧读取后登记 | 响应闸门拦住真实 `read_seq=110` 的读取，Memory 在 `seq=111` 更正，同一 Worker 的另一轮先消费它，再放行旧读取。下一轮首个模型请求（下标 `30`）含白鹭厅、无黄鹤厅。冻结的输入证据仍可分别查到修订 1 和 2。 |
| 回执丢失与独立恢复 | 回环代理在角色回复已写出且 Memory 已提交后，向 Harness 返回 503。重启 Harness，待办经 `/v2/receipts` 对账得原回执 `seq=118`，该记录只有一条变更，渠道写出 1 次。另一条字段错误命令登记为 `REFUSED`，下一条有效消息入账成功。 |
| 保持三种读取目的 | `HISTORY` 的 `scoped` 返回同会话证据，`recent` 提供近期对话且话题命中计数为 0。OneBot 私聊发出两问，`SELF_IDENTITY` 得到已确认称呼“小林”，`SELF_HISTORY` 仅返回该发件人在群里说的“白鹭航线由我负责”。渠道确认两次回复，第三人原话未交付。Worker 直接尝试发布派生项得到 403。 |

## 本次证据

批次 `20261122T142805559680Z` 的原始文件保存于 `target/acceptance/20261122T142805559680Z/`。`results.json` 给出各组断言、进程及 JAR 的 SHA-256。`meeting-audit.json` 关联本轮写入、召回和交付。`model-inputs.json` 按发送顺序保存实际请求。`registration-race-audit.json`、`recovery-audit.json`、`onebot-audit.json` 分别对应登记竞态、丢失回执和本人读取。`ledger/memory.sqlite` 保存权威修订、回执与变更。`audit/memory-v2-misty-dev.sqlite` 保存 Harness 的输入、依赖和待办。

首版以显式 `assertion.supersedes_ref` 解析会议变更。操作员未提供明确关系时，原话可检索，并行断言可报告冲突。通用跨会话授权、多 Worker 广播、向量精排与长期整理仍依草案的后续阶段验收。
