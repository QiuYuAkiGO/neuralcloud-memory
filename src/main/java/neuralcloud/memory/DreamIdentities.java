package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;
import java.sql.SQLException;
import java.util.*;

/** 角色库中的人物及带证据的称呼。所有方法都使用调用方已经取得的 Memory 单写事务。 */
final class DreamIdentities {
    private final Store store;
    private final Reader reader;
    private final String home;

    /** 断言的每条来源都必须仍是当前修订、在线并可被 Dreaming 引用。读和再次推理共用此条件。 */
    private static final String CURRENT = """
            AND NOT EXISTS (SELECT 1 FROM json_each(c.refs) s WHERE NOT EXISTS (
              SELECT 1 FROM records r JOIN revisions v ON v.record_id=r.id AND v.revision=r.revision
              WHERE r.id=json_extract(s.value,'$.record_id') AND r.revision=json_extract(s.value,'$.revision') AND
            """ + Dreaming.ELIGIBLE + "))";

    DreamIdentities(Store store, Reader reader) {
        this.store = store;
        this.reader = reader;
        home = "dreaming:" + store.character;
    }

    /** 账号和角色身份从首次签发起就有稳定 ID。维护者后续为同一账号登记称呼时复用原 ID。 */
    private String bind(List<String> keys) throws SQLException {
        var matches = store.rows("SELECT person_id,count(*) n FROM dream_identity_keys WHERE identity_key IN"
                + " (SELECT value FROM json_each(?)) GROUP BY person_id", JSON.toJson(keys));
        if (matches.size() > 1) throw invalid("dreamingPeopleFile 已有不同人物的身份键");
        String pid = matches.isEmpty() ? recordId(home, keys.getFirst()) : get(matches.getFirst(), "person_id");
        if (!matches.isEmpty() && num(matches.getFirst(), "n") == keys.size()) return pid;
        store.update("INSERT OR IGNORE INTO dream_identities(person_id) VALUES(?)", pid);
        for (String key : keys)
            store.update("INSERT OR IGNORE INTO dream_identity_keys(identity_key,person_id) VALUES(?,?)", key, pid);
        return pid;
    }

    /** 人工登记保留独立来源，不能被模型声明覆盖。旧表仍是人工配置的权威。 */
    String manualId(String name) throws SQLException {
        var rows = store.rows("SELECT root.* FROM dream_person_names n JOIN dream_person_names root ON"
                + " root.name=n.subject WHERE n.name=?", name);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        var keys = new ArrayList<String>();
        if (num(row, "is_self") == 1) keys.add("self");
        if (!row.get("qq_sender_id").isJsonNull()) keys.add("qq:" + get(row, "qq_sender_id"));
        keys.add("manual:" + get(row, "subject"));
        return bind(keys);
    }

    private String qq(String sender) throws SQLException {
        var assigned = store.rows("SELECT subject FROM dream_person_names WHERE qq_sender_id=?", sender);
        return assigned.isEmpty() ? bind(List.of("qq:" + sender)) : manualId(get(assigned.getFirst(), "subject"));
    }

    private String self() throws SQLException {
        var named = store.rows("SELECT subject FROM dream_person_names WHERE is_self=1");
        return named.isEmpty() ? bind(List.of("self")) : manualId(get(named.getFirst(), "subject"));
    }

    boolean known(String pid) throws SQLException {
        return !store.rows("SELECT 1 FROM dream_identities WHERE person_id=?", pid).isEmpty();
    }

    /** 发言人只取真实渠道或角色自己的发言。人工审核的身份观测描述的是记录中的账号，不一定是发言者。 */
    void annotate(JsonObject e, JsonObject body) throws SQLException {
        String kind = get(e, "kind");
        if (kind.equals("self_expression")) e.addProperty("speaker_person_id", self());
        var channel = body.getAsJsonObject("channel");
        if (channel != null && Set.of("qq_private", "qq_group").contains(get(channel, "type"))) {
            if (kind.equals("observation")) e.addProperty("speaker_person_id", qq(get(channel, "sender_id")));
            for (String field : List.of("message_id", "mention_ids", "reply_message_id", "nickname", "card"))
                if (channel.has(field)) e.add(field, channel.get(field));
        }
        if (body.has("identity")) {
            var identity = body.getAsJsonObject("identity");
            e.addProperty("reviewed_person_id", qq(get(identity, "account_id")));
            e.add("reviewed_name", identity.get("name"));
        }
    }

