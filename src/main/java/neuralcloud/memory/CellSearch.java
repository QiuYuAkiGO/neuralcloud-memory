package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.JsonObject;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * A cell links attributable utterances. Each returned row still has its own revision and author.
 */
final class CellSearch {
    enum Mode {
        LEXICAL,
        CELLS,
        TEMPORAL,
        HYBRID
    }

    private static final Pattern RELATIVE = Pattern.compile("大后天|大前天|前天|昨天|今天|明天|后天");
    private static final Map<String, Integer> DAYS =
            Map.of("大前天", -3, "前天", -2, "昨天", -1, "今天", 0, "明天", 1, "后天", 2, "大后天", 3);
    private final Store store;

    CellSearch(Store store) {
        this.store = store;
    }

    static List<String> dates(JsonObject body) {
        var result = new LinkedHashSet<String>();
        var match = RELATIVE.matcher(get(body, "content"));
        // 相对日期取原发言的日历日和偏移，不依赖服务器时区或查询当天。
        while (match.find())
            result.add(
                    OffsetDateTime.parse(get(body, "occurred_at"))
                            .toLocalDate()
                            .plusDays(DAYS.get(match.group()))
                            .toString());
        return List.copyOf(result);
    }

    private static String document(JsonObject body) {
        var parts = new ArrayList<String>();
        parts.add(get(body, "content"));
        for (String key : List.of("speaker", "topic", "action_id"))
            if (body.has(key)) parts.add(get(body, key));
        if (body.has("assertion")) parts.add(get(object(body, "assertion"), "subject_id"));
        return String.join(" ", parts);
    }

    static void index(Store store, String id, JsonObject body) throws SQLException {
        store.update(
                "INSERT INTO cell_search(rowid,words,dates) SELECT rowid,?,? FROM records WHERE"
                        + " id=?",
                String.join(" ", Terms.terms(document(body))),
                String.join(" ", Terms.terms(String.join(" ", dates(body)))),
                id);
    }

    private record Hit(JsonObject row, JsonObject body, Set<String> terms, double lexical) {
        String id() {
            return get(row, "id");
        }
    }

    private static double coverage(Set<String> terms, Set<String> query) {
        double score = 0;
        for (String term : query) if (terms.contains(term)) score += term.length() == 1 ? 0.25 : 1;
        return score;
    }

    private static final Comparator<Hit> ORDER =
            Comparator.comparingDouble(Hit::lexical)
                    .reversed()
                    .thenComparing(h -> get(h.row(), "occurred"), Comparator.reverseOrder())
                    .thenComparing(Hit::id);

    private static Hit hit(JsonObject row, Set<String> query, boolean temporal) {
        var body = parse(get(row, "body"));
        var tokens =
                Terms.terms(document(body) + (temporal ? " " + String.join(" ", dates(body)) : ""));
        return new Hit(row, body, tokens, coverage(tokens, query));
    }

