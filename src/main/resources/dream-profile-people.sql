-- 旧档案和历史修订沿用原存储键。主称呼可以更改，两个账号也可以同时使用同一称呼
CREATE TABLE dream_profile_people(profile_key TEXT PRIMARY KEY, person_id TEXT NOT NULL UNIQUE);
-- 每份正文依赖的别称除当前标题以外单独记账，撤回与受众验权都按有效证据重新判断
CREATE TABLE dream_profile_name_refs(
  profile_key TEXT NOT NULL, version INTEGER NOT NULL, name TEXT NOT NULL, scope TEXT NOT NULL,
  PRIMARY KEY(profile_key,version,name,scope));
-- 对旧档的判定可撤销，原档与原引用仍由旧版本表持有
CREATE TABLE dream_legacy_decisions(profile_key TEXT NOT NULL,version INTEGER NOT NULL,
  person_id TEXT,status TEXT NOT NULL,reason TEXT NOT NULL,
  PRIMARY KEY(profile_key,version));
-- 展示版本与原始存储修订分别保存。身份改称或拆分也产生可对账的新版本
CREATE TABLE dream_person_editions(person_id TEXT NOT NULL,version INTEGER NOT NULL,body TEXT NOT NULL,
  PRIMARY KEY(person_id,version));
-- 两条通道共用一份在途包，事务只推进该包所属的水位
CREATE TABLE dream_identity_progress(singleton INTEGER PRIMARY KEY CHECK(singleton=1),
  cursor INTEGER NOT NULL, target INTEGER NOT NULL, last_lane TEXT NOT NULL);
INSERT INTO dream_identity_progress SELECT singleton,0,covered,'live' FROM dream_state;
UPDATE dream_state SET packet=NULL,policy_version=policy_version+1 WHERE packet IS NOT NULL;
PRAGMA user_version=7;
