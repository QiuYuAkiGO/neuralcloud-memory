package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;
import java.sql.SQLException;
import java.util.*;

/** 人物档案的存储键和显示标题分别维护。旧档归并保留各自原文，读取时按仍生效的身份证据组合。 */
final class DreamProfiles {
    @FunctionalInterface interface Firsthand {
        JsonObject find(String recordId) throws SQLException;
    }
    record Part(String key, String owner, JsonObject body) {}
    record View(String owner, JsonObject body, List<Part> parts) {}
    private record Link(String person, boolean direct) {}

    private final Store store;
    private final Reader reader;
    private final DreamIdentities names;
    private final Firsthand firsthand;

    DreamProfiles(Store store, Reader reader, DreamIdentities names, Firsthand firsthand) {
        this.store = store;
        this.reader = reader;
        this.names = names;
        this.firsthand = firsthand;
    }

    /** 主称呼或 QQ 可在格式升级之后才由部署确认；历史修订和原命令都保留原键。 */
    static void bindExisting(Store store) throws SQLException {
        for (var row : store.rows("SELECT subject FROM dream_person_names WHERE name=subject ORDER BY subject"))
            DreamIdentities.manualId(store, get(row, "subject"));
        for (var row : store.rows("""
                SELECT p.subject,m.person_id FROM dream_profiles p
                LEFT JOIN dream_profile_people m ON m.profile_key=p.subject
                WHERE p.kind='person' ORDER BY p.active DESC,p.subject
                """)) {
            String key = get(row, "subject");
            String current = row.get("person_id").isJsonNull() ? null : get(row, "person_id");
            String manual = store.dreamPeople.isAlias(key) ? null : DreamIdentities.manualId(store, key);
            if (current != null && (manual == null || current.equals(manual))) continue;
            String wanted = manual != null && keyOf(store, manual) == null ? manual : legacyId(store.character, key);
            store.update("""
                    INSERT INTO dream_profile_people VALUES(?,?) ON CONFLICT(profile_key)
                    DO UPDATE SET person_id=excluded.person_id
                    """, key, wanted);
        }
    }

    static String legacyId(String character, String key) { return recordId(character, "dream-legacy:" + key); }

    private static String keyOf(Store store, String owner) throws SQLException {
        var rows = store.rows("SELECT profile_key FROM dream_profile_people WHERE person_id=?", owner);
        return rows.isEmpty() ? null : get(rows.getFirst(), "profile_key");
    }

    /** 未绑定的非 QQ 旧客户端仍按旧键修订；新账号可与其他账号共用显示名。 */
    String allocate(String owner, String subject) throws SQLException {
        String key = keyOf(store, owner);
        if (key != null) return key;
        String proposed = subject;
        if (!owner.equals(legacyId(store.character, subject)) && !owner.equals(names.manualId(subject)))
            proposed = "person:" + owner;
        while (!store.rows("SELECT 1 FROM dream_profile_people WHERE profile_key=?", proposed).isEmpty())
            proposed = "person:" + UUID.randomUUID();
        store.update("INSERT INTO dream_profile_people VALUES(?,?)", proposed, owner);
        return proposed;
    }

    /** 同一条一手消息的内容和归属需要各自成立，修订中保存用到的确切称呼及会话。 */
    void dependency(String key, long version, String name, String scope) throws SQLException {
        if (!name.isEmpty()) store.update(
                "INSERT OR IGNORE INTO dream_profile_name_refs VALUES(?,?,?,?)", key, version, name, scope);
    }

    /** 接受旧版同一原句时，也继承旧版记录下的称呼依据，避免改写正文使私下归属变成公开事实。 */
    void inheritDependencies(Part part, Map<String, Set<String>> deps) throws SQLException {
        for (var r : store.rows("SELECT name,scope FROM dream_profile_name_refs WHERE profile_key=? AND version=?",
                part.key(), num(part.body(), "version")))
            deps.computeIfAbsent(get(r, "name"), n -> new TreeSet<>()).add(get(r, "scope"));
    }

    private boolean dependencies(Part part, Access viewer) throws SQLException {
        for (var row : store.rows(
                "SELECT name,scope FROM dream_profile_name_refs WHERE profile_key=? AND version=?",
                part.key(), num(part.body(), "version"))) {
            if (!names.permitsName(part.owner(), get(row, "name"), get(row, "scope"), viewer)) return false;
        }
        return true;
    }

    boolean sourcesVisible(JsonObject body, Access viewer) throws SQLException {
        for (var entry : body.getAsJsonArray("source_refs")) {
            var ref = entry.getAsJsonObject();
            String id = get(ref, "record_id");
            var source = viewer == null ? firsthand.find(id) : reader.currentFor(viewer, "CITE", id);
            if (source == null || num(source, "revision") != num(ref, "revision")
                    || (viewer != null && !"ONLINE".equals(get(source, "status")))) return false;
        }
        return true;
    }

