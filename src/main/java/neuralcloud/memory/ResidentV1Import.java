package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** 从一对只读 resident v1 快照一次性生成新的 v2 目录。迁移保留原账本和身份库的原始字段。 */
public final class ResidentV1Import {
    private ResidentV1Import() {}

    /** 参数依次为旧记忆、旧身份、新目录、角色及新写入方。目标存在即拒绝，失败目录不能发布。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 5)
            throw new IllegalArgumentException(
                    "memory.sqlite3 identity.sqlite3 new-directory character writer");
        Path memory = Path.of(args[0]).toRealPath(), identity = Path.of(args[1]).toRealPath();
        for (Path snapshot : List.of(memory, identity)) {
            Path wal = Path.of(snapshot + "-wal");
            if (Files.exists(wal) && Files.size(wal) > 0)
                throw new java.io.IOException(
                        "需要 SQLite backup 独立快照，发现尚未合入的 WAL: " + snapshot.getFileName());
        }
        Path target = Files.createDirectory(Path.of(args[2]));
        try (var store = new Store(target, args[3], args[4])) {
            store.update("ATTACH DATABASE ? AS old", memory.toUri() + "?mode=ro&immutable=1");
            store.update("ATTACH DATABASE ? AS ident", identity.toUri() + "?mode=ro&immutable=1");
            var result = store.transaction(() -> convert(store));
            System.out.println(JSON.toJson(result));
        }
    }

    /** 上游提供的 SQLite backup 已包含完整 WAL；角色、记录版本、回执以及身份引用必须一致。 */
    private static JsonObject convert(Store s) throws SQLException {
        require(
                s,
                "SELECT 1 FROM old.metadata WHERE key='format' AND value='1'",
                "resident format");
        require(
                s,
                "SELECT 1 FROM old.metadata WHERE key='character_id' AND value=?",
                "character_id",
                s.character);
        for (String table :
                List.of(
                        "records",
                        "revisions",
                        "changes",
                        "receipts",
                        "sources",
                        "metadata",
                        "protections",
                        "archive_batches"))
            s.update("CREATE TABLE resident_v1_" + table + " AS SELECT * FROM old." + table);
        for (String table : List.of("facts", "metadata"))
            s.update(
                    "CREATE TABLE resident_v1_identity_"
                            + table
                            + " AS SELECT * FROM ident."
                            + table);
        s.update(
                "CREATE UNIQUE INDEX resident_v1_version ON resident_v1_revisions(record_id,revision)");
        s.update(
                "CREATE TABLE resident_v1_ids(original_id TEXT PRIMARY KEY, record_id TEXT UNIQUE NOT NULL)");
        // v1 的来源边没有版本字段。仅当来源只有一个版本时，才存在唯一可证明的绑定。
        reject(
                s,
                "SELECT 1 FROM old.sources e LEFT JOIN old.records r ON r.id=e.source_id WHERE r.id IS NULL OR r.revision<>1",
                "ambiguous source revision");
        reject(
                s,
                "SELECT 1 FROM ident.facts f LEFT JOIN old.revisions v ON v.record_id=f.record_id AND v.revision=f.revision WHERE v.record_id IS NULL OR f.platform<>'qq' OR f.confirmation<>'operator_confirmed'",
                "identity source or confirmation");
        reject(
                s,
                "SELECT 1 FROM old.records WHERE batch_id IS NOT NULL",
                "archived record requires its envelope");
        reject(
                s,
                "SELECT 1 FROM old.changes GROUP BY record_id,revision HAVING count(*)<>1",
                "non-version change");
        reject(
                s,
                "SELECT 1 FROM old.changes WHERE operation NOT IN ('LEGACY_IMPORT','INGEST')",
                "unmapped v1 operation");
        try (var p =
                        s.db.prepareStatement(
                                """
                SELECT r.*,v.payload FROM old.records r JOIN old.revisions v ON v.record_id=r.id AND v.revision=r.revision
                """);
                var rows = p.executeQuery()) {
            while (rows.next()) {
                String oldId = rows.getString("id"), id = idFor(oldId);
                var value = parse(rows.getString("payload"));
                String source = get(value, "source_instance");
                String scope =
                        value.get("conversation_id").isJsonNull()
                                ? "resident-v1-unscoped:" + id
                                : get(value, "conversation_id");
                if (!value.get("conversation_id").isJsonNull()
                        && scope.startsWith("resident-v1-unscoped:"))
                    throw new SQLException("reserved unscoped namespace");
                String status =
                        switch (rows.getString("status")) {
                            case "INACTIVE", "RETRACTED" -> "RETRACTED";
                            case "ONLINE", "SOURCE_CHANGED" -> rows.getString("status");
                            default -> throw new SQLException("unmapped v1 status");
                        };
                s.update("INSERT INTO resident_v1_ids VALUES(?,?)", oldId, id);
                s.update(
                        "INSERT INTO records VALUES(?,?,?,?,?,?,?)",
                        id,
                        scope,
                        source,
                        source,
                        kind(rows.getString("kind")),
                        rows.getInt("revision"),
                        status);
            }
        }
        // 每个来源的原 ID、正文哈希、操作、序号与旧命令回执在 resident_v1_* 中可逐项比对。
        // v2 面向受众的投影只使用旧契约中已经绑定到原会话的字段，不从昵称、原文或 QQ 号猜测身份。
        try (var p =
                        s.db.prepareStatement(
                                """
                SELECT c.seq,c.record_id,c.revision,c.received_at,v.payload,v.hash,r.status,r.revision AS latest,
                       f.account_id AS peer,f.name AS confirmed_name
                FROM old.changes c JOIN old.revisions v ON v.record_id=c.record_id AND v.revision=c.revision
                JOIN old.records r ON r.id=c.record_id
                LEFT JOIN ident.facts f ON f.record_id=c.record_id AND f.revision=c.revision
                ORDER BY c.seq""");
                var rows = p.executeQuery()) {
            while (rows.next()) {
                String raw = rows.getString("payload"), id = idFor(rows.getString("record_id"));
                if (!hash(raw).equals(rows.getString("hash")))
                    throw new SQLException("revision SHA-256 mismatch at " + rows.getLong("seq"));
                var v = parse(raw);
                if (!s.character.equals(get(v, "character_id")))
                    throw new SQLException("revision character_id");
                String kind = kind(get(v, "kind")),
                        occurred = timestamp(v.get("occurred_at").getAsBigDecimal());
                var body =
                        obj(
                                "kind",
                                kind,
                                "content",
                                get(v, "content"),
                                "occurred_at",
                                occurred,
                                "privacy",
                                one(v, "privacy", "private", "public", "sensitive"));
                var refs = v.getAsJsonArray("source_ids");
                if (!refs.isEmpty()) {
                    var sources = new com.google.gson.JsonArray();
                    for (var ref : refs) sources.add(ref(idFor(ref.getAsString()), 1));
                    body.add("source_refs", sources);
                }
                attachChannel(v, body);
                if (rows.getString("peer") != null) {
                    if (!kind.equals("observation"))
                        throw new SQLException("identity evidence kind");
                    var identity =
                            obj(
                                    "account_id",
                                    rows.getString("peer"),
                                    "name",
                                    rows.getString("confirmed_name"));
                    Access.qqId(identity, "account_id");
                    text(identity, "name", 128);
                    body.add("identity", identity);
                }
                long seq = rows.getLong("seq"), revision = rows.getLong("revision");
                s.update(
                        "INSERT INTO changes VALUES(?,?,?,?,?)",
                        seq,
                        revision > 1 ? "REPLACE" : "INGEST",
                        id,
                        revision,
                        timestamp(rows.getBigDecimal("received_at")));
                s.update(
                        "INSERT INTO revisions VALUES(?,?,?,?,?)",
                        id,
                        revision,
                        seq,
                        occurred,
                        JSON.toJson(body));
                if (revision == rows.getLong("latest") && rows.getString("status").equals("ONLINE"))
                    Ledger.index(s, id, body);
            }
        }
        s.update(
                """
                INSERT INTO sources SELECT d.record_id,p.record_id,1 FROM old.sources e
                JOIN resident_v1_ids d ON d.original_id=e.record_id JOIN resident_v1_ids p ON p.original_id=e.source_id""");
        // v1 存命令哈希而 v2 存完整命令。占用原键并保留原回执，但不把新协议命令当作旧命令的重放。
        s.update(
                """
                INSERT INTO receipts(source,event,op,command,record_id,revision,seq)
                SELECT r.source,r.event,'resident_v1',json_object('sha256',r.hash),m.record_id,r.revision,r.seq
                FROM old.receipts r JOIN resident_v1_ids m ON m.original_id=r.record_id""");
        for (String table : List.of("records", "revisions", "changes", "receipts", "sources"))
            require(
                    s,
                    "SELECT 1 WHERE (SELECT count(*) FROM "
                            + table
                            + ")=(SELECT count(*) FROM old."
                            + table
                            + ")",
                    table + " count");
        reject(
                s,
                "SELECT 1 FROM receipts r LEFT JOIN revisions v ON r.record_id=v.record_id AND r.revision=v.revision AND r.seq=v.seq WHERE v.seq IS NULL",
                "receipt version");
        require(
                s,
                "SELECT 1 WHERE (SELECT count(*) FROM search)=(SELECT count(*) FROM records WHERE status='ONLINE') AND (SELECT count(*) FROM search)=(SELECT count(*) FROM cell_search)",
                "online indexes");
        if (!s.rows("PRAGMA foreign_key_check").isEmpty()
                || !"ok"
                        .equals(
                                get(
                                        s.rows("PRAGMA main.integrity_check").getFirst(),
                                        "integrity_check")))
            throw new SQLException("destination integrity");
        return s.rows(
                        "SELECT (SELECT count(*) FROM records) records,(SELECT count(*) FROM revisions) revisions,(SELECT count(*) FROM sources) edges,(SELECT count(*) FROM receipts) receipts,(SELECT count(*) FROM resident_v1_identity_facts) identities,max(seq) head_seq FROM changes")
                .getFirst();
    }

