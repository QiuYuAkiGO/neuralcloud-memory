package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.sql.SQLException;
import java.util.*;

/** Memory 权威整理入口。批次、档案、来源依赖和覆盖回执均由调用方所在的单写事务保护。 */
final class Dreaming {
    private final Store store;
    private final Reader reader;
    private final String home;
    private static final String POSITION = "stream_id policy_version after through";
    static final String PROFILE_ROWS =
            """
SELECT p.kind,p.subject,p.version,p.active,v.body FROM dream_profiles p JOIN dream_revisions v
USING(kind,subject,version)
""";
    // 当前部署负责的会话与原作者给整理器的 CITE 授权是两种独立的合法来源。
    private static final String ELIGIBLE =
            """
r.status='ONLINE' AND r.kind IN ('observation','self_expression','tool_result')
AND json_type(v.body,'$.source_refs') IS NULL AND (r.conversation IN
(SELECT value FROM json_each((SELECT scopes FROM dream_state WHERE singleton=1)))
OR EXISTS (SELECT 1 FROM visibility_grants g WHERE g.record_id=r.id AND g.revision=r.revision
AND g.target=? AND g.use='CITE' AND g.status='ACTIVE'))
""";

    /** 对外导出沿用当前服务的受众读取策略，内部取证由 ELIGIBLE 校验整理范围。 */
    Dreaming(Store store, Reader reader) {
        this.store = store;
        this.reader = reader;
        home = "dreaming:" + store.character;
    }

    private void internal(Access ctx, String role) {
        if (!home.equals(ctx.scope())
                || !"DREAMING".equals(ctx.actor())
                || !role.equals(ctx.role())) throw denied();
    }

    /** 来源失效时同事务退役当前认识，历史修订仍供内部对账。传 null 表示部署会话集合刚刚变更。 */
    static void invalidate(Store store, String id) throws SQLException {
        if (id != null
                && store.rows("SELECT 1 FROM dream_sources WHERE record_id=? LIMIT 1", id)
                        .isEmpty()) return;
        String sql =
                """
                UPDATE dream_profiles AS p SET active=0 WHERE p.active=1 AND EXISTS
                (SELECT 1 FROM dream_sources d WHERE d.kind=p.kind AND d.subject=p.subject
                """
                        + (id == null ? "" : " AND d.record_id=? ")
                        + """
 AND NOT EXISTS (SELECT 1 FROM records r JOIN revisions v ON r.id=v.record_id AND r.revision=v.revision
 WHERE r.id=d.record_id AND r.revision=d.revision AND
"""
                        + ELIGIBLE
                        + "))";
        if (id == null) store.update(sql, "dreaming:" + store.character);
        else store.update(sql, id, "dreaming:" + store.character);
    }

    /** 领取不确认。资料或授权在推理期间变化时，下一次取证将发出新拍的材料。 */
    JsonObject batch(JsonObject req) throws SQLException {
        keys(req, "context scopes limit");
        internal(Access.from(store, req), "ORCHESTRATOR");
        long limit = number(req, "limit", 1, 16);
        var scopes = new TreeSet<String>();
        for (var raw : array(req, "scopes", 0, 1024)) {
            if (!(raw instanceof JsonPrimitive p)
                    || !p.isString()
                    || p.getAsString().isBlank()
                    || p.getAsString().length() > 256
                    || !scopes.add(p.getAsString())) throw invalid("scopes");
        }
        String allowed = JSON.toJson(scopes);
        var state = state();
        if (!allowed.equals(get(state, "scopes"))) {
            store.update(
                    "UPDATE dream_state SET"
                        + " scopes=?,policy_version=policy_version+1,covered=0,packet=NULL",
                    allowed);
            invalidate(store, null);
            state = state();
        }
        long after = num(state, "covered"), policy = num(state, "policy_version");
        long through =
                num(
                        store.rows(
                                        "SELECT coalesce(max(seq),?) n FROM (SELECT seq FROM"
                                            + " changes WHERE seq>? ORDER BY seq LIMIT ?)",
                                        after,
                                        after,
                                        limit)
                                .getFirst(),
                        "n");
        var packet = material(policy, after, through);
        if (through != after) {
            var previous = state.get("packet");
            if (!previous.isJsonNull()) {
                var old = parse(previous.getAsString());
                if (old.equals(packet)) return old;
                // 相同覆盖区间也可能对应不同的修订或引用权，阻止旧的空判断覆盖新证据。
                policy++;
                packet.addProperty("policy_version", policy);
            }
            store.update(
                    "UPDATE dream_state SET policy_version=?,packet=?",
                    policy,
                    JSON.toJson(packet));
        }
        return packet;
    }

