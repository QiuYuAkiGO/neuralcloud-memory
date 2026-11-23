-- Memory v2 基础模式（schema v2）：权威账本、修订历史、派生依赖、幂等回执与基础全文检索
-- 注意：Store.schema() 按英文分号切分执行，本脚本注释中禁止出现英文分号，且末尾语句后不得附尾随注释

-- 角色主体元数据：绑定唯一 character_id 与当前数据历史流标识 stream_id
CREATE TABLE meta(character_id TEXT PRIMARY KEY, stream_id TEXT UNIQUE NOT NULL);
-- 记忆记录主表：保存每条记录的归属会话、写入主体、类型、当前最新修订号与在线状态（ONLINE/RETRACTED/SOURCE_CHANGED）
CREATE TABLE records(id TEXT PRIMARY KEY, conversation TEXT NOT NULL, owner TEXT NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL, status TEXT NOT NULL);
-- 按会话、类型和状态加速受众范围前置过滤
CREATE INDEX records_scope ON records(conversation,kind,status);
-- 全局单调递增变更流：记录每次入账、派生、更正、撤回、依赖失效与授权决策（不存正文，供 Harness 消费翻页）
CREATE TABLE changes(seq INTEGER PRIMARY KEY AUTOINCREMENT, op TEXT NOT NULL, record_id TEXT NOT NULL REFERENCES records(id), revision INTEGER NOT NULL, recorded_at TEXT NOT NULL);
-- 修订历史表：保留每条记录各版本的完整 JSON 正文与写入序号，支撑审计回读与按历史知识水位切片查询
CREATE TABLE revisions(record_id TEXT NOT NULL REFERENCES records(id), revision INTEGER NOT NULL, seq INTEGER NOT NULL REFERENCES changes(seq), occurred TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(record_id,revision));
-- 按记录与提交序号倒序定位 as_of_known_seq 对应的历史修订
CREATE INDEX revisions_history ON revisions(record_id,seq);
-- 派生来源边表：记录摘要、印象或带引用回复（derived）对具体来源记录及精确版本（source, revision）的依赖
CREATE TABLE sources(derived TEXT NOT NULL REFERENCES records(id), source TEXT NOT NULL REFERENCES records(id), revision INTEGER NOT NULL, PRIMARY KEY(derived,source));
-- 反向来源索引：当源记录修订或撤回时，在同一事务内快速级联定位下游派生项
CREATE INDEX sources_reverse ON sources(source,derived);
-- 写入幂等回执表：以 (source, event) 为主键持久化原始命令与最终提交水位，供超时后对账和冲突检测
CREATE TABLE receipts(source TEXT NOT NULL, event TEXT NOT NULL, op TEXT NOT NULL, command TEXT NOT NULL, record_id TEXT NOT NULL, revision INTEGER NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(source,event));
-- 基础全文索引虚拟表（FTS5）：rowid 与 records.rowid 对齐，存储经 Terms 切分的在线记录词项
CREATE VIRTUAL TABLE search USING fts5(terms,tokenize='unicode61');
-- 标记模式版本为 2（新库初始化基线，旧库升级后续依次执行 visibility.sql 与 catalog.sql）
PRAGMA user_version=2;
