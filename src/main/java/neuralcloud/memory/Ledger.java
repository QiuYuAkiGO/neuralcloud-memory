package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * 权威账本写入引擎：处理入账（ingest）、派生发布（candidates）、更正/撤回（corrections）
 * 以及跨会话授权/撤销（visibility-grants）的统一事务落盘与幂等回执。
 *
 * <p>核心不变量：
 * <ul>
 *   <li>以 {@code (source_instance, event_id)} 实现严格幂等：同键同内容返回原回执，同键异内容返回 409。</li>
 *   <li>记录主体、修订版本、来源依赖、当前全文索引、变更流与持久回执在单一 SQLite 事务内原子提交。</li>
 *   <li>原文编辑、撤回或印象替换会在同一事务中级联失效依赖其结果的下游派生项，并向被替代的旧记录补发变更事件。</li>
 * </ul>
 */
final class Ledger {
    /** 所有写命令共用的顶层必选字段前缀。 */
    private static final String COMMAND = "context source_instance event_id ";
    private final Store store;
    private final Reader reader;

    Ledger(Store store, Reader reader) {
        this.store = store;
        this.reader = reader;
    }

    /**
     * {@code GET /v2/receipts} 入口：按写入方实例与事件 ID 查询持久回执，供 Harness 在超时后对账恢复。
     * 仅允许与服务配置的 {@code sourceInstance} 一致的写入方查询，避免越权探测回执。
     */
    JsonObject receipt(String source, String event) throws SQLException {
        if (!source.equals(store.writer)) throw denied();
        var rows =
                store.rows(
                        "SELECT record_id,revision,seq,grant_id FROM receipts WHERE source=? AND"
                                + " event=?",
                        source,
                        event);
        if (rows.isEmpty()) return obj("status", "NOT_FOUND");
        var r = rows.getFirst();
        if (r.get("grant_id").isJsonNull()) r.remove("grant_id");
        r.addProperty("status", "DURABLE");
        return r;
    }

