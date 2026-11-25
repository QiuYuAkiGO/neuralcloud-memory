package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Pattern;

/** 从原渠道或受支持的 Resident 快照建立索引。投影仅引用原记录，不重写历史会话或增设账本授权。 */
final class DreamGroupHistory {
    private static final String QQ = "[1-9][0-9]{4,15}";
    private static final String GROUP = "[1-9][0-9]*";
    private static final Pattern ROOM = Pattern.compile("group:onebot:(" + QQ + "):(" + GROUP + ")");
    private static final Pattern OLD_ROOM = Pattern.compile("qq:group:(" + GROUP + ")");
    private static final Pattern MEMBER = Pattern.compile("qq:member:(" + QQ + ")");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern OLD_ID = Pattern.compile("shared-memory:[0-9a-f]{64}");
    private static final String PROTOCOL = "qq-protocol://sha256/";
    // 旧写入方给观察或资料本身的短标识是 32 位。它指向的底层 QQ 协议包另有完整 SHA-256。
    private static final Pattern POINTER = Pattern.compile("qq-(visual|profile):[0-9a-f]{32}");
    private static final Pattern MESSAGE = Pattern.compile("qq-protocol-message:(" + GROUP + "):(" + QQ + "):(-?[0-9]+)");
    private static final Pattern MEDIA = Pattern.compile(
            "【图片[1-9][0-9]*】|\\[(?:(?:图片|引用消息|视频|语音|文件|转发消息)(?:[\\s:：][^]\\r\\n]*)?|未解析的消息附件|QQ表情)\\]");

    private DreamGroupHistory() {}

    /** 初始化时只扫描一次既有原生渠道与真实导入器留下的旧修订。新入账的原生消息由 Ledger 同事务登记。 */
    static void index(Store store) throws SQLException {
        try (var query = store.db.prepareStatement(Reader.LATEST + """
                 WHERE r.kind='observation' AND r.status='ONLINE' AND json_extract(v.body,'$.channel.type')='qq_group'
                """); var rows = query.executeQuery()) {
            while (rows.next()) indexNative(store, rows.getString("id"), parse(rows.getString("body")));
        }
        if (store.rows("SELECT 1 FROM sqlite_master WHERE type='table' AND name='resident_v1_ids'").isEmpty()) return;
        try (var query = store.db.prepareStatement("""
                SELECT r.id,r.revision,r.source,r.conversation,v.seq,json_extract(v.body,'$.content') AS content,
                       raw.record_id AS old_id,raw.payload,raw.hash
                FROM resident_v1_ids ids JOIN records r ON r.id=ids.record_id
                JOIN revisions v ON v.record_id=r.id AND v.revision=r.revision
                JOIN resident_v1_revisions raw ON raw.record_id=ids.original_id AND raw.revision=r.revision
                WHERE r.conversation LIKE 'qq:group:%' AND r.status='ONLINE' AND r.kind='observation'
                  AND json_type(v.body,'$.source_refs') IS NULL AND json_type(v.body,'$.channel') IS NULL
                  AND NOT EXISTS (SELECT 1 FROM sources d WHERE d.derived=r.id)
                """); var rows = query.executeQuery()) {
            while (rows.next()) indexArchived(store, rows);
        }
    }

    /** 带附件的文字仍是原话。只有整段都是已知媒体占位或空白时才跳过。 */
    private static boolean hasWords(String text) {
        return !MEDIA.matcher(text).replaceAll("").isBlank();
    }

    /** 原生 channel 在 Ledger 或正式 v1 导入边界已核实。保留机器人账号供跨来源去重，不填补旧协议缺失的账号。 */
    static void indexNative(Store store, String id, JsonObject body) throws SQLException {
        var channel = body.getAsJsonObject("channel");
        if (channel == null || !"observation".equals(get(body, "kind")) || body.has("source_refs") || body.has("identity")
                || !"qq_group".equals(get(channel, "type")) || !hasWords(get(body, "content"))) return;
        String sender = get(channel, "sender_id"), account = get(channel, "account_id");
        if (sender.equals(account)) return;
        String room = get(channel, "conversation_id");
        store.update("""
                INSERT OR REPLACE INTO dream_group_sources(record_id,revision,seq,group_id,sender_id,message_id,account_id)
                SELECT r.id,r.revision,v.seq,?,?,?,? FROM records r JOIN revisions v
                ON v.record_id=r.id AND v.revision=r.revision WHERE r.id=? AND r.conversation=? AND r.status='ONLINE'
                """, room.substring(room.lastIndexOf(':') + 1), sender, get(channel, "message_id"), account, id, room);
    }

