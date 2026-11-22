package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

final class Store implements AutoCloseable {
    final Connection db;
    final String character, stream, writer;
    private final FileChannel lock;

    Store(Path directory, String character, String writer) throws Exception {
        this.character = character;
        this.writer = writer;
        Files.createDirectories(directory);
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
                if (!rows("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")
                        .isEmpty()) throw new IOException("unrecognized existing database");
                schema("/schema.sql");
                update("INSERT INTO meta VALUES(?,?)", character, UUID.randomUUID().toString());
            } else if (version != 2 && version != 3)
                throw new IOException("unsupported schema version " + version);
            // 旧库原地增加授权索引，保留原有流标识、版本与消费水位。
            if (version < 3) schema("/visibility.sql");
            var metadata = rows("SELECT * FROM meta").getFirst();
            if (!character.equals(get(metadata, "character_id")))
                throw new IOException("character_id mismatch");
            stream = get(metadata, "stream_id");
            rows("SELECT rowid FROM search WHERE search MATCH 'ready' LIMIT 1");
            db.commit();
        } catch (Exception e) {
            db.close();
            lock.close();
            throw e;
        }
    }

    private void schema(String resource) throws IOException, SQLException {
        try (var input = Store.class.getResourceAsStream(resource);
                var stmt = db.createStatement()) {
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                if (!sql.isBlank()) stmt.execute(sql);
        }
    }

    @FunctionalInterface
    interface Work {
        JsonObject run() throws SQLException;
    }

    // One database owner serializes an entire request, including the read watermark and all
    // returned rows.
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

    List<JsonObject> rows(String sql, Object... args) throws SQLException {
        try (var p = prepare(sql, args);
                var rs = p.executeQuery()) {
            var out = new ArrayList<JsonObject>();
            var md = rs.getMetaData();
            while (rs.next()) {
                var row = new JsonObject();
                for (int i = 1; i <= md.getColumnCount(); i++)
                    row.add(md.getColumnLabel(i), JSON.toJsonTree(rs.getObject(i)));
                out.add(row);
            }
            return out;
        }
    }

    void update(String sql, Object... args) throws SQLException {
        try (var p = prepare(sql, args)) {
            p.executeUpdate();
        }
    }

    long head() throws SQLException {
        return num(rows("SELECT coalesce(max(seq),0) n FROM changes").getFirst(), "n");
    }

    JsonObject snapshot() throws SQLException {
        return obj("stream_id", stream, "read_seq", head());
    }

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

    @Override
    public synchronized void close() throws Exception {
        try {
            db.close();
        } finally {
            lock.close();
        }
    }
}
