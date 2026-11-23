package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * 权威账本只读查询引擎：服务于变更流翻页（{@code /v2/changes}）、精确原文读取（{@code /v2/read}）
 * 与分域受众召回（{@code /v2/recall}）。
 *
 * <p>职责与边界：
 * <ul>
 *   <li>严格区分三种读取目的：{@code HISTORY}（会话/跨会话授权历史）、{@code SELF_IDENTITY}（经确认的本人称呼）
 *       与 {@code SELF_HISTORY}（可信 QQ 私聊入站本人在本账号下的历史原话）。</li>
 *   <li>将受众过滤、有效时间过滤与显式替代判断尽量下推到候选截断（LIMIT）之前，避免越权或过期记录挤占 Top-K。</li>
 *   <li>所有读响应共用同一次事务内的 {@code stream_id} 与 {@code read_seq} 快照，供 Harness 登记证据和补查变更。</li>
 * </ul>
 */
final class Reader {
    private final Store store;
    /** 当前最新修订视图：关联 records 与其最新 revision 对应的正文。 */
    static final String LATEST =
            """
            SELECT r.id,r.conversation,r.kind,r.status,v.revision,v.occurred,v.body FROM records r
            JOIN revisions v ON v.record_id=r.id AND v.revision=r.revision """;
    /**
     * 历史知识水位视图（{@code as_of_known_seq}）：取每条记录在指定 seq 及之前的最高已提交修订，
     * 使后续消息编辑不会改写“在历史某一时刻系统当时所知的内容”。
     */
    private static final String AT_SEQ =
            """
SELECT r.id,r.conversation,r.kind,r.status,v.revision,v.occurred,v.body FROM records r
JOIN revisions v ON v.record_id=r.id AND v.seq=(
  SELECT max(prev.seq) FROM revisions prev WHERE prev.record_id=r.id AND prev.seq<=?) """;

    private final CellSearch.Mode mode;

    Reader(Store store) {
        this(store, CellSearch.Mode.LEXICAL);
    }

    Reader(Store store, CellSearch.Mode mode) {
        this.store = store;
        this.mode = mode;
    }

    /**
     * {@code GET /v2/changes} 入口：按单调递增序号分页返回 {@code (after, through]} 区间内的变更信封（不含正文）。
     * 首次省略 {@code through} 时冻结为当前 {@code head_seq}；遇到换流（stream_id 不匹配）或游标越界时返回 409，
     * 提示 Harness 停止推进消费水位并按新快照重核活跃依赖。
     */
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

    /**
     * 沿 {@code sources} 递归展开至最底层叶子原始记录引用（无进一步上游依赖的一手记录）；
     * 若当前记录自身即为叶子记录，则返回其自身引用。
     */
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

    /** 计算派生项或带引用回复的有效期上界：沿版本绑定的依赖链继承最早的 {@code valid_until}。 */
    String dependencyExpiry(String id) throws SQLException {
        var rows =
                store.rows(
                        """
SELECT min(json_extract(v.body,'$.assertion.valid_until')) expiry
FROM evidence_dependencies d JOIN revisions v ON v.record_id=d.source AND v.revision=d.revision
WHERE d.derived=?""",
                        id);
        var value = rows.getFirst().get("expiry");
        return value.isJsonNull() ? null : value.getAsString();
    }