    /** 不符合旧档来源契约的行继续保留于原账本，不能借正文或 aliases 里的协议样式取得发言人身份。 */
    private static void indexArchived(Store store, ResultSet row) throws SQLException {
        String raw = row.getString("payload"), oldId = row.getString("old_id"), room = row.getString("conversation");
        var oldRoom = OLD_ROOM.matcher(room);
        if (!oldRoom.matches() || !OLD_ID.matcher(oldId).matches() || !ResidentV1Import.hash(raw).equals(row.getString("hash"))) return;
        var envelope = parse(raw);
        String source = row.getString("source");
        if (!(envelope.get("provenance") instanceof JsonObject provenance)
                || !verifiedOrigin(provenance, source, oldId, string(envelope.get("event_id")), room)
                || !source.equals(string(envelope.get("source_instance"))) || !store.character.equals(string(envelope.get("character_id")))
                || !new JsonPrimitive(1).equals(envelope.get("schema_version")) || !"observation".equals(string(envelope.get("kind")))
                || !room.equals(string(envelope.get("conversation_id"))) || !room.equals(string(envelope.get("session_id")))
                || !emptyArray(envelope.get("source_ids"))
                || !(envelope.get("participants") instanceof JsonArray members) || members.size() != 1
                || !(provenance.get("external_refs") instanceof JsonArray refs)) return;
        String senderTag = string(members.get(0));
        if (senderTag == null) return;
        var member = MEMBER.matcher(senderTag);
        String text = row.getString("content");
        if (!member.matches() || !text.equals(string(envelope.get("content")))) return;
        String group = oldRoom.group(1), sender = member.group(1);
        var pointers = pointers(refs, group, sender);
        if (pointers == null) return;
        if (pointers.kind.equals("profile")) {
            indexName(store, row, group, sender, pointers.protocols, text);
        } else if (hasWords(text)) {
            store.update("""
                    INSERT OR REPLACE INTO dream_group_sources(record_id,revision,seq,group_id,sender_id,message_id,protocol_digest,content_digest)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, row.getString("id"), row.getLong("revision"), row.getLong("seq"), group, sender, pointers.message,
                    pointers.protocols.iterator().next(), ResidentV1Import.hash(text));
        }
    }

    private static String string(JsonElement value) {
        return value instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static boolean emptyArray(JsonElement value) {
        return value instanceof JsonArray a && a.isEmpty();
    }

    /** 原档的身份来自其快照写入者、原 ID 与修订、来源类型和明确的旧群可见范围。 */
    private static boolean verifiedOrigin(JsonObject origin, String source, String oldId, String event, String room) {
        String snapshot = string(origin.get("snapshot_sha256")), payloadHash = string(origin.get("payload_sha256"));
        return "production-memory-v1-v2".equals(string(origin.get("format")))
                && "observation".equals(string(origin.get("semantic_status"))) && "qq_group".equals(string(origin.get("source_channel")))
                && snapshot != null && SHA256.matcher(snapshot).matches() && source.equals("legacy:" + snapshot.substring(0, 32))
                && payloadHash != null && SHA256.matcher(payloadHash).matches() && oldId.equals(string(origin.get("original_id")))
                && origin.get("original_revision") instanceof JsonPrimitive n && n.isNumber() && n.toString().matches("0|[1-9][0-9]*")
                && (oldId + ":" + n).equals(event) && emptyArray(origin.get("evidence"))
                && origin.get("visibility") instanceof JsonArray visibility && visibility.contains(new JsonPrimitive(room));
    }

    private record Pointers(String kind, Set<String> protocols, String message) {}

    /** 原话须有唯一消息摘要和真实的群、发件人、消息号。资料可同时引用自己的协议摘要和同一条原话的摘要。 */
    private static Pointers pointers(JsonArray refs, String group, String sender) {
        String marker = null, message = null;
        var packets = new LinkedHashSet<String>();
        for (var entry : refs) {
            String value = string(entry);
            if (value == null) return null;
            if (value.startsWith("qq-visual:") || value.startsWith("qq-profile:")) {
                if (!POINTER.matcher(value).matches() || marker != null && !marker.equals(value)) return null;
                marker = value;
            } else if (value.startsWith(PROTOCOL)) {
                String hash = value.substring(PROTOCOL.length());
                if (!SHA256.matcher(hash).matches()) return null;
                packets.add(hash);
            } else if (value.startsWith("qq-protocol-message:")) {
                var triple = MESSAGE.matcher(value);
                if (!triple.matches() || !group.equals(triple.group(1)) || !sender.equals(triple.group(2)) || triple.group(3).length() > 128
                        || message != null && !message.equals(triple.group(3))) return null;
                message = triple.group(3);
            } else if (value.startsWith("qq-delivery:") || value.startsWith("qq-segment-delivery:")
                    || value.startsWith("qq-system:") || value.startsWith("qq-notice:")) return null;
        }
        if (marker == null || packets.isEmpty()) return null;
        if (marker.startsWith("qq-profile:")) return message == null ? new Pointers("profile", packets, null) : null;
        return message != null && packets.size() == 1 ? new Pointers("visual", packets, message) : null;
    }

    /** 只从正式资料 JSON 取渠道群名片或平台昵称。来源保留自己的修订，可独立撤回。 */
    private static void indexName(Store store, ResultSet row, String group, String sender, Set<String> packets, String text)
            throws SQLException {
        JsonElement decoded;
        try {
            decoded = JSON.fromJson(text, JsonElement.class);
        } catch (JsonParseException malformed) {
            return;
        }
        if (!(decoded instanceof JsonObject profile)) return;
        String source = string(profile.get("display_name_source")), name = string(profile.get("observed_display_name"));
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 128
                || !"qq_metadata".equals(source) && !"qq_profile".equals(source)) return;
        String field = source.equals("qq_metadata") ? "card" : "nickname";
        for (String hash : packets) {
            store.update("INSERT OR REPLACE INTO dream_group_names VALUES(?,?,?,?,?,?,?)",
                    row.getString("id"), row.getLong("revision"), group, sender, hash, field, name);
        }
    }

    /** 已获准原生会话分别保留。旧归档只知道群号，同一群号对应多套机器人账号时不投影。 */
    static void rooms(Store store, Collection<String> scopes) throws SQLException {
        record Room(String scope, String account, String group) {}
        var active = new ArrayList<Room>();
        var counts = new HashMap<String, Integer>();
        for (String scope : scopes) {
            var match = ROOM.matcher(scope);
            if (!match.matches()) continue;
            active.add(new Room(scope, match.group(1), match.group(2)));
            counts.merge(match.group(2), 1, Integer::sum);
        }
        store.update("DELETE FROM dream_group_rooms");
        for (var room : active) {
            store.update("INSERT INTO dream_group_rooms VALUES(?,?,?,?)", room.scope(), room.group(), room.account(),
                    counts.get(room.group()) == 1 ? 1 : 0);
        }
    }

    /** 只为当前获准的旧原话补上可信来源，姓名依据指向资料版本本身，正文与 RecordRef 始终沿用原话。 */
    static boolean project(Store store, JsonObject evidence) throws SQLException {
        String id = get(evidence, "record_id");
        var rows = store.rows("""
                SELECT room,sender_id,message_id FROM dream_group_messages
                WHERE record_id=? AND revision=? AND protocol_digest IS NOT NULL
                """, id, num(evidence, "revision"));
        if (rows.isEmpty()) return false;
        var message = rows.getFirst();
        evidence.add("conversation_id", message.get("room"));
        evidence.add("sender_id", message.get("sender_id"));
        evidence.add("message_id", message.get("message_id"));
        var names = store.rows("SELECT record_id,revision,field,name FROM dream_group_paired_names WHERE message_record=?", id);
        if (!names.isEmpty()) {
            var name = names.getFirst();
            evidence.add(get(name, "field"), name.get("name"));
            evidence.add("channel_name_ref", ref(get(name, "record_id"), num(name, "revision")));
        }
        return true;
    }

    /** 资料可作一条称呼的依据，不能作为群聊正文发给模型。 */
    static boolean nameRecord(Store store, String id) throws SQLException {
        return !store.rows("SELECT 1 FROM dream_group_paired_names WHERE record_id=? LIMIT 1", id).isEmpty();
    }

    /** 更正或撤回资料后，用原协议摘要查找受影响的原话。此处须能定位刚失效的旧资料。 */
    static List<String> associated(Store store, String id) throws SQLException {
        return store.rows("""
                SELECT DISTINCT m.record_id FROM dream_group_names n JOIN dream_group_messages m
                ON m.group_id=n.group_id AND m.sender_id=n.sender_id AND m.protocol_digest=n.protocol_digest
                WHERE n.record_id=?
                """, id).stream().map(row -> get(row, "record_id")).toList();
    }

    /** 同一群的新旧场合名只在已核实的精确版本上等价。沿用普通 CITE 策略复核，不授予其他会话新的读取权。 */
    static JsonObject currentFor(Store store, Reader reader, Access viewer, String id) throws SQLException {
        var ordinary = reader.currentFor(viewer, "CITE", id);
        if (ordinary != null) return ordinary;
        var matches = store.rows("""
                SELECT r.conversation FROM dream_group_access a JOIN records r ON r.id=a.record_id AND r.revision=a.revision
                WHERE a.record_id=? AND a.room=? AND r.conversation LIKE 'qq:group:%' LIMIT 1
                """, id, viewer.scope());
        if (matches.isEmpty()) return null;
        var original = new Access(get(matches.getFirst(), "conversation"), viewer.session(), viewer.actor(), viewer.role(),
                viewer.account(), viewer.peer(), viewer.memberGroups());
        return reader.currentFor(original, "CITE", id);
    }
}
