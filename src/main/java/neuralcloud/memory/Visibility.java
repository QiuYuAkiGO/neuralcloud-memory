package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.List;

/**
 * 当前角色的会话可见性策略与原作者版本授权决策。
 *
 * <p>职责：
 * <ul>
 *   <li>生成统一的受众过滤 SQL 条件，供召回排名、原文读取、卡片目录与派生发布共享，确保越权候选在 Top-K 之前即被过滤。</li>
 *   <li>处理经可信 QQ 渠道认证的原作者授权（grant）与撤销（revoke）命令；授权严格绑定到单条原文的精确修订版本。</li>
 * </ul>
 * @param sessionIsolation 为 true 时按会话、授权和已核验成员筛选，为 false 时共享本角色所有隐私等级的现版。
 */
record Visibility(boolean sessionIsolation) {
    /** 授予跨会话可见性权限的 HTTP 路由名。 */
    static final String GRANTS = "visibility-grants";

    /** 判断路径是否属于授权/撤销命令（该类命令必须使用独立的渠道授权凭据调用）。 */
    static boolean isCommand(String route) {
        return GRANTS.equals(route) || route.matches("visibility-grants/[0-9a-f]{64}/revoke");
    }

    /**
     * 构造受众可见性 SQL 谓词，并按占位符顺序将参数追加到 {@code args}。
     *
     * <p>排名、原文、目录及派生发布均在截断前使用该条件。共享许可涵盖所有隐私等级的现版，
     * 上游逐条核对其确切修订、在线状态及引用权，避免通过一条可读回复带出隐藏来源。
     */
    String allowed(Access ctx, String use, List<Object> args) {
        args.add(ctx.scope());
        if (!sessionIsolation) {
            // 本会话保留历史窗口。跨会话只共享现版，派生正文仍须逐一匹配在线来源的修订。
            return """
                    (r.conversation=? OR v.revision=r.revision)
                    AND (r.status<>'ONLINE' OR NOT EXISTS (SELECT 1 FROM sources s WHERE s.derived=r.id) OR NOT EXISTS (
                        SELECT 1 FROM evidence_dependencies d WHERE d.derived=r.id AND NOT EXISTS (
                            SELECT 1 FROM records p WHERE p.id=d.source AND p.revision=d.revision AND p.status='ONLINE')))
                    """;
        }
        // 已交付过的目录称呼可能不在原始正文里，回切时连同引用它的回复、摘要一起收回。
        String independent = "json_extract(v.body,'$.requires_identity_lookup') IS NULL AND ";
        String privacy = ctx.externalAudience()
                ? " AND json_extract(v.body,'$.privacy') IN ('public','private')" : "";
        if (use.equals("LOCAL")) {
            // 隔离模式下省略 use 的旧客户端只读本会话，包含外会话来源的派生正文也要隐藏。
            args.add(ctx.scope());
            return independent + "r.conversation=? "
                    + privacy
                    + " AND NOT EXISTS (SELECT 1 FROM evidence_dependencies d "
                    + "JOIN records p ON p.id=d.source WHERE d.derived=r.id AND p.conversation<>?)";
        }
        String direct = "(r.conversation=?" + privacy + ")" + """
                     OR (v.revision=r.revision AND EXISTS (
                        SELECT 1 FROM visibility_grants g WHERE g.record_id=r.id AND g.revision=v.revision
                        AND g.target=? AND g.status='ACTIVE' AND (g.use='CITE' OR g.use=?)))
                    """;
        args.add(ctx.scope());
        args.add(use);
        String sourceRight = """
                    EXISTS (SELECT 1 FROM audience_rights a WHERE a.id=p.id AND a.revision=p.revision
                            AND a.audience=? AND a.use='CITE')
                    """;
        boolean memberAccess = !ctx.memberGroups().isEmpty();
        if (memberAccess) {
            String memberNotices = MEMBER_NOTICES.formatted(
                    String.join(",", java.util.Collections.nCopies(ctx.memberGroups().size(), "?")));
            direct += " OR (r.id,v.revision) IN (" + memberNotices + ")";
            sourceRight += " OR (p.id,p.revision) IN (" + memberNotices + ")";
            args.addAll(ctx.memberGroups());
        }
        args.add(ctx.scope());
        if (memberAccess) args.addAll(ctx.memberGroups());
        // 原话没有上游，无需展开整库的递归依赖。派生记录仍逐一核对当前受众对全部上游的引用权。
        return independent + """
            (%s)
            AND (r.status<>'ONLINE' OR NOT EXISTS (SELECT 1 FROM sources s WHERE s.derived=r.id) OR NOT EXISTS (
               SELECT 1 FROM evidence_dependencies d WHERE d.derived=r.id AND NOT EXISTS (
                  SELECT 1 FROM records p WHERE p.id=d.source AND p.revision=d.revision
                  AND p.status='ONLINE' AND (%s))))
            """
                .formatted(direct, sourceRight);
    }

