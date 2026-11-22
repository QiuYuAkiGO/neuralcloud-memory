# NeuralCloud Memory v2

Java 21 记忆服务负责原文与修订、派生来源、全文检索、持久回执和变更流。`neuralcloud-harness` 通过回环 HTTP 写入和查询，在自己的审计目录登记实际交给模型的证据。两个 JVM 分别持有自己的 SQLite 数据库。[架构与数据契约草案](docs/proposals/neuralcloud-memory-v2-architecture-and-data-contracts.md) · [阶段 0 和 1 验收报告](docs/acceptance/memory-v2-stage-0-1.md)

## 构建和复跑首版验收

在本仓库根目录执行，默认两个仓库位于同一父目录。`java`、`javac`、`keytool` 使用 JDK 21。回放另需 Maven、Python 3.9 或以上、OpenSSL 及下列 Python 验证依赖。

```powershell
$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent (Get-Command javac.exe).Source)
mvn -B -ntp verify
mvn -B -ntp -f ../neuralcloud-harness/java/pom.xml verify
python -m pip install "jsonschema>=4.17,<5" "websockets>=11,<16"
python -u tests/acceptance.py --harness ../neuralcloud-harness
```

回放启动两个真实 JVM，通过 `POST /api/nodes/chat-worker/messages` 向 Harness 发送会议发言和问题。测试工具在回环地址提供模型、OneBot 和提醒执行器，并生成隔离凭据。`target/acceptance/<批次>/` 保存逐项结果、模型请求、审计和数据库。默认回放结束时关闭本次进程，断言失败以非零状态退出。追加 `--serve-demo` 可在验收后保留采用固定模型的回归控制台，打开 `demo.json` 中的 `chat_url` 可在 `meeting-demo` 会话继续查问。按 Ctrl+C 或在批次目录创建名为 `STOP` 的空文件即可结束。

## 用真实 Gemini 验收会议改期

构建两个 JAR 并安装上述验证依赖后，在启动环境中设置 `GEMINI_API_KEY`。Chat Worker 使用 Harness 的 `chat-worker-gemini.properties` 选择官方模型与代理。运行以下命令会将本次合成会话发给 Gemini，在独立会话中按用户的原问题复盘两次通知，再核对原安排、改期、提醒回执、原文编辑和撤回。提醒执行器使用本地测试清单。

```powershell
python -u tests/gemini_meeting.py --harness ../neuralcloud-harness --serve
```

`gemini-case.json` 保存每轮真实回答、规划调用的 token 计数、记忆版本和持久回执。`meeting-audit.json` 保存正式入口的审计链。`--serve` 在验收通过后保留控制台，打开同批次 `demo.json` 的 `chat_url` 可继续这条会议会话，停止文件位置见 `stop_file`。要沿用已停止实例的端口、记忆库和审计目录，可追加 `--resume-demo <上个批次的 demo.json>`。[真实 Gemini 验收记录](docs/acceptance/gemini-meeting.md)

## 单独启动两个服务

在两个进程的环境中设置同一个 `NC_MEMORY_TOKEN`，取值为 32 至 256 个可见 ASCII 字符。在本仓库创建 `memory.properties`，其中 `dataDirectory` 相对于此配置文件解析，每个角色使用独立目录。

```properties
port=18792
dataDirectory=./memory-data
characterId=misty-dev
sourceInstance=neuralcloud-java
accessTokenEnv=NC_MEMORY_TOKEN
```

```powershell
java -jar target/neuralcloud-memory-2.0.0-SNAPSHOT.jar --config memory.properties
```

为 Harness 创建客户端配置，在已有控制台配置中将 `memoryConfig` 指向该文件的绝对路径。Windows 的 properties 路径可使用正斜杠。

```properties
endpoint=http://127.0.0.1:18792/
characterId=misty-dev
accessTokenEnv=NC_MEMORY_TOKEN
apiVersion=2
recallMode=scoped
```

在 Harness 仓库启动控制台。`console.properties` 同时指定实际使用的模型配置与审计目录。握手成功后，`memory_ready` 审计事件给出角色、协议和流标识。

```powershell
java -cp java/target/neuralcloud-control-plane-0.1.0-SNAPSHOT.jar neuralcloud.controlplane.app.ConsoleMain --config console.properties
```

## 阶段 2 的跨会话授权

