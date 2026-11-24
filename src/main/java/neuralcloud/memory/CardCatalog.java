package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * 阶段 4 人物/事项分域卡片目录与增量离线整理器。
 *
 * <p>核心设计边界：
 * <ul>
 *   <li>消费水位（{@code catalog_state.cursor}）与派生目录指针（{@code card_members}）在同一事务中原子提交；
 *       原始读取（{@code /v2/recall}、{@code /v2/read}）从不依赖本表，因此目录积压或中断不影响原文查证。</li>
 *   <li>{@code card_members} 只是指向原文/派生记录的索引指针，绝非授权缓存：读取 L0 目录或 L1 卡片时，
 *       必须重新核对当前版本、受众 {@code CITE} 权限、叶子来源链与断言有效期。</li>
 * </ul>
 */
final class CardCatalog {
    private final Store store;
    private final Reader reader;

    CardCatalog(Store store, Reader reader) {
        this.store = store;
        this.reader = reader;
    }

    /** {@code POST /v2/organize} 入口：仅允许 ORCHESTRATOR 显式按页推进目录整理水位。 */
    JsonObject organize(JsonObject req) throws SQLException {
        keys(req, "context limit");
        if (!Access.from(store, req).role().equals("ORCHESTRATOR")) throw denied();
        return advance((int) number(req, "limit", 1, 256));
    }

    /**
     * 按页清理绝对到期指针，并从上次水位向前消费至多 {@code limit} 条变更记录，重建受影响记录的卡成员指针。
     *
     * <p>业务原因：墙钟到期不会写入新的 {@code changes} 序号，因此即使在空闲轮次（无新增变更），
     * 也先按页回收 {@code expires_at <= now} 的过期指针。
     */
    JsonObject advance(int limit) throws SQLException {
        long cursor = cursor();
        String now = utc(Instant.now());
        // 绝对时间到期不会推进账本水位，因此在空闲整理轮次也要按页回收已过期指针。
        store.update(
                "DELETE FROM card_members WHERE rowid IN (SELECT rowid FROM card_members WHERE"
                        + " expires_at<=? ORDER BY expires_at LIMIT ?)",
                now,
                limit);
        var changes =
                store.rows(
                        "SELECT seq,record_id FROM changes WHERE seq>? ORDER BY seq LIMIT ?",
                        cursor,
                        limit);
        var ids = new LinkedHashSet<String>();
        for (var change : changes) ids.add(get(change, "record_id"));
        for (var id : ids) {
            // 先清空该记录旧指针再按最新 ONLINE 版本重建，确保撤回、失效或改标题的旧指针立即移除。
            store.update("DELETE FROM card_members WHERE record_id=?", id);
            for (var row : store.rows(Reader.LATEST + " WHERE r.id=? AND r.status='ONLINE'", id)) {
                var body = parse(get(row, "body"));
                // 派生项继承来源链中最早的到期上界；带 assertion.valid_until 的记录以其自身有效期为准。
                String expiry = body.has("source_refs") ? reader.dependencyExpiry(id) : null;
                if (body.has("assertion") && object(body, "assertion").has("valid_until"))
                    expiry = time(object(body, "assertion"), "valid_until");
                if (expiry != null && expiry.compareTo(now) <= 0) continue;
                if (body.has("speaker")) member("PERSON", get(body, "speaker"), row, expiry);
                if (body.has("topic")) member("SUBJECT", get(body, "topic"), row, expiry);
                if (body.has("assertion"))
                    member("SUBJECT", get(object(body, "assertion"), "subject_id"), row, expiry);
            }
        }
        if (!changes.isEmpty()) {
            cursor = num(changes.getLast(), "seq");
            store.update("UPDATE catalog_state SET cursor=? WHERE singleton=1", cursor);
        }
        var out = store.snapshot();
        out.addProperty("organized_seq", cursor);
        out.addProperty("processed", changes.size());
        return out;
    }

