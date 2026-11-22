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

回放启动两个真实 JVM，通过 `POST /api/nodes/chat-worker/messages` 向 Harness 发送会议发言和问题。测试工具在回环地址提供模型、OneBot 和提醒执行器，并生成隔离凭据。`target/acceptance/<批次>/` 保存逐项结果、模型请求、审计和数据库。默认回放结束时关闭本次进程，断言失败以非零状态退出。追加 `--serve-demo` 可在验收后保留两个服务，使用 `demo.json` 中的地址和 `meeting-demo` 会话继续查问。按 Ctrl+C 或在批次目录创建名为 `STOP` 的空文件即可结束。

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

## 接口与证据

八个首版接口使用同一 Bearer 凭据。写入以 `(source_instance, event_id)` 幂等。事项的显式替代关系、生效时间和修订历史用于区分原安排与现安排。更正或撤回在同一事务中使派生内容失效。`stream_id` 与 `read_seq` 随每次读取交付给 Harness，供下轮刷新和动作前复核使用。[v2 OpenAPI](contracts/v2/openapi.json) · [会议请求与响应](contracts/v2/examples/meeting.json) · [v1 对照样本](contracts/v2/examples/v1-replay.json)
