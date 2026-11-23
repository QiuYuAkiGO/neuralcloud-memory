-- 阶段 3/4 原地迁移脚本（schema v3 -> v4）：分域词表、时态断言索引、回复链索引与增量人物/事项卡目录
-- 注意：注释中禁止出现英文分号，且末尾 PRAGMA 语句后不得附尾随注释

-- 分域事件检索 FTS5 虚拟表：words 存正文与归属词项，dates 存从相对日期锚定出的绝对日期（供 TEMPORAL/HYBRID 检索）
CREATE VIRTUAL TABLE cell_search USING fts5(words,dates);
-- 时态断言索引表：提取每条带 assertion 修订的归一化生效时间及所替代的前序版本，支持在 SQL 层下推有效时间与替代过滤
CREATE TABLE claim_times(record_id TEXT NOT NULL,revision INTEGER NOT NULL,effective_utc TEXT NOT NULL,previous_id TEXT,previous_revision INTEGER,PRIMARY KEY(record_id,revision),FOREIGN KEY(record_id,revision) REFERENCES revisions(record_id,revision));
-- 按被替代的前序记录快速反查在给定时点前已生效的后续替代项
CREATE INDEX claim_successors ON claim_times(previous_id,previous_revision,effective_utc);
-- 回复关系表达式索引：加速 CellSearch 沿 reply_to 向上下游做 1-2 跳邻接扩展
CREATE INDEX revisions_reply ON revisions(json_extract(body,'$.reply_to.record_id'));
-- 人物/事项卡目录指针表（非权威派生数据，绝非授权缓存）：记录维度（PERSON/SUBJECT）、标题到最新版本的映射及到期上界
CREATE TABLE card_members(axis TEXT NOT NULL,title TEXT NOT NULL,record_id TEXT NOT NULL REFERENCES records(id),revision INTEGER NOT NULL,expires_at TEXT,PRIMARY KEY(axis,title,record_id));
-- 部分索引：供后台整理空闲轮次按页快速回收绝对到期的目录指针
CREATE INDEX card_expiry ON card_members(expires_at) WHERE expires_at IS NOT NULL;
-- 按源记录 ID 反查并重建该记录对应的目录指针
CREATE INDEX card_source ON card_members(record_id);
-- 目录整理单行状态表：持久化已消费到的变更流最高水位 cursor，确保服务重启后从断点续追
CREATE TABLE catalog_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),cursor INTEGER NOT NULL);
-- 初始化目录水位为 0，服务就绪后由定时任务或 /v2/organize 增量追赶
INSERT INTO catalog_state VALUES(1,0);
-- 升级模式版本至 4
PRAGMA user_version=4;