    /** 读取当前已提交的目录整理消费水位。 */
    private long cursor() throws SQLException {
        return num(
                store.rows("SELECT cursor FROM catalog_state WHERE singleton=1").getFirst(),
                "cursor");
    }

    /** 写入一条维度（PERSON/SUBJECT）+ 标题到记录最新版本的指针。 */
    private void member(String axis, String title, JsonObject row, String expiry)
            throws SQLException {
        store.update(
                "INSERT OR IGNORE INTO card_members VALUES(?,?,?,?,?)",
                axis,
                title,
                get(row, "id"),
                num(row, "revision"),
                expiry);
    }

    /**
     * {@code POST /v2/cards} 入口：按受众查询 L0 目录指针或 L1 单卡证据摘录。
     *
     * <p>契约规则：
     * <ul>
     *   <li>{@code view=L0}（禁止传 title，axis 可选）：返回受众当前可引用的去重标题列表，未决 deadline/commitment 排首位（priority=OPEN）。</li>
     *   <li>{@code view=L1}（必传 axis 与 title）：展开单张卡片，返回至多 {@code limit} 条 320 码点摘录及去重后的叶子 {@code source_refs}/{@code source_count}。</li>
     *   <li>逐条复核 {@code CITE} 权限、当前断言活跃态及全部叶子来源有效性，防止已过期、被替代或撤权的内容泄露到标题或计数中。</li>
     * </ul>
     *
     * <p>{@code query} 请求体示例（L0 目录，{@code axis} 可省略；若为 QQ 私聊受众，{@code context} 需附 {@code qq: {"account_id": "...", "peer_id": "..."}}）：
     * <pre>{@code
     * {
     *   "context": {
     *     "character_id": "misty-dev",
     *     "session_id": "session-1",
     *     "conversation_id": "group:demo",
     *     "actor_id": "MASTER",
     *     "role": "MASTER"
     *   },
     *   "view": "L0",
     *   "axis": "SUBJECT",
     *   "limit": 12
     * }
     * }</pre>
     *
     * <p>{@code query} 请求体示例（L1 单卡展开，必传 {@code axis} 与 {@code title}）：
     * <pre>{@code
     * {
     *   "context": {
     *     "character_id": "misty-dev",
     *     "session_id": "session-1",
     *     "conversation_id": "group:demo",
     *     "actor_id": "MASTER",
     *     "role": "MASTER"
     *   },
     *   "view": "L1",
     *   "axis": "SUBJECT",
     *   "title": "项目例会",
     *   "limit": 8
     * }
     * }</pre>
     */
    JsonObject cards(JsonObject query) throws SQLException {
        keys(query, "context view axis title limit");
        var ctx = Access.from(store, query);
        boolean details = one(query, "view", "L0", "L1").equals("L1");
        int limit = (int) number(query, "limit", 1, 32);
        if (details != query.has("title") || (details && !query.has("axis")))
            throw invalid("title/axis");
        var args = new ArrayList<Object>();
        // 目录与卡片始终要求 CITE 权限，确保跨会话仅授 READ 的记录不会进入可引用目录。
        String policy = reader.visibility.allowed(ctx, "CITE", args);
        if (query.has("axis")) {
            policy += " AND c.axis=?";
            args.add(one(query, "axis", "PERSON", "SUBJECT"));
        }
        if (details) {
            policy += " AND c.title=?";
            args.add(text(query, "title", 256));
        }
        var now = utc(Instant.now());
        policy += " AND (c.expires_at IS NULL OR c.expires_at>?)";
        args.add(now);
        // 目录成员表只是指针，绝非授权缓存。在暴露标题或计入叶子来源前，必须核对当前版本、实时引用权限与来源链有效性。
        var sql =
                """
SELECT r.id,r.conversation,r.kind,r.status,v.revision,v.occurred,v.body,c.axis,c.title,
       CASE WHEN c.axis='SUBJECT' AND json_extract(v.body,'$.assertion.predicate')
                 IN ('deadline','commitment') THEN 1 ELSE 0 END AS urgent
FROM card_members c JOIN records r ON r.id=c.record_id AND r.revision=c.revision
JOIN revisions v ON v.record_id=r.id AND v.revision=r.revision
WHERE r.status='ONLINE' AND %s
ORDER BY urgent DESC,v.seq DESC,c.axis,c.title,r.id LIMIT 48 OFFSET ?
"""
                        .formatted(policy);
        var cards = new LinkedHashMap<List<String>, JsonObject>();
        var used = new LinkedHashSet<JsonObject>();
        int size = 0;
        // 分页扫描直到凑满 limit 或底层无更多行（因部分指针可能在应用层复核时因替代/失效被跳过）。
        for (int offset = 0; size < limit; offset += 48) {
            var pageArgs = new ArrayList<>(args);
            pageArgs.add(offset);
            var rows = store.rows(sql, pageArgs.toArray());
            for (var row : rows) {
                var key = List.of(get(row, "axis"), get(row, "title"));
                if (!details && cards.containsKey(key)) continue;
                if (!reader.currentClaim(ctx, row, now)) continue;
                var item = reader.item(ctx, row, "HISTORY", "CITE", 0, 320, "L1", now);
                var leaves = item.getAsJsonArray("source_refs");
                // 对派生项逐一复核每个叶子原始来源，确保其当前仍在线、版本匹配且对应断言未被替代或过期。
                boolean valid = true;
                for (var leaf : leaves) {
                    var ref = leaf.getAsJsonObject();
                    if (get(row, "id").equals(get(ref, "record_id"))) continue;
                    var source = reader.currentFor(ctx, "CITE", get(ref, "record_id"));
                    if (source == null
                            || !get(source, "status").equals("ONLINE")
                            || num(source, "revision") != num(ref, "revision")
                            || !reader.currentClaim(ctx, source, now)) {
                        valid = false;
                        break;
                    }
                }
                // L1 展开时，若某条派生摘要的全部叶子来源都已被更靠前的条目涵盖，则跳过重复摘要（同一原始来源只计一次）。
                if (!valid
                        || (details
                                && leaves.asList().stream()
                                        .allMatch(l -> used.contains(l.getAsJsonObject()))))
                    continue;
                var card =
                        cards.computeIfAbsent(
                                key,
                                k ->
                                        obj(
                                                "axis",
                                                k.getFirst(),
                                                "title",
                                                k.getLast(),
                                                "priority",
                                                num(row, "urgent") == 1 ? "OPEN" : "CARD",
                                                "ref",
                                                item.get("ref")));
                // 仅从该卡片的首条（最高优先级/最新）代表记录提取期限与有效期上界，供热索引直接展示。
                if (card.get("ref").equals(item.get("ref")) && item.has("assertion")) {
                    var assertion = object(item, "assertion");
                    if (assertion.has("valid_until"))
                        card.add("valid_until", assertion.get("valid_until"));
                    if ("deadline".equals(get(assertion, "predicate")))
                        card.add("deadline", assertion.get("value"));
                }
                if (details) {
                    if (!card.has("items")) card.add("items", new JsonArray());
                    if (item.has("assertion")) item.addProperty("assertion_active", true);
                    card.getAsJsonArray("items").add(item);
                    leaves.forEach(l -> used.add(l.getAsJsonObject()));
                }
                if (++size == limit) break;
            }
            if (rows.size() < 48) break;
        }
        if (details && !cards.isEmpty()) {
            var card = cards.values().iterator().next();
            card.add("source_refs", JSON.toJsonTree(used));
            card.addProperty("source_count", used.size());
        }
        var out = store.snapshot();
        out.addProperty("organized_seq", cursor());
        out.add("items", JSON.toJsonTree(cards.values()));
        return out;
    }
}
