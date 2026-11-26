package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

/** 单一角色已确认的人物称呼。启动时导入并归并旧档，运行期间只读。 */
final class DreamPeople {
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, SortedSet<String>> aliases = new TreeMap<>();
    private final Map<String, String> qqSenders = new HashMap<>();
    private String self;

    /** 配置为增量登记。整份文件经 Store 启动事务提交，后续启动可直接从角色库恢复。 */
    static DreamPeople load(Store store, Path file) throws IOException, SQLException {
        var people = new DreamPeople();
        for (var row : store.rows("SELECT * FROM dream_person_names")) {
            String subject = get(row, "subject");
            people.remember(get(row, "name"), subject);
            if (!row.get("qq_sender_id").isJsonNull())
                people.qqSenders.put(get(row, "qq_sender_id"), subject);
            if (num(row, "is_self") == 1) people.self = subject;
        }
        if (file == null) return people;
        var config = parse(Files.readString(file));
        if (config == null) throw invalid("dreamingPeopleFile");
        keys(config, "character_id people");
        if (!store.character.equals(text(config, "character_id", 128)))
            throw invalid("dreamingPeopleFile.character_id");
        var seen = new HashSet<String>();
        boolean changed = false;
        for (var raw : array(config, "people", 0, Integer.MAX_VALUE)) {
            if (!(raw instanceof JsonObject row)) throw invalid("dreamingPeopleFile.people");
            changed |= people.register(store, row, seen);
        }
        // 已发出但尚未取得回执的旧命令必须重新取证，已取得的回执和已覆盖的位置仍有效。
        if (changed) store.update("UPDATE dream_state SET policy_version=policy_version+1,packet=NULL");
        return people;
    }

    private void remember(String name, String subject) {
        names.put(name, subject);
        var previous = aliases.computeIfAbsent(subject, key -> new TreeSet<>());
        if (!name.equals(subject)) previous.add(name);
    }

    /** 主称呼本身也占用名称，禁止别称链、重复声明或将同一称呼分配给两个人。 */
    private boolean bind(Store store, String name, String subject, Set<String> seen) throws SQLException {
        String existing = names.get(name);
        if (!seen.add(name) || (existing != null && !existing.equals(subject)))
            throw invalid("dreamingPeopleFile 称呼冲突 " + name);
        if (existing != null) return false;
        store.update("INSERT INTO dream_person_names(name,subject) VALUES(?,?)", name, subject);
        remember(name, subject);
        return true;
    }

    private boolean register(Store store, JsonObject row, Set<String> seen) throws SQLException {
        keys(row, "subject aliases qq_sender_id self legacy_profiles");
        String subject = text(row, "subject", 128);
        boolean changed = bind(store, subject, subject, seen);
        boolean addedAlias = false;
        for (var raw : array(row, "aliases", 0, Integer.MAX_VALUE)) {
            if (!(raw instanceof JsonPrimitive p) || !p.isString()) throw invalid("aliases");
            String alias = p.getAsString();
            if (alias.isBlank() || alias.codePointCount(0, alias.length()) > 128) throw invalid("aliases");
            addedAlias |= bind(store, alias, subject, seen);
        }
        if (row.has("qq_sender_id")) {
            String id = Access.qqId(row, "qq_sender_id");
            if (!subject.equals(qqSenders.get(id))) {
                if (qqSenders.containsKey(id) || qqSenders.containsValue(subject)) throw invalid("qq_sender_id");
                store.update("UPDATE dream_person_names SET qq_sender_id=? WHERE name=?", id, subject);
                qqSenders.put(id, subject);
                changed = true;
            }
        }
        if (row.has("self")) {
            if (!(row.get("self") instanceof JsonPrimitive p) || !p.isBoolean() || !p.getAsBoolean()
                    || (self != null && !self.equals(subject))) throw invalid("dreamingPeopleFile.self");
            if (self == null) {
                store.update("UPDATE dream_person_names SET is_self=1 WHERE name=?", subject);
                self = subject;
                changed = true;
            }
        }
        var legacy = new LinkedHashSet<String>();
        if (row.has("legacy_profiles")) {
            for (var entry : array(row, "legacy_profiles", 0, Integer.MAX_VALUE)) {
                if (!(entry instanceof JsonObject profile)) throw invalid("legacy_profiles");
                keys(profile, "subject expected_version");
                String title = text(profile, "subject", 128);
                long version = number(profile, "expected_version", 1, Long.MAX_VALUE);
                if (!seen.add(title) || names.containsKey(title))
                    throw invalid("legacy_profiles.subject 已登记为称呼或重复归档 " + title);
                if (importLegacy(store, subject, title, version)) legacy.add(title);
            }
        }
        if (addedAlias || !legacy.isEmpty()) merge(store, subject, legacy);
        return changed || addedAlias || !legacy.isEmpty();
    }

    /** 清单只认领所指版本，既有回执可跨重启或后续修订重放。与其他稳定身份绑定的原件须留在原处。 */
    private static boolean importLegacy(Store store, String target, String title, long version) throws SQLException {
        var prior = store.rows("SELECT subject FROM dream_legacy_imports WHERE profile_key=? AND version=?", title, version);
        if (!prior.isEmpty()) {
            if (!target.equals(get(prior.getFirst(), "subject"))) throw invalid("legacy_profiles.subject 已归入其他人物档案");
            return false;
        }
        if (store.rows("SELECT 1 FROM dream_profiles WHERE kind='person' AND subject=? AND active=1 AND version=?",
                title, version).isEmpty())
            throw invalid("legacy_profiles.expected_version 须与有效旧人物档的当前版本一致 " + title);
        store.update("INSERT INTO dream_legacy_imports(profile_key,version,subject) VALUES(?,?,?)", title, version, target);
        return true;
    }

