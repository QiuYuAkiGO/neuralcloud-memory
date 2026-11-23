"""离线导入的精确版本、旧 ID、推断类型、身份及损坏快照回归。"""
from pathlib import Path
from contextlib import closing
import hashlib
import json
import sqlite3
import subprocess
import tempfile


def verify(root):
    source, identity = root / "v1.sqlite", root / "identity.sqlite"
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
          insert into metadata values('format','1'),('character_id','test');
        """)
        # 同一旧记录有两个版本，另有一条只有单个版本的原始依据。
        for seq, rid, rev, kind, text, refs in ((1,"a"*64,1,"observation","原文",[]),
                (2,"legacy-id",1,"relationship","初稿",["a"*64]),(3,"legacy-id",2,"relationship","变更后",["a"*64])):
            value = dict(character_id="test",source_instance="source",kind=kind,content=text,privacy="private",
                         conversation_id="group:test",occurred_at=1700000000.125,source_ids=refs)
            raw = json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(",",":"))
            digest = hashlib.sha256(raw.encode()).hexdigest()
            db.execute("insert or replace into records values(?,?,?,1700000000,?,0,null)",
                       (rid,rev,"ONLINE" if kind=="observation" else "INACTIVE",kind))
            db.execute("insert into revisions values(?,?,?,?)",(rid,rev,raw,digest))
            db.execute("insert into changes values(?,?,?,'LEGACY_IMPORT',1700000000)",(seq,rid,rev))
            db.execute("insert into receipts values(?,?,?,?,?,?)",("source",str(seq),digest,seq,rid,rev))
        db.execute("insert into sources values(?,?)",("legacy-id","a"*64))
    with closing(sqlite3.connect(identity)) as db, db:
        db.executescript("create table metadata(key,value); create table facts(platform,account_id,record_id,revision,confirmation,name,relation);")
        db.execute("insert into facts values('qq','12345',?,1,'operator_confirmed','测试名',null)",("a"*64,))
    jar=next(p for p in (Path(__file__).resolve().parents[1]/"target").glob("neuralcloud-memory-*.jar") if "shaded" not in p.name)
    cmd=["java","-cp",str(jar),"neuralcloud.memory.ResidentV1Import",str(source),str(identity)]
    out=root/"new"
    subprocess.run([*cmd,str(out),"test","new-writer"],check=True)
    with closing(sqlite3.connect(out/"memory.sqlite")) as db:
        dest=db.execute("select record_id from resident_v1_ids where original_id='legacy-id'").fetchone()[0]
        assert len(dest)==64
        assert db.execute("select revision,status,kind from records where id=?",(dest,)).fetchone()==(2,"RETRACTED","impression")
        assert db.execute("select source,revision from sources where derived=?",(dest,)).fetchone()==("a"*64,1)
        assert db.execute("select json_extract(body,'$.content') from revisions where record_id=? order by revision",(dest,)).fetchall()==[("初稿",),("变更后",)]
        assert db.execute("select json_extract(body,'$.identity.name') from revisions where record_id=?",("a"*64,)).fetchone()==("测试名",)
        assert db.execute("select count(*) from search").fetchone()==(1,)
    with closing(sqlite3.connect(source)) as db, db:
        db.execute("update revisions set hash='bad' where revision=2")
    failed=subprocess.run([*cmd,str(root/"bad"),"test","new-writer"],capture_output=True)
    assert failed.returncode and b"SHA-256 mismatch" in failed.stderr
    print("PASS resident v1 精确版本、回执与身份、来源及损坏快照拒绝")


if __name__=="__main__":
    with tempfile.TemporaryDirectory() as tmp:
        verify(Path(tmp))
