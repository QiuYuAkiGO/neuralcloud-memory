package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.JsonObject;
import java.sql.SQLException;
import java.util.List;

/** 三个整理通道共用一份在途包，提交只推进该通道。所有调用都在 Memory 的同一写事务内。 */
final class DreamGroupProgress {
    static final String LIVE = "live", IDENTITY = "identity_backfill", GROUP = "group_history";
    private static final List<String> LANES = List.of(LIVE, IDENTITY, GROUP);

    record Range(String lane, long after, long through) {}

    private DreamGroupProgress() {}

    static boolean enabled(Store store) throws SQLException {
        return num(store.rows("SELECT enabled FROM dream_group_progress").getFirst(), "enabled") == 1;
    }

    /** 范围变更由正常流从零重评。同范围重新启用时，补扫关闭期间正常流已确认的部分。 */
    static boolean enable(Store store, boolean requested, boolean scopesChanged) throws SQLException {
        boolean changed = requested != enabled(store);
        if (scopesChanged) {
            store.update("UPDATE dream_group_progress SET enabled=?,cursor=0,target=0", requested ? 1 : 0);
        } else if (changed) {
            store.update("""
                    UPDATE dream_group_progress SET enabled=?,
                      target=max(target,(SELECT covered FROM dream_state))
                    """, requested ? 1 : 0);
        }
        return changed;
    }

    /** 重领优先复核原包；空闲时按上次提交的下一通道轮转，连续到来的实时发言不会饿死升级回扫。 */
    static Range next(Store store, JsonObject state, JsonObject identityProgress, long limit) throws SQLException {
        var group = store.rows("SELECT enabled,cursor,target FROM dream_group_progress").getFirst();
        String issued = null;
        if (!state.get("packet").isJsonNull()) {
            var packet = parse(get(state, "packet"));
            issued = packet.has("lane") ? get(packet, "lane") : LIVE;
        }
        int first = issued == null ? LANES.indexOf(get(identityProgress, "last_lane")) + 1 : LANES.indexOf(issued);
        long covered = num(state, "covered"), head = store.head();
        for (int i = 0; i < LANES.size(); i++) {
            String lane = LANES.get((first + i) % LANES.size());
            if (GROUP.equals(lane)) {
                if (num(group, "enabled") == 0 || num(group, "cursor") == num(group, "target")) continue;
                Range pending = groupPage(store, group, limit, GROUP.equals(issued));
                if (pending != null) return pending;
            } else {
                long after = LIVE.equals(lane) ? covered : num(identityProgress, "cursor");
                long target = LIVE.equals(lane) ? head : num(identityProgress, "target");
                if (after < target) {
                    var row = store.rows("""
                            SELECT max(seq) AS last FROM (SELECT seq FROM changes WHERE seq>? AND seq<=?
                            ORDER BY seq LIMIT ?)
                            """, after, target, limit).getFirst();
                    return new Range(lane, after, num(row, "last"));
                }
            }
        }
        return new Range(LIVE, covered, covered);
    }

    /** 以获准原话的序号分页。多取一条判断最后一页，避免把数万条无关变更逐页交给整理器。 */
    private static Range groupPage(Store store, JsonObject group, long limit, boolean issued) throws SQLException {
        long after = num(group, "cursor"), target = num(group, "target");
        var matches = store.rows("""
                SELECT seq FROM dream_group_messages WHERE seq>? AND seq<=? ORDER BY seq LIMIT ?
                """, after, target, limit + 1);
        if (matches.isEmpty() && !issued) {
            // 该范围内已没有能签发的修订。尚在途的旧页交还调用方重新签发，旧提案按版本冲突处理。
            store.update("UPDATE dream_group_progress SET cursor=target");
            return null;
        }
        long through = matches.size() <= limit ? target : num(matches.get((int) limit - 1), "seq");
        return new Range(GROUP, after, through);
    }

    /** 提交边界对照提交者指定通道的权威水位，与当前策略、在途包共同确认。 */
    static long cursor(Store store, String lane) throws SQLException {
        String sql = switch (lane) {
            case LIVE -> "SELECT covered AS value FROM dream_state";
            case IDENTITY -> "SELECT cursor AS value FROM dream_identity_progress";
            case GROUP -> "SELECT cursor AS value FROM dream_group_progress";
            default -> throw new IllegalArgumentException(lane);
        };
        return num(store.rows(sql).getFirst(), "value");
    }

    /** 和档案、回执同事务写入；last_lane 仅决定下一次的调度顺序，不代替任何通道的游标。 */
    static void advance(Store store, String lane, long through) throws SQLException {
        if (LIVE.equals(lane)) {
            store.update("UPDATE dream_state SET covered=?,packet=NULL", through);
            // 已开启并追平的群历史由正常流接续，关掉再开启也无需重复这一段。
            store.update("UPDATE dream_group_progress SET cursor=?,target=? WHERE enabled=1 AND cursor=target",
                    through, through);
        } else {
            store.update("UPDATE dream_state SET packet=NULL");
        }
        if (GROUP.equals(lane)) {
            // 回扫结束时，其上界之后是开启期间已确认的正常流；一并记为已覆盖。
            store.update("""
                    UPDATE dream_group_progress SET cursor=CASE WHEN ?=target THEN (SELECT covered FROM dream_state)
                      ELSE ? END, target=CASE WHEN ?=target THEN (SELECT covered FROM dream_state) ELSE target END
                    """, through, through, through);
        }
        if (IDENTITY.equals(lane)) {
            store.update("UPDATE dream_identity_progress SET cursor=?,last_lane=?", through, lane);
        } else {
            store.update("UPDATE dream_identity_progress SET last_lane=?", lane);
        }
    }
}
