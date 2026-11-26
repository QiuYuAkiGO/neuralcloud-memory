package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;
import java.sql.SQLException;
import java.util.*;

/** 已确认属于同一人、但完整引用超过单档上限时，从各原档交替摘录确实可引用的原话。 */
final class DreamProfileSummary {
    static final int MAX_REFS = 16;
    private static final int EXCERPT_LENGTH = 80;

    private DreamProfileSummary() {}

    /** 每段摘录与引用同序。旧档的叙述可能依赖已省略的来源，因此只写所选现版原文。 */
    static boolean compact(Store store, List<JsonObject> profiles, JsonObject result) throws SQLException {
        var sources = profiles.stream().map(p -> p.getAsJsonArray("source_refs")).toList();
        var selected = new LinkedHashMap<String, JsonObject>();
        for (int offset = 0; offset < MAX_REFS && selected.size() < MAX_REFS; offset++) {
            for (var group : sources) {
                if (offset >= group.size()) continue;
                var ref = group.get(offset).getAsJsonObject();
                selected.putIfAbsent(get(ref, "record_id"), ref);
                if (selected.size() == MAX_REFS) break;
            }
        }
        // 用同一事务中的整理授权与原文修订取数，不从旧印象正文猜测某一句的出处。
        var rows = store.rows(Reader.LATEST + " WHERE r.id IN (SELECT value FROM json_each(?)) AND " + Dreaming.ELIGIBLE,
                JSON.toJson(selected.keySet()), "dreaming:" + store.character);
        var originals = new HashMap<String, JsonObject>();
        for (var row : rows) originals.put(get(row, "id"), row);
        var text = new StringBuilder("我记下了与").append(get(result, "subject")).append("有关的这些原话。");
        int index = 0;
        for (var entry : selected.entrySet()) {
            var row = originals.get(entry.getKey());
            if (row == null || num(row, "revision") != num(entry.getValue(), "revision")) return false;
            String content = get(parse(get(row, "body")), "content").strip();
            int length = content.codePointCount(0, content.length());
            String label = switch (get(row, "kind")) {
                case "self_expression" -> "我当时说";
                case "tool_result" -> "工具记录";
                case "observation" -> "原文";
                default -> throw new IllegalStateException("非一手记录进入 Dreaming 摘录");
            };
            text.append("\n[").append(++index).append("] ").append(label).append("「")
                    .append(content, 0, content.offsetByCodePoints(0, Math.min(length, EXCERPT_LENGTH))).append("」");
            if (length > EXCERPT_LENGTH) text.append("…");
        }
        result.addProperty("text", text.toString());
        result.addProperty("scope", "已确认归属的人物档案中仍可引用的对话与记录");
        result.addProperty("reason", "从各旧档交替节录一手原文，编号对应下列来源。完整认识和引用保存在各自的历史修订中。");
        result.add("source_refs", JSON.toJsonTree(selected.values()));
        return true;
    }
}
