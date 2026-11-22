package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.List;

final class Visibility {
    static final String GRANTS = "visibility-grants";

    static boolean isCommand(String route) {
        return GRANTS.equals(route) || route.matches("visibility-grants/[0-9a-f]{64}/revoke");
    }

    // 在排名、原文窗口和派生发布处使用同一个受众条件。派生正文须可引用所有依赖。
    static String allowed(Access ctx, String use, List<Object> args) {
        args.add(ctx.scope());
        String privacy =
                ctx.peer() == null
                        ? ""
                        : " AND json_extract(v.body,'$.privacy') IN ('public','private')";
        if (use.equals("LOCAL")) {
            args.add(ctx.scope());
            return "r.conversation=? "
                    + privacy
                    + " AND NOT EXISTS (SELECT 1 FROM evidence_dependencies d "
                    + "JOIN records p ON p.id=d.source WHERE d.derived=r.id AND p.conversation<>?)";
        }
        args.add(ctx.scope());
        args.add(use);
        args.add(ctx.scope());
        return """
            ((r.conversation=? %s) OR (v.revision=r.revision AND EXISTS (
               SELECT 1 FROM visibility_grants g WHERE g.record_id=r.id AND g.revision=v.revision
               AND g.target=? AND g.status='ACTIVE' AND (g.use='CITE' OR g.use=?))))
            AND (r.status<>'ONLINE' OR NOT EXISTS (
               SELECT 1 FROM evidence_dependencies d WHERE d.derived=r.id AND NOT EXISTS (
                  SELECT 1 FROM audience_rights a JOIN records p ON p.id=a.id
                  WHERE a.id=d.source AND a.revision=d.revision AND a.audience=? AND a.use='CITE'
                  AND p.status='ONLINE')))
            """
                .formatted(privacy);
    }

    record Decision(String record, long revision, String grant) {}

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
