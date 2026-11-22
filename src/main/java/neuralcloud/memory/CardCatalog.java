package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * The cursor and derived memberships commit together; original reads never depend on this table.
 */
final class CardCatalog {
    private final Store store;
    private final Reader reader;

    CardCatalog(Store store, Reader reader) {
        this.store = store;
        this.reader = reader;
    }

    JsonObject organize(JsonObject req) throws SQLException {
        keys(req, "context limit");
        if (!Access.from(store, req).role().equals("ORCHESTRATOR")) throw denied();
        return advance((int) number(req, "limit", 1, 256));
    }

    JsonObject advance(int limit) throws SQLException {
        long cursor = cursor();
        String now = utc(Instant.now());
        // Absolute expiry does not advance the ledger. Reclaim a bounded page even on an idle tick.
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
            store.update("DELETE FROM card_members WHERE record_id=?", id);
            for (var row : store.rows(Reader.LATEST + " WHERE r.id=? AND r.status='ONLINE'", id)) {
                var body = parse(get(row, "body"));
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

    private long cursor() throws SQLException {
        return num(
                store.rows("SELECT cursor FROM catalog_state WHERE singleton=1").getFirst(),
                "cursor");
    }

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

    JsonObject cards(JsonObject query) throws SQLException {
        keys(query, "context view axis title limit");
        var ctx = Access.from(store, query);
        boolean details = one(query, "view", "L0", "L1").equals("L1");
        int limit = (int) number(query, "limit", 1, 32);
        if (details != query.has("title") || (details && !query.has("axis")))
            throw invalid("title/axis");
        var args = new ArrayList<Object>();
        String policy = Visibility.allowed(ctx, "CITE", args);
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
        // Membership is a pointer, never an authorization cache. Check the current revision, live
        // citation right, and source-chain validity before exposing even a title or counting a
        // leaf.
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