    /** 可信 QQ 渠道仅认旧账本已验证的账号、发件人、消息 ID 和与其完全匹配的原会话。 */
    private static void attachChannel(JsonObject v, JsonObject body) throws SQLException {
        if (!v.has("channel") || v.get("channel").isJsonNull()) return;
        var old = object(v, "channel");
        String account = Access.qqId(old, "account_id"),
                sender = Access.qqId(old, "sender_id"),
                scope = text(old, "conversation_id", 256);
        String msg = text(old, "message_id", 128), type;
        if (scope.matches("group:onebot:" + account + ":[1-9][0-9]*")) type = "qq_group";
        else if (scope.matches("c2c:onebot:" + account + ":[1-9][0-9]*")
                && (scope.endsWith(":" + sender) || sender.equals(account))) type = "qq_private";
        else throw new SQLException("unbound QQ conversation");
        if (!"qq".equals(get(old, "platform")) || !scope.equals(get(v, "conversation_id")))
            throw new SQLException("channel scope");
        body.add(
                "channel",
                obj(
                        "platform",
                        "qq",
                        "type",
                        type,
                        "account_id",
                        account,
                        "sender_id",
                        sender,
                        "message_id",
                        msg,
                        "conversation_id",
                        scope));
    }

    /** 原关系作为推断，历史人设作为其原会话的一条观察；所有原类型在审计副本中保留。 */
    private static String kind(String old) throws SQLException {
        return switch (old) {
            case "observation", "self_expression", "summary" -> old;
            case "relationship" -> "impression";
            case "persona_history" -> "observation";
            default -> throw new SQLException("unmapped v1 kind: " + old);
        };
    }

    /** 保留已符合 v2 的 ID，早期 ID 使用独立命名空间，唯一索引拒绝碰撞。 */
    private static String idFor(String id) {
        return id.matches("[0-9a-f]{64}") ? id : recordId("resident-v1-original-id", id);
    }

    private static String timestamp(BigDecimal seconds) {
        long whole = seconds.longValue();
        return utc(
                Instant.ofEpochSecond(
                        whole,
                        seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).longValue()));
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** 要求约束成立或反例集合为空，不在不明确的快照上做部分迁移。 */
    private static void require(Store s, String sql, String field, Object... args)
            throws SQLException {
        if (s.rows(sql, args).isEmpty()) throw new SQLException("resident v1: " + field);
    }

    private static void reject(Store s, String sql, String field) throws SQLException {
        if (!s.rows(sql + " LIMIT 1").isEmpty()) throw new SQLException("resident v1: " + field);
    }
}