    /**
     * 统一写命令入口：先核验调用角色与写入方身份，再执行幂等去重，随后分发到授权决策、更正或新增路径，
     * 最后将最终提交水位 {@code store.head()} 与完整原始命令记入 {@code receipts} 表。
     */
    JsonObject write(String route, JsonObject cmd) throws SQLException {
        var ctx = Access.from(store, cmd);
        ctx.write();
        String source = text(cmd, "source_instance", 128), event = text(cmd, "event_id", 256);
        if (!source.equals(store.writer)) throw denied();
        // 幂等对账：解析历史命令 JSON 为树做结构比较（忽略键顺序），路由或载荷不同则拒绝重放。
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

    /**
     * 新增一条原始记录（{@code /v2/ingest}）或派生候选（{@code /v2/candidates}）。
     *
     * <p>关键业务校验：
     * <ul>
     *   <li>原始入账支持 observation/self_expression/tool_* /delivery_receipt，仅 self_expression 允许携带已送达的 source_refs。</li>
     *   <li>派生候选支持 summary/impression（impression 仅限 author=MASTER），逐一复核所有 source_refs 在当前受众下处于 ONLINE 且可 CITE。</li>
     *   <li>派生项继承敏感来源的等级和目录引用的用途。隔离模式下外部私聊受众无法据敏感来源创建候选。</li>
     *   <li>若携带 {@code replaces_impression}（仅 ORCHESTRATOR），验证新叶子均为当前活跃原始证据后，在同一事务中退役旧印象并失效其下游。</li>
     * </ul>
     */
    private String insert(Access ctx, JsonObject cmd, boolean candidate) throws SQLException {
        String base = "kind content occurred_at speaker source_venue privacy";
        if (candidate) keys(cmd, COMMAND + base + " source_refs author topic replaces_impression");
        else
            keys(
                    cmd,
                    COMMAND
                            + base
                            + " channel action_id assertion identity source_refs topic reply_to");
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
        if (cmd.has("topic")) {
            if (!candidate && !kind.equals("observation")) throw invalid("topic");
            text(cmd, "topic", 128);
        }
        if (cmd.has("reply_to")) {
            if (!kind.equals("observation")) throw invalid("reply_to");
            validateReply(ctx, object(cmd, "reply_to"), null);
        }
        if (candidate) {
            String author = text(cmd, "author", 128);
            if (kind.equals("impression") && !author.equals("MASTER")) throw denied();
        } else {
            boolean action =
                    Set.of("tool_attempt", "tool_result", "delivery_receipt").contains(kind);
            if (action) text(cmd, "action_id", 256);
            else if (cmd.has("action_id")) throw invalid("action_id");
            if (cmd.has("channel")) channel(ctx, object(cmd, "channel"), kind);
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
                keys(ref, "record_id revision purpose");
                String rid = id(ref, "record_id");
                long expected = number(ref, "revision", 1, Long.MAX_VALUE);
                String purpose = ref.has("purpose") ? one(ref, "purpose", "IDENTITY") : "HISTORY";
                JsonObject sourceBody;
                if (candidate) {
                    var row = reader.currentFor(ctx, purpose, "CITE", rid);
                    if (row == null
                            || !get(row, "status").equals("ONLINE")
                            || num(row, "revision") != expected) throw conflict("SOURCE_CHANGED");
                    sourceBody = parse(get(row, "body"));
                    // 修订印象时，新证据必须是直接的一手记录（非派生、无 source_refs）且其断言当前仍有效，防止用过时或二次转述覆盖印象。
                    if (cmd.has("replaces_impression")
                            && (!Set.of(
                                                    "observation",
                                                    "self_expression",
                                                    "tool_attempt",
                                                    "tool_result",
                                                    "delivery_receipt")
                                            .contains(get(row, "kind"))
                                    || !reader.currentClaim(ctx, row, utc(Instant.now()))
                                    || sourceBody.has("source_refs")))
                        throw conflict("SOURCE_CHANGED");
                } else {
                    // 渠道可以晚于撤权补记已送达事实。按送达时的确切修订继承资料用途与敏感级别。
                    var revision = store.rows("SELECT body FROM revisions WHERE record_id=? AND revision=?", rid, expected);
                    if (revision.isEmpty()) throw conflict("SOURCE_CHANGED");
                    sourceBody = parse(get(revision.getFirst(), "body"));
                    if (purpose.equals("IDENTITY") && !sourceBody.has("identity")) throw conflict("SOURCE_CHANGED");
                }
                inheritVisibility(ctx, body, sourceBody, purpose, candidate);
                store.update("INSERT OR IGNORE INTO sources VALUES(?,?,?)", id, rid, expected);
            }
        }
        if (cmd.has("replaces_impression")) {
            if (!kind.equals("impression") || !ctx.role().equals("ORCHESTRATOR")) throw denied();
            var old = object(cmd, "replaces_impression");
            keys(old, "record_id revision");
            String oldId = id(old, "record_id");
            var prior = current(ctx.scope(), oldId);
            long expected = number(old, "revision", 1, Long.MAX_VALUE);
            // 禁止循环依赖（新印象来源不得引用被替代的旧印象），且旧印象必须匹配版本并处于 ONLINE 或 SOURCE_CHANGED。
            if (prior == null
                    || !get(prior, "kind").equals("impression")
                    || !Set.of("ONLINE", "SOURCE_CHANGED").contains(get(prior, "status"))
                    || num(prior, "revision") != expected
                    || !store.rows(
                                    "SELECT 1 FROM evidence_dependencies WHERE derived=? AND"
                                            + " source=?",
                                    id,
                                    oldId)
                            .isEmpty()) throw conflict("SOURCE_CHANGED");
            // 新印象、其经核验的新叶子依赖以及旧印象下游摘要的退役在同一事务内原子提交。
            var visible = reader.currentFor(ctx, "CITE", oldId);
            if (visible == null) throw conflict("SOURCE_CHANGED");
            var oldBody = parse(get(prior, "body"));
            inheritVisibility(ctx, body, oldBody, "HISTORY", true);
            // 若旧印象仍在线且新印象未指定新标题，自动沿用旧 topic 保持事项卡归类连贯。
            if (get(prior, "status").equals("ONLINE") && !body.has("topic") && oldBody.has("topic"))
                body.add("topic", oldBody.get("topic"));
            retire(prior, oldId);
        }
        version(id, 1, occurred, body, candidate ? "CANDIDATE" : "INGEST");
        notifyPredecessors(body, null);
        return id;
    }

    /** 送达正文和各级派生材料继承来源限制，避免回切后从摘要或印象中重新读出目录称呼。 */
    private void inheritVisibility(Access ctx, JsonObject body, JsonObject source, String purpose, boolean candidate) {
        if ("sensitive".equals(get(source, "privacy"))) {
            if (candidate && reader.visibility.sessionIsolation() && ctx.externalAudience()) throw denied();
            body.addProperty("privacy", "sensitive");
        }
        if (purpose.equals("IDENTITY") || source.has("requires_identity_lookup"))
            body.addProperty("requires_identity_lookup", true);
    }