    private JsonObject state() throws SQLException {
        return store.rows("SELECT * FROM dream_state WHERE singleton=1").getFirst();
    }

    /** 一手证据带上人物/场合线索。被替换的版本和未获授权的正文始终不进入模型材料。 */
    private JsonObject evidence(String id) throws SQLException {
        var rows = store.rows(Reader.LATEST + " WHERE r.id=? AND " + ELIGIBLE, id, home);
        if (rows.isEmpty()) return null;
        var r = rows.getFirst();
        var body = parse(get(r, "body"));
        var e =
                obj(
                        "record_id",
                        id,
                        "revision",
                        num(r, "revision"),
                        "conversation_id",
                        get(r, "conversation"),
                        "kind",
                        get(r, "kind"),
                        "occurred_at",
                        get(r, "occurred"),
                        "content",
                        get(body, "content"));
        for (var field : List.of("speaker", "topic", "source_venue"))
            if (body.has(field)) e.add(field, body.get(field));
        var channel = body.getAsJsonObject("channel");
        if (channel != null && channel.has("sender_id"))
            e.add("sender_id", channel.get("sender_id"));
        store.dreamPeople.annotateSpeaker(e, channel);
        return e;
    }

    /** 以具体变更选取相关档案；旧版只作线索，其所有可用原文需重新按现版取证。 */
    private JsonObject material(long policy, long after, long through) throws SQLException {
        var evidence = new LinkedHashMap<String, JsonObject>();
        var profiles = new JsonArray();
        var personNames = new TreeSet<String>();
        if (through > after) {
            var touched =
                    store.rows(
                            "SELECT record_id FROM changes WHERE seq>? AND seq<=? GROUP BY"
                                + " record_id ORDER BY min(seq)",
                            after,
                            through);
            var ids = new ArrayList<String>();
            for (var row : touched) {
                String id = get(row, "record_id");
                ids.add(id);
                var current = evidence(id);
                if (current != null) evidence.put(id, current);
            }
            // 直接依赖此次变更，或人物/主题名称出现在一手材料中时才取相关旧版。
            var firsthand = List.copyOf(evidence.values());
            personNames.addAll(store.dreamPeople.mentioned(firsthand));
            var related =
                    store.rows(
                            PROFILE_ROWS
                                    + """
WHERE (p.kind<>'person' OR NOT EXISTS (SELECT 1 FROM dream_person_names n
WHERE n.name=p.subject AND n.name<>n.subject)) AND (
(p.kind='person' AND p.subject IN (SELECT value FROM json_each(?))) OR
EXISTS (SELECT 1 FROM dream_sources d WHERE d.kind=p.kind AND d.subject=p.subject
AND d.record_id IN (SELECT value FROM json_each(?))) OR EXISTS (SELECT 1 FROM json_each(?) e
WHERE instr(json_extract(e.value,'$.content'),p.subject)>0 OR json_extract(e.value,'$.speaker')=p.subject
OR json_extract(e.value,'$.topic')=p.subject)) ORDER BY p.kind,p.subject
""",
                            JSON.toJson(personNames),
                            JSON.toJson(ids),
                            JSON.toJson(firsthand));
            for (var profile : related) {
                var current = parse(get(profile, "body"));
                boolean supported = false;
                for (var ref : current.getAsJsonArray("source_refs")) {
                    String id = get(ref.getAsJsonObject(), "record_id");
                    var leaf = evidence.get(id);
                    if (leaf == null) leaf = evidence(id);
                    if (leaf != null) {
                        evidence.put(id, leaf);
                        supported = true;
                    }
                }
                // 全部原依据已撤回时，若本批独立材料重新提及同一主题，仅交付版本身份。
                String subject = get(profile, "subject");
                if (supported
                        || (get(profile, "kind").equals("person") && personNames.contains(subject))
                        || firsthand.stream()
                                .anyMatch(
                                        e ->
                                                get(e, "content").contains(subject)
                                                        || (e.has("speaker")
                                                                && subject.equals(
                                                                        get(e, "speaker")))
                                                        || (e.has("topic")
                                                                && subject.equals(
                                                                        get(e, "topic"))))) {
                    if (get(profile, "kind").equals("person")) personNames.add(subject);
                    profiles.add(
                            num(profile, "active") == 1
                                    ? current
                                    : obj(
                                            "kind",
                                            get(profile, "kind"),
                                            "subject",
                                            get(profile, "subject"),
                                            "version",
                                            num(profile, "version")));
                }
            }
        }
        var packet = obj(
                "stream_id",
                store.stream,
                "policy_version",
                policy,
                "after",
                after,
                "through",
                through,
                "evidence",
                evidence.values(),
                "profiles",
                profiles);
        var hints = store.dreamPeople.hints(personNames);
        if (!hints.isEmpty()) packet.add("person_names", hints);
        return packet;
    }