    /** 仅身份桥接失效时，找回同一档仍有独立证据的最近认识；正文已退役的档案由 Dreaming.invalidate 处理。 */
    private Part supported(Part part) throws SQLException {
        if (dependencies(part, null)) return part;
        for (var row : store.rows("""
                SELECT body FROM dream_revisions WHERE kind='person' AND subject=? AND version<? ORDER BY version DESC
                """, part.key(), num(part.body(), "version"))) {
            var older = new Part(part.key(), part.owner(), parse(get(row, "body")));
            if (dependencies(older, null) && sourcesVisible(older.body(), null)) return older;
        }
        return null;
    }

    private void decision(Part p, String person, String status, String reason) throws SQLException {
        store.update("""
                INSERT INTO dream_legacy_decisions VALUES(?,?,?,?,?) ON CONFLICT(profile_key,version) DO UPDATE SET
                person_id=excluded.person_id,status=excluded.status,reason=excluded.reason WHERE
                person_id IS NOT excluded.person_id OR status<>excluded.status OR reason<>excluded.reason
                """, p.key(), num(p.body(), "version"), person, status, reason);
    }

    /** 逐条查旧档自己的原话及说话人。仅标题、群聊对端或被他人转述的自称不能认领整份档。 */
    private Link legacyOwner(Part part) throws SQLException {
        String name = get(part.body(), "subject"), owner = null;
        var prior = store.rows("""
                SELECT person_id FROM dream_legacy_decisions WHERE profile_key=? AND version=?
                AND status IN ('DIRECT','PENDING_DIRECT')
                """, part.key(), num(part.body(), "version"));
        String certified = prior.isEmpty() ? null : get(prior.getFirst(), "person_id");
        boolean direct = true;
        for (var raw : part.body().getAsJsonArray("source_refs")) {
            var ref = raw.getAsJsonObject();
            var e = firsthand.find(get(ref, "record_id"));
            if (e == null || num(e, "revision") != num(ref, "revision")) return null;
            var matches = names.ownersOf(name, get(e, "conversation_id"));
            String found;
            if (e.has("speaker_person_id")) {
                found = get(e, "speaker_person_id");
                if ((!matches.contains(found) && !found.equals(certified))
                        || !(names.selfIntroduces(e, name) || DreamNameStatements.directSelfReport(get(e, "content"))))
                    return null;
            } else {
                if (matches.size() != 1 || !e.has("speaker") || !name.equals(get(e, "speaker"))
                        || !names.selfIntroduces(e, name)) return null;
                found = matches.iterator().next();
                direct = false;
            }
            if (owner != null && !owner.equals(found)) return null;
            owner = found;
        }
        // 直接归属只承载依据明确的本人事实。展示中还用到旧称时仍保留可撤回的称呼桥接。
        for (var field : List.of("text", "scope", "reason"))
            if (get(part.body(), field).contains(name)) direct = false;
        return new Link(owner, direct);
    }

    /** 得到本次事务的权威主档及参与合并的原件，另行受众过滤不会改写其全局版本。 */
    Map<String, View> snapshot() throws SQLException {
        var originals = new LinkedHashMap<String, Part>();
        var candidates = new LinkedHashMap<String, List<Part>>();
        var direct = new HashSet<String>();
        for (var row : store.rows("""
                SELECT p.subject,m.person_id,v.body FROM dream_profiles p
                JOIN dream_revisions v USING(kind,subject,version)
                JOIN dream_profile_people m ON m.profile_key=p.subject
                WHERE p.kind='person' AND p.active=1 ORDER BY p.subject
                """)) {
            String key = get(row, "subject");
            var raw = new Part(key, get(row, "person_id"), parse(get(row, "body")));
            var part = supported(raw);
            if (part == null) continue;
            originals.put(part.owner(), part);
            if (names.known(part.owner())) continue;
            var link = legacyOwner(part);
            if (link != null) {
                candidates.computeIfAbsent(link.person(), k -> new ArrayList<>()).add(part);
                if (link.direct()) direct.add(part.key());
            }
            else decision(part, null, "PENDING", "旧档原句尚不能唯一指向同一人物");
        }
        var visible = new LinkedHashMap<String, View>();
        for (var group : candidates.entrySet()) {
            String target = group.getKey(), subject = names.primary(target, null);
            if (subject == null) continue;
            var parts = new ArrayList<Part>();
            if (originals.containsKey(target)) parts.add(originals.get(target));
            parts.addAll(group.getValue());
            var combined = combine(parts, subject, target);
            for (var former : group.getValue()) {
                boolean independent = direct.contains(former.key());
                String status = combined == null ? (independent ? "PENDING_DIRECT" : "PENDING")
                        : (independent ? "DIRECT" : "LINKED");
                String reason = combined == null ? "合并超过单档限制"
                        : (independent ? "本人账号和一手自述可以独立归属" : "旧档须同时引用有效称呼依据");
                decision(former, target, status, reason);
            }
            if (combined == null) continue;
            visible.put(target, edition(target, combined, parts));
            for (var p : parts) originals.remove(p.owner());
        }
        for (var p : originals.values()) {
            var body = p.body().deepCopy();
            String title = names.known(p.owner()) ? names.primary(p.owner(), null) : get(body, "subject");
            if (title == null) continue;
            body.addProperty("subject", title);
            body.addProperty("person_id", p.owner());
            visible.put(p.owner(), edition(p.owner(), body, List.of(p)));
        }
        return visible;
    }