    private List<JsonObject> manual(String pid) throws SQLException {
        return store.rows("""
                SELECT n.name,n.subject FROM dream_identity_keys k JOIN dream_person_names n
                ON k.identity_key='manual:'||n.subject WHERE k.person_id=? ORDER BY n.name<>n.subject,n.name
                """, pid);
    }

    private static JsonArray refs(JsonObject row) {
        return JSON.fromJson(get(row, "refs"), JsonArray.class);
    }

    /** 受众读到的称呼除了满足整理范围，还须能在该受众当前权限下引用整组身份依据。 */
    private boolean visible(JsonObject row, Access viewer) throws SQLException {
        if (viewer == null) return true;
        for (var raw : refs(row)) {
            var ref = raw.getAsJsonObject();
            var source = reader.currentFor(viewer, "CITE", get(ref, "record_id"));
            if (source == null || !"ONLINE".equals(get(source, "status"))
                    || num(source, "revision") != num(ref, "revision")) return false;
        }
        return true;
    }

    private static boolean applicable(String saved, String scope, Access viewer) {
        return saved.equals("global") || (scope == null || saved.equals(scope))
                && (viewer == null || saved.equals(viewer.scope()));
    }

    private enum Origin { MANUAL, PREFERRED, DECLARED, NICKNAME, CARD, MANUAL_ALIAS }
    private record Choice(String name, Origin origin) {}

    /** 返回有效称呼及来源类别。全局改称按原话发生时间排列，独立证据允许在撤回后重选。 */
    private List<Choice> choices(String pid, Collection<String> rooms, Access viewer) throws SQLException {
        var result = new ArrayList<Choice>();
        for (var m : manual(pid)) result.add(new Choice(get(m, "name"),
                get(m, "name").equals(get(m, "subject")) ? Origin.MANUAL : Origin.MANUAL_ALIAS));
        var claims = store.rows("SELECT c.* FROM dream_name_claims c WHERE c.person_id=? AND c.confirmed=1 "
                + CURRENT + """
                 ORDER BY CASE WHEN c.relation='PREFERRED_NAME' AND c.scope='global' THEN 0
                   WHEN c.relation IN ('SELF_NAME','REVIEWED','DIRECT_CONFIRMATION','PREFERRED_NAME') THEN 1
                   WHEN c.relation='NICKNAME' THEN 2 ELSE 3 END,
                   CASE WHEN c.relation='PREFERRED_NAME' AND c.scope='global' THEN c.occurred END DESC,
                   CASE WHEN c.relation='PREFERRED_NAME' AND c.scope='global' THEN c.id END DESC,c.id
                """, pid, home);
        for (var claim : claims) {
            String scope = get(claim, "scope");
            if (rooms != null && rooms.stream().noneMatch(room -> applicable(scope, room, viewer))
                    || !applicable(scope, null, viewer) || !visible(claim, viewer)) continue;
            Origin origin = switch (get(claim, "relation")) {
                case "PREFERRED_NAME" -> scope.equals("global") ? Origin.PREFERRED : Origin.DECLARED;
                case "NICKNAME" -> Origin.NICKNAME;
                case "CARD" -> Origin.CARD;
                default -> Origin.DECLARED;
            };
            result.add(new Choice(get(claim, "name"), origin));
        }
        return result;
    }

    /** 已发布的主名只有在明确全局改称或唯一依据失效时才换。首次只有多个同级显示名时等待确认。 */
    private static String choose(List<Choice> choices, String prior) {
        for (Origin rank : List.of(Origin.MANUAL, Origin.PREFERRED))
            for (Choice option : choices) if (option.origin == rank) return option.name;
        if (prior != null && choices.stream().anyMatch(c -> c.name.equals(prior))) return prior;
        for (Choice option : choices) if (option.origin == Origin.DECLARED) return option.name;
        for (Origin rank : List.of(Origin.NICKNAME, Origin.CARD)) {
            var display = new LinkedHashSet<String>();
            for (Choice choice : choices) if (choice.origin == rank) display.add(choice.name);
            if (!display.isEmpty()) return display.size() == 1 ? display.getFirst() : null;
        }
        return null;
    }

