package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.List;

/**
 * 阶段 2 跨会话受众可见性策略与原作者版本授权决策。
 *
 * <p>职责：
 * <ul>
 *   <li>生成统一的受众过滤 SQL 条件，供召回排名、原文读取、卡片目录与派生发布共享，确保越权候选在 Top-K 之前即被过滤。</li>
 *   <li>处理经可信 QQ 渠道认证的原作者授权（grant）与撤销（revoke）命令；授权严格绑定到单条原文的精确修订版本。</li>
 * </ul>
 */
final class Visibility {
    /** 授予跨会话可见性权限的 HTTP 路由名。 */
    static final String GRANTS = "visibility-grants";

    /** 判断路径是否属于授权/撤销命令（该类命令必须使用独立的渠道授权凭据调用）。 */
    static boolean isCommand(String route) {
        return GRANTS.equals(route) || route.matches("visibility-grants/[0-9a-f]{64}/revoke");
    }

    /**
     * 构造受众可见性 SQL 谓词，并按占位符顺序将参数追加到 {@code args}。
     *
     * <p>调用方在排名、原文窗口、卡片目录和派生发布处共用同一条件。
     * <ul>
     *   <li>{@code use=LOCAL}（兼容省略 use 的旧客户端）：仅限当前会话自身记录，且排除依赖其他会话来源的派生内容。</li>
     *   <li>{@code use=READ/CITE}：允许当前会话记录，或已获针对当前修订版本的有效跨会话授权（CITE 包含 READ）。</li>
     *   <li>对于仍在线（ONLINE）的派生记录，必须确保其全部递归依赖（{@code evidence_dependencies}）
     *       在当前受众下均具备在线且可引用的 {@code CITE} 权限；一旦任一上游撤权或修订，派生正文立即对该受众隐藏。</li>
     * </ul>
     */
    // 在排名、原文窗口和派生发布处使用同一个受众条件。派生正文须可引用所有依赖。
    static String allowed(Access ctx, String use, List<Object> args) {
        args.add(ctx.scope());
        String privacy = ctx.externalAudience()
                ? " AND json_extract(v.body,'$.privacy') IN ('public','private')" : "";
        if (use.equals("LOCAL")) {
            args.add(ctx.scope());
            return "r.conversation=? "
                    + privacy
                    + " AND NOT EXISTS (SELECT 1 FROM evidence_dependencies d "
                    + "JOIN records p ON p.id=d.source WHERE d.derived=r.id AND p.conversation<>?)";
        }
        args.add(ctx.scope());
        args.add(use);
        String direct = "", dependency = "";
        if (ctx.mobileGroup() != null) {
            args.add(ctx.mobileGroup());
            direct = " OR (r.id,v.revision) IN (" + MEMBER_NOTICES + ")";
        }
        args.add(ctx.scope());
        if (ctx.mobileGroup() != null) {
            args.add(ctx.mobileGroup());
            dependency = " AND (d.source,d.revision) NOT IN (" + MEMBER_NOTICES + ")";
        }
        // 注意：跨会话授权只匹配当前最新修订（v.revision=r.revision），且状态为 ACTIVE。
        // 非 ONLINE 项（如 RETRACTED/SOURCE_CHANGED）跳过依赖检查，以便读接口可向原有受众返回失效状态而非正文。
        return """
            ((r.conversation=? %s) OR (v.revision=r.revision AND EXISTS (
               SELECT 1 FROM visibility_grants g WHERE g.record_id=r.id AND g.revision=v.revision
               AND g.target=? AND g.status='ACTIVE' AND (g.use='CITE' OR g.use=?))) %s)
            AND (r.status<>'ONLINE' OR NOT EXISTS (
               SELECT 1 FROM evidence_dependencies d WHERE d.derived=r.id AND NOT EXISTS (
                  SELECT 1 FROM audience_rights a JOIN records p ON p.id=a.id
                  WHERE a.id=d.source AND a.revision=d.revision AND a.audience=? AND a.use='CITE'
                  AND p.status='ONLINE') %s))
            """
                .formatted(privacy, direct, dependency);
    }

    /** 同群成员仅能引用群通知的在线现版。派生回复的每条证据也使用同一集合复核。 */
    private static final String MEMBER_NOTICES = """
            SELECT n.id,n.revision FROM records n JOIN revisions nv
            ON nv.record_id=n.id AND nv.revision=n.revision
            WHERE n.conversation=? AND n.kind='observation' AND n.status='ONLINE'
            AND json_extract(nv.body,'$.channel.platform')='mistyai'
            AND json_extract(nv.body,'$.channel.type')='mobile_group_notice'
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

    private Visibility() {}
}
