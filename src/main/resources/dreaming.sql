-- 独立整理器以 Memory 角色库为边界。已发材料、确认位置与命令回执一起参与 SQLite 事务
CREATE TABLE dream_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1), scopes TEXT NOT NULL, policy_version INTEGER NOT NULL, covered INTEGER NOT NULL, packet TEXT);
INSERT INTO dream_state VALUES(1,'[]',1,0,NULL);
CREATE TABLE dream_profiles(kind TEXT NOT NULL, subject TEXT NOT NULL, version INTEGER NOT NULL, active INTEGER NOT NULL, PRIMARY KEY(kind,subject));
CREATE TABLE dream_revisions(kind TEXT NOT NULL, subject TEXT NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(kind,subject,version), FOREIGN KEY(kind,subject) REFERENCES dream_profiles(kind,subject));
-- 当前版保留原文 ID 以便撤回时退役并按剩余依据重评。历史各版本的依据在 dream_revisions 内保存
CREATE TABLE dream_sources(kind TEXT NOT NULL, subject TEXT NOT NULL, record_id TEXT NOT NULL REFERENCES records(id), revision INTEGER NOT NULL, PRIMARY KEY(kind,subject,record_id), FOREIGN KEY(kind,subject) REFERENCES dream_profiles(kind,subject));
CREATE INDEX dream_source_record ON dream_sources(record_id);
CREATE TABLE dream_receipts(source TEXT NOT NULL, event TEXT NOT NULL, command TEXT NOT NULL, receipt TEXT NOT NULL, PRIMARY KEY(source,event));
PRAGMA user_version=5;