    /** 同群成员仅能引用群通知的在线现版。派生回复的每条证据也使用同一集合复核。 */
    private static final String MEMBER_NOTICES =
            """
            SELECT n.id,n.revision FROM records n JOIN revisions nv
            ON nv.record_id=n.id AND nv.revision=n.revision
            WHERE n.conversation IN (%s) AND n.kind='observation' AND n.status='ONLINE'
            AND json_extract(nv.body,'$.channel.type') IN ('mobile_group_notice','qq_group_notice')
            AND json_extract(nv.body,'$.privacy') IN ('public','private')
            """;

    /** 授权或撤销决策结果，供 Ledger 写入变更流与持久回执。 */
    record Decision(String record, long revision, String grant) {}

    /**
     * 执行原作者授予或撤销跨会话权限的数据库写入。
     *
     * <p>就绪与安全条件：HTTP 入口已核验独立渠道凭据与机器人账号一致；此处再次要求
     * {@code ORCHESTRATOR} 角色与可信 QQ 私聊身份，并逐项核对原消息的渠道平台、账号、发件人与精确修订号。
     */
    static Decision decide(Store store, Access ctx, String route, JsonObject cmd)
            throws SQLException {
        ctx.requireQq();
        if (!ctx.role().equals("ORCHESTRATOR")) throw denied();
        String reason = text(cmd, "reason", 1024);
        String common = "context source_instance event_id reason";
        if (GRANTS.equals(route)) {
            keys(cmd, common + " source_ref target_conversation_id use");
            var ref = object(cmd, "source_ref");
            keys(ref, "record_id revision");
            String id = id(ref, "record_id"), use = one(cmd, "use", "READ", "CITE");
            long rev = number(ref, "revision", 1, Long.MAX_VALUE);
            String target = text(cmd, "target_conversation_id", 256);
            // 仅原作者本人可授予自己发出的 QQ 观察记录，显示名与模型文本不能代替渠道发件人身份。
            var rows =
                    store.rows(
                            """
                SELECT r.conversation,r.revision,r.status FROM records r JOIN revisions v ON v.record_id=r.id
                AND v.revision=r.revision WHERE r.id=? AND r.kind='observation'
                AND json_extract(v.body,'$.channel.platform')='qq'
                AND json_extract(v.body,'$.channel.account_id')=? AND json_extract(v.body,'$.channel.sender_id')=?
                """,
                            id,
                            ctx.account(),
                            ctx.peer());
            if (rows.isEmpty()) throw denied();
            var row = rows.getFirst();
            // 同会话天然具备本会话访问权，禁止向原会话重复颁发跨会话授权。
            if (target.equals(get(row, "conversation"))) throw invalid("target_conversation_id");
            if (num(row, "revision") != rev || !get(row, "status").equals("ONLINE"))
                throw conflict("SOURCE_CHANGED");
            if (!store.rows(
                            "SELECT 1 FROM visibility_grants WHERE record_id=? AND revision=? AND target=? AND use=? AND status='ACTIVE'",
                            id,
                            rev,
                            target,
                            use)
                    .isEmpty()) throw conflict("GRANT_EXISTS");
            String grant = recordId(store.writer, get(cmd, "event_id"));
            store.update(
                    "INSERT INTO visibility_grants VALUES(?,?,?,?,?,?,?,?, 'ACTIVE',NULL)",
                    grant,
                    id,
                    rev,
                    target,
                    use,
                    ctx.account(),
                    ctx.peer(),
                    reason);
            return new Decision(id, rev, grant);
        }
        // 撤销分支：从路由 visibility-grants/{grant_id}/revoke 提取授权 ID，并核验调用方即为原授权作者。
        keys(cmd, common);
        String grant = route.split("/")[1];
        var rows =
                store.rows(
                        "SELECT record_id,revision,status FROM visibility_grants WHERE grant_id=? AND account=? AND author=?",
                        grant,
                        ctx.account(),
                        ctx.peer());
        if (rows.isEmpty()) throw denied();
        var row = rows.getFirst();
        if (!get(row, "status").equals("ACTIVE")) throw conflict("GRANT_INACTIVE");
        store.update(
                "UPDATE visibility_grants SET status='REVOKED',revoke_reason=? WHERE grant_id=?",
                reason,
                grant);
        return new Decision(get(row, "record_id"), num(row, "revision"), grant);
    }
}