    /** 去重引用及相同自然段，任一限制超出便保留所有原档。 */
    private JsonObject combine(List<Part> parts, String title, String owner) {
        var refs = new LinkedHashMap<String, JsonObject>();
        var body = obj("kind", "person", "subject", title, "person_id", owner, "author", "DREAMING");
        for (var p : parts)
            for (var entry : p.body().getAsJsonArray("source_refs")) {
                var ref = entry.getAsJsonObject();
                var old = refs.putIfAbsent(get(ref, "record_id"), ref);
                if (old != null && !old.equals(ref)) return null;
            }
        if (refs.size() > 16) return null;
        body.add("source_refs", JSON.toJsonTree(refs.values()));
        for (var field : List.of("text", "scope", "reason")) {
            var paragraphs = new LinkedHashSet<String>();
            for (var p : parts) paragraphs.addAll(List.of(get(p.body(), field).split("\\n\\n", -1)));
            String value = String.join("\n\n", paragraphs);
            int limit = switch (field) { case "text" -> 2048; case "scope" -> 512; default -> 1024; };
            if (value.codePointCount(0, value.length()) > limit) return null;
            body.addProperty(field, value);
        }
        return body;
    }

    /** 按完整展示内容核定版本，原始引用的修订与之前发布过的正文都可追溯。 */
    private View edition(String owner, JsonObject body, List<Part> parts) throws SQLException {
        long floor = parts.stream().filter(p -> owner.equals(p.owner()))
                .mapToLong(p -> num(p.body(), "version")).max().orElse(1);
        body.remove("version");
        var rows = store.rows("SELECT version,body FROM dream_person_editions WHERE person_id=? ORDER BY version DESC LIMIT 1", owner);
        long version = rows.isEmpty() ? floor : num(rows.getFirst(), "version");
        if (rows.isEmpty() || !parse(get(rows.getFirst(), "body")).equals(body) || floor > version) {
            if (!rows.isEmpty()) version = Math.max(floor, Math.incrementExact(version));
            store.update("INSERT INTO dream_person_editions VALUES(?,?,?)", owner, version, JSON.toJson(body));
        }
        body.addProperty("version", version);
        return new View(owner, body, List.copyOf(parts));
    }

    long lastVersion(String owner) throws SQLException {
        return num(store.rows("SELECT coalesce(max(version),0) n FROM dream_person_editions WHERE person_id=?", owner).getFirst(), "n");
    }

    /** 直接归属的旧认识无需向读者披露旧名。依靠名称才找到本人的旧档仍需核对其称呼依据。 */
    String bridge(Part part, String person) throws SQLException {
        boolean direct = !store.rows("""
                SELECT 1 FROM dream_legacy_decisions WHERE profile_key=? AND version=? AND person_id=? AND status='DIRECT'
                """, part.key(), num(part.body(), "version"), person).isEmpty();
        return direct ? "" : get(part.body(), "subject");
    }

    /** 展示合并关系须能同时引用原档内容和身份桥接；即使正文公开，私下自称也需单独授权。 */
    boolean visible(View view, Access viewer) throws SQLException {
        if (names.known(view.owner()) && !names.permitsName(view.owner(), get(view.body(), "subject"), viewer.scope(), viewer))
            return false;
        for (var p : view.parts()) {
            if (!sourcesVisible(p.body(), viewer) || !dependencies(p, viewer)) return false;
            if (!p.owner().equals(view.owner())) {
                String name = bridge(p, view.owner());
                if (!name.isEmpty() && !names.permitsName(view.owner(), name, viewer.scope(), viewer)) return false;
            }
        }
        return true;
    }
}
