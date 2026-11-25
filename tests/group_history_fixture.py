"""按 Resident v1 的公开输入格式建立合成快照，通过正式离线入口导入。"""
import hashlib
import json
import sqlite3
import subprocess
from contextlib import closing
from urllib.parse import urlencode

from acceptance import ref


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def history_row(key, group_id, sender_id, message_id, content, *, card=False, display_name_source="qq_metadata",
                protocol_digest=None, occurred=1700000000, record_id=None):
    """返回可修改的 (v1 ID, v1 信封)。同一次协议消息的观察与资料共享消息摘要。"""
    old_id = record_id or "shared-memory:" + digest(key)
    room = "qq:group:" + group_id
    msg_digest = protocol_digest or digest(f"protocol:{group_id}:{sender_id}:{message_id}")
    protocol = "qq-protocol://sha256/" + msg_digest
    if card:
        content = encoded({"display_name_source": display_name_source, "observed_display_name": content})
        pointers = ["qq-profile:" + digest(key)[:32], "qq-protocol://sha256/" + digest("profile:" + key), protocol]
    else:
        pointers = ["qq-visual:" + digest(key)[:32], protocol,
                    f"qq-protocol-message:{group_id}:{sender_id}:{message_id}"]
    snapshot = digest("合成群消息迁移快照")
    payload = dict(schema_version=1, character_id="misty-dev", source_instance="legacy:" + snapshot[:32],
                   event_id=old_id + ":0", kind="observation", session_id=room, conversation_id=room,
                   content=content, privacy="private", occurred_at=occurred, participants=["qq:member:" + sender_id],
                   aliases=pointers.copy(), source_ids=[], provenance=dict(format="production-memory-v1-v2",
                   original_id=old_id, original_revision=0, snapshot_sha256=snapshot, payload_sha256=digest(content),
                   semantic_status="observation", source_channel="qq_group", external_refs=pointers, evidence=[],
                   visibility=["local:main", room]))
    return old_id, payload


def import_history(service, rows):
    """创建两份 v1 输入库，调用目标 JAR 导入到未启动的服务目录，保留原回执供 HTTP 验证。"""
    source, identity = service.out / "group-v1.sqlite", service.out / "group-identity.sqlite"
    with closing(sqlite3.connect(source)) as db, db:
        db.executescript("""
            create table metadata(key primary key,value);
            create table records(id primary key,revision,status,occurred,kind,pinned,batch_id);
            create table revisions(record_id,revision,payload,hash,primary key(record_id,revision));
            create table changes(seq integer primary key,record_id,revision,operation,received_at);
            create table sources(record_id,source_id);
            create table receipts(source,event,hash,seq,record_id,revision);
            create table protections(record_id,owner);
            create table archive_batches(id,state,plan,envelope_hash,verification);
            insert into metadata values('format','1'),('character_id','misty-dev');
        """)
        for seq, (old_id, payload) in enumerate(rows, start=1):
            raw = encoded(payload)
            checksum = digest(raw)
            db.execute("insert into records values(?,1,'ONLINE',?,?,0,null)",
                       (old_id, payload["occurred_at"], payload["kind"]))
            db.execute("insert into revisions values(?,1,?,?)", (old_id, raw, checksum))
            db.execute("insert into changes values(?,?,1,'LEGACY_IMPORT',?)", (seq, old_id, payload["occurred_at"]))
            db.execute("insert into receipts values(?,?,?,?,?,1)",
                       (payload["source_instance"], payload["event_id"], checksum, seq, old_id))
            db.executemany("insert into sources values(?,?)", [(old_id, parent) for parent in payload["source_ids"]])
    with closing(sqlite3.connect(identity)) as db, db:
        db.executescript("""create table metadata(key,value);
            create table facts(platform,account_id,record_id,revision,confirmation,name,relation);""")
    cmd = ["java", *service.native_args, "-cp", str(service.memjar), "neuralcloud.memory.ResidentV1Import",
           str(source), str(identity), str(service.ledger), "misty-dev", "neuralcloud-java"]
    result = subprocess.run(cmd, capture_output=True, timeout=60)
    if result.returncode:
        raise AssertionError(result.stderr.decode("utf-8", errors="replace"))


def history_refs(service, stream_id, rows):
    """按快照中的原序号，从公开变更流一次读取旧 ID 到 v2 RecordRef 的映射。"""
    query = urlencode(dict(stream_id=stream_id, after=0, through=len(rows), limit=len(rows)))
    items = service.api("changes?" + query)["items"]
    assert [item["seq"] for item in items] == list(range(1, len(rows) + 1))
    return {row[0]: ref(item) for row, item in zip(rows, items, strict=True)}