原作者在 OpenQQ 私聊中授予或撤销自己发言的跨会话使用权。Memory 核对已认证连接绑定的机器人账号、私聊发件人和原消息的渠道来源。授权精确到一条原文的一个修订和一个目标会话。

- Memory 配置增加 `grantAccountId` 和 `grantAccessTokenEnv`。后者指定独立的渠道凭据环境变量，值为 32 至 256 位可见 ASCII 字符。Harness 的 v2 scoped 记忆配置增加同一个 `grantAccessTokenEnv`，OpenQQ 配置的 `accountId` 与 Memory 对应。需要处理群消息时，在 OpenQQ 配置增加 `allowedGroupId`，群内 @ 机器人的纯文本消息才进入会话。
- 原作者私聊发送 `/memory grant <record_id>@<revision> <目标会话> READ|CITE <理由>`。例如目标私聊的标识为 `c2c:onebot:<机器人号>:<接收者号>`。渠道返回授权 ID，原作者可发 `/memory revoke <grant_id> <理由>` 撤销。模型和网页转录均无法代替渠道身份。
- `READ` 允许专用读取方请求原文窗口。面向用户的三个 Worker 均以 `CITE` 读取。省略 `use` 的旧客户端沿用首版可见范围。修订、撤回或撤权后，Worker 再次查询所获正文及派生项时按当前受众复核。授权决定和记录修订使用同一个变更流。[请求与回执示例](contracts/v2/examples/visibility-grant.json)

构建两个仓库后，使用下列命令复跑。第一条覆盖模型边界、进程中断与行动计数，第二条在设置 `GEMINI_API_KEY` 的环境中经真实 Gemini 验证授权前、授权后和撤销后的渠道回复。两次运行各使用隔离的 OneBot 测试渠道和数据目录。[阶段 2 验收报告](docs/acceptance/memory-v2-stage-2.md)

```powershell
python -u tests/stage2.py --harness ../neuralcloud-harness
python -u tests/stage2.py --harness ../neuralcloud-harness --gemini
```

## 接口与证据

八个首版接口使用同一 Bearer 凭据。写入以 `(source_instance, event_id)` 幂等。事项的显式替代关系、生效时间和修订历史用于区分原安排与现安排。更正或撤回在同一事务中使派生内容失效。`stream_id` 与 `read_seq` 随每次读取交付给 Harness，供下轮刷新和动作前复核使用。[v2 OpenAPI](contracts/v2/openapi.json) · [会议请求与响应](contracts/v2/examples/meeting.json) · [v1 对照样本](contracts/v2/examples/v1-replay.json)


## 阶段 3、4 的配置与回放

Memory 默认使用 `retrievalMode=CELLS`，后台整理间隔默认为 30 秒。在配有单一 llama.cpp Master 的 Harness 客户端配置中设置 `hotIndexTokens=96`，可启用按实际分词计数的常驻人物、事项和期限目录。通过 `POST /v2/cards` 读取 L0 或 L1，再用带版本的来源引用读取 L2。原作者的跨会话引用授权同样约束目录与摘要。[字段和生效时间规则](docs/proposals/neuralcloud-memory-v2-architecture-and-data-contracts.md)

首次复跑时，在忽略目录构建已验证的阶段 1 历史版本。两个工作树可在后续回放复用。

```powershell
git worktree add --detach target/stage34-baseline/memory 711768ccc3da2243139ea0a72d45d894e247ff43
git -C ../neuralcloud-harness worktree add --detach ../neuralcloud-memory/target/stage34-baseline/harness 94bffbcccb30745e4d3b4839b61b03cf8403e531
mvn -B -ntp -f target/stage34-baseline/memory/pom.xml verify
mvn -B -ntp -f target/stage34-baseline/harness/java/pom.xml verify
python -X utf8 -u tests/stage3.py --chat --gate
python -X utf8 -u tests/stage4.py
python -X utf8 -u tests/gemini_meeting.py --max-output-tokens 4096
```

P3 在两个阶段使用相同语料和本地模型边界测量首字延迟。P4 的正式 Master 回放使用本机 `127.0.0.1:18081` 的 Qwen；真实会议回放使用已配置的 Gemini。结果、逐题来源和本轮配置决策见[阶段三、四验收报告](docs/acceptance/memory-v2-stage-3-4.md)。
