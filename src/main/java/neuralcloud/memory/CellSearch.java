package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.JsonObject;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 阶段 3 分域事件单元检索器（含回复链扩展、相对日期锚定、稀疏向量融合与精排）。
 *
 * <p>核心原则：事件单元通过 {@code reply_to} 连接可溯源的原话，但每条返回行仍保持自身独立的
 * 修订号、原作者与归属场合，不把多条发言合并为失去归属的伪造文本；且每一跳邻接扩展都必须在
 * 当前查询的受众 ACL、有效时间与知识水位内过滤，禁止不可见中间节点桥接路径。
 */
final class CellSearch {
    /** 检索消融模式：纯词法基线 / 事件单元两跳扩展 / 扩展+相对日期 / 混合稀疏向量 RRF 融合。 */
    enum Mode {
        LEXICAL,
        CELLS,
        TEMPORAL,
        HYBRID
    }

    /** 支持锚定为绝对日历日的中文相对日期表达（长词优先匹配，避免“大后天”先命中“后天”）。 */
    private static final Pattern RELATIVE = Pattern.compile("大后天|大前天|前天|昨天|今天|明天|后天");
    private static final Map<String, Integer> DAYS =
            Map.of("大前天", -3, "前天", -2, "昨天", -1, "今天", 0, "明天", 1, "后天", 2, "大后天", 3);
    private final Store store;

    CellSearch(Store store) {
        this.store = store;
    }

    /**
     * 解析正文中的中文相对日期并锚定为 YYYY-MM-DD 绝对日期列表（去重保序）。
     * 业务原因：必须以原发言 {@code occurred_at} 自带的日历日与原始时区为基准，而非服务器时区或查询当天，
     * 才能保证历史回顾时“明天”始终指向发言当时所指的那一天。
     */
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

    /** 拼装用于分域词表索引与内存覆盖打分的文本视图（正文 + 说话人/事项/动作/断言主题）。 */
    private static String document(JsonObject body) {
        var parts = new ArrayList<String>();
        parts.add(get(body, "content"));
        for (String key : List.of("speaker", "topic", "action_id"))
            if (body.has(key)) parts.add(get(body, key));
        if (body.has("assertion")) parts.add(get(object(body, "assertion"), "subject_id"));
        return String.join(" ", parts);
    }

    /** 将在线记录的词项与解析出的绝对日期写入 FTS5 {@code cell_search} 虚拟表。 */
    static void index(Store store, String id, JsonObject body) throws SQLException {
        store.update(
                "INSERT INTO cell_search(rowid,words,dates) SELECT rowid,?,? FROM records WHERE"
                        + " id=?",
                String.join(" ", Terms.terms(document(body))),
                String.join(" ", Terms.terms(String.join(" ", dates(body)))),
                id);
    }

    /** 检索候选命中项：缓存解析后的正文、词元集合与词法覆盖得分，避免后续扩展/融合重复解析。 */
    private record Hit(JsonObject row, JsonObject body, Set<String> terms, double lexical) {
        String id() {
            return get(row, "id");
        }
    }

    /** 计算查询词项在文档中的加权覆盖分：多字词/双字 bigram 计 1.0，单字计 0.25 以抑制高频单字噪音。 */
    private static double coverage(Set<String> terms, Set<String> query) {
        double score = 0;
        for (String term : query) if (terms.contains(term)) score += term.length() == 1 ? 0.25 : 1;
        return score;
    }

    /** 稳定排序规则：词法得分降序 -> 发生时间降序 -> 记录 ID 升序，保证同分回放结果可复现。 */
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

    /**
     * 在已带受众、有效时间与知识水位约束的 SQL 视图上执行检索排序：
     * <ol>
     *   <li>种子召回：最新水位走 {@code cell_search} FTS5（CELLS 仅搜 words 列，TEMPORAL/HYBRID 兼搜 dates 列）；
     *       历史水位流式扫描修订切片并用定长优先队列保留至多 {@code limit} 个种子。</li>
     *   <li>最多两跳回复链扩展：双向沿 {@code reply_to} 查找同会话且精确匹配版本号的上下游原话，
     *       借由衰减传播分拉回无字面重叠但属于同一对话脉络的指代证据。</li>
     *   <li>可选融合与精排：HYBRID 在有界候选池内执行稀疏 TF-IDF + RRF(k=60) 融合；
     *       THINKING 按原话与查询的最长公共子串长度稳定重排。</li>
     * </ol>
     */
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
        // 历史知识水位查询不能依赖仅索引最新正文的 FTS 表：改为流式扫描该修订切片，并用定长堆保留至多 K 个种子。
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
        // 仅当两端都通过本次查询的 ACL、有效时间过滤和知识水位时，才沿同会话的精确版本边扩展；
        // 不可见或已修订的中间节点不能桥接路径。
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
            // 在物化邻接记录前先以 limit 限制扇出；同级按账本提交序号降序，确保回放结果确定。
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
                    // 将锚点得分按跳数衰减叠加到邻接原话，使无字面词但直接承接指代的回复能进入前 K。
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
            // THINKING 精排只重排已有候选池，按原文连续子串匹配长度降序；Java List.sort 保持同分相对顺序稳定。
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

    /** 校验精确版本回复关系：child.reply_to 必须同时命中 parent 的记录 ID 与当时修订号。 */
    private static boolean replies(Hit child, Hit parent) {
        if (!child.body().has("reply_to")) return false;
        var edge = object(child.body(), "reply_to");
        return get(edge, "record_id").equals(parent.id())
                && num(edge, "revision") == num(parent.row(), "revision");
    }

    /**
     * 在有界且已授权的候选池内计算二元 TF-IDF 余弦相似度，并与现有词法/图排序做倒数排名融合（RRF，k=60）。
     */
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

    /** 动态规划计算查询与原文的最长连续公共子串长度，用于 THINKING 模式偏向完整原话匹配。 */
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