    String primary(String pid, Access viewer) throws SQLException {
        var rows = store.rows("SELECT primary_name FROM dream_identities WHERE person_id=?", pid);
        if (rows.isEmpty()) return null;
        var old = rows.getFirst().get("primary_name");
        String prior = old.isJsonNull() ? null : old.getAsString();
        String current = choose(choices(pid, null, null), prior);
        if (!Objects.equals(current, prior))
            store.update("UPDATE dream_identities SET primary_name=? WHERE person_id=?", current, pid);
        return viewer == null || current == null || permitsName(pid, current, viewer.scope(), viewer) ? current : null;
    }

    boolean permitsName(String pid, String name, String scope, Access viewer) throws SQLException {
        return choices(pid, scope == null ? null : List.of(scope), viewer).stream().anyMatch(c -> c.name.equals(name));
    }

    /** 全部精确同名者均参与歧义判断，群名片只在它有依据的原群内参与查询。 */
    Set<String> ownersOf(String name, String scope) throws SQLException {
        var owners = new TreeSet<String>();
        String manual = manualId(name);
        if (manual != null) owners.add(manual);
        for (var row : store.rows("SELECT DISTINCT c.person_id,c.scope FROM dream_name_claims c"
                + " WHERE c.name=? AND c.confirmed=1 " + CURRENT, name, home))
            if (applicable(get(row, "scope"), scope, null)) owners.add(get(row, "person_id"));
        return owners;
    }

    /** 关系或其可用证据发生变化时才换版，重复签发、空批次和重启都保持原代数。 */
    private long version(String pid) throws SQLException {
        var parts = new ArrayList<String>();
        for (var row : manual(pid)) parts.add("m:" + get(row, "name"));
        for (var row : store.rows("SELECT c.id FROM dream_name_claims c WHERE c.person_id=? " + CURRENT
                + " ORDER BY c.id", pid, home)) parts.add("c:" + num(row, "id"));
        String signature = parts.isEmpty() ? "" : JSON.toJson(parts);
        store.update("UPDATE dream_identities SET version=version+1,signature=? WHERE person_id=? AND signature<>?",
                signature, pid, signature);
        return num(store.rows("SELECT version FROM dream_identities WHERE person_id=?", pid).getFirst(), "version");
    }

    private Set<String> mentionedAccounts(JsonObject e) throws SQLException {
        var result = new LinkedHashSet<String>();
        if (e.has("mention_ids")) for (var account : e.getAsJsonArray("mention_ids")) result.add(qq(account.getAsString()));
        return result;
    }

    /** 新成员仍能以真实账号签入。文本提及只提供相关候选，按完整称呼查索引并保留所有同名者。 */
    private Map<String, Set<String>> mentionedNames(JsonObject e) throws SQLException {
        String content = get(e, "content"), room = get(e, "conversation_id");
        String speaker = e.has("speaker") ? get(e, "speaker") : "";
        var found = new LinkedHashMap<String, Set<String>>();
        var rows = store.rows("""
                SELECT name FROM dream_person_names WHERE instr(?,name)>0 OR name=?
                UNION SELECT name FROM dream_name_claims WHERE confirmed=1 AND (instr(?,name)>0 OR name=?)
                ORDER BY name
                """, content, speaker, content, speaker);
        for (var row : rows) {
            String name = get(row, "name");
            var people = ownersOf(name, room);
            if (!people.isEmpty()) found.put(name, people);
        }
        // 只有作为完整较长称呼的一部分出现的短名，不能额外签发第二个人。
        var all = List.copyOf(found.keySet());
        found.keySet().removeIf(shortName -> !speaker.equals(shortName) && containedOnly(content, shortName, all));
        return found;
    }

    private static boolean containedOnly(String text, String shortName, List<String> names) {
        for (int at = text.indexOf(shortName); at >= 0; at = text.indexOf(shortName, at + shortName.length())) {
            boolean inside = false;
            for (String longer : names) {
                if (longer.length() <= shortName.length()) continue;
                for (int k = longer.indexOf(shortName); k >= 0; k = longer.indexOf(shortName, k + 1))
                    if (text.startsWith(longer, at - k)) inside = true;
            }
            if (!inside) return false;
        }
        return true;
    }