    /** 先按原命令查回执，再核对已发批次与当前证据。所有档案修订、覆盖和回执共同提交或回滚。 */
    JsonObject commit(JsonObject cmd) throws SQLException {
        keys(cmd, "context source_instance event_id changes " + POSITION);
        internal(Access.from(store, cmd), "ROUTER");
        String event = event(cmd);
        var prior = lookup(event);
        if (!prior.isEmpty()) {
            var row = prior.getFirst();
            if (!parse(get(row, "command")).equals(cmd))
                throw conflict("EVENT_ID_CONTENT_CONFLICT");
            return parse(get(row, "receipt"));
        }
        String stream = text(cmd, "stream_id", 128);
        long after = number(cmd, "after", 0, Long.MAX_VALUE),
                through = number(cmd, "through", 0, Long.MAX_VALUE);
        long policy = number(cmd, "policy_version", 1, Long.MAX_VALUE);
        var changes = array(cmd, "changes", 0, 8);
        var state = state();
        if (!store.stream.equals(stream)
                || num(state, "covered") != after
                || num(state, "policy_version") != policy
                || through <= after
                || state.get("packet").isJsonNull()) throw conflict("DREAM_BATCH_CHANGED");
        var issued = parse(get(state, "packet"));
        if (num(issued, "through") != through || !issued.equals(material(policy, after, through)))
            throw conflict("DREAM_BATCH_CHANGED");
        var offered = new HashMap<String, Long>();
        for (var entry : issued.getAsJsonArray("evidence")) {
            var e = entry.getAsJsonObject();
            offered.put(get(e, "record_id"), num(e, "revision"));
        }
        var identities = new HashSet<List<String>>();
        for (var raw : changes) {
            if (!(raw instanceof JsonObject change)) throw invalid("changes");
            publish(change, offered, identities);
        }
        var receipt =
                obj(
                        "status",
                        "DURABLE",
                        "event_id",
                        event,
                        "stream_id",
                        stream,
                        "policy_version",
                        policy,
                        "after",
                        after,
                        "through",
                        through);
        store.update(
                "INSERT INTO dream_receipts VALUES(?,?,?,?)",
                store.writer,
                event,
                JSON.toJson(cmd),
                JSON.toJson(receipt));
        store.update("UPDATE dream_state SET covered=?,packet=NULL", through);
        return receipt;
    }

