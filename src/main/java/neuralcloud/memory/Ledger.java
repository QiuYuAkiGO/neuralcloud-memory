package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

final class Ledger {
    private static final String COMMAND = "context source_instance event_id ";
    private final Store store;

    Ledger(Store store) {
        this.store = store;
    }

    JsonObject receipt(String source, String event) throws SQLException {
        if (!source.equals(store.writer)) throw denied();
        var rows =
                store.rows(
                        "SELECT record_id,revision,seq,grant_id FROM receipts WHERE source=? AND event=?",
                        source,
                        event);
        if (rows.isEmpty()) return obj("status", "NOT_FOUND");
        var r = rows.getFirst();
        if (r.get("grant_id").isJsonNull()) r.remove("grant_id");
        r.addProperty("status", "DURABLE");
        return r;
    }

    JsonObject write(String route, JsonObject cmd) throws SQLException {
        var ctx = Access.from(store, cmd);
        ctx.write();
        String source = text(cmd, "source_instance", 128), event = text(cmd, "event_id", 256);
        if (!source.equals(store.writer)) throw denied();
        var prev =
                store.rows(
                        "SELECT op,command FROM receipts WHERE source=? AND event=?",
                        source,
                        event);
        if (!prev.isEmpty()) {
            var previous = prev.getFirst();
            if (!route.equals(get(previous, "op")) || !parse(get(previous, "command")).equals(cmd))
                throw conflict("EVENT_ID_CONTENT_CONFLICT");
            return receipt(source, event);
        }
        String id, grant = null;
        long rev;
        if (Visibility.isCommand(route)) {
            var decision = Visibility.decide(store, ctx, route, cmd);
            id = decision.record();
            rev = decision.revision();
            grant = decision.grant();
            change(id, rev, route.equals(Visibility.GRANTS) ? "GRANT" : "REVOKE");
            // 新通知的可见范围变化，也可能改变该受众眼中旧通知的生效状态。
            var body =
                    store.rows(
                                    "SELECT body FROM revisions WHERE record_id=? AND revision=?",
                                    id,
                                    rev)
                            .getFirst();
            notifyPredecessors(parse(get(body, "body")), null);
        } else {
            id =
                    route.equals("corrections")
                            ? correct(ctx, cmd)
                            : insert(ctx, cmd, route.equals("candidates"));
            rev =
                    num(
                            store.rows("SELECT revision FROM records WHERE id=?", id).getFirst(),
                            "revision");
        }
        store.update(
                "INSERT INTO receipts VALUES(?,?,?,?,?,?,?,?)",
                source,
                event,
                route,
                JSON.toJson(cmd),
                id,
                rev,
                store.head(),
                grant);
        return receipt(source, event);
    }

