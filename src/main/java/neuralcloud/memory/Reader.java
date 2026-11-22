package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

final class Reader {
    private final Store store;
    private static final String LATEST =
            """
            SELECT r.id,r.conversation,r.kind,r.status,v.revision,v.occurred,v.body FROM records r
            JOIN revisions v ON v.record_id=r.id AND v.revision=r.revision """;
    private static final String AT_SEQ =
            """
SELECT r.id,r.conversation,r.kind,r.status,v.revision,v.occurred,v.body FROM records r
JOIN revisions v ON v.record_id=r.id AND v.seq=(
  SELECT max(prev.seq) FROM revisions prev WHERE prev.record_id=r.id AND prev.seq<=?) """;

    Reader(Store store) {
        this.store = store;
    }

    JsonObject changes(JsonObject q) throws SQLException {
        keys(q, "stream_id after through limit");
        if (!store.stream.equals(text(q, "stream_id", 128))) throw conflict("STREAM_CHANGED");
        long head = store.head(), after = number(q, "after", 0, Long.MAX_VALUE);
        long through = q.has("through") ? number(q, "through", 0, Long.MAX_VALUE) : head;
        if (after > through || through > head) throw conflict("CURSOR_OUT_OF_RANGE");
        long limit = q.has("limit") ? number(q, "limit", 1, 500) : 100;
        var rows =
                store.rows(
                        "SELECT seq,op,record_id,revision FROM changes WHERE seq>? AND seq<=? ORDER"
                            + " BY seq LIMIT ?",
                        after,
                        through,
                        limit);
        long next = rows.size() == limit ? num(rows.getLast(), "seq") : through;
        return obj(
                "stream_id", store.stream, "items", rows, "next_after", next, "through", through);
    }

    private List<JsonObject> refs(JsonObject row) throws SQLException {
        var sources =
                store.rows(
                        """
WITH RECURSIVE deps(id,rev) AS (SELECT source,revision FROM sources WHERE derived=?
UNION SELECT s.source,s.revision FROM sources s JOIN deps d ON s.derived=d.id)
SELECT id AS record_id,rev AS revision FROM deps WHERE NOT EXISTS(SELECT 1 FROM sources WHERE derived=id)
ORDER BY id""",
                        get(row, "id"));
        return sources.isEmpty() ? List.of(ref(get(row, "id"), num(row, "revision"))) : sources;
    }

    private JsonObject item(
            Access ctx,
            JsonObject row,
            String purpose,
            String use,
            int start,
            int length,
            String view)
            throws SQLException {
        var reference = ref(get(row, "id"), num(row, "revision"));
        if (!"ONLINE".equals(get(row, "status")))
            return obj("ref", reference, "status", get(row, "status"));
        var body = parse(get(row, "body"));
        String kind = get(row, "kind"), value = get(body, "content");
        String basis =
                switch (kind) {
                    case "impression", "summary" -> "inferred";
                    default -> "direct";
                };
        var out =
                obj(
                        "ref",
                        reference,
                        "source_refs",
                        refs(row),
                        "status",
                        "ONLINE",
                        "kind",
                        kind,
                        "basis",
                        basis,
                        "view",
                        view,
                        "occurred_at",
                        get(body, "occurred_at"),
                        "citation_scope",
                        ctx.scope());
        if (use.equals("READ")) {
            out.remove("citation_scope");
            out.addProperty("use", "READ");
        }
        if (purpose.equals("HISTORY")
                && (!get(row, "conversation").equals(ctx.scope())
                        || !store.rows(
                                        "SELECT 1 FROM evidence_dependencies d JOIN records p ON p.id=d.source WHERE d.derived=? AND p.conversation<>? LIMIT 1",
                                        get(row, "id"),
                                        ctx.scope())
                                .isEmpty())) out.addProperty("grant_dependent", true);
        if (purpose.equals("SELF_IDENTITY")) {
            value = get(object(body, "identity"), "name");
        } else if (purpose.equals("SELF_HISTORY")) {
            out.addProperty(
                    "source_venue",
                    get(object(body, "channel"), "type").equals("qq_group") ? "QQ 群聊" : "QQ 私聊");
            out.addProperty("speaker", "当前账号本人");
        } else {
            for (String key : List.of("speaker", "source_venue", "action_id", "assertion"))
                if (body.has(key)) out.add(key, body.get(key));
            if (!get(row, "conversation").equals(ctx.scope()) && body.has("channel")) {
                if (out.has("speaker") && "用户".equals(get(out, "speaker")))
                    out.addProperty("speaker", "原发言者");
                if (out.has("source_venue") && "当前 QQ 私聊".equals(get(out, "source_venue")))
                    out.addProperty("source_venue", "QQ 私聊");
            }
            if (out.has("assertion")
                    && out.getAsJsonObject("assertion").has("supersedes_ref")
                    && !get(row, "conversation").equals(ctx.scope())) {
                var assertion = out.getAsJsonObject("assertion");
                var previous = assertion.getAsJsonObject("supersedes_ref");
                var accessible = currentFor(ctx, use, get(previous, "record_id"));
                if (accessible == null
                        || !get(accessible, "status").equals("ONLINE")
                        || num(accessible, "revision") != num(previous, "revision"))
                    assertion.remove("supersedes_ref");
            }
        }
        out.addProperty("text", excerpt(value, start, length));
        return out;
    }