    /** 独立档案以 kind/subject 为身份。宿主绑定 author，保留历史正文与原始版本引用。 */
    private void publish(JsonObject change, Map<String, Long> offered, Set<List<String>> identities)
            throws SQLException {
        keys(change, "kind subject expected_version text scope reason source_refs");
        String kind = one(change, "kind", "person", "topic"),
                subject = text(change, "subject", 128);
        if ((kind.equals("person") && store.dreamPeople.isAlias(subject))
                || !identities.add(List.of(kind, subject))) throw invalid("changes.kind/subject");
        long expected = number(change, "expected_version", 0, Long.MAX_VALUE - 1);
        checkBody(change);
        var refs = array(change, "source_refs", 1, 16);
        var seen = new HashSet<String>();
        for (var entry : refs) {
            if (!(entry instanceof JsonObject r)) throw invalid("source_refs");
            keys(r, "record_id revision");
            String id = id(r, "record_id");
            long revision = number(r, "revision", 1, Long.MAX_VALUE);
            if (!seen.add(id)) throw invalid("source_refs.record_id");
            if (!Objects.equals(offered.get(id), revision)) throw conflict("SOURCE_CHANGED");
        }
        var rows =
                store.rows(
                        "SELECT version FROM dream_profiles WHERE kind=? AND subject=?",
                        kind,
                        subject);
        if ((rows.isEmpty() ? 0 : num(rows.getFirst(), "version")) != expected)
            throw conflict("REVISION_CONFLICT");
        var profile = change.deepCopy();
        profile.remove("expected_version");
        profile.addProperty("version", expected + 1);
        profile.addProperty("author", "DREAMING");
        saveRevision(store, profile);
    }

    /** 归档正文和手工确认身份后的合并版本遵守相同的发布限制。 */
    static void checkBody(JsonObject profile) {
        if (!text(profile, "text", 2048).startsWith("我")) throw invalid("text");
        text(profile, "scope", 512);
        text(profile, "reason", 1024);
    }

    /** 版本正文和撤回依赖索引必须在同一事务内完成，供普通修订和旧人物归并共用。 */
    static void saveRevision(Store store, JsonObject profile) throws SQLException {
        String kind = get(profile, "kind"), subject = get(profile, "subject");
        long next = num(profile, "version");
        store.update(
                "INSERT INTO dream_profiles VALUES(?,?,?,1) ON CONFLICT(kind,subject) DO UPDATE SET"
                        + " version=excluded.version,active=1",
                kind,
                subject,
                next);
        store.update(
                "INSERT INTO dream_revisions VALUES(?,?,?,?)",
                kind,
                subject,
                next,
                JSON.toJson(profile));
        store.update("DELETE FROM dream_sources WHERE kind=? AND subject=?", kind, subject);
        for (var entry : profile.getAsJsonArray("source_refs")) {
            var ref = entry.getAsJsonObject();
            store.update(
                    "INSERT INTO dream_sources VALUES(?,?,?,?)",
                    kind,
                    subject,
                    get(ref, "record_id"),
                    num(ref, "revision"));
        }
    }

    private String event(JsonObject cmd) {
        if (!store.writer.equals(text(cmd, "source_instance", 128))) throw denied();
        return text(cmd, "event_id", 256);
    }

    private List<JsonObject> lookup(String event) throws SQLException {
        return store.rows(
                "SELECT command,receipt FROM dream_receipts WHERE source=? AND event=?",
                store.writer,
                event);
    }

    JsonObject receipt(JsonObject req) throws SQLException {
        keys(req, "context source_instance event_id");
        internal(Access.from(store, req), "ORCHESTRATOR");
        var rows = lookup(event(req));
        return rows.isEmpty() ? obj("status", "NOT_FOUND") : parse(get(rows.getFirst(), "receipt"));
    }

    /** 档案标题、认识和理由一同验权，部分依据可见时不暴露该档案的存在。 */
    JsonObject profiles(JsonObject req) throws SQLException {
        keys(req, "context");
        var ctx = Access.from(store, req);
        boolean export = home.equals(ctx.scope());
        if (export) internal(ctx, "ORCHESTRATOR");
        var profiles = new JsonArray();
        for (var row : store.rows(PROFILE_ROWS + " WHERE p.active=1 ORDER BY p.kind,p.subject")) {
            var profile = parse(get(row, "body"));
            boolean visible = true;
            if (!export)
                for (var entry : profile.getAsJsonArray("source_refs")) {
                    var ref = entry.getAsJsonObject();
                    var source = reader.currentFor(ctx, "CITE", get(ref, "record_id"));
                    if (source == null
                            || !"ONLINE".equals(get(source, "status"))
                            || num(source, "revision") != num(ref, "revision")) {
                        visible = false;
                        break;
                    }
                }
            if (visible) profiles.add(profile);
        }
        return obj("profiles", profiles);
    }
}
