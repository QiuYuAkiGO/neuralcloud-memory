CREATE TABLE visibility_grants(grant_id TEXT PRIMARY KEY, record_id TEXT NOT NULL REFERENCES records(id), revision INTEGER NOT NULL, target TEXT NOT NULL, use TEXT NOT NULL CHECK(use IN ('READ','CITE')), account TEXT NOT NULL, author TEXT NOT NULL, reason TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('ACTIVE','REVOKED','SOURCE_CHANGED')), revoke_reason TEXT);
CREATE UNIQUE INDEX grant_active ON visibility_grants(record_id,revision,target,use) WHERE status='ACTIVE';
ALTER TABLE receipts ADD COLUMN grant_id TEXT;
CREATE VIEW audience_rights AS SELECT id,revision,conversation AS audience,'CITE' AS use FROM records UNION ALL SELECT g.record_id,g.revision,g.target,g.use FROM visibility_grants g JOIN records r ON r.id=g.record_id AND r.revision=g.revision WHERE g.status='ACTIVE' AND r.status='ONLINE';
CREATE VIEW evidence_dependencies AS WITH RECURSIVE deps(derived,source,revision) AS (SELECT derived,source,revision FROM sources UNION SELECT d.derived,s.source,s.revision FROM deps d JOIN sources s ON s.derived=d.source) SELECT * FROM deps;
PRAGMA user_version=3;