    JsonArray persons(Collection<JsonObject> evidence, Collection<String> extraPersonIds) throws SQLException {
        var rooms = new TreeMap<String, Set<String>>();
        for (var e : evidence) {
            var related = mentionedAccounts(e);
            for (var field : List.of("speaker_person_id", "reviewed_person_id"))
                if (e.has(field)) related.add(get(e, field));
            for (var matches : mentionedNames(e).values()) related.addAll(matches);
            for (String pid : related)
                rooms.computeIfAbsent(pid, key -> new TreeSet<>()).add(get(e, "conversation_id"));
        }
        for (String pid : extraPersonIds) if (known(pid)) rooms.computeIfAbsent(pid, key -> new TreeSet<>());
        var people = new JsonArray();
        for (var entry : rooms.entrySet()) {
            String pid = entry.getKey();
            // 即使另一个群的卡片在同一角色库里有效，它也不能因同账号在本群出现而落入这个批次。
            var contexts = entry.getValue();
            var displayed = choices(pid, contexts.isEmpty() ? Set.of("global") : contexts, null);
            // 首次签发即可引用获准的真实显示名。只有正式 commit 才会把它及其来源记入身份索引。
            for (var e : evidence) {
                if (spokenBy(e, pid) && get(e, "kind").equals("observation")) {
                    if (e.has("nickname")) displayed.add(new Choice(get(e, "nickname"), Origin.NICKNAME));
                    if (e.has("card")) displayed.add(new Choice(get(e, "card"), Origin.CARD));
                }
                if (pid.equals(e.has("reviewed_person_id") ? get(e, "reviewed_person_id") : null))
                    displayed.add(new Choice(get(e, "reviewed_name"), Origin.DECLARED));
            }
            String main = choose(displayed, primary(pid, null));
            var aliases = new TreeSet<String>();
            for (Choice c : displayed) if (!c.name.equals(main)) aliases.add(c.name);
            var hint = obj("person_id", pid, "identity_version", version(pid));
            if (main != null) hint.addProperty("subject", main);
            hint.add("aliases", JSON.toJsonTree(aliases));
            people.add(hint);
        }
        return people;
    }

    /** 回复 ID 只在同一真实会话中定位。歧义定位无法证明该问题就是被回答的那条。 */
    private String replyTarget(JsonObject e) throws SQLException {
        if (!e.has("reply_message_id")) return null;
        return replyTarget(get(e, "conversation_id"), get(e, "reply_message_id"));
    }

    private String replyTarget(String room, String messageId) throws SQLException {
        var rows = store.rows("""
                SELECT r.id FROM records r JOIN revisions v ON v.record_id=r.id AND v.revision=r.revision
                WHERE r.conversation=? AND r.kind IN ('observation','self_expression')
                AND json_extract(v.body,'$.channel.type') IN ('qq_private','qq_group')
                AND json_extract(v.body,'$.channel.message_id')=? LIMIT 2
                """, room, messageId);
        return rows.size() == 1 ? get(rows.getFirst(), "id") : null;
    }

    List<String> relatedEvidence(Collection<JsonObject> initial) throws SQLException {
        var related = new LinkedHashSet<String>();
        for (var e : initial) {
            String target = replyTarget(e);
            if (target != null) {
                related.add(target);
                // 问句本身回复某条一手发言时，该条消息的真实作者证明问题是对谁问的。
                var rows = store.rows(Reader.LATEST + " WHERE r.id=? AND " + Dreaming.ELIGIBLE, target, home);
                if (!rows.isEmpty()) {
                    var previous = rows.getFirst();
                    var channel = parse(get(previous, "body")).getAsJsonObject("channel");
                    if (channel != null && channel.has("reply_message_id")) {
                        String original = replyTarget(get(previous, "conversation"), get(channel, "reply_message_id"));
                        if (original != null) related.add(original);
                    }
                }
            }
            if (!e.has("speaker_person_id")) continue;
            for (var row : store.rows("SELECT c.refs FROM dream_name_claims c WHERE c.person_id=? AND c.confirmed=0"
                    + " AND instr(?,c.name)>0 AND (c.scope='global' OR c.scope=?) " + CURRENT + " ORDER BY c.id",
                    get(e, "speaker_person_id"), get(e, "content"), get(e, "conversation_id"), home))
                for (var source : refs(row)) related.add(get(source.getAsJsonObject(), "record_id"));
        }
        return List.copyOf(related);
    }

    boolean selfIntroduces(JsonObject e, String name) {
        String content = get(e, "content");
        return DreamNameStatements.declares(content, content, name, false, false);
    }