    private String allowed(Access ctx, String purpose, String use, List<Object> args) {
        if (purpose.equals("SELF_HISTORY")) {
            ctx.requireQq();
            args.add(ctx.account());
            args.add(ctx.peer());
            return """
r.kind='observation' AND json_extract(v.body,'$.privacy') IN ('public','private')
AND json_extract(v.body,'$.channel.type') IN ('qq_private','qq_group')
AND json_extract(v.body,'$.channel.account_id')=? AND json_extract(v.body,'$.channel.sender_id')=? """;
        }
        if (purpose.equals("SELF_IDENTITY")) {
            if (ctx.peer() == null) return "0";
            args.add(ctx.peer());
            return "r.kind='observation' AND json_extract(v.body,'$.identity.account_id')=? AND"
                       + " json_extract(v.body,'$.privacy') IN ('private','public')";
        }
        return Visibility.allowed(ctx, use, args);
    }

    private static String use(JsonObject req, String purpose) {
        if (!req.has("use")) return "LOCAL";
        if (!purpose.equals("HISTORY")) throw invalid("use");
        return one(req, "use", "READ", "CITE");
    }

    JsonObject currentFor(Access ctx, String use, String id) throws SQLException {
        var args = new ArrayList<Object>();
        String policy = Visibility.allowed(ctx, use, args);
        args.add(id);
        var rows = store.rows(LATEST + " WHERE " + policy + " AND r.id=?", args.toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    JsonObject read(JsonObject req) throws SQLException {
        keys(req, "context targets start length kind use");
        var ctx = Access.from(store, req);
        String purpose =
                req.has("kind")
                        ? one(req, "kind", "HISTORY", "SELF_IDENTITY", "SELF_HISTORY")
                        : "HISTORY";
        String use = use(req, purpose);
        int start = req.has("start") ? (int) number(req, "start", 0, Integer.MAX_VALUE) : 0;
        int length = req.has("length") ? (int) number(req, "length", 1, 16384) : 4096;
        if (purpose.equals("SELF_HISTORY")) length = Math.min(length, 2048);
        var items = new JsonArray();
        for (var target : array(req, "targets", 1, 128)) {
            if (!target.isJsonObject()) throw invalid("targets");
            var t = target.getAsJsonObject();
            keys(t, "record_id revision");
            String id = id(t, "record_id");
            var args = new ArrayList<Object>();
            String join = LATEST;
            if (t.has("revision")) {
                long revision = number(t, "revision", 1, Long.MAX_VALUE);
                join =
                        purpose.equals("HISTORY")
                                ? LATEST.replace("v.revision=r.revision", "v.revision=?")
                                : LATEST.replace(
                                        "v.revision=r.revision",
                                        "v.revision=r.revision AND v.revision=?");
                args.add(revision);
            }
            String policy = allowed(ctx, purpose, use, args);
            args.add(id);
            var rows = store.rows(join + " WHERE " + policy + " AND r.id=?", args.toArray());
            if (rows.isEmpty()) {
                items.add(obj("status", "NOT_FOUND", "record_id", id));
                continue;
            }
            var result = item(ctx, rows.getFirst(), purpose, use, start, length, "L2");
            if (result.has("assertion")) {
                var a = object(result, "assertion");
                var active =
                        timeline(
                                ctx,
                                use,
                                get(a, "subject_id"),
                                get(a, "predicate"),
                                utc(Instant.now()),
                                store.head(),
                                "9999-12-31T23:59:59.999999999Z");
                result.addProperty(
                        "assertion_active",
                        active.stream()
                                .anyMatch(
                                        r ->
                                                get(r, "id").equals(id)
                                                        && num(r, "revision")
                                                                == num(
                                                                        object(result, "ref"),
                                                                        "revision")));
            }
            items.add(result);
        }
        var out = store.snapshot();
        out.add("items", items);
        return out;
    }

    // Resolve explicit supersession at both the business instant and the recorded sequence.
    // The SQL revision slice preserves the old assertion after a later message edit.
    private List<JsonObject> timeline(
            Access ctx,
            String use,
            String subject,
            String predicate,
            String at,
            long known,
            String before)
            throws SQLException {
        var args = new ArrayList<Object>();
        args.add(known);
        String scope = allowed(ctx, "HISTORY", use, args);
        Collections.addAll(args, before, subject, predicate);
        var all =
                store.rows(
                        AT_SEQ
                                + " WHERE "
                                + scope
                                + """
AND r.status='ONLINE' AND v.occurred<?
AND json_extract(v.body,'$.assertion.subject_id')=? AND json_extract(v.body,'$.assertion.predicate')=?
ORDER BY v.seq DESC""",
                        args.toArray());
        record Revision(String id, long version) {}
        var eligible = new ArrayList<JsonObject>();
        var superseded = new HashSet<Revision>();
        for (var row : all) {
            var a = object(parse(get(row, "body")), "assertion");
            if (time(a, "effective_at").compareTo(at) > 0) continue;
            eligible.add(row);
            if (a.has("supersedes_ref")) {
                var ref = object(a, "supersedes_ref");
                superseded.add(new Revision(get(ref, "record_id"), num(ref, "revision")));
            }
        }
        return eligible.stream()
                .filter(r -> !superseded.contains(new Revision(get(r, "id"), num(r, "revision"))))
                .toList();
    }

    JsonObject recall(JsonObject req) throws SQLException {
        keys(
                req,
                "context kind query profile limit use subject_id predicate action_id as_of_valid"
                        + " as_of_known_seq before");
        var ctx = Access.from(store, req);
        String purpose = one(req, "kind", "HISTORY", "SELF_HISTORY", "SELF_IDENTITY");
        String use = use(req, purpose);
        one(req, "profile", "QUICK", "THINKING");
        String query = str(req, "query", 256);
        if (purpose.equals("SELF_IDENTITY") ? !query.isEmpty() : query.isBlank())
            throw invalid("query");
        int limit = (int) number(req, "limit", 1, purpose.equals("SELF_HISTORY") ? 16 : 50);
        if (!purpose.equals("HISTORY")) {
            for (String key :
                    List.of(
                            "subject_id",
                            "predicate",
                            "action_id",
                            "as_of_valid",
                            "as_of_known_seq")) if (req.has(key)) throw invalid(key);
        }
        String before = req.has("before") ? time(req, "before") : "9999-12-31T23:59:59.999999999Z";
        if (purpose.equals("SELF_HISTORY") && !req.has("before")) throw invalid("before");
        String at = req.has("as_of_valid") ? time(req, "as_of_valid") : utc(Instant.now());
        long head = store.head(),
                known = req.has("as_of_known_seq") ? number(req, "as_of_known_seq", 0, head) : head;
        List<JsonObject> rows;
        if (req.has("subject_id")) {
            String predicate = req.has("predicate") ? text(req, "predicate", 128) : "scheduled_for";
            rows = timeline(ctx, use, text(req, "subject_id", 256), predicate, at, known, before);
        } else {
            if (req.has("predicate")) throw invalid("predicate");
            rows = search(ctx, purpose, query, req, known, before, limit);
        }
        var items = new JsonArray();
        boolean direct = false;
        var attempts = new HashSet<String>();
        var completed = new HashSet<String>();
        var competing = new HashMap<String, Set<String>>();
        var timelines = new HashMap<List<String>, List<JsonObject>>();
        var identities = new HashSet<String>();
        for (var row : rows) {
            JsonObject result = item(ctx, row, purpose, use, 0, 2048, "L1");
            if (purpose.equals("SELF_IDENTITY")) identities.add(get(result, "text"));
            if (result.has("assertion")) {
                var a = object(result, "assertion");
                var key = List.of(get(a, "subject_id"), get(a, "predicate"));
                var current = timelines.get(key);
                if (current == null) {
                    current = timeline(ctx, use, key.get(0), key.get(1), at, known, before);
                    timelines.put(key, current);
                }
                boolean active =
                        current.stream()
                                .anyMatch(
                                        r ->
                                                get(r, "id").equals(get(row, "id"))
                                                        && num(r, "revision")
                                                                == num(row, "revision"));
                result.addProperty("assertion_active", active);
                if (req.has("as_of_valid") && !active) continue;
                var values = competing.computeIfAbsent(key.toString(), ignored -> new HashSet<>());
                for (var r : current)
                    values.add(get(object(parse(get(r, "body")), "assertion"), "value"));
            }
            String kind = get(row, "kind");
            if (kind.equals("tool_attempt")) attempts.add(get(result, "action_id"));
            if (kind.equals("tool_result")) completed.add(get(result, "action_id"));
            if (get(result, "basis").equals("direct")) direct = true;
            if (items.size() < limit) items.add(result);
        }
        boolean conflicting =
                identities.size() > 1 || competing.values().stream().anyMatch(v -> v.size() > 1);
        if (purpose.equals("SELF_IDENTITY") && conflicting) items = new JsonArray();
        String state = items.isEmpty() ? "NOT_FOUND" : direct ? "DIRECT" : "INFERRED";
        if (conflicting) state = "CONFLICT";
        else if (!completed.containsAll(attempts)) state = "INCOMPLETE";
        var out = store.snapshot();
        out.add("items", items);
        out.add(
                "assessment",
                obj(
                        "state",
                        state,
                        "sufficient",
                        state.equals("DIRECT"),
                        "absence_confirmed",
                        false));
        return out;
    }

    private List<JsonObject> search(
            Access ctx,
            String purpose,
            String query,
            JsonObject req,
            long known,
            String before,
            int limit)
            throws SQLException {
        boolean historic = req.has("as_of_known_seq") || req.has("as_of_valid");
        var args = new ArrayList<Object>();
        String from = historic ? AT_SEQ : LATEST;
        if (historic) args.add(known);
        String where =
                " WHERE "
                        + allowed(ctx, purpose, use(req, purpose), args)
                        + " AND r.status='ONLINE' AND v.occurred<?";
        args.add(before);
        if (req.has("action_id")) {
            where += " AND json_extract(v.body,'$.action_id')=?";
            args.add(text(req, "action_id", 256));
        }
        boolean lexical = !purpose.equals("SELF_IDENTITY") && !req.has("action_id");
        if (lexical && !historic) {
            String expression = Terms.expression(query);
            if (expression.isEmpty()) return List.of();
            // Scope and purpose filter are inside the ranked SELECT, before LIMIT.
            args.add(expression);
            args.add(limit);
            String order =
                    " ORDER BY CASE WHEN json_type(v.body,'$.assertion')='object' OR r.kind IN"
                        + " ('tool_attempt','tool_result') THEN 0 ELSE 1 END,rank,v.seq DESC LIMIT"
                        + " ?";
            return store.rows(
                    from
                            + " JOIN search ON search.rowid=r.rowid "
                            + where
                            + " AND search MATCH ?"
                            + order,
                    args.toArray());
        }
        var rows = store.rows(from + where + " ORDER BY v.seq DESC", args.toArray());
        if (!lexical) return rows;
        var terms = Terms.terms(query);
        return rows.stream()
                .filter(
                        r ->
                                !Collections.disjoint(
                                        terms, Terms.terms(get(parse(get(r, "body")), "content"))))
                .toList();
    }
}
