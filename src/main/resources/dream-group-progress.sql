-- 格式 7 已确认的正常水位是首次群回扫的上界。实时与身份通道继续使用原有权威位置及回执。
CREATE TABLE dream_group_progress(singleton INTEGER PRIMARY KEY CHECK(singleton=1),
  enabled INTEGER NOT NULL CHECK(enabled IN (0,1)), cursor INTEGER NOT NULL, target INTEGER NOT NULL,
  CHECK(cursor>=0 AND cursor<=target));
INSERT INTO dream_group_progress SELECT singleton,0,0,covered FROM dream_state;
PRAGMA user_version=8;