    /** 只归并仍有效的当前认识。保留原历史版本与回执；旧称的当前入口退役后由登记表指向主称呼。 */
    private static void merge(Store store, String subject, Set<String> legacy) throws SQLException {
        String oldTitles = JSON.toJson(legacy);
        String owner = DreamIdentities.manualId(store, subject);
        // 新协议的人物可能已经用稳定键发布过多版。续写同一键可保留其版本和同名者各自的归属。
        String established = DreamProfiles.keyOf(store, owner);
        String primary = established == null ? subject : established;
        var rows = store.rows("""
                SELECT p.subject,p.version,p.active,v.body,d.person_id AS bound_owner FROM dream_profiles p
                JOIN dream_revisions v USING(kind,subject,version)
                LEFT JOIN dream_profile_people m ON m.profile_key=p.subject
                LEFT JOIN dream_identities d ON d.person_id=m.person_id
                WHERE p.kind='person' AND (p.subject=? OR p.subject IN
                    (SELECT name FROM dream_person_names WHERE subject=? UNION SELECT value FROM json_each(?)))
                ORDER BY p.subject<>?,p.subject
                """, primary, subject, oldTitles, primary);
        boolean hasLiveAlias = rows.stream().anyMatch(r -> num(r, "active") == 1 && !get(r, "subject").equals(primary));
        if (!hasLiveAlias) return;
        var bodies = new ArrayList<JsonObject>();
        var parts = new LinkedHashMap<String, Long>();
        long version = 0;
        for (var row : rows) {
            String key = get(row, "subject");
            long edition = num(row, "version");
            if (key.equals(primary)) version = edition;
            if (num(row, "active") == 1) {
                var bound = row.get("bound_owner");
                if (!bound.isJsonNull() && !owner.equals(bound.getAsString()))
                    throw invalid("dreamingPeopleFile 旧档已绑定其他人物 " + key);
                parts.put(key, edition);
                bodies.add(parse(get(row, "body")));
            }
        }
        var refs = new LinkedHashMap<String, JsonObject>();
        for (var body : bodies)
            for (var raw : body.getAsJsonArray("source_refs")) {
                var ref = raw.getAsJsonObject();
                String id = get(ref, "record_id");
                var existing = refs.putIfAbsent(id, ref);
                if (existing != null && !existing.equals(ref)) throw conflict("SOURCE_CHANGED");
            }
        var merged = obj("kind", "person", "subject", subject, "author", "DREAMING",
                "version", Math.incrementExact(version), "source_refs", refs.values());
        if (refs.size() > DreamProfileSummary.MAX_REFS) {
            if (!DreamProfileSummary.compact(store, bodies, merged)) throw conflict("SOURCE_CHANGED");
        } else {
            for (var field : List.of("text", "scope", "reason")) {
                var paragraphs = new LinkedHashSet<String>();
                for (var body : bodies) paragraphs.add(get(body, field));
                merged.addProperty(field, String.join("\n\n", paragraphs));
            }
        }
        Dreaming.checkBody(merged);
        Dreaming.saveRevision(store, primary, merged);
        for (var part : parts.entrySet()) {
            // 以往学到的称呼可能受原会话的引用权约束。合并后仍以原有权限展示相关认识。
            store.update("""
                    INSERT OR IGNORE INTO dream_profile_name_refs(profile_key,version,name,scope)
                    SELECT ?,?,name,scope FROM dream_profile_name_refs WHERE profile_key=? AND version=?
                    """, primary, num(merged, "version"), part.getKey(), part.getValue());
            if (!primary.equals(part.getKey()))
                store.update("UPDATE dream_profiles SET active=0 WHERE kind='person' AND subject=?", part.getKey());
        }
    }

    boolean isAlias(String name) {
        return names.containsKey(name) && !name.equals(names.get(name));
    }

    /** 只有角色自己的记录或带真实 QQ 发件人的观察能认定发言者。私聊会话只能提示对端身份。 */
    void annotateSpeaker(JsonObject evidence, JsonObject channel) {
        String kind = get(evidence, "kind");
        String speaker = null;
        if (kind.equals("self_expression")) speaker = self;
        if (kind.equals("observation") && channel != null && channel.has("sender_id")
                && Set.of("qq_private", "qq_group").contains(get(channel, "type")))
            speaker = qqSenders.get(get(channel, "sender_id"));
        if (speaker != null) evidence.addProperty("speaker_subject", speaker);
    }

    /** 名字出现在原文时仅作为相关档案线索；只有精确登记的别称才会归并档案。 */
    SortedSet<String> mentioned(Collection<JsonObject> records) {
        var result = new TreeSet<String>();
        for (var e : records) {
            if (e.has("speaker_subject")) result.add(get(e, "speaker_subject"));
            String conversation = get(e, "conversation_id");
            if (conversation.startsWith("c2c:onebot:")) {
                String peer = qqSenders.get(conversation.substring(conversation.lastIndexOf(':') + 1));
                if (peer != null) result.add(peer);
            }
            String content = get(e, "content");
            for (var entry : names.entrySet()) {
                String name = entry.getKey();
                if (content.contains(name) || (e.has("speaker") && get(e, "speaker").equals(name))
                        || (e.has("topic") && get(e, "topic").equals(name))) result.add(entry.getValue());
            }
        }
        return result;
    }

    /** 批次只公开相关主称呼和别称。部署文件中的账号和整份身份目录不随请求交给模型。 */
    JsonArray hints(Set<String> subjects) {
        var result = new JsonArray();
        for (String subject : subjects)
            if (aliases.containsKey(subject))
                result.add(obj("subject", subject, "aliases", aliases.get(subject)));
        return result;
    }
}