    /** 校验渠道元数据与会话的一致性。只有移动群的明确通知可以取得成员引用权。 */
    private void channel(Access ctx, JsonObject channel, String kind) {
        keys(channel, "platform account_id sender_id message_id conversation_id type");
        if (one(channel, "platform", "qq", "mistyai").equals("mistyai")) {
            one(channel, "type", "mobile_group_notice");
            String group = "group:mistyai:" + Access.mobileInstance(channel, "account_id");
            Access.mobileId(channel, "sender_id");
            Access.mobileId(channel, "message_id");
            if (!kind.equals("observation") || !group.equals(ctx.scope())
                    || !group.equals(text(channel, "conversation_id", 256))) throw denied();
            return;
        }
        String type = one(channel, "type", "qq_private", "qq_group", "qq_group_notice");
        if (type.equals("qq_group_notice") && !kind.equals("observation"))
            throw invalid("channel.type");
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

    /** 校验 {@code reply_to}：必须指向同一会话中当前 ONLINE 的其他记录的精确修订版本。 */
    private void validateReply(Access ctx, JsonObject parent, String ownId) throws SQLException {
        keys(parent, "record_id revision");
        String parentId = id(parent, "record_id");
        var row = current(ctx.scope(), parentId);
        long revision = number(parent, "revision", 1, Long.MAX_VALUE);
        if (parentId.equals(ownId)
                || row == null
                || !get(row, "status").equals("ONLINE")
                || num(row, "revision") != revision) throw conflict("SOURCE_CHANGED");
    }

    /**
     * 校验显式事项断言 {@code assertion}：检查有效区间（{@code valid_until > effective_at}）、
     * 会议排期时间格式，以及 {@code supersedes_ref} 所指前序记录必须同会话在线、同主题同谓词且生效时间不晚于本条。
     */
    private void validateAssertion(Access ctx, JsonObject a, String ownId) throws SQLException {
        keys(a, "subject_id predicate value effective_at supersedes_ref valid_until");
        String subject = text(a, "subject_id", 256), predicate = text(a, "predicate", 128);
        text(a, "value", 2048);
        String effective = time(a, "effective_at");
        if (a.has("valid_until") && time(a, "valid_until").compareTo(effective) <= 0)
            throw invalid("valid_until");
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

    /**
     * {@code POST /v2/corrections} 入口：按乐观锁（{@code expected_revision}）执行正文替换（REPLACE）或撤回（RETRACT）。
     *
     * <p>边界与副作用：
     * <ul>
     *   <li>仅原写入主体或 ORCHESTRATOR 可修订；版本号不匹配返回 {@code REVISION_CONFLICT}，非 ONLINE 返回 {@code SOURCE_CHANGED}。</li>
     *   <li>无论 REPLACE 还是 RETRACT，均在同一事务内令绑定旧版本的活跃跨会话授权失效（置为 SOURCE_CHANGED），
     *       并级联使所有下游在线派生记录转为 SOURCE_CHANGED。</li>
     *   <li>REPLACE 时 {@code topic}/{@code reply_to} 省略则保留原值、传 null 则清除、传新值则覆盖；
     *       {@code assertion} 与 {@code identity} 默认清除，需由调用方按新正文显式重提。</li>
     * </ul>
     */
    private String correct(Access ctx, JsonObject cmd) throws SQLException {
        keys(
                cmd,
                COMMAND
                        + "record_id expected_revision operation reason content assertion topic"
                        + " reply_to channel");
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
        // 群通知的每次替换或撤回来自同一作者的新群消息；保留各版实际消息 ID 供审计。
        var priorChannel = previousBody.getAsJsonObject("channel");
        boolean notice =
                priorChannel != null
                        && Set.of("mobile_group_notice", "qq_group_notice")
                                .contains(get(priorChannel, "type"));
        if (notice != cmd.has("channel")) throw invalid("channel");
        if (notice) {
            var next = object(cmd, "channel");
            channel(ctx, next, get(old, "kind"));
            if (!get(priorChannel, "type").equals(get(next, "type"))
                    || !get(priorChannel, "account_id").equals(get(next, "account_id"))
                    || !get(priorChannel, "sender_id").equals(get(next, "sender_id")))
                throw denied();
            if (get(priorChannel, "message_id").equals(get(next, "message_id"))) throw invalid("message_id");
            body.add("channel", next);
        }
        body.remove("assertion");
        body.remove("identity");
        if (op.equals("REPLACE")) {
            body.addProperty("content", text(cmd, "content", 32768));
            for (String key : List.of("topic", "reply_to")) {
                if (!cmd.has(key)) continue;
                String kind = get(old, "kind");
                if (!kind.equals("observation")
                        && !(key.equals("topic") && Set.of("impression", "summary").contains(kind)))
                    throw invalid(key);
                if (cmd.get(key).isJsonNull()) {
                    body.remove(key);
                    continue;
                }
                if (key.equals("topic")) text(cmd, key, 128);
                else validateReply(ctx, object(cmd, key), id);
                body.add(key, cmd.get(key));
            }
            if (cmd.has("assertion")) {
                if (!get(old, "kind").equals("observation")) throw invalid("assertion");
                validateAssertion(ctx, object(cmd, "assertion"), id);
                body.add("assertion", cmd.get("assertion"));
            }
        } else {
            if (cmd.has("content")
                    || cmd.has("assertion")
                    || cmd.has("topic")
                    || cmd.has("reply_to")) throw invalid("operation");
            body.addProperty("content", "");
        }
        store.update(
                "UPDATE records SET revision=?,status=? WHERE id=?",
                expected + 1,
                op.equals("RETRACT") ? "RETRACTED" : "ONLINE",
                id);
        // 授权精确绑定原修订版本：一旦原文被编辑或撤回，既有活跃授权自动失效，须由原作者对新版本重新授权。
        store.update(
                "UPDATE visibility_grants SET status='SOURCE_CHANGED' WHERE record_id=? AND"
                        + " status='ACTIVE'",
                id);
        invalidateDescendants(id);
        version(id, expected + 1, get(old, "occurred"), body, op);
        notifyPredecessors(previousBody, body);
        return id;
    }

    /** 将被新印象替换的旧印象原子退役（版本递增、正文置空、状态转为 RETRACTED）并级联失效其下游派生。 */
    private void retire(JsonObject old, String id) throws SQLException {
        long revision = num(old, "revision") + 1;
        var body = parse(get(old, "body"));
        body.addProperty("content", "");
        store.update("UPDATE records SET status='RETRACTED',revision=? WHERE id=?", revision, id);
        invalidateDescendants(id);
        version(id, revision, get(old, "occurred"), body, "RETRACT");
    }

    /**
     * 在同一事务内递归查找所有直接/间接依赖 {@code id} 的在线派生记录，将其置为 {@code SOURCE_CHANGED}、
     * 移出全文索引并追加变更事件（派生项自身的 revision 保持不变，以便消费者识别“版本未变但来源已失效”）。
     */
    private void invalidateDescendants(String id) throws SQLException {
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
    }

    /**
     * 为涉及显式替代（{@code supersedes_ref}）的前序记录追加 {@code ASSERTION_UPDATED} 变更。
     * 业务原因：新通知不会撤销或修改旧通知的原话，但会改变该事项在旧通知之后的当前有效性；
     * 曾只读过旧通知的 Worker 依赖该变更事件触发下一轮重读与状态刷新。
     */
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

    /** 查询指定会话下某记录的当前主表与最新修订正文，不存在时返回 null。 */
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

    /** 向全局单调递增变更流 {@code changes} 追加一条事件记录（不存正文，仅存操作类型与目标版本）。 */
    private void change(String id, long rev, String op) throws SQLException {
        store.update(
                "INSERT INTO changes(op,record_id,revision,recorded_at) VALUES(?,?,?,?)",
                op,
                id,
                rev,
                utc(Instant.now()));
    }

    /** 从基础全文表 {@code search} 和分域词表 {@code cell_search} 中移除记录索引。 */
    private void unindex(String id) throws SQLException {
        store.update(
                "DELETE FROM cell_search WHERE rowid=(SELECT rowid FROM records WHERE id=?)", id);
        store.update("DELETE FROM search WHERE rowid=(SELECT rowid FROM records WHERE id=?)", id);
    }

    /**
     * 落盘新修订版本：归一化断言到期时间、写入变更流与 {@code revisions} 表、更新时态索引 {@code claim_times}，
     * 并对非撤回版本重建基础 FTS 与分域 FTS 索引（撤回操作只清空旧索引）。
     */
    private void version(String id, long rev, String occurred, JsonObject body, String op)
            throws SQLException {
        if (body.has("assertion") && object(body, "assertion").has("valid_until")) {
            var assertion = object(body, "assertion");
            assertion.addProperty("valid_until", time(assertion, "valid_until"));
        }
        change(id, rev, op);
        store.update(
                "INSERT INTO revisions VALUES(?,?,?,?,?)",
                id,
                rev,
                store.head(),
                occurred,
                JSON.toJson(body));
        if (body.has("assertion")) store.indexClaim(id, rev, object(body, "assertion"));
        unindex(id);
        if (!op.equals("RETRACT")) {
            index(store, id, body);
        }
    }

    /** 正常入账和离线迁移共用相同的在线词表与事件单元索引。 */
    static void index(Store store, String id, JsonObject body) throws SQLException {
        CellSearch.index(store, id, body);
        String searchable = get(body, "content");
        for (String k : List.of("speaker", "action_id"))
            if (body.has(k)) searchable += " " + get(body, k);
        if (body.has("assertion")) searchable += " " + get(object(body, "assertion"), "subject_id");
        store.update(
                "INSERT INTO search(rowid,terms) SELECT rowid,? FROM records WHERE id=?",
                String.join(" ", Terms.terms(searchable)),
                id);
    }
}
