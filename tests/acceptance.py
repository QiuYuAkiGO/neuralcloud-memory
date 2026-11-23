"""阶段 0/1 契约与双 JVM 端到端验收回放脚本。

在隔离的本地回环环境中拉起真实的 Memory 与 Harness 两个 Java 进程，配合可控模型挡板、
CONNECT TLS 代理、故障注入中继（Relay）、OneBot 渠道与提醒执行器，逐项验证：
OpenAPI 校验、v1 黄金回放样本、握手拒绝、会议改期与双时态查询、工具尝试与回执、
Top-K 前置受众过滤、更正乐观锁与派生失效、QQ 本人历史/称呼、重启续读、登记竞态及断链回执对账。
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import hashlib
import os
import secrets
import platform
import socket
import socketserver
import sqlite3
import ssl
import subprocess
import threading
import tempfile
import time
from xml.etree import ElementTree
from zipfile import ZipFile
from jsonschema import Draft202012Validator
from onebot import OneBot
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, build_opener, ProxyHandler

# 禁用系统环境代理，确保所有回环 HTTP 调用直连 127.0.0.1。
ROOT = Path(__file__).resolve().parents[1]
OPENER = build_opener(ProxyHandler({}))
HIDDEN = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def encode(value):
    """将对象编码为保留中文字符的 UTF-8 JSON 字节串。"""
    return json.dumps(value, ensure_ascii=False).encode("utf-8")


def dump(path, value):
    """以格式化 UTF-8 JSON 写出证据或验收结果文件。"""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def send(url, body=None, token=None, expected=200, timeout=10):
    """发送 GET/POST JSON 请求，断言响应状态码等于 expected 并返回解析后的 JSON。"""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = Request(url, data=None if body is None else encode(body), headers=headers)
    try:
        response = OPENER.open(req, timeout=timeout)
    except HTTPError as exc:
        response = exc
    with response:
        raw = response.read()
        data = json.loads(raw)
        assert response.status == expected, (url, expected, response.status, data)
        return data


def free_port():
    """向操作系统申请一个临时空闲回环 TCP 端口。"""
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def props(path, config):
    """写出 Java `.properties` 配置文件并返回 POSIX 风格路径（兼容 Windows 下的配置引用）。"""
    path.write_text("".join(f"{k}={v}\n" for k, v in config.items()), encoding="utf-8")
    return path.as_posix()


def wait_ready(proc, probe, log):
    """轮询探针直到子进程就绪；若子进程提前退出则直接抛错并附带进程日志。"""
    for _ in range(120):
        if proc.poll() is not None:
            raise RuntimeError(log.read_text(encoding="utf-8", errors="replace"))
        try:
            return probe()
        except (URLError, OSError):
            time.sleep(0.05)
    raise TimeoutError(str(log))


def stop(proc):
    """优雅终止仍在运行的子进程并等待退出。"""
    if proc and proc.poll() is None:
        proc.terminate()
        proc.wait(timeout=15)


def apply_reminder(database, command):
    """可控外部提醒执行器：按 action_id 幂等写入提醒表，落盘提交后再返回外部回执。"""
    with sqlite3.connect(database) as db:
        db.execute("CREATE TABLE IF NOT EXISTS reminders(action TEXT PRIMARY KEY, title TEXT, scheduled_for TEXT)")
        db.execute("INSERT OR IGNORE INTO reminders VALUES(?,?,?)",
                   (command["action_id"], command["title"], command["scheduled_for"]))
        title, when = db.execute("SELECT title,scheduled_for FROM reminders WHERE action=?",
                                 (command["action_id"],)).fetchone()
    return {"action_id":command["action_id"], "status":"APPLIED", "title":title, "scheduled_for":when}


class Model(BaseHTTPRequestHandler):
    """确定性模型与工具边界挡板：仅根据本次实际交付的证据材料挑选答复，绝不读取标准答案。"""
    def log_message(self, *args):
        pass

    def reply(self, value):
        raw = encode(value)
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/v2/health":
            self.reply(self.server.handshake)
        else:
            self.reply({})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/reminders":
            self.reply(apply_reminder(self.server.reminders, body))
            return
        self.server.calls.append(body)
        if hasattr(self.server, "model_response"):
            result = self.server.model_response(body)
            if result is not None:
                self.reply(result)
            return
        if "contents" not in body:
            self.reply({"error": "unexpected model route"})
            return
        parts = body["contents"][-1]["parts"]
        question = parts[0]["text"]
        if "responseSchema" in body["generationConfig"]:
            query = ""
            if "？" in question:
                query = "提醒" if "提醒" in question else "例会"
            kind = "HISTORY" if query else "REPLY"
            if "本人称呼" in question:
                kind, query = "SELF_IDENTITY", ""
            elif "本人说过" in question:
                kind, query = "SELF_HISTORY", "白鹭航线"
            text = json.dumps({"kind":kind,"query":query}, ensure_ascii=False)
        elif len(parts) > 1:
            material = parts[1]["text"].splitlines()[1:]
            if "提醒" in question:
                candidates = [line for line in material if "的工具执行回执（" in line]
                text = candidates[0] if candidates else "尚无匹配的工具执行回执，提醒状态未知。"
            elif "本人称呼" in question or "本人说过" in question:
                text = " ".join(material)
            else:
                tag = "历史安排" if "原" in question else "当前安排"
                matches = [line for line in material if "的原话，" + tag in line]
                text = matches[0] if matches else "当前材料没有相应安排。"
        else:
            text = "收到。"
        self.reply({"candidates": [{"finishReason": "STOP", "content": {"parts": [{"text": text}]}}]})


class Proxy(socketserver.StreamRequestHandler):
    """终止测试用 Gemini HTTPS CONNECT 隧道，并使用仅当前测试信任的自签证书接入本地 Model 挡板。"""
    def handle(self):
        request = self.rfile.readline()
        if not request.startswith(b"CONNECT model.test:443 "):
            return
        while self.rfile.readline().strip():
            pass
        self.wfile.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        self.wfile.flush()
        with self.server.tls.wrap_socket(self.connection, server_side=True) as conn:
            Model(conn, self.client_address, self.server)


class LocalProxy(socketserver.ThreadingTCPServer):
    daemon_threads = True


def model_boundaries(out):
    """为单次测试动态签发仅信任 model.test 的临时证书与 TrustStore，并启动本地 CONNECT 代理和 HTTP 挡板。"""
    key, crt, trust = [out / n for n in ("fixture.key", "fixture.crt", "fixture.p12")]
    conf = out / "openssl.cnf"
    conf.write_text("[req]\ndistinguished_name=dn\n[dn]\nCN=model.test\n", encoding="ascii")
    subprocess.run(["openssl", "req", "-config", str(conf), "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
                    "-out", str(crt), "-days", "2", "-subj", "/CN=model.test", "-addext", "subjectAltName=DNS:model.test"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=HIDDEN)
    subprocess.run(["keytool", "-importcert", "-noprompt", "-alias", "fixture", "-file", str(crt), "-keystore",
                    str(trust), "-storepass", "fixture-only"], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, creationflags=HIDDEN)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(crt, key)
    proxy = LocalProxy(("127.0.0.1", 0), Proxy)
    proxy.tls = tls
    proxy.calls = []
    http = ThreadingHTTPServer(("127.0.0.1", 0), Model)
    http.calls = proxy.calls
    http.reminders = out / "fixture-reminders.sqlite"
    for s in (proxy, http):
        threading.Thread(target=s.serve_forever, daemon=True).start()
    return proxy, http, trust


class Relay(BaseHTTPRequestHandler):
    """位于 Harness 与真实 Memory 之间的故障注入 HTTP 中继：用于在真实写入/查询完成后人为暂停或丢弃回执，
    分别构造“更正先于读取登记”的竞态和“写已落盘但回执丢失”的重启对账恢复场景。
    """
    def log_message(self, *args):
        pass

    def forward(self):
        body = self.rfile.read(int(self.headers["Content-Length"])) if self.command == "POST" else None
        request = Request(self.server.upstream + self.path, data=body,
                          headers={"Authorization": self.headers["Authorization"], "Content-Type": "application/json"})
        try:
            response = OPENER.open(request, timeout=4)
        except HTTPError as error:
            response = error
        with response:
            status, raw = response.status, response.read()
        if self.path == "/v2/recall" and self.server.pause_next:
            self.server.pause_next = False
            self.server.old_snapshot = json.loads(raw)
            self.server.snapshot_taken.set()
            self.server.release.wait(4)
        if self.path == "/v2/ingest" and self.server.lose_delivery and json.loads(body)["kind"] == "self_expression":
            self.server.lose_delivery = False
            self.server.lost = (json.loads(body), json.loads(raw))
            status, raw = 503, encode({"code": "FIXTURE_RESPONSE_LOST"})
            self.server.response_lost.set()
        if self.path.startswith("/v2/receipts?"):
            self.server.reconciled.append((self.path, json.loads(raw)))
        self.send_response(status)
        self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    do_GET = do_POST = forward


class ServicePair:
    """拉起真实的 Memory 与 Harness 两个正式 JVM 入口，管理隔离证据目录、OpenAPI 契约校验与审计查询。"""
    def __init__(self, harness, *, ports=None, ledger=None, audit_dir=None):
        self.out = ROOT / "target" / "acceptance" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        try:
            self.out.mkdir(parents=True)
        except FileExistsError:
            self.out = Path(tempfile.mkdtemp(prefix=self.out.name+"-", dir=self.out.parent))
        self.harness = harness.resolve()
        def artifact(project):
            pom = ElementTree.parse(project / "pom.xml").getroot()
            return project / "target" / (pom.findtext("{*}artifactId")+"-"+pom.findtext("{*}version")+".jar")
        self.memjar = artifact(ROOT)
        self.hjar = artifact(self.harness / "java")
        self.jar_hashes={j.name:hashlib.sha256(j.read_bytes()).hexdigest() for j in (self.memjar,self.hjar)}
        self.native_args = []
        if os.name == "nt" and platform.machine().lower() in ("amd64", "x86_64"):
            # Windows 下预先从 JAR 解压同一份未修改的 sqlitejdbc.dll 到隔离目录，避免两个 JVM 并发解压临时 DLL 冲突。
            location = self.out / "native"
            location.mkdir()
            with ZipFile(self.memjar) as jar:
                (location / "sqlitejdbc.dll").write_bytes(jar.read("org/sqlite/native/Windows/x86_64/sqlitejdbc.dll"))
            self.native_args.append("-Dorg.sqlite.lib.path=" + str(location))
        self.token = secrets.token_urlsafe(36)
        self.env = dict(os.environ, NC_ACCEPTANCE_TOKEN=self.token)
        self.harness_env = None
        self.harness_vm_args = []
        self.port, self.console_port = ports or (free_port(), free_port())
        self.base = f"http://127.0.0.1:{self.port}/v2/"
        self.console = f"http://127.0.0.1:{self.console_port}/api/"
        self.ledger = ledger or self.out / "ledger"
        self.audit_dir = audit_dir or self.out / "audit"
        self.console_config = self.out / "console.properties"
        self.spec = json.loads((ROOT/"contracts/v2/openapi.json").read_text(encoding="utf-8"))
        self.contract_calls = set()
        self.mproc = self.hproc = None
        self.pids, self.results = [], []
        props(self.out / "memory.properties", dict(port=self.port, dataDirectory=self.ledger.as_posix(), characterId="misty-dev",
                                                  sourceInstance="neuralcloud-java", accessTokenEnv="NC_ACCEPTANCE_TOKEN"))
        self.client_config = dict(endpoint=self.base[:-3], characterId="misty-dev", accessTokenEnv="NC_ACCEPTANCE_TOKEN",
                                  recallMode="scoped", apiVersion=2)
        self.client = self.out / "client.properties"
        props(self.client, self.client_config)

    def launch(self, name, args, env=None):
        """以隔离日志与独立 SQLite 临时目录启动 Java 子进程；Memory 启用 stdin 管道以配合 --stop-on-eof 优雅退出。"""
        log = self.out / (name + "-" + str(len(self.pids)) + ".log")
        native_tmp = self.out / (log.stem + "-native")
        native_tmp.mkdir()
        with log.open("wb") as stream:
            p = subprocess.Popen(["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                                  *self.native_args, "-Dorg.sqlite.tmpdir="+str(native_tmp), *args], stdout=stream, stderr=subprocess.STDOUT,
                                 stdin=subprocess.PIPE if name=="memory" else subprocess.DEVNULL,
                                 env=env or self.env, cwd=self.out, creationflags=HIDDEN)
        self.pids.append({"service": name, "pid": p.pid, "log": log.name})
        return p, log

    def memory(self):
        """启动真实 Memory v2 JVM 并轮询 `/v2/health` 直至 TEXT_READY。"""
        self.mproc, log = self.launch("memory", ["-jar", str(self.memjar), "--config", "memory.properties", "--stop-on-eof"])
        return wait_ready(self.mproc, lambda: self.api("health"), log)

    def harness_start(self, failure=None, **settings):
        """写入客户端覆盖配置并启动真实 Harness ConsoleMain；若传入 failure 则断言启动因预期原因失败退出。"""
        props(self.client, dict(self.client_config, **settings))
        args = [*self.harness_vm_args,
                "-cp", str(self.hjar), "neuralcloud.controlplane.app.ConsoleMain", "--config", str(self.console_config)]
        self.hproc, log = self.launch("harness", args, self.harness_env)
        if failure:
            assert self.hproc.wait(timeout=12) != 0
            assert failure in log.read_text(encoding="utf-8",errors="replace"), log
            return
        wait_ready(self.hproc, lambda: send(self.console+"nodes"), log)

    def api(self, path, body=None, expected=200):
        """直接调用 Memory `/v2/` 接口，并用 contracts/v2/openapi.json 对请求体和响应体做 JSON Schema 契约校验。"""
        route = "/v2/" + path.split("?")[0]
        op = self.spec["paths"][route]["get" if body is None else "post"]
        def validate(schema, value):
            Draft202012Validator(dict(schema,components=self.spec["components"])).validate(value)
        if body is not None and expected == 200:
            validate(op["requestBody"]["content"]["application/json"]["schema"], body)
        result = send(self.base+path, body, self.token, expected)
        validate(op["responses"][str(expected)]["content"]["application/json"]["schema"],result)
        self.contract_calls.add(route)
        return result

    def msg(self, chain, text, origin=None, *, timeout=10):
        """通过 Harness 正式控制台入口向 Chat Worker 发消息，并轮询审计链直到出站回复也取得 memory_durable 持久回执。"""
        body = dict(chain=chain,text=text)
        if origin is not None:
            body["observation"] = origin
        result = send(self.console+"nodes/chat-worker/messages", body, timeout=timeout)
        assert result["type"] == "chat_worker_reply"
        for _ in range(100):
            audit = self.audit(chain)
            deliveries = [e for e in audit if e["type"]=="console_response_written" and e["causedBy"]==result["eventId"]
                          and e["runId"]==audit[-1]["runId"]]
            if deliveries and any(e["type"]=="memory_durable" and e["eventId"]>deliveries[-1]["eventId"]
                                  and e["runId"]==deliveries[-1]["runId"] for e in audit):
                return result["data"]["text"]
            time.sleep(0.02)
        raise AssertionError("missing outgoing durable receipt")

    def audit(self, chain):
        """读取 Harness 指定会话链路的完整审计事件列表。"""
        return send(self.console+"audit/chains/"+chain)["events"]

    def check(self, name, **detail):
        """登记一项已通过的验收检查点并即时打印 PASS 进度。"""
        self.results.append(dict(check=name, **detail))
        print("PASS", name, flush=True)


class Run(ServicePair):
    """带全套本地挡板（可控模型、CONNECT 代理、OneBot 渠道、故障中继 Relay）的完整验收运行环境。"""
    def __init__(self, harness):
        super().__init__(harness)
        self.proxy, self.http, self.trust = model_boundaries(self.out)
        self.harness_vm_args = ["-Djavax.net.ssl.trustStore="+str(self.trust), "-Djavax.net.ssl.trustStorePassword=fixture-only"]
        self.env["NC_FIXTURE_KEY"] = "fixture-only"
        self.env["NC_FIXTURE_QQ"] = secrets.token_urlsafe(32)
        self.onebot = OneBot(self.env["NC_FIXTURE_QQ"])
        qq_config = props(self.out/"onebot.properties",dict(endpoint=f"ws://127.0.0.1:{self.onebot.port}/",
                          accountId=12345,allowedUserId=67890,accessTokenEnv="NC_FIXTURE_QQ"))
        self.relay = ThreadingHTTPServer(("127.0.0.1",0),Relay)
        self.relay.upstream = self.base[:-4]
        self.relay.pause_next = self.relay.lose_delivery = False
        self.relay.snapshot_taken, self.relay.release, self.relay.response_lost = [threading.Event() for _ in range(3)]
        self.relay.reconciled = []
        threading.Thread(target=self.relay.serve_forever,daemon=True).start()
        self.model_base = f"http://127.0.0.1:{self.http.server_port}"
        model = props(self.out / "worker.properties", dict(endpoint="https://model.test/v1beta/models/fixture:generateContent",
                      apiKeyEnv="NC_FIXTURE_KEY", httpVersion="HTTP_1_1", proxy="127.0.0.1:"+str(self.proxy.server_address[1]),
                      connectTimeoutSeconds=4, requestTimeoutSeconds=8, maxOutputTokens=512, thinkingLevel="LOW",
                      replyPreferences="classpath:/config/chat-worker-misty-reply-preferences.txt"))
        llm = props(self.out / "llm.properties", dict(endpoint=self.model_base+"/v1/chat/completions", model="fixture",
                      httpVersion="HTTP_1_1", enableThinking="false", connectTimeoutSeconds=4, requestTimeoutSeconds=8,
                      probePrompt="ping", probeMaxTokens=8, probeTemperature=0))
        props(self.console_config, dict(listenHost="127.0.0.1", listenPort=self.console_port,
              auditDirectory=(self.out/"audit").as_posix(), memoryConfig=self.client.as_posix(), chatWorkerConfig=model, openQqConfig=qq_config,
              chatWorkerRolePrompt="classpath:/config/chat-worker-misty-prompt.txt", routerConfig=llm,
              routerRolePrompt="classpath:/config/router-role-prompt.txt", routerMetrics=self.model_base+"/metrics",
              routerMetricsFormat="VLLM", masterConfig=llm, masterMetrics=self.model_base+"/metrics", masterMetricsFormat="VLLM",
              masterMaxTokens=64, masterTemperature=0, gpuMetrics="NONE", metricsIntervalSeconds=60, metricsTimeoutSeconds=2))

    def save(self):
        dump(self.out/"model-inputs.json", self.proxy.calls)
        dump(self.out/"results.json", {"processes":self.pids, "jar_sha256":self.jar_hashes, "checks":self.results})

    def close(self):
        stop(self.hproc)
        stop(self.mproc)
        self.relay.release.set()
        self.onebot.close()
        self.relay.shutdown(); self.relay.server_close()
        self.http.shutdown(); self.proxy.shutdown()
        self.http.server_close(); self.proxy.server_close()
        self.save()


def context(scope="console:project", role="ROUTER", qq=None):
    """构造标准 v2 调用上下文（context）。"""
    c = dict(character_id="misty-dev", session_id=scope, conversation_id=scope, actor_id=role, role=role)
    if qq:
        c["qq"] = qq
    return c


def event(key, content, kind="observation", scope="console:project", **fields):
    """构造标准写入/派生命令载荷（默认 ROUTER 入账观察记录）。"""
    return {**dict(context=context(scope), source_instance="neuralcloud-java", event_id=key, kind=kind,
                   occurred_at="2026-11-19T09:00:00+08:00", content=content), **fields}


def ref(rc):
    """从持久回执中提取带版本号的 RecordRef（record_id + revision）。"""
    return {k: rc[k] for k in ("record_id","revision")}


def query(q, scope="console:project", **fields):
    """构造标准 `/v2/recall` 历史检索请求体。"""
    return {**dict(context=context(scope,"MASTER"),kind="HISTORY",query=q,profile="QUICK",limit=4), **fields}


def meeting_source(value, effective, predecessor=None):
    """构造小林在项目群发出的会议排期观察元数据（可选附带指向旧安排的 supersedes_ref）。"""
    assertion=dict(subject_id="weekly-review",predicate="scheduled_for",value=value,effective_at=effective)
    if predecessor:
        assertion["supersedes_ref"]=ref(predecessor)
    return dict(speaker="小林",source_venue="项目群",occurred_at=effective,assertion=assertion)


def replay(run):
    """按序执行阶段 0/1 全量契约与双 JVM 集成验收场景。"""
    # 1. 阶段 0 基线：验证 v1 黄金样本的记录 ID 哈希、Unicode 码点窗口切片、同键幂等与异内容 409 冲突。
    health = run.memory()
    frozen = json.loads((ROOT/"contracts/v2/examples/v1-replay.json").read_text(encoding="utf-8"))
    first = run.api("ingest", frozen["ingest"])
    assert first == frozen["expected_receipt"]
    window = frozen["window"]
    read = run.api("read",dict(context=context("console:golden","MASTER"),targets=[ref(first)],start=window["start"],length=window["length"]))
    assert read["items"][0]["text"] == window["text"]
    assert run.api("ingest", frozen["ingest"]) == first
    assert run.api("ingest", dict(frozen["ingest"],content="changed"),409)["code"]=="EVENT_ID_CONTENT_CONFLICT"
    assert run.api("receipts?"+urlencode(dict(source_instance="neuralcloud-java",event_id=frozen["ingest"]["event_id"])))==first
    assert run.api("health")["head_seq"]==first["seq"]
    run.check("v1-golden-id-receipt-unicode-window-and-idempotence",receipt=first,excerpt=window["text"])

    # 2. 启动握手负例与正例：验证 Harness 拒绝错凭据、错角色、旧协议和缺失能力，通过后登记 stream_id。
    run.harness_start("HTTP 401", accessTokenEnv="NC_FIXTURE_BAD")
    run.harness_start("角色、版本或就绪状态不匹配",characterId="wrong-character")
    proxy_url=run.model_base+"/"
    run.http.handshake=dict(health,api_major=1)
    run.harness_start("角色、版本或就绪状态不匹配",endpoint=proxy_url)
    run.http.handshake=dict(health,capabilities=[])
    run.harness_start("缺少必需能力",endpoint=proxy_url)
    run.check("harness-rejects-credentials-character-protocol-capabilities")
    run.harness_start()
    ready=run.audit("memory-startup")[-1]["data"]
    assert ready["characterId"]==health["character_id"] and ready["streamId"]==health["stream_id"] and ready["apiVersion"]==2
    assert run.hproc.pid!=run.mproc.pid
    run.check("two-independent-jvms-handshake",memory=run.mproc.pid,harness=run.hproc.pid,handshake=ready)

    # 3. 正式会议改期主链路：两条独立观察记录、显式替代触发 Worker 刷新、双时态查询与提醒工具尝试/执行回执。
    chain="project"
    run.msg(chain,"例会定在周五 15 点。",meeting_source("2026-11-20T15:00:00+08:00","2026-11-18T09:00:00+08:00"))
    def observations():
        audit=run.audit(chain)
        announcements={e["eventId"] for e in audit if e["type"]=="operator_message" and "observation" in e["data"]}
        writes={e["eventId"] for e in audit if e["type"]=="router_memory_write" and e["data"]["sourceEventId"] in announcements}
        return [dict(record_id=e["data"]["recordId"],revision=e["data"]["revision"],seq=e["data"]["sequence"])
                for e in audit if e["type"]=="memory_durable" and e["causedBy"] in writes]
    old=observations()[0]
    prior=run.msg(chain,"例会现在是什么时候？")
    assert "当前安排" in prior and "周五 15 点" in prior
    first_changed=len(run.proxy.calls)
    run.msg(chain,"例会改到周六 10 点。",meeting_source("2026-11-21T10:00:00+08:00","2026-11-19T10:00:00+08:00",old))
    new=observations()[1]
    transition=json.dumps(run.proxy.calls[first_changed]["contents"],ensure_ascii=False)
    assert "周五 15 点" in transition and "历史安排" in transition and "周六 10 点" in transition and "当前安排" in transition
    run.check("supersession-refreshes-worker-that-only-saw-original",first_model_request=first_changed)
    assert old["record_id"]!=new["record_id"] and old["seq"]<new["seq"] and old["revision"]==new["revision"]==1
    original=run.msg(chain,"例会原来是什么时候？")
    current=run.msg(chain,"例会现在是什么时候？")
    assert all(x in original for x in ("周五 15 点","小林","项目群","历史安排"))
    assert all(x in current for x in ("周六 10 点","小林","项目群","当前安排"))
    assert "周五" not in current and "周六" not in original
    run.check("formal-conversation-distinct-observations-and-source-attribution",old=old,new=new,original=original,current=current)
    def at(**params):
        return run.api("recall",query("例会",subject_id="weekly-review",**params))["items"]
    assert [x["ref"] for x in at(as_of_valid="2026-11-18T12:00:00+08:00")]==[ref(old)]
    assert [x["ref"] for x in at(as_of_known_seq=old["seq"],as_of_valid="2026-11-20T12:00:00+08:00")]==[ref(old)]
    assert [x["ref"] for x in at(as_of_known_seq=new["seq"],as_of_valid="2026-11-20T12:00:00+08:00")]==[ref(new)]
    lookup=query("例会",subject_id="weekly-review")
    latest=run.api("recall",lookup)
    with sqlite3.connect(run.out/"ledger/memory.sqlite") as db:
        commands=[json.loads(db.execute("SELECT command FROM receipts WHERE record_id=?",(r["record_id"],)).fetchone()[0]) for r in (old,new)]
    dump(run.out/"meeting-v2.json",dict(ingests=commands,receipts=[dict(old,status="DURABLE"),dict(new,status="DURABLE")],
                                       recall_request=lookup,recall_response=latest))
    run.check("business-time-and-known-sequence")
    run.api("ingest", event("reminder-attempt","已请求更新提醒",kind="tool_attempt",action_id="rem-1"))
    run.api("ingest", event("different-action","另一项工具已完成",kind="tool_result",action_id="rem-2"))
    assert run.api("recall",query("提醒",action_id="rem-1"))["assessment"]["state"]=="INCOMPLETE"
    unknown=run.msg(chain,"提醒有没有更新？")
    attempt_input=run.proxy.calls[-1]["contents"][-1]["parts"][1]["text"]
    assert "未知" in unknown and "工具尝试" in attempt_input and "rem-1" in attempt_input
    attempt=dict(action_id="rem-1",title="例会",scheduled_for=latest["items"][0]["assertion"]["value"])
    external=send(run.model_base+"/reminders",attempt)
    assert external["status"]=="APPLIED" and external["scheduled_for"]==attempt["scheduled_for"]
    tool=run.api("ingest",event("reminder-done","提醒清单已更新为 "+external["scheduled_for"],kind="tool_result",action_id=external["action_id"]))
    done=run.msg(chain,"提醒更新结果是什么？")
    assert "工具执行回执" in done and "清单已更新" in done
    assert any(x["ref"]==ref(tool) for x in run.api("recall",query("提醒",action_id="rem-1"))["items"])
    run.check("tool-attempt-and-matching-result",before=unknown,after=done,receipt=tool,executor_receipt=external)

    # 4. 前置受众过滤验证：即使其他会话存在大量高相关性诱饵文本，也不能在 Top-K 之前挤掉本会话合法命中。
    unique=run.api("ingest",event("needle","琥珀航线发布"))
    for i in range(65):
        run.api("ingest",event("noise-"+str(i),"琥珀航线发布 "*6+"例会 私聊哨兵",scope="console:private"))
    found=run.api("recall",query("琥珀航线发布",limit=1))
    assert [x["ref"] for x in found["items"]]==[ref(unique)]
    other=run.api("read",dict(context=context("console:private","MASTER"),targets=[ref(unique)]))
    assert other["items"][0]["status"]=="NOT_FOUND" and "text" not in other["items"][0]
    run.check("prefilter-before-top-k-and-scoped-read",out_of_scope_decoys=65)

    # 5. 更正乐观锁（CAS）、晚到派生拒绝、已发布摘要级联失效与下一轮首个模型输入自动刷新及撤回隐藏。
    def correction(rc,key,operation="REPLACE",**extra):
        return dict(context=context(),source_instance="neuralcloud-java",event_id=key,record_id=rc["record_id"],
                    expected_revision=rc["revision"],operation=operation,reason="渠道编辑",**extra)
    candidate=event("candidate-1","会议安排摘要",kind="summary",source_refs=[ref(new)],author="MASTER")
    summary=run.api("candidates",candidate)
    change=correction(new,"platform-edit",content="例会改到周日 14 点。",assertion=meeting_source(
        "2026-11-22T14:00:00+08:00","2026-11-19T10:00:00+08:00",old)["assertion"])
    revised=run.api("corrections",change)
    assert revised["record_id"]==new["record_id"] and revised["revision"]==2
    assert run.api("corrections",change)==revised
    assert run.api("corrections",dict(change,event_id="stale-cas"),409)["code"]=="REVISION_CONFLICT"
    assert run.api("candidates",dict(candidate,event_id="late-candidate"),409)["code"]=="SOURCE_CHANGED"
    invalid=run.api("read",dict(context=context(),targets=[ref(summary)]))["items"][0]
    assert invalid["status"]=="SOURCE_CHANGED" and invalid["ref"]==ref(summary) and "text" not in invalid
    run.check("correction-cas-late-derivation-and-published-summary-invalidation",new_revision=revised,invalid=invalid)

    before=len(run.proxy.calls)
    fixed=run.msg(chain,"例会现在是什么时候？")
    initial=run.proxy.calls[before]["contents"]
    assert "周日 14 点" in json.dumps(initial,ensure_ascii=False) and "记忆更正" in json.dumps(initial,ensure_ascii=False)
    assert "周六 10 点" not in json.dumps(initial,ensure_ascii=False)
    assert "周日 14 点" in fixed
    trace=run.audit(chain)
    captured=[e for e in trace if e["type"]=="memory_recall" and e["data"].get("bindings")]
    assert any(b["item"].get("ref")==ref(revised) for b in captured[-1]["data"]["bindings"])
    run.check("registered-version-and-next-turn-first-input",refreshed=fixed,first_model_request=before,read_seq=captured[-1]["data"]["read_seq"])
    rr=run.api("corrections",correction(revised,"withdraw",operation="RETRACT"))
    assert run.api("read",dict(context=context(),targets=[ref(new)]))["items"][0]["status"]=="RETRACTED"
    run.check("withdrawn-source-excludes-historical-body",receipt=rr)

    # 6. QQ 渠道专有边界：本人历史（SELF_HISTORY）核对原发件人并排除第三方群发言，本人称呼（SELF_IDENTITY）仅返审核名称。
    qq="c2c:onebot:12345:67890"; group="group:onebot:12345:23456"
    acc=dict(account_id="12345",peer_id="67890")
    channel=dict(platform="qq",type="qq_group",account_id="12345",sender_id="67890",conversation_id=group,message_id="m-1")
    authored=run.api("ingest",event("authored","白鹭航线由我负责",scope=group,channel=channel))
    run.api("ingest",event("third-party","白鹭航线另一人的发言",scope=group,channel=dict(channel,sender_id="78901",message_id="m-2")))
    run.api("ingest",event("self-name","录入已审核的身份",scope=group,context=context(group,"ORCHESTRATOR"),
                           identity=dict(account_id="67890",name="小林")))
    own=query("白鹭航线",scope=qq,kind="SELF_HISTORY",before="2099-01-01T00:00:00Z")
    own["context"]=context(qq,"CHAT_WORKER",acc)
    assert [x["ref"] for x in run.api("recall",own)["items"]]==[ref(authored)]
    assert run.api("recall",dict(own,kind="SELF_IDENTITY",query=""))["items"][0]["text"]=="小林"
    nickname=run.onebot.ask("现在确认的本人称呼是什么？",71001)
    assert "小林" in nickname and "录入已审核" not in nickname
    personal=run.onebot.ask("我本人说过白鹭航线的什么事？",71002)
    assert "白鹭航线由我负责" in personal and "另一人的发言" not in personal
    for _ in range(100):
        private=run.audit("qq-private-12345-67890")
        if sum(e["type"]=="memory_durable" and e["data"]["kind"]=="SELF_EXPRESSION" for e in private)==2: break
        time.sleep(.02)
    else: raise AssertionError("missing confirmed OneBot deliveries")
    actual=[e["data"]["kind"] for e in private if e["type"]=="memory_call_result"]
    assert actual==["SELF_IDENTITY","SELF_HISTORY"]
    dump(run.out/"onebot-audit.json",private)
    run.check("qq-authored-history-and-confirmed-identity",nickname=nickname,history=personal,confirmed_deliveries=2)

    # 7. 变更流分页、双服务独立重启延续流身份与消费绑定，以及 recent/scoped 召回模式计数对照。
    cursor=0; through=run.api("health")["head_seq"]; seq=[]
    while cursor<through:
        page=run.api("changes?"+urlencode(dict(stream_id=health["stream_id"],after=cursor,through=through,limit=9)))
        seq.extend(x["seq"] for x in page["items"]); cursor=page["next_after"]
    assert seq==sorted(set(seq)) and seq[-1]==through
    run.mproc.stdin.close()
    assert run.mproc.wait(timeout=8)==0
    health2=run.memory()
    assert health2["head_seq"]==through and health2["stream_id"]==health["stream_id"]
    stop(run.hproc); run.harness_start()
    assert run.audit("memory-startup")[-1]["data"]["streamId"]==health["stream_id"]
    before=len(run.proxy.calls)
    run.msg(chain,"继续。")
    assert "失效" in json.dumps(run.proxy.calls[before]["contents"],ensure_ascii=False)
    with sqlite3.connect(run.out/"audit/memory-v2-misty-dev.sqlite") as db:
        assert db.execute("SELECT count(*) FROM inputs WHERE status='DELIVERED'").fetchone()[0]>0
    run.check("restart-both-services-paged-changes-and-durable-worker-binding",stream_id=health2["stream_id"],head_seq=through)
    stop(run.hproc); run.harness_start(recallMode="recent")
    run.msg(chain,"例会原来是什么时候？")
    last=[e["data"] for e in run.audit(chain) if e["type"]=="memory_call_result"][-1]
    assert last["count"]==0 and "近期对话" in last["sentences"][0]
    run.check("recent-replay-counts-and-scoped-mode")

    ambiguous_scope="console:ambiguous"
    for i,value in enumerate(["2026-11-20T14:00:00Z","2026-11-20T15:00:00Z"]):
        run.api("ingest",event("alternative-"+str(i),"安排可能在 "+value,scope=ambiguous_scope,
                assertion=dict(subject_id="retrospective",predicate="scheduled_for",value=value,effective_at="2026-11-17T00:00:00Z")))
    conflict=run.api("recall",query("安排",scope=ambiguous_scope,subject_id="retrospective"))
    assert len(conflict["items"])==2 and conflict["assessment"]==dict(state="CONFLICT",sufficient=False,absence_confirmed=False)
    empty=run.api("recall",query("nonexistent2026paperid",scope=ambiguous_scope))
    assert empty["items"]==[] and empty["assessment"]==dict(state="NOT_FOUND",sufficient=False,absence_confirmed=False)
    run.check("conflicting-assertions-and-unconfirmed-absence")

    forbidden=dict(candidate,event_id="denied-write",context=context(role="CHAT_WORKER"))
    assert run.api("candidates",forbidden,403)["code"]=="FORBIDDEN"
    late_multi=dict(candidate,event_id="late-multi",source_refs=[ref(unique),ref(new)])
    assert run.api("candidates",late_multi,409)["code"]=="SOURCE_CHANGED"
    assert run.api("receipts?source_instance=neuralcloud-java&event_id=late-multi")["status"]=="NOT_FOUND"
    run.check("multi-source-publication-is-atomic-and-worker-is-read-only")
    dump(run.out/"meeting-audit.json",run.audit(chain))

    # 8. 故障注入中继场景：构造“更正已被消费，旧快照才完成登记”的并发竞态，以及“写入已提交但响应丢失”的重启对账恢复。
    stop(run.hproc)
    relay_url = f"http://127.0.0.1:{run.relay.server_port}/"
    run.harness_start(endpoint=relay_url)
    scope = "console:race"
    subject = run.api("ingest",event("race-source","例会地点在黄鹤厅。",scope=scope))
    run.msg("race","例会的地点？")
    run.relay.pause_next=True
    with ThreadPoolExecutor(max_workers=1) as pool:
        late=pool.submit(run.msg,"race","例会的地点？")
        try:
            assert run.relay.snapshot_taken.wait(3), "recall did not reach the response gate"
            snapshot=run.relay.old_snapshot
            assert any(row["ref"]==ref(subject) for row in snapshot["items"])
            edited=run.api("corrections",dict(correction(subject,"race-edit",content="例会地点改在白鹭厅。"),context=context(scope)))
            # 在旧 HTTP 召回响应尚未登记前，同一 Worker 先消费了更正；后续补查机制必须确保旧版不会覆盖新版。
            run.msg("race","继续登记。")
            with sqlite3.connect(run.out/"audit/memory-v2-misty-dev.sqlite") as db:
                assert db.execute("SELECT max(seq) FROM watches WHERE scope=? AND id=?",(scope,subject["record_id"])).fetchone()[0]>=edited["seq"]
        finally:
            run.relay.release.set()
        late.result(timeout=5)
    cursor=len(run.proxy.calls)
    run.msg("race","继续。")
    first_input=json.dumps(run.proxy.calls[cursor]["contents"],ensure_ascii=False)
    assert "白鹭厅" in first_input and "黄鹤厅" not in first_input
    with sqlite3.connect(run.out/"audit/memory-v2-misty-dev.sqlite") as db:
        versions=dict(db.execute("SELECT seq,json_extract(item,'$.ref.revision') FROM input_evidence WHERE id=?",(subject["record_id"],)))
    assert versions[snapshot["read_seq"]]==1 and 2 in versions.values() and snapshot["read_seq"]<edited["seq"]
    run.check("consumed-correction-before-late-registration",snapshot=snapshot["read_seq"],edit=edited["seq"],first_model_request=cursor,versions=versions)
    dump(run.out/"registration-race-audit.json",run.audit("race"))

    run.relay.lose_delivery=True
    reply=send(run.console+"nodes/chat-worker/messages",dict(chain="recovery",text="回执恢复演练。"))
    assert reply["type"]=="chat_worker_reply" and run.relay.response_lost.wait(3)
    command,committed=run.relay.lost
    for _ in range(100):
        if any(e["type"]=="memory_write_failed" for e in run.audit("recovery")): break
        time.sleep(.02)
    else: raise AssertionError("lost acknowledgement was treated as a durable write")
    stop(run.hproc)
    run.harness_start(endpoint=relay_url)
    assert any(rc==committed for _,rc in run.relay.reconciled)
    with sqlite3.connect(run.out/"audit/memory-v2-misty-dev.sqlite") as db:
        recovered=json.loads(db.execute("SELECT receipt FROM commands WHERE event=?",(command["event_id"],)).fetchone()[0])
    assert recovered==committed
    with sqlite3.connect(run.out/"ledger/memory.sqlite") as db:
        assert db.execute("SELECT count(*) FROM changes WHERE record_id=?",(committed["record_id"],)).fetchone()[0]==1
    assert sum(e["type"]=="console_response_written" for e in run.audit("recovery"))==1
    run.check("lost-ack-reconciles-without-repeating-channel-delivery",receipt=recovered,channel_writes=1)
    dump(run.out/"recovery-audit.json",run.audit("recovery"))

    bad=send(run.console+"nodes/chat-worker/messages",dict(chain="refused",text="无效更正。",observation={"assertion":{}}),expected=502)
    assert "422" in str(bad)
    run.msg("refused","继续处理有效输入。")
    with sqlite3.connect(run.out/"audit/memory-v2-misty-dev.sqlite") as db:
        assert db.execute("SELECT count(*) FROM commands WHERE json_extract(receipt,'$.status')='REFUSED'").fetchone()[0]==1
    run.check("refused-command-does-not-block-subsequent-session-writes")
    assert "私聊哨兵" not in json.dumps(run.proxy.calls,ensure_ascii=False)
    assert run.contract_calls=={"/v2/"+p for p in ("ingest","candidates","corrections","recall","read","health","receipts","changes")}
    run.check("openapi-schemas-for-all-eight-live-endpoints",paths=sorted(run.contract_calls))


def serve_demo(run):
    """验收通过后在全新演示会话中写入会议改期样例，保持双 JVM 运行直至 Ctrl+C 或出现 STOP 文件。"""
    stop(run.hproc)
    run.harness_start()
    chain="meeting-demo"
    run.msg(chain,"例会定在周五 15 点。",meeting_source("2026-11-20T15:00:00+08:00","2026-11-18T09:00:00+08:00"))
    first=next(e["data"] for e in run.audit(chain) if e["type"]=="memory_durable" and e["data"]["kind"]=="OBSERVATION")
    run.msg(chain,"例会改到周六 10 点。",meeting_source("2026-11-21T10:00:00+08:00","2026-11-19T10:00:00+08:00",
            dict(record_id=first["recordId"],revision=first["revision"])))
    answer=run.msg(chain,"例会现在是什么时候？")
    assert "周六 10 点" in answer and "小林" in answer
    console=run.console.removesuffix("api/")
    dump(run.out/"demo.json",dict(console=console,chat_url=console+"?chain="+chain,memory=run.base,chain=chain,
         memory_pid=run.mproc.pid,harness_pid=run.hproc.pid,stop_file=str(run.out/"STOP"),answer=answer))
    run.save()
    print("Demo ready:",run.out/"demo.json",flush=True)
    while not (run.out/"STOP").exists():
        if run.mproc.poll() is not None or run.hproc.poll() is not None:
            raise RuntimeError("Demo JVM exited. See its process log.")
        time.sleep(1)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--harness",type=Path,default=ROOT.parent/"neuralcloud-harness")
    parser.add_argument("--serve-demo",action="store_true",help="Keep both JVMs and local fixtures running after acceptance until Ctrl+C or a STOP file.")
    args=parser.parse_args()
    run=Run(args.harness)
    run.env["NC_FIXTURE_BAD"]=secrets.token_urlsafe(36)
    print("Evidence:",run.out,flush=True)
    try:
        replay(run)
        print("Passed",len(run.results),"acceptance groups.",flush=True)
        if args.serve_demo:
            serve_demo(run)
    finally:
        run.close()


if __name__=="__main__":
    main()