    /** 空串代表无需称呼桥接；按名称定位时返回那一项供档案版本长期复核，无法消歧则不作归属。 */
    String attribution(String pid, JsonObject e) throws SQLException {
        var directed = mentionedAccounts(e);
        if (!directed.isEmpty()) return directed.size() == 1 && directed.contains(pid) ? "" : null;
        String actor = e.has("speaker_person_id") ? get(e, "speaker_person_id") : null;
        var mentions = mentionedNames(e);
        var others = new HashSet<String>();
        for (var names : mentions.values()) others.addAll(names);
        if (actor != null) {
            others.remove(actor);
            if (pid.equals(actor) && (others.isEmpty() && !DreamNameStatements.describesAnother(get(e, "content"))
                    || mentions.keySet().stream().anyMatch(n -> selfIntroduces(e, n))))
                return "";
            if (pid.equals(actor) || others.size() != 1 || !others.contains(pid)) return null;
        }
        for (var entry : mentions.entrySet()) {
            String name = entry.getKey();
            if (entry.getValue().size() != 1 || !entry.getValue().contains(pid)) continue;
            if (actor != null && selfIntroduces(e, name)) continue;
            if (actor == null && e.has("speaker") && get(e, "speaker").equals(name) && !selfIntroduces(e, name)) continue;
            if (actor == null && others.size() != 1) return null;
            if (!selfIntroduces(e, name) && !DreamNameStatements.refers(get(e, "content"), name)) continue;
            return name;
        }
        return null;
    }

    /** 所有提案先与同一个签发快照对照；渠道显示名和首选称呼的变动均在这次公开提交所属事务中生效。 */
    void applyClaims(JsonArray claims, JsonObject issued) throws SQLException {
        if (claims.size() > 16) throw invalid("name_claims");
        var offered = new HashMap<String, JsonObject>();
        for (var value : issued.getAsJsonArray("evidence")) {
            var e = value.getAsJsonObject();
            offered.put(get(e, "record_id"), e);
        }
        var versions = new HashMap<String, Long>();
        for (var raw : issued.getAsJsonArray("persons")) {
            var p = raw.getAsJsonObject();
            versions.put(get(p, "person_id"), num(p, "identity_version"));
        }
        for (var raw : claims) {
            if (!(raw instanceof JsonObject claim)) throw invalid("name_claims");
            register(claim, offered, versions);
        }
        for (var raw : issued.getAsJsonArray("evidence")) {
            var e = raw.getAsJsonObject();
            // 显示名来自入站元数据；孤立的会话标题和模型转述都不会抵达这个入口。
            if (e.has("speaker_person_id") && get(e, "kind").equals("observation")) {
                if (e.has("nickname")) recordField(e, "speaker_person_id", "nickname", "NICKNAME", "global");
                if (e.has("card")) recordField(e, "speaker_person_id", "card", "CARD", get(e, "conversation_id"));
            }
            if (e.has("reviewed_person_id")) recordField(e, "reviewed_person_id", "reviewed_name", "REVIEWED", "global");
        }
        // 先确认本次归属的主名，再允许后来的普通别称进入；两次提交之间即使没有读取也保持原名。
        for (String pid : versions.keySet()) primary(pid, null);
    }

    private void recordField(JsonObject e, String personField, String field, String relation, String scope) throws SQLException {
        String name = get(e, field);
        save(get(e, personField), name, relation, scope, List.of(obj("record_id", get(e, "record_id"),
                "revision", num(e, "revision"), "quote", name)), get(e, "occurred_at"), true);
    }

    private record Proof(JsonObject evidence, String quote) {}

