-- 阶段 2 原地迁移脚本（schema v2 -> v3）：跨会话原作者版本授权、受众权限视图与递归依赖视图
-- 注意：注释中禁止出现英文分号，且末尾 PRAGMA 语句后不得附尾随注释

-- 跨会话可见性授权表：将单条原文的精确修订版本授予指定目标会话 READ 或 CITE 权限，并保留授权/撤销理由
CREATE TABLE visibility_grants(grant_id TEXT PRIMARY KEY, record_id TEXT NOT NULL REFERENCES records(id), revision INTEGER NOT NULL, target TEXT NOT NULL, use TEXT NOT NULL CHECK(use IN ('READ','CITE')), account TEXT NOT NULL, author TEXT NOT NULL, reason TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('ACTIVE','REVOKED','SOURCE_CHANGED')), revoke_reason TEXT);
-- 部分唯一索引：同一记录同一版本对同一目标会话的同种用途至多存在一条 ACTIVE 授权
CREATE UNIQUE INDEX grant_active ON visibility_grants(record_id,revision,target,use) WHERE status='ACTIVE';
-- 扩展回执表以记录授权/撤销命令产出的 grant_id（普通写入命令该列为 NULL）
ALTER TABLE receipts ADD COLUMN grant_id TEXT;
-- 受众实时权限视图：合并记录原会话天然拥有的 CITE 权与当前在线版本的有效跨会话授权，供依赖链校验使用
CREATE VIEW audience_rights AS SELECT id,revision,conversation AS audience,'CITE' AS use FROM records UNION ALL SELECT g.record_id,g.revision,g.target,g.use FROM visibility_grants g JOIN records r ON r.id=g.record_id AND r.revision=g.revision WHERE g.status='ACTIVE' AND r.status='ONLINE';
-- 递归来源依赖视图：展开所有派生项到直接及间接上游来源的版本绑定关系，用于撤权和过期传播核验
CREATE VIEW evidence_dependencies AS WITH RECURSIVE deps(derived,source,revision) AS (SELECT derived,source,revision FROM sources UNION SELECT d.derived,s.source,s.revision FROM deps d JOIN sources s ON s.derived=d.source) SELECT * FROM deps;
-- 升级模式版本至 3
PRAGMA user_version=3;
