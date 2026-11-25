-- 原生记录保存渠道已经验证的机器人账号，旧信封只保存群号。授权表在整理范围改变时重建。
CREATE TABLE dream_group_rooms(
    room TEXT PRIMARY KEY, group_id TEXT NOT NULL, account_id TEXT NOT NULL, unique_group INTEGER NOT NULL
);
CREATE INDEX dream_group_rooms_group ON dream_group_rooms(group_id,account_id);
CREATE TABLE dream_group_sources(
    record_id TEXT PRIMARY KEY REFERENCES records(id), revision INTEGER NOT NULL, seq INTEGER NOT NULL,
    group_id TEXT NOT NULL, sender_id TEXT NOT NULL, message_id TEXT NOT NULL, account_id TEXT,
    protocol_digest TEXT, content_digest TEXT
);
CREATE INDEX dream_group_sources_seq ON dream_group_sources(seq);
CREATE INDEX dream_group_sources_message ON dream_group_sources(group_id,sender_id,message_id,account_id,seq);
CREATE INDEX dream_group_sources_packet ON dream_group_sources(group_id,sender_id,protocol_digest);

-- 一份资料可以指向几个原始协议摘要，同一资料内的相同摘要只登记一次。
CREATE TABLE dream_group_names(
    record_id TEXT NOT NULL REFERENCES records(id), revision INTEGER NOT NULL, group_id TEXT NOT NULL,
    sender_id TEXT NOT NULL, protocol_digest TEXT NOT NULL, field TEXT NOT NULL, name TEXT NOT NULL,
    PRIMARY KEY(record_id,revision,protocol_digest)
);
CREATE INDEX dream_group_names_packet ON dream_group_names(group_id,sender_id,protocol_digest,record_id);

-- 仅签发当前在线的一手消息。完全相同的副本取首条，旧信封相互矛盾时只认另有渠道证明的原生记录。
CREATE VIEW dream_group_messages AS
SELECT m.record_id,m.revision,m.seq,g.room,m.group_id,g.account_id,m.sender_id,m.message_id,m.protocol_digest
FROM dream_group_sources m
JOIN records r ON r.id=m.record_id AND r.revision=m.revision AND r.status='ONLINE'
JOIN dream_group_rooms g ON g.group_id=m.group_id
    AND (g.account_id=m.account_id OR (m.account_id IS NULL AND g.unique_group=1))
WHERE m.sender_id<>g.account_id AND NOT EXISTS (
    SELECT 1 FROM dream_group_sources other
    JOIN records current ON current.id=other.record_id AND current.revision=other.revision AND current.status='ONLINE'
    WHERE other.group_id=m.group_id AND other.sender_id=m.sender_id AND other.message_id=m.message_id AND (
        (other.account_id=g.account_id AND (m.account_id IS NULL OR other.seq<m.seq))
        OR (other.account_id IS NULL AND m.account_id IS NULL AND
            (other.seq<m.seq OR other.protocol_digest<>m.protocol_digest OR other.content_digest<>m.content_digest))
    )
);

-- 姓名对应的是另一条资料的精确版本。同群、同发言人、同一原始协议必须只剩一份有效资料。
CREATE VIEW dream_group_paired_names AS
SELECT m.record_id AS message_record,n.record_id,n.revision,m.room,n.field,n.name
FROM dream_group_messages m
JOIN dream_group_names n ON n.group_id=m.group_id AND n.sender_id=m.sender_id AND n.protocol_digest=m.protocol_digest
JOIN records r ON r.id=n.record_id AND r.revision=n.revision AND r.status='ONLINE'
WHERE NOT EXISTS (
    SELECT 1 FROM dream_group_names other
    JOIN records current ON current.id=other.record_id AND current.revision=other.revision AND current.status='ONLINE'
    WHERE other.group_id=n.group_id AND other.sender_id=n.sender_id AND other.protocol_digest=n.protocol_digest
      AND other.record_id<>n.record_id
);
CREATE VIEW dream_group_access AS
SELECT record_id,revision,room FROM dream_group_messages
UNION ALL SELECT record_id,revision,room FROM dream_group_paired_names;