    private String insert(Access ctx, JsonObject cmd, boolean candidate) throws SQLException {
        String base = "kind content occurred_at speaker source_venue privacy";
        if (candidate) keys(cmd, COMMAND + base + " source_refs author");
        else keys(cmd, COMMAND + base + " channel action_id assertion identity source_refs");
        String kind =
                candidate
                        ? one(cmd, "kind", "summary", "impression")
                        : one(
                                cmd,
                                "kind",
                                "observation",
                                "self_expression",
                                "tool_attempt",
                                "tool_result",
                                "delivery_receipt");
        String occurred = time(cmd, "occurred_at");
        if (!candidate && cmd.has("source_refs") && !kind.equals("self_expression"))
            throw invalid("source_refs");
        text(cmd, "content", 32768);
        var body = cmd.deepCopy();
        for (String key : List.of("context", "event_id", "source_instance")) body.remove(key);
        if (cmd.has("privacy")) one(cmd, "privacy", "public", "private", "sensitive");
        else body.addProperty("privacy", "private");
        for (String key : List.of("speaker", "source_venue")) if (cmd.has(key)) text(cmd, key, 256);
        if (candidate) {
            String author = text(cmd, "author", 128);
            if (kind.equals("impression") && !author.equals("MASTER")) throw denied();
        } else {
            boolean action =
                    Set.of("tool_attempt", "tool_result", "delivery_receipt").contains(kind);
            if (action) text(cmd, "action_id", 256);
            else if (cmd.has("action_id")) throw invalid("action_id");
            if (cmd.has("channel")) channel(ctx, object(cmd, "channel"));
            if (cmd.has("identity")) {
                if (!ctx.role().equals("ORCHESTRATOR") || !kind.equals("observation"))
                    throw denied();
                var identity = object(cmd, "identity");
                keys(identity, "account_id name");
                Access.qqId(identity, "account_id");
                text(identity, "name", 128);
            }
            if (cmd.has("assertion")) {
                if (!kind.equals("observation")) throw invalid("assertion");
                validateAssertion(ctx, object(cmd, "assertion"), null);
            }
        }
        String id = recordId(get(cmd, "source_instance"), get(cmd, "event_id"));
        store.update(
                "INSERT INTO records VALUES(?,?,?,?,?,1,'ONLINE')",
                id,
                ctx.scope(),
                ctx.actor(),
                store.writer,
                kind);
        if (candidate || cmd.has("source_refs")) {
            for (var e : array(cmd, "source_refs", 1, 128)) {
                if (!e.isJsonObject()) throw invalid("source_refs");
                var ref = e.getAsJsonObject();
                keys(ref, "record_id revision");
                String rid = id(ref, "record_id");
                long expected = number(ref, "revision", 1, Long.MAX_VALUE);
                if (candidate) {
                    var row = new Reader(store).currentFor(ctx, "CITE", rid);
                    if (row == null
                            || !get(row, "status").equals("ONLINE")
                            || num(row, "revision") != expected) throw conflict("SOURCE_CHANGED");
                    if ("sensitive".equals(get(parse(get(row, "body")), "privacy"))) {
                        if (ctx.peer() != null) throw denied();
                        body.addProperty("privacy", "sensitive");
                    }
                } else if (store.rows(
                                "SELECT 1 FROM revisions WHERE record_id=? AND revision=?",
                                rid,
                                expected)
                        .isEmpty()) {
                    throw conflict("SOURCE_CHANGED");
                }
                // 已送达的渠道正文如实入账。即使在写回之前撤权，此依赖也会阻止再次交付旧正文。
                store.update("INSERT OR IGNORE INTO sources VALUES(?,?,?)", id, rid, expected);
            }
        }
        version(id, 1, occurred, body, candidate ? "CANDIDATE" : "INGEST");
        notifyPredecessors(body, null);
        return id;
    }

    private void channel(Access ctx, JsonObject channel) {
        keys(channel, "platform account_id sender_id message_id conversation_id type");
        one(channel, "platform", "qq");
        String type = one(channel, "type", "qq_private", "qq_group");
        String account = Access.qqId(channel, "account_id"),
                sender = Access.qqId(channel, "sender_id");
        String scope = text(channel, "conversation_id", 256);
        text(channel, "message_id", 128);
        if (!ctx.scope().equals(scope)) throw denied();
        if (type.equals("qq_private")) {
            ctx.requireQq();
            if (!ctx.account().equals(account)
                    || !(ctx.peer().equals(sender) || account.equals(sender))) throw denied();
        } else if (!scope.matches("group:onebot:" + account + ":[1-9][0-9]*")) throw denied();
    }

    private void validateAssertion(Access ctx, JsonObject a, String ownId) throws SQLException {
        keys(a, "subject_id predicate value effective_at supersedes_ref");
        String subject = text(a, "subject_id", 256), predicate = text(a, "predicate", 128);
        text(a, "value", 2048);
        String effective = time(a, "effective_at");
        if (predicate.equals("scheduled_for")) time(a, "value");
        if (!a.has("supersedes_ref")) return;
        var ref = object(a, "supersedes_ref");
        keys(ref, "record_id revision");
        String id = id(ref, "record_id");
        long revision = number(ref, "revision", 1, Long.MAX_VALUE);
        var old = current(ctx.scope(), id);
        if (id.equals(ownId)
                || old == null
                || num(old, "revision") != revision
                || !get(old, "status").equals("ONLINE")) throw conflict("SOURCE_CHANGED");
        var original = parse(get(old, "body"));
        if (!original.has("assertion")) throw invalid("supersedes_ref");
        var prior = object(original, "assertion");
        if (!get(prior, "subject_id").equals(subject)
                || !get(prior, "predicate").equals(predicate)
                || time(prior, "effective_at").compareTo(effective) > 0)
            throw invalid("supersedes_ref");
    }

