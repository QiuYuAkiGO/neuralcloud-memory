-- 称呼能更换也能重名。身份键记录真实 QQ、角色本人或人工绑定，档案只持有角色库内稳定的人物 ID。
CREATE TABLE dream_identities(person_id TEXT PRIMARY KEY, version INTEGER NOT NULL DEFAULT 0, signature TEXT NOT NULL DEFAULT '', primary_name TEXT);
CREATE TABLE dream_identity_keys(identity_key TEXT PRIMARY KEY, person_id TEXT NOT NULL REFERENCES dream_identities(person_id));
CREATE INDEX dream_identity_owner ON dream_identity_keys(person_id);
-- 每项独立断言保存完整原句及其精确修订。所有来源均有效时这项断言才可参与检索或决定主名。
CREATE TABLE dream_name_claims(id INTEGER PRIMARY KEY, person_id TEXT NOT NULL REFERENCES dream_identities(person_id), name TEXT NOT NULL, relation TEXT NOT NULL, scope TEXT NOT NULL, refs TEXT NOT NULL, occurred TEXT NOT NULL, confirmed INTEGER NOT NULL CHECK(confirmed IN (0,1)), UNIQUE(person_id,name,relation,scope,refs));
CREATE INDEX dream_claim_name ON dream_name_claims(name,person_id);
-- 回复从同一会话的实际消息号还原。旧库已有的渠道记录使用同一个索引参与首轮回扫。
CREATE INDEX dream_channel_message ON revisions(json_extract(body,'$.channel.message_id')) WHERE json_extract(body,'$.channel.message_id') IS NOT NULL;