    /**
     * 将数据库记录行投影为面向 Harness 的结构化证据项（L1 摘要或 L2 窗口）。
     *
     * <p>关键业务处理：
     * <ul>
     *   <li>非 ONLINE 记录只返回 {@code ref} 与状态（如 RETRACTED/SOURCE_CHANGED），绝不暴露旧正文。</li>
     *   <li>{@code use=READ} 为只读查验，移除 {@code citation_scope} 以防调用方误当成可对外引用依据。</li>
     *   <li>跨会话或依赖跨会话来源的条目打上 {@code grant_dependent=true}，方便 Harness 在撤权时识别并刷新上下文。</li>
     *   <li>跨会话展示时，将本地视角的“用户”与“当前 QQ 私聊”去语境化为“原发言者”与“QQ 私聊”，并隐去当前受众无权查看的 {@code supersedes_ref}。</li>
     * </ul>
     */
    JsonObject item(
            Access ctx,
            JsonObject row,
            String purpose,
            String use,
            int start,
            int length,
            String view,
            String at)
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
                                        "SELECT 1 FROM evidence_dependencies d JOIN records p ON"
                                                + " p.id=d.source WHERE d.derived=? AND"
                                                + " p.conversation<>? LIMIT 1",
                                        get(row, "id"),
                                        ctx.scope())
                                .isEmpty())) out.addProperty("grant_dependent", true);
        if (purpose.equals("SELF_IDENTITY")) {
            value = get(object(body, "identity"), "name");
        } else if (purpose.equals("SELF_HISTORY")) {
            out.addProperty(
                    "source_venue",
                    get(object(body, "channel"), "type").equals("qq_private") ? "QQ 私聊" : "QQ 群聊");
            out.addProperty("speaker", "当前账号本人");
        } else {
            if (body.has("source_refs")) {
                String expiry = dependencyExpiry(get(row, "id"));
                if (expiry != null) {
                    out.addProperty("evidence_valid_until", expiry);
                    out.addProperty("evidence_active", expiry.compareTo(at) > 0);
                }
            }
            var dates = CellSearch.dates(body);
            if (!dates.isEmpty()) out.add("resolved_dates", JSON.toJsonTree(dates));
            for (String key : List.of("speaker", "source_venue", "action_id", "assertion", "topic"))
                if (body.has(key)) out.add(key, body.get(key));
            // 跨会话引用他人原话时去除原会话第一人称语境，避免目标受众把“用户/当前 QQ 私聊”误解为自己。
            if (!get(row, "conversation").equals(ctx.scope()) && body.has("channel")) {
                if (get(object(body, "channel"), "type").equals("qq_group_notice"))
                    out.addProperty("member_notice", true);
                if (out.has("speaker") && "用户".equals(get(out, "speaker")))
                    out.addProperty("speaker", "原发言者");
                if (out.has("source_venue") && "当前 QQ 私聊".equals(get(out, "source_venue")))
                    out.addProperty("source_venue", "QQ 私聊");
            }
            // 若断言的 supersedes_ref 指向当前受众无权访问或已失效的跨会话前序记录，则裁剪该引用避免信息泄露。
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

    /**
     * 按读取目的生成 SQL 可见范围条件：
     * <ul>
     *   <li>{@code SELF_HISTORY}：要求可信 QQ 私聊身份，仅返回同一机器人账号下由该本人发出的非 sensitive 原话。</li>
     *   <li>{@code SELF_IDENTITY}：仅返回与该 QQ 账号匹配的经审核身份观测记录。</li>
     *   <li>{@code HISTORY}：委托 {@link Visibility#allowed} 应用同会话与跨会话版本授权策略。</li>
     * </ul>
     */
    private String allowed(Access ctx, String purpose, String use, List<Object> args) {
        if (purpose.equals("SELF_HISTORY")) {
            ctx.requireQq();
            args.add(ctx.account());
            args.add(ctx.peer());
            return """
r.kind='observation' AND json_extract(v.body,'$.privacy') IN ('public','private')
AND json_extract(v.body,'$.channel.type') IN ('qq_private','qq_group','qq_group_notice')
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

    /** 解析读取用途 {@code use}：仅 HISTORY 允许指定 READ/CITE，省略时回落为首版同会话 LOCAL 语义。 */
    private static String use(JsonObject req, String purpose) {
        if (!req.has("use")) return "LOCAL";
        if (!purpose.equals("HISTORY")) throw invalid("use");
        return one(req, "use", "READ", "CITE");
    }

    /** 按受众权限与用途查询某条记录的当前最新版本，不可见或不存在时统一返回 null。 */
    JsonObject currentFor(Access ctx, String use, String id) throws SQLException {
        var args = new ArrayList<Object>();
        String policy = Visibility.allowed(ctx, use, args);
        args.add(id);
        var rows = store.rows(LATEST + " WHERE " + policy + " AND r.id=?", args.toArray());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * {@code POST /v2/read} 入口（L2 精确原文读取与行动前依据复核）。
     *
     * <p>支持批量指定 {@code record_id}（可选精确 {@code revision}）和码点窗口 {@code [start, start+length)}；
     * 不可见与不存在统一返回 {@code status=NOT_FOUND} 以防探测存在性；对包含断言的在线项额外计算 {@code assertion_active}。
     */
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
            var result =
                    item(
                            ctx,
                            rows.getFirst(),
                            purpose,
                            use,
                            start,
                            length,
                            "L2",
                            utc(Instant.now()));
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

    /**
     * 在双时态坐标（业务有效时点 {@code at} + 系统已知水位 {@code known} + 原发言时间上界 {@code before}）上
     * 解析同一 {@code (subject_id, predicate)} 的现行有效断言列表。
     *
     * <p>业务规则：
     * <ul>
     *   <li>通过 {@link #AT_SEQ} 读取历史修订切片，避免后续正文编辑覆盖当时已存在的旧断言。</li>
     *   <li>业务时间采用左闭右开 {@code [effective_at, valid_until)}。</li>
     *   <li>凡在 {@code at} 及之前生效的断言所声明的 {@code supersedes_ref}，均永久终止被替代的旧版本；
     *       即使新断言自身随后过期，旧断言也不会自动复活。</li>
     * </ul>
     */
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
            if (!a.has("valid_until") || time(a, "valid_until").compareTo(at) > 0)
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

    /** 判断单条记录（在当前受众 CITE 权限与给定时点 {@code at} 下）是否仍为有效现行陈述；无断言记录恒为 true。 */
    boolean currentClaim(Access ctx, JsonObject row, String at) throws SQLException {
        var body = parse(get(row, "body"));
        if (!body.has("assertion")) return true;
        var a = object(body, "assertion");
        return timeline(
                        ctx,
                        "CITE",
                        get(a, "subject_id"),
                        get(a, "predicate"),
                        at,
                        store.head(),
                        "9999-12-31T23:59:59.999999999Z")
                .stream()
                .anyMatch(
                        r ->
                                get(r, "id").equals(get(row, "id"))
                                        && num(r, "revision") == num(row, "revision"));
    }

    /**
     * {@code POST /v2/recall} 入口：执行结构化时间线查询（指定 {@code subject_id}）或分域文本/事件检索，
     * 并输出逐条证据项及整包证据评估 {@code assessment}。
     *
     * <p>评估状态优先级：{@code CONFLICT}（多个不同称呼或同一事项存在多个竞争有效断言，SELF_IDENTITY 冲突时清空返回项）
     * &gt; {@code INCOMPLETE}（存在未匹配 {@code tool_result} 的 {@code tool_attempt}）
     * &gt; {@code NOT_FOUND} / {@code DIRECT}（含一手直接记录） / {@code INFERRED}（仅有摘要/印象）。
     * {@code absence_confirmed} 恒为 false，召回为空仅代表未查到而非证明不存在。
     */
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
            rows = search(ctx, purpose, query, req, known, before, at, limit);
        }
        var items = new JsonArray();
        boolean direct = false;
        var attempts = new HashSet<String>();
        var completed = new HashSet<String>();
        var competing = new HashMap<String, Set<String>>();
        var timelines = new HashMap<List<String>, List<JsonObject>>();
        var identities = new HashSet<String>();
        for (var row : rows) {
            JsonObject result = item(ctx, row, purpose, use, 0, 2048, "L1", at);
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

    /**
     * 执行分域检索的核心 SQL 构造与模式分发：
     * <ul>
     *   <li>对 {@code HISTORY} 查询，先在 SQL 层排除已过期的断言、来源链含过期断言的派生项；若指定 {@code as_of_valid}，
     *       同步下推未来生效与已被同受众可见后续记录替代的断言过滤，确保无效项在 LIMIT 截断之前即被剔除。</li>
     *   <li>非 {@code LEXICAL} 模式的普通历史查询交由 {@link CellSearch#rank} 执行事件单元扩展与排序。</li>
     *   <li>纯词法实时查询走基础 FTS5 {@code search}，优先排断言与工具记录；历史水位或非词法查询则扫描视图并按词项交集过滤。</li>
     * </ul>
     */
    private List<JsonObject> search(
            Access ctx,
            String purpose,
            String query,
            JsonObject req,
            long known,
            String before,
            String at,
            int limit)
            throws SQLException {
        boolean historic = req.has("as_of_known_seq") || req.has("as_of_valid");
        var args = new ArrayList<Object>();
        boolean expanded = mode != CellSearch.Mode.LEXICAL && purpose.equals("HISTORY") && !req.has("action_id");
        boolean past = req.has("as_of_known_seq") && known < store.head();
        boolean slice = expanded ? past : historic;
        String from = slice ? AT_SEQ : LATEST;
        if (slice) args.add(known);
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
        if (purpose.equals("HISTORY") && !req.has("action_id")) {
            where +=
                    " AND (json_extract(v.body,'$.assertion.valid_until') IS NULL OR"
                            + " json_extract(v.body,'$.assertion.valid_until')>?)";
            args.add(at);
            where +=
                    """
AND (json_type(v.body,'$.source_refs') IS NULL OR NOT EXISTS (
  SELECT 1 FROM evidence_dependencies d JOIN revisions dep
  ON dep.record_id=d.source AND dep.revision=d.revision
  WHERE d.derived=r.id AND json_extract(dep.body,'$.assertion.valid_until')<=?)) """;
            args.add(at);
            if (req.has("as_of_valid")) {
                // 在候选数量截断（LIMIT）前即排除尚未生效和已被显式替代的断言；
                // 替代项自身也必须在请求的知识水位与当前受众权限下可见，才算构成本受众视角下的有效替代。
                String successor = past ? AT_SEQ : LATEST;
                args.add(at);
                if (past) args.add(known);
                args.add(at);
                args.add(before);
                String readable = allowed(ctx, purpose, use(req, purpose), args);
                where +=
                        " AND (json_type(v.body,'$.assertion') IS NULL OR EXISTS (SELECT 1 FROM"
                            + " claim_times active WHERE active.record_id=r.id AND"
                            + " active.revision=v.revision AND active.effective_utc<=? AND NOT"
                            + " EXISTS ("
                                + successor
                                + " JOIN claim_times c ON c.record_id=r.id AND"
                                + " c.revision=v.revision WHERE c.previous_id=active.record_id AND"
                                + " c.previous_revision=active.revision AND c.effective_utc<=? AND"
                                + " v.occurred<? AND r.status='ONLINE' AND "
                                + readable
                                + ")))";
            }
            if (expanded) {
                return new CellSearch(store)
                        .rank(
                                from,
                                where,
                                args,
                                past,
                                query,
                                mode,
                                get(req, "profile").equals("THINKING"),
                                limit);
            }
        }
        if (lexical && !historic) {
            String expression = Terms.expression(query);
            if (expression.isEmpty()) return List.of();
            // 受众范围与目的过滤必须位于带排序的 SELECT 内部（LIMIT 之前），防止其他会话大量相似文本挤掉本会话合法命中。
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