    private void register(JsonObject c, Map<String, JsonObject> offered, Map<String, Long> versions) throws SQLException {
        keys(c, "person_id expected_identity_version name relation scope source_refs");
        String pid = text(c, "person_id", 128), name = text(c, "name", 128), scope = text(c, "scope", 256);
        String relation = one(c, "relation", "SELF_NAME", "PREFERRED_NAME", "MENTION", "DIRECT_CONFIRMATION");
        if (!versions.containsKey(pid)) throw invalid("name_claims.person_id");
        if (number(c, "expected_identity_version", 0, Long.MAX_VALUE) != versions.get(pid))
            throw conflict("IDENTITY_VERSION_CONFLICT");
        var proofs = new ArrayList<Proof>();
        var sources = new TreeMap<String, JsonObject>();
        String latest = "";
        for (var value : array(c, "source_refs", 1, 16)) {
            if (!(value instanceof JsonObject ref)) throw invalid("name_claims.source_refs");
            keys(ref, "record_id revision quote");
            String id = id(ref, "record_id"), quote = text(ref, "quote", 2048);
            var e = offered.get(id);
            if (e == null || num(e, "revision") != number(ref, "revision", 1, Long.MAX_VALUE)) throw conflict("SOURCE_CHANGED");
            if (!get(e, "content").contains(quote) || sources.putIfAbsent(id, obj("record_id", id,
                    "revision", num(e, "revision"), "quote", quote)) != null) throw invalid("name_claims.source_refs.quote");
            if (!scope.equals("global") && !scope.equals(get(e, "conversation_id"))) throw invalid("name_claims.scope");
            proofs.add(new Proof(e, quote));
            if (get(e, "occurred_at").compareTo(latest) > 0) latest = get(e, "occurred_at");
        }
        if (proofs.stream().noneMatch(p -> p.quote.contains(name))) throw invalid("name_claims.name");
        boolean confirmed = false;
        if (relation.equals("SELF_NAME") || relation.equals("PREFERRED_NAME")) {
            if (proofs.stream().noneMatch(p -> spokenBy(p.evidence, pid))) throw invalid("name_claims.person_id");
            confirmed = proofs.stream().allMatch(p -> spokenBy(p.evidence, pid) && DreamNameStatements.declares(
                    get(p.evidence, "content"), p.quote, name, relation.equals("PREFERRED_NAME"), scope.equals("global")));
        } else if (relation.equals("DIRECT_CONFIRMATION")) {
            if (proofs.stream().noneMatch(p -> spokenBy(p.evidence, pid))) throw invalid("name_claims.person_id");
            confirmed = directedConfirmation(pid, name, scope, proofs, offered, sources);
        }
        save(pid, name, relation, scope, sources.values(), latest, confirmed);
    }

    private static boolean spokenBy(JsonObject e, String pid) {
        return e.has("speaker_person_id") && get(e, "speaker_person_id").equals(pid);
    }

    /** 同场合真实回复指针、明确的问句、目标账号的肯定原话缺一不可。错答另一句仅保留候选。 */
    private boolean directedConfirmation(String pid, String name, String scope, List<Proof> proofs,
                                         Map<String, JsonObject> offered, Map<String, JsonObject> sources) throws SQLException {
        if (proofs.size() < 2 || proofs.size() > 3) return false;
        for (var answer : proofs) {
            if (!spokenBy(answer.evidence, pid)) continue;
            String questionId = replyTarget(answer.evidence);
            for (var question : proofs) {
                var e = question.evidence;
                if (!get(e, "record_id").equals(questionId) || !e.has("speaker_person_id") || spokenBy(e, pid)) continue;
                var addressed = mentionedAccounts(e);
                if (!addressed.isEmpty() && !(addressed.size() == 1 && addressed.contains(pid))) continue;
                if (scope.equals("global") && DreamNameStatements.locallyScoped(get(e, "content"))
                        || !DreamNameStatements.asks(get(e, "content"), question.quote, name)
                        || !DreamNameStatements.agrees(get(answer.evidence, "content"), answer.quote, name, scope.equals("global"))) continue;
                if (!addressed.isEmpty() || get(e, "conversation_id").startsWith("c2c:onebot:")) return proofs.size() == 2;
                String earlier = replyTarget(e);
                var witness = offered.get(earlier);
                if (witness == null || !spokenBy(witness, pid) || proofs.size() == 3 && !sources.containsKey(earlier)) continue;
                // 群问题没有 @ 时，原被回复消息也是身份依据；撤回或失去它的 CITE 时问答即无法继续佐证。
                sources.putIfAbsent(earlier, ref(earlier, num(witness, "revision")));
                return true;
            }
        }
        return false;
    }

    private void save(String pid, String name, String relation, String scope, Collection<JsonObject> refs,
                      String occurred, boolean confirmed) throws SQLException {
        store.update("""
                INSERT INTO dream_name_claims(person_id,name,relation,scope,refs,occurred,confirmed) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(person_id,name,relation,scope,refs) DO UPDATE SET confirmed=max(confirmed,excluded.confirmed)
                """, pid, name, relation, scope, JSON.toJson(refs), occurred, confirmed ? 1 : 0);
    }
}