    private String correct(Access ctx, JsonObject cmd) throws SQLException {
        keys(cmd, COMMAND + "record_id expected_revision operation reason content assertion");
        String id = id(cmd, "record_id"), op = one(cmd, "operation", "REPLACE", "RETRACT");
        text(cmd, "reason", 1024);
        long expected = number(cmd, "expected_revision", 1, Long.MAX_VALUE);
        var old = current(ctx.scope(), id);
        if (old == null
                || !(get(old, "owner").equals(ctx.actor()) || ctx.role().equals("ORCHESTRATOR")))
            throw denied();
        if (num(old, "revision") != expected) throw conflict("REVISION_CONFLICT");
        if (!get(old, "status").equals("ONLINE")) throw conflict("SOURCE_CHANGED");
        var previousBody = parse(get(old, "body"));
        var body = previousBody.deepCopy();
        body.remove("assertion");
        body.remove("identity");
        if (op.equals("REPLACE")) {
            body.addProperty("content", text(cmd, "content", 32768));
            if (cmd.has("assertion")) {
                if (!get(old, "kind").equals("observation")) throw invalid("assertion");
                validateAssertion(ctx, object(cmd, "assertion"), id);
                body.add("assertion", cmd.get("assertion"));
            }
        } else {
            if (cmd.has("content") || cmd.has("assertion")) throw invalid("operation");
            body.addProperty("content", "");
        }
        store.update(
                "UPDATE records SET revision=?,status=? WHERE id=?",
                expected + 1,
                op.equals("RETRACT") ? "RETRACTED" : "ONLINE",
                id);
        store.update(
                "UPDATE visibility_grants SET status='SOURCE_CHANGED' WHERE record_id=? AND status='ACTIVE'",
                id);
        // Invalidate descendants in the same transaction. Their own revision can remain unchanged.
        var dependents =
                store.rows(
                        """
WITH RECURSIVE deps(id) AS (SELECT derived FROM sources WHERE source=?
UNION SELECT s.derived FROM sources s JOIN deps d ON s.source=d.id)
SELECT r.id,r.revision FROM records r JOIN deps USING(id) WHERE status='ONLINE'""",
                        id);
        for (var dep : dependents) {
            String affected = get(dep, "id");
            store.update("UPDATE records SET status='SOURCE_CHANGED' WHERE id=?", affected);
            unindex(affected);
            change(affected, num(dep, "revision"), "SOURCE_CHANGED");
        }
        version(id, expected + 1, get(old, "occurred"), body, op);
        notifyPredecessors(previousBody, body);
        return id;
    }

    // A later announcement preserves the old transcript but changes its schedule's validity.
    private void notifyPredecessors(JsonObject first, JsonObject second) throws SQLException {
        var ids = new LinkedHashSet<String>();
        for (var body : new JsonObject[] {first, second}) {
            if (body == null || !body.has("assertion")) continue;
            var a = object(body, "assertion");
            if (a.has("supersedes_ref")) ids.add(get(object(a, "supersedes_ref"), "record_id"));
        }
        for (String id : ids) {
            var parent = store.rows("SELECT revision FROM records WHERE id=?", id).getFirst();
            change(id, num(parent, "revision"), "ASSERTION_UPDATED");
        }
    }

    JsonObject current(String scope, String id) throws SQLException {
        var rows =
                store.rows(
                        """
SELECT r.*,v.body,v.occurred FROM records r JOIN revisions v ON r.id=v.record_id AND r.revision=v.revision
WHERE r.conversation=? AND r.id=?""",
                        scope,
                        id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void change(String id, long rev, String op) throws SQLException {
        store.update(
                "INSERT INTO changes(op,record_id,revision,recorded_at) VALUES(?,?,?,?)",
                op,
                id,
                rev,
                utc(Instant.now()));
    }

    private void unindex(String id) throws SQLException {
        store.update("DELETE FROM search WHERE rowid=(SELECT rowid FROM records WHERE id=?)", id);
    }

    private void version(String id, long rev, String occurred, JsonObject body, String op)
            throws SQLException {
        change(id, rev, op);
        store.update(
                "INSERT INTO revisions VALUES(?,?,?,?,?)",
                id,
                rev,
                store.head(),
                occurred,
                JSON.toJson(body));
        unindex(id);
        if (!op.equals("RETRACT")) {
            String searchable = get(body, "content");
            for (String k : List.of("speaker", "action_id"))
                if (body.has(k)) searchable += " " + get(body, k);
            if (body.has("assertion"))
                searchable += " " + get(object(body, "assertion"), "subject_id");
            store.update(
                    "INSERT INTO search(rowid,terms) SELECT rowid,? FROM records WHERE id=?",
                    String.join(" ", Terms.terms(searchable)),
                    id);
        }
    }
}
