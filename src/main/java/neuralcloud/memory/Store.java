package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Memory v2 单写者 SQLite 存储封装。
 *
 * <p>职责与就绪条件：
 * <ul>
 *   <li>通过目录内 {@code memory.lock} 文件排他锁确保同一角色主体目录同一时刻仅有一个写进程，避免并发破坏恢复边界。</li>
 *   <li>启用 WAL、{@code synchronous=FULL} 与外键约束，保证持久回执与事务原子落盘。</li>
 *   <li>启动事务内执行空库建表或 v2 → v3 → v4 原地模式迁移、核对 {@code character_id} 身份并验证 FTS5 可用性，全部通过后才提交并对外开放服务。</li>
 *   <li>通过 {@link #transaction(Work)} 串行化单次请求，保证读取水位（{@code read_seq}）与返回数据来自同一权威快照。</li>
 * </ul>
 */
final class Store implements AutoCloseable {
    final Connection db;
    /** 绑定的角色主体 ID、当前数据历史流 ID 与唯一允许的写入方实例名。 */
    final String character, stream, writer;
    private final FileChannel lock;

    /**
     * 打开并校验记忆数据库，完成必要的模式初始化或原地升级。
     *
     * @param directory 角色主体隔离的数据目录（存放 memory.lock 与 memory.sqlite）
     * @param character 期望绑定的角色 ID，须与已有库 meta.character_id 一致
     * @param writer    允许提交写命令的 source_instance 标识
     */
    Store(Path directory, String character, String writer) throws Exception {
        this.character = character;
        this.writer = writer;
        Files.createDirectories(directory);
        // 先获取目录级排他文件锁再打开 SQLite，防止两个 Memory 实例同时写入同一账本。
        lock =
                FileChannel.open(
                        directory.resolve("memory.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        if (lock.tryLock() == null) {
            lock.close();
            throw new IOException("memory directory already has a writer");
        }
        db =
                DriverManager.getConnection(
                        "jdbc:sqlite:" + directory.resolve("memory.sqlite").toAbsolutePath());
        try {
            try (var s = db.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=FULL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            db.setAutoCommit(false);
            long version = num(rows("PRAGMA user_version").getFirst(), "user_version");
            if (version == 0) {
                // 拒绝非空的未知库，避免误覆写其他程序或未迁移的旧格式数据库。
                if (!rows("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")
                        .isEmpty()) throw new IOException("unrecognized existing database");
                schema("/schema.sql");
                update("INSERT INTO meta VALUES(?,?)", character, UUID.randomUUID().toString());
            } else if (version < 2 || version > 4)
                throw new IOException("unsupported schema version " + version);
            // 旧库原地增加授权索引（v2 -> v3），保留原有流标识、版本与消费水位。
            if (version < 3) schema("/visibility.sql");
            if (version < 4) {
                // v3 -> v4 迁移：在同一启动事务内补建时态断言索引与当前分域全文词表，确保就绪后检索即准确。
                schema("/catalog.sql");
                try (var query =
                                db.prepareStatement(
                                        "SELECT record_id,revision,body FROM revisions WHERE"
                                            + " json_type(body,'$.assertion')='object'");
                        var history = query.executeQuery()) {
                    while (history.next())
                        indexClaim(
                                history.getString(1),
                                history.getLong(2),
                                object(parse(history.getString(3)), "assertion"));
                }
                // 新词表在启动事务中补齐旧库；卡目录有单独水位，可在服务就绪后增量追赶。
                try (var query =
                                db.prepareStatement(
                                        "SELECT r.id,v.body FROM records r JOIN revisions v ON"
                                            + " v.record_id=r.id AND v.revision=r.revision WHERE"
                                            + " r.status='ONLINE'");
                        var records = query.executeQuery()) {
                    while (records.next())
                        CellSearch.index(this, records.getString(1), parse(records.getString(2)));
                }
            }
            var metadata = rows("SELECT * FROM meta").getFirst();
            // 校验目录归属的角色主体，防止错配配置把 A 角色的数据挂到 B 角色进程。
            if (!character.equals(get(metadata, "character_id")))
                throw new IOException("character_id mismatch");
            stream = get(metadata, "stream_id");
            // 探测 FTS5 扩展是否正常加载且索引表可读，确认就绪后再暴露 HTTP 端口。
            rows("SELECT rowid FROM search WHERE search MATCH 'ready' LIMIT 1");
            db.commit();
        } catch (Exception e) {
            db.close();
            lock.close();
            throw e;
        }
    }

    /** 读取 classpath 下的 SQL 脚本并按分号逐句执行（脚本内注释不得含英文分号或置于末尾分号之后）。 */
    private void schema(String resource) throws IOException, SQLException {
        try (var input = Store.class.getResourceAsStream(resource);
                var stmt = db.createStatement()) {
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                if (!sql.isBlank()) stmt.execute(sql);
        }
    }

    /** 在单一数据库事务中执行的业务回调。 */
    @FunctionalInterface
    interface Work {
        JsonObject run() throws SQLException;
    }

    /**
     * 在唯一数据库写者上串行执行一次完整请求（包含读取水位和所有返回行），
     * 成功则提交事务，遇到 SQL 或业务运行时异常则整体回滚。
     */
    synchronized JsonObject transaction(Work operation) throws SQLException {
        try {
            var result = operation.run();
            db.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        }
    }

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        var p = db.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
        return p;
    }

    /** 执行查询并将结果集全部收集为 JsonObject 列表。 */
    List<JsonObject> rows(String sql, Object... args) throws SQLException {
        var out = new ArrayList<JsonObject>();
        scan(sql, out::add, args);
        return out;
    }

    /** 流式扫描查询结果并逐行回调，供历史水位检索等有界队列场景避免一次性物化全部行。 */
    void scan(String sql, java.util.function.Consumer<JsonObject> consumer, Object... args) throws SQLException {
        try (var p = prepare(sql,args); var rs = p.executeQuery()) {
            var md = rs.getMetaData();
            while (rs.next()) {
                var row = new JsonObject();
                for (int i = 1; i <= md.getColumnCount(); i++)
                    row.add(md.getColumnLabel(i), JSON.toJsonTree(rs.getObject(i)));
                consumer.accept(row);
            }
        }
    }

    /** 执行 INSERT / UPDATE / DELETE 语句。 */
    void update(String sql, Object... args) throws SQLException {
        try (var p = prepare(sql, args)) {
            p.executeUpdate();
        }
    }

    /** 将带 assertion 的修订写入 claim_times 时态索引，供有效时间与显式替代关系查询在 SQL 层下推过滤。 */
    void indexClaim(String id, long revision, JsonObject assertion) throws SQLException {
        var prior = assertion.getAsJsonObject("supersedes_ref");
        update(
                "INSERT INTO claim_times VALUES(?,?,?,?,?)",
                id,
                revision,
                time(assertion, "effective_at"),
                prior == null ? null : get(prior, "record_id"),
                prior == null ? null : num(prior, "revision"));
    }

    /** 返回当前权威变更流的最高已提交序号，空库时为 0。 */
    long head() throws SQLException {
        return num(rows("SELECT coalesce(max(seq),0) n FROM changes").getFirst(), "n");
    }

    /** 构造随每次读响应交付给 Harness 的一致性快照标识（stream_id + read_seq）。 */
    JsonObject snapshot() throws SQLException {
        return obj("stream_id", stream, "read_seq", head());
    }

    /** 构造 `/v2/health` 的基础就绪载荷，供 Harness 启动握手时校验协议、角色与能力集合。 */
    JsonObject info() throws SQLException {
        return obj(
                "api_major",
                2,
                "status",
                "TEXT_READY",
                "character_id",
                character,
                "stream_id",
                stream,
                "head_seq",
                head(),
                "capabilities",
                List.of(
                        "durable_ingest",
                        "read_refs",
                        "scoped_recall",
                        "changes",
                        "self_identity",
                        "self_history",
                        "assertions",
                        "candidates",
                        "corrections"));
    }

    /** 串行释放数据库连接与目录排他锁（在停机钩子中调用）。 */
    @Override
    public synchronized void close() throws Exception {
        try {
            db.close();
        } finally {
            lock.close();
        }
    }
}
