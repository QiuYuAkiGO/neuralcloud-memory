-- 每个称呼在角色库中只归属一人。主称呼自身也占一个名字，账号与角色本人只绑定在这一行
CREATE TABLE dream_person_names(name TEXT PRIMARY KEY,subject TEXT NOT NULL REFERENCES dream_person_names(name),qq_sender_id TEXT UNIQUE,is_self INTEGER NOT NULL DEFAULT 0 CHECK(is_self IN (0,1)),CHECK(name=subject OR (qq_sender_id IS NULL AND is_self=0)));
CREATE INDEX dream_person_subject ON dream_person_names(subject);
CREATE UNIQUE INDEX dream_person_self ON dream_person_names(is_self) WHERE is_self=1;
PRAGMA user_version=6;