    List<JsonObject> rank(
            String from,
            String where,
            List<Object> args,
            boolean historical,
            String query,
            Mode mode,
            boolean thinking,
            int limit)
            throws SQLException {
        var terms = Terms.terms(query);
        if (terms.isEmpty()) return List.of();
        boolean temporal = mode != Mode.CELLS;
        var seedArgs = new ArrayList<>(args);
        String sql = from + where;
        if (!historical) {
            seedArgs.add(
                    (temporal ? "" : "words : (")
                            + Terms.expression(query)
                            + (temporal ? "" : ")"));
            seedArgs.add(limit);
            sql =
                    from
                            + " JOIN cell_search ON cell_search.rowid=r.rowid "
                            + where
                            + " AND cell_search MATCH ? ORDER BY rank,v.seq DESC LIMIT ?";
        }
        // Historic FTS cannot use newer text. Stream that revision slice and retain at most K
        // seeds.
        var best = new PriorityQueue<Hit>(limit, ORDER.reversed());
        store.scan(
                sql,
                row -> {
                    var h = hit(row, terms, temporal);
                    if (h.lexical() == 0) return;
                    if (best.size() == limit) {
                        if (ORDER.compare(h, best.element()) >= 0) return;
                        best.remove();
                    }
                    best.add(h);
                },
                seedArgs.toArray());
        var seeds = best.stream().sorted(ORDER).toList();
        var candidates = new LinkedHashMap<String, Hit>();
        var scores = new HashMap<String, Double>();
        for (var h : seeds) {
            candidates.put(h.id(), h);
            scores.put(h.id(), h.lexical());
        }
        var frontier = seeds;
        // Only traverse an exact, same-conversation edge when both ends pass this query's ACL,
        // valid-time filter and knowledge watermark. A hidden intermediate cannot bridge the path.
        for (int depth = 1; depth <= 2 && !frontier.isEmpty(); depth++) {
            var previousScores = Map.copyOf(scores);
            var ids = new LinkedHashSet<String>();
            var parents = new LinkedHashSet<String>();
            for (var h : frontier) {
                ids.add(h.id());
                if (h.body().has("reply_to"))
                    parents.add(get(object(h.body(), "reply_to"), "record_id"));
            }
            var edgeArgs = new ArrayList<>(args);
            var condition = "json_extract(v.body,'$.reply_to.record_id') IN (" + marks(ids) + ")";
            edgeArgs.addAll(ids);
            if (!parents.isEmpty()) {
                condition += " OR r.id IN (" + marks(parents) + ")";
                edgeArgs.addAll(parents);
            }
            // Bound fan-out before materializing it. Ties use ledger order for repeatable replay.
            edgeArgs.add(limit);
            var neighbors =
                    store.rows(
                            from + where + " AND (" + condition + ") ORDER BY v.seq DESC LIMIT ?",
                            edgeArgs.toArray());
            var next = new ArrayList<Hit>();
            for (var row : neighbors) {
                var neighbor = hit(row, terms, temporal);
                boolean fresh = !candidates.containsKey(neighbor.id());
                for (var anchor : frontier) {
                    if (!get(row, "conversation").equals(get(anchor.row(), "conversation")))
                        continue;
                    if (!(replies(neighbor, anchor) || replies(anchor, neighbor))) continue;
                    double score =
                            neighbor.lexical()
                                    + previousScores.get(anchor.id()) * (depth == 1 ? 0.28 : 0.42);
                    candidates.putIfAbsent(neighbor.id(), neighbor);
                    scores.merge(neighbor.id(), score, Math::max);
                    if (fresh) {
                        next.add(neighbor);
                        fresh = false;
                    }
                }
            }
            frontier = next;
        }
        var ranked =
                candidates.values().stream()
                        .sorted(
                                Comparator.<Hit>comparingDouble(h -> scores.get(h.id()))
                                        .reversed()
                                        .thenComparing(ORDER))
                        .limit(limit)
                        .toList();
        if (mode == Mode.HYBRID) ranked = fuse(ranked, terms);
        if (thinking) {
            // Rerank only the existing pool by contiguous original-language matches. Keep ties
            // stable.
            var copy = new ArrayList<>(ranked);
            copy.sort(
                    Comparator.<Hit>comparingInt(
                                    h -> longest(query, excerpt(get(h.body(), "content"), 0, 2048)))
                            .reversed());
            ranked = copy;
        }
        return ranked.stream().map(Hit::row).toList();
    }

    private static String marks(Collection<String> ids) {
        return String.join(",", Collections.nCopies(ids.size(), "?"));
    }

    private static boolean replies(Hit child, Hit parent) {
        if (!child.body().has("reply_to")) return false;
        var edge = object(child.body(), "reply_to");
        return get(edge, "record_id").equals(parent.id())
                && num(edge, "revision") == num(parent.row(), "revision");
    }

    // Binary TF-IDF cosine over the bounded, authorized pool and reciprocal-rank fusion (k=60).
    private static List<Hit> fuse(List<Hit> ranked, Set<String> query) {
        var df = new HashMap<String, Integer>();
        for (var h : ranked) for (var term : h.terms()) df.merge(term, 1, Integer::sum);
        var cosine = new HashMap<String, Double>();
        for (var h : ranked) {
            double dot = 0, norm = 0;
            for (var term : h.terms()) {
                double idf = 1 + Math.log((1.0 + ranked.size()) / (1 + df.get(term)));
                norm += idf * idf;
                if (query.contains(term)) dot += idf * idf;
            }
            cosine.put(h.id(), dot / Math.sqrt(Math.max(norm, 1)));
        }
        var vectors = new ArrayList<>(ranked);
        vectors.sort(Comparator.<Hit>comparingDouble(h -> cosine.get(h.id())).reversed());
        var rrf = new HashMap<String, Double>();
        for (int i = 0; i < ranked.size(); i++) {
            rrf.merge(ranked.get(i).id(), 1.0 / (61 + i), Double::sum);
            rrf.merge(vectors.get(i).id(), 1.0 / (61 + i), Double::sum);
        }
        return ranked.stream()
                .sorted(Comparator.<Hit>comparingDouble(h -> rrf.get(h.id())).reversed())
                .toList();
    }

    private static int longest(String query, String text) {
        int[] matches = new int[query.length() + 1];
        int best = 0;
        for (int i = 0; i < text.length(); i++) {
            for (int j = query.length(); j > 0; j--) {
                matches[j] = text.charAt(i) == query.charAt(j - 1) ? matches[j - 1] + 1 : 0;
                best = Math.max(best, matches[j]);
            }
        }
        return best;
    }
}
