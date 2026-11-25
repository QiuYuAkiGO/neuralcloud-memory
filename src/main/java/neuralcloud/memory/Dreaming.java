package neuralcloud.memory;

import static neuralcloud.memory.DreamGroupProgress.GROUP;
import static neuralcloud.memory.DreamGroupProgress.IDENTITY;
import static neuralcloud.memory.DreamGroupProgress.LIVE;
import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

import java.sql.SQLException;
import java.util.*;

/** Memory 权威整理入口。批次、档案、来源依赖和覆盖回执均由调用方所在的单写事务保护。 */
final class Dreaming {
    private final Store store;
    private final String home;
    private final DreamIdentities names;
    private final DreamProfiles people;
    private static final String POSITION = "stream_id policy_version after through";
    static final String PROFILE_ROWS =
            """
SELECT p.kind,p.subject,p.version,p.active,v.body FROM dream_profiles p JOIN dream_revisions v
USING(kind,subject,version)
""";
    // 实时和回扫共用成员发言准入。其他一手记录沿用负责会话、可核实的旧群来源或原作者的 CITE 授权。
    static final String ELIGIBLE =
            """
r.status='ONLINE' AND r.kind IN ('observation','self_expression','tool_result')
AND json_type(v.body,'$.source_refs') IS NULL AND CASE
WHEN r.kind='observation' AND json_extract(v.body,'$.channel.type')='qq_group'
AND json_type(v.body,'$.identity') IS NULL AND EXISTS (SELECT 1 FROM dream_group_rooms g WHERE g.room=r.conversation)
THEN EXISTS (SELECT 1 FROM dream_group_messages m WHERE m.record_id=r.id AND m.revision=r.revision)
ELSE (r.conversation IN
(SELECT value FROM json_each((SELECT scopes FROM dream_state WHERE singleton=1)))
OR EXISTS (SELECT 1 FROM dream_group_access a WHERE a.record_id=r.id AND a.revision=r.revision)
OR EXISTS (SELECT 1 FROM visibility_grants g WHERE g.record_id=r.id AND g.revision=r.revision
AND g.target=? AND g.use='CITE' AND g.status='ACTIVE')) END
""";

    /** 对外导出沿用当前服务的受众读取策略，内部取证由 ELIGIBLE 校验整理范围。 */
    Dreaming(Store store, Reader reader) {
        this.store = store;
        home = "dreaming:" + store.character;
        names = new DreamIdentities(store, reader);
        people = new DreamProfiles(store, reader, names, this::evidence);
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
        keys(req, "context scopes limit include_group_history");
        internal(Access.from(store, req), "ORCHESTRATOR");
        long limit = number(req, "limit", 1, 16);
        boolean includeGroups = false;
        if (req.has("include_group_history")) {
            if (!(req.get("include_group_history") instanceof JsonPrimitive p) || !p.isBoolean())
                throw invalid("include_group_history");
            includeGroups = p.getAsBoolean();
        }
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
        boolean changedScopes = !allowed.equals(get(state, "scopes"));
        boolean changedMode = DreamGroupProgress.enable(store, includeGroups, changedScopes);
        if (changedScopes || changedMode) {
            // 只有负责的群可以解释旧群号。先更换范围，再复核旧档，以便收回已关闭群的来源。
            DreamGroupHistory.rooms(store, includeGroups ? scopes : List.of());
            store.update(
                    "UPDATE dream_state SET scopes=?,policy_version=policy_version+1,covered=?,packet=NULL",
                    allowed, changedScopes ? 0 : num(state, "covered"));
            if (changedScopes) store.update("UPDATE dream_identity_progress SET cursor=target");
            invalidate(store, null);
            state = state();
        }
        var progress = progress();
        long policy = num(state, "policy_version");
        var range = DreamGroupProgress.next(store, state, progress, limit);
        long after = range.after(), through = range.through();
        var packet = material(policy, after, through, range.lane());
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

    private JsonObject progress() throws SQLException {
        return store.rows("SELECT * FROM dream_identity_progress WHERE singleton=1").getFirst();
    }

    /** 一手证据带上人物/场合线索。被替换的版本和未获授权的正文始终不进入模型材料。 */
    private JsonObject evidence(String id) throws SQLException {
        var rows = store.rows(Reader.LATEST + " WHERE r.id=? AND " + ELIGIBLE, id, home);
        if (rows.isEmpty()) return null;
        var r = rows.getFirst();
        boolean legacyGroup = get(r, "conversation").startsWith("qq:group:");
        if (legacyGroup && DreamGroupHistory.nameRecord(store, id)) return null;
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
        if (legacyGroup && DreamGroupHistory.project(store, e)) {
            // 规范群场合来自当前获准的唯一映射，作者来自封存协议指针，两者都不能从正文推断。
            String sender = get(e, "sender_id");
            store.dreamPeople.annotateQqSpeaker(e, sender);
            names.annotateQqSpeaker(e, sender);
            return e;
        }
        if (channel != null && channel.has("sender_id"))
            e.add("sender_id", channel.get("sender_id"));
        store.dreamPeople.annotateSpeaker(e, channel);
        names.annotate(e, body);
        return e;
    }

    /** 以具体变更选取相关档案；旧版只作线索，其所有可用原文需重新按现版取证。 */
    private JsonObject material(long policy, long after, long through, String lane) throws SQLException {
        var records = new LinkedHashMap<String, JsonObject>();
        var profiles = new JsonArray();
        var manualNames = new TreeSet<String>();
        var relatedIds = new TreeSet<String>();
        if (through > after) {
            var touched = new LinkedHashSet<String>();
            for (var row : touched(after, through, lane)) {
                String id = get(row, "record_id");
                touched.add(id);
                include(records, id);
                // 初次入账的配对名片由对应原话一起携带。只有后来的修订或撤权才需重评关联原话。
                if (num(row, "recheck_pair") != 0)
                    for (String related : DreamGroupHistory.associated(store, id))
                        include(records, related);
            }
            var firsthand = List.copyOf(records.values());
            var rooms = new TreeSet<String>();
            var speakers = new HashSet<String>();
            for (var e : firsthand) {
                rooms.add(get(e, "conversation_id"));
                if (e.has("speaker_person_id")) speakers.add(get(e, "speaker_person_id"));
            }
            // 群历史的回复链与旧档只能补入本页这些群里的真实成员发言。
            Set<String> groupRooms = GROUP.equals(lane) ? rooms : null;
            for (String id : names.relatedEvidence(firsthand)) includeContext(records, id, groupRooms);
            manualNames.addAll(store.dreamPeople.mentioned(firsthand));
            for (String name : manualNames) {
                String pid = names.manualId(name);
                if (pid != null) relatedIds.add(pid);
            }
            var issuedNames = new HashMap<String, JsonObject>();
            for (var item : names.persons(records.values(), relatedIds)) {
                var person = item.getAsJsonObject();
                String id = get(person, "person_id");
                relatedIds.add(id);
                issuedNames.put(id, person);
            }

            // 以人物 ID 找到改名后的档案及各自旧依据，重名的两个账号仍有独立版本。
            for (var view : people.snapshot().values()) {
                if (groupRooms != null && !speakers.contains(view.owner())) continue;
                var body = view.body();
                String subject = get(body, "subject");
                if (relatedIds.contains(view.owner()) || (!names.known(view.owner()) && mentioned(firsthand, subject))
                        || affected(body, touched)) {
                    if (names.known(view.owner()) && !applicable(view.owner(), subject, rooms)) {
                        // 另一群的名片不能通过旧档正文进入本群。当前范围有独立称呼时只给版本线索。
                        var local = issuedNames.get(view.owner());
                        if (local != null && local.has("subject"))
                            profiles.add(obj("kind", "person", "subject", get(local, "subject"),
                                    "person_id", view.owner(), "version", num(body, "version")));
                        continue;
                    }
                    for (var ref : body.getAsJsonArray("source_refs"))
                        includeContext(records, get(ref.getAsJsonObject(), "record_id"), groupRooms);
                    profiles.add(body);
                    manualNames.add(subject);
                    if (names.known(view.owner())) relatedIds.add(view.owner());
                }
            }

            // 主题沿用原版本。已退役的档案只交付当前版本号及仍合法的独立原文。
            for (var row : store.rows(PROFILE_ROWS + " WHERE p.kind='topic' OR p.active=0 ORDER BY p.kind,p.subject")) {
                var body = parse(get(row, "body"));
                String kind = get(row, "kind"), subject = get(body, "subject");
                if (kind.equals("person") && store.dreamPeople.isAlias(subject)) continue;
                String owner = body.has("person_id") ? get(body, "person_id") : names.manualId(subject);
                if (groupRooms != null && kind.equals("person") && !speakers.contains(owner)) continue;
                if (!affected(body, touched) && !mentioned(firsthand, subject) && !manualNames.contains(subject)
                        && (owner == null || !relatedIds.contains(owner))) continue;
                boolean supported = false, complete = true;
                for (var ref : body.getAsJsonArray("source_refs")) {
                    boolean included = includeContext(records, get(ref.getAsJsonObject(), "record_id"), groupRooms);
                    supported |= included;
                    complete &= included;
                }
                // 群友提到旧主题，只在其全部依据均可作为本页成员原话时提供正文。
                if (num(row, "active") == 1 && (groupRooms == null || complete)) profiles.add(body);
                else if (supported || mentioned(firsthand, subject) || manualNames.contains(subject)
                        || (owner != null && relatedIds.contains(owner))) {
                    var stub = obj("kind", kind, "subject", subject, "version", num(row, "version"));
                    if (kind.equals("person") && owner != null) {
                        stub.addProperty("version", Math.max(num(row, "version"), people.lastVersion(owner)));
                        if (names.known(owner)) {
                            var name = issuedNames.get(owner);
                            if (name == null || !name.has("subject")) continue;
                            stub.addProperty("subject", get(name, "subject"));
                            stub.addProperty("person_id", owner);
                        }
                    }
                    profiles.add(stub);
                }
            }
        }
        var packet = obj("stream_id", store.stream, "policy_version", policy, "after", after, "through", through,
                "evidence", records.values(), "profiles", profiles, "persons", names.persons(records.values(), relatedIds));
        if (!LIVE.equals(lane)) packet.addProperty("lane", lane);
        var hints = store.dreamPeople.hints(manualNames);
        if (!hints.isEmpty()) packet.add("person_names", hints);
        return packet;
    }

    /** 历史群通道只签发当前仍可辨认的群发言。旧身份回扫继续推进自己的位置。 */
    private List<JsonObject> touched(long after, long through, String lane) throws SQLException {
        if (GROUP.equals(lane))
            return store.rows("SELECT record_id,0 AS recheck_pair FROM dream_group_messages WHERE seq>? AND seq<=? ORDER BY seq",
                    after, through);
        // 同一段升级历史可能还欠身份回扫。群通道已负责的原话只签发一次，其他身份材料照常推进。
        String owned = IDENTITY.equals(lane) ? """
                 AND NOT EXISTS (SELECT 1 FROM dream_group_messages m CROSS JOIN dream_group_progress g
                   WHERE g.enabled=1 AND m.record_id=c.record_id AND m.seq<=g.target)
                """ : "";
        return store.rows("""
                SELECT c.record_id,max(c.op NOT IN ('LEGACY_IMPORT','INGEST')) AS recheck_pair
                FROM changes c WHERE c.seq>? AND c.seq<=?
                """ + owned + " GROUP BY c.record_id ORDER BY min(c.seq)", after, through);
    }

    /** 补入回复链、尚待本人确认的旧问题或旧档来源前，按当前会话和授权重新取证。 */
    private boolean include(Map<String, JsonObject> records, String id) throws SQLException {
        if (records.containsKey(id)) return true;
        var source = evidence(id);
        if (source == null) return false;
        records.put(id, source);
        return true;
    }

    /** 群历史的补充材料须在同一页已经获准的群场合内，普通整理仍可使用全部已授权的相关原话。 */
    private boolean includeContext(Map<String, JsonObject> records, String id, Set<String> groupRooms) throws SQLException {
        if (records.containsKey(id)) return true;
        if (groupRooms != null && store.rows("SELECT room FROM dream_group_messages WHERE record_id=?", id)
                .stream().noneMatch(m -> groupRooms.contains(get(m, "room")))) return false;
        return include(records, id);
    }

    private static boolean affected(JsonObject profile, Set<String> touched) {
        for (var raw : profile.getAsJsonArray("source_refs"))
            if (touched.contains(get(raw.getAsJsonObject(), "record_id"))) return true;
        return false;
    }

    private static boolean mentioned(Collection<JsonObject> evidence, String subject) {
        return evidence.stream().anyMatch(e -> get(e, "content").contains(subject)
                || (e.has("speaker") && subject.equals(get(e, "speaker")))
                || (e.has("topic") && subject.equals(get(e, "topic"))));
    }

    private boolean applicable(String person, String display, Set<String> rooms) throws SQLException {
        for (String room : rooms) if (names.permitsName(person, display, room, null)) return true;
        return false;
    }

    /** 先按原命令查回执，再核对已发批次与当前证据。所有档案修订、覆盖和回执共同提交或回滚。 */
    JsonObject commit(JsonObject cmd) throws SQLException {
        keys(cmd, "context source_instance event_id changes name_claims lane " + POSITION);
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
        String lane = cmd.has("lane") ? one(cmd, "lane", IDENTITY, GROUP) : LIVE;
        var claims = cmd.has("name_claims") ? array(cmd, "name_claims", 0, 16) : new JsonArray();
        var state = state();
        if (!store.stream.equals(stream)
                || DreamGroupProgress.cursor(store, lane) != after
                || num(state, "policy_version") != policy
                || through <= after
                || state.get("packet").isJsonNull()) throw conflict("DREAM_BATCH_CHANGED");
        var issued = parse(get(state, "packet"));
        if (!Objects.equals(issued.get("lane"), cmd.get("lane")) || num(issued, "through") != through
                || !issued.equals(material(policy, after, through, lane)))
            throw conflict("DREAM_BATCH_CHANGED");
        var offered = new HashMap<String, JsonObject>();
        for (var entry : issued.getAsJsonArray("evidence")) {
            var e = entry.getAsJsonObject();
            offered.put(get(e, "record_id"), e);
        }
        var persons = new HashMap<String, JsonObject>();
        for (var raw : issued.getAsJsonArray("persons")) {
            var p = raw.getAsJsonObject();
            persons.put(get(p, "person_id"), p);
        }
        // 两类版本均以发给模型的那一拍为基准。同批本人自称可以立即支撑认识的归属。
        var before = people.snapshot();
        names.applyClaims(claims, issued);
        var identities = new HashSet<List<String>>();
        var historicalProfiles = GROUP.equals(lane) ? issued.getAsJsonArray("profiles") : null;
        for (var raw : changes) {
            if (!(raw instanceof JsonObject change)) throw invalid("changes");
            publish(change, offered, identities, persons, before, claims, historicalProfiles);
        }
        people.snapshot();
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
        if (!LIVE.equals(lane)) receipt.addProperty("lane", lane);
        store.update(
                "INSERT INTO dream_receipts VALUES(?,?,?,?)",
                store.writer,
                event,
                JSON.toJson(cmd),
                JSON.toJson(receipt));
        DreamGroupProgress.advance(store, lane, through);
        return receipt;
    }

    /** 主题沿用原名，人物按稳定 ID 定位。显示主名及每条原文归属均由 Memory 复核。 */
    private void publish(JsonObject change, Map<String, JsonObject> offered, Set<List<String>> identities,
            Map<String, JsonObject> persons, Map<String, DreamProfiles.View> before, JsonArray claims,
            JsonArray historicalProfiles) throws SQLException {
        keys(change, "kind subject person_id expected_version text scope reason source_refs");
        String kind = one(change, "kind", "person", "topic"),
                subject = text(change, "subject", 128);
        String person = null, key = subject;
        boolean signed = change.has("person_id");
        if (signed) {
            person = text(change, "person_id", 128);
            if (!kind.equals("person") || !persons.containsKey(person)) throw invalid("changes.person_id");
        } else if (kind.equals("person")) {
            if (store.dreamPeople.isAlias(subject)) throw invalid("changes.kind/subject");
            person = names.manualId(subject);
            if (person == null) person = DreamProfiles.legacyId(store.character, subject);
        }
        if (!identities.add(List.of(kind, person == null ? subject : person))) throw invalid("changes.kind/subject");
        long expected = number(change, "expected_version", 0, Long.MAX_VALUE - 1);
        checkBody(change);
        var refs = array(change, "source_refs", 1, 16);
        var seen = new HashSet<String>();
        var rooms = new TreeSet<String>();
        var deps = new LinkedHashMap<String, Set<String>>();
        var retained = new JsonArray();
        if (historicalProfiles != null && signed)
            for (var value : historicalProfiles) {
                var profile = value.getAsJsonObject();
                if (change.get("person_id").equals(profile.get("person_id")) && profile.has("source_refs"))
                    retained.addAll(profile.getAsJsonArray("source_refs"));
            }
        var sources = retained.isEmpty() ? offered : new HashMap<>(offered);
        boolean hasPageSource = false, hasOwnPageSource = false, keepsPriorSource = false;
        for (var entry : refs) {
            if (!(entry instanceof JsonObject r)) throw invalid("source_refs");
            keys(r, "record_id revision");
            String id = id(r, "record_id");
            long revision = number(r, "revision", 1, Long.MAX_VALUE);
            if (!seen.add(id)) throw invalid("source_refs.record_id");
            var e = offered.get(id);
            if (e != null) {
                hasPageSource = true;
                if (signed && change.get("person_id").equals(e.get("speaker_person_id"))) hasOwnPageSource = true;
            } else if (retained.contains(r)) {
                // 只能承接本页所发同一人物现版中的精确引用。旧原文留在 Memory，提交时重新验权、验修订。
                e = evidence(id);
                if (e != null) sources.put(id, e);
                keepsPriorSource = true;
            }
            if (e == null || num(e, "revision") != revision) throw conflict("SOURCE_CHANGED");
            if (!signed && person != null && e.has("speaker_person_id")) throw invalid("changes.person_id");
            if (signed) {
                String link = attribution(person, e, before.get(person));
                if (link == null) throw invalid("changes.source_refs 不能将该原话归属此人");
                String room = get(e, "conversation_id");
                rooms.add(room);
                if (!link.isEmpty()) deps.computeIfAbsent(link, n -> new TreeSet<>()).add(room);
                var previous = before.get(person);
                if (previous != null)
                    for (var part : previous.parts())
                        if (part.owner().equals(person) && affected(part.body(), Set.of(id)))
                            people.inheritDependencies(part, deps);
            }
        }
        // 群友的明确见证可支持新判断。沿用未进入本页的旧档引用，则须有档案本人本页的发言。
        if (historicalProfiles != null && (!hasPageSource || keepsPriorSource && !hasOwnPageSource))
            throw invalid("changes.source_refs 须引用本页群成员原话，保留旧档引用时须有本人的本页发言");
        if (signed) {
            String main = names.primary(person, null);
            var issued = persons.get(person);
            boolean current = subject.equals(main);
            boolean scoped = issued.has("subject") && subject.equals(get(issued, "subject"))
                    && (main == null || !applicable(person, main, rooms));
            if ((!current && !scoped) || !applicable(person, subject, rooms)) throw invalid("changes.subject");
        }
        if (person != null) key = people.allocate(person, subject);
        var rows =
                store.rows(
                        "SELECT version FROM dream_profiles WHERE kind=? AND subject=?",
                        kind,
                        key);
        long storageVersion = rows.isEmpty() ? 0 : num(rows.getFirst(), "version");
        long published = person == null ? storageVersion : Math.max(storageVersion, people.lastVersion(person));
        if (published != expected)
            throw conflict("REVISION_CONFLICT");
        var profile = change.deepCopy();
        profile.remove("expected_version");
        profile.addProperty("version", storageVersion + 1);
        profile.addProperty("author", "DREAMING");
        if (person != null) profile.addProperty("person_id", person);
        saveRevision(store, key, profile);
        if (signed) rememberNames(person, key, profile, persons.get(person), claims, sources, deps);
    }

    /** 合并后的旧档原句可以继续使用；取用其中一条时保留当时不可省略的别称桥接。 */
    private String attribution(String person, JsonObject source, DreamProfiles.View previous) throws SQLException {
        if (source.has("sender_id") && source.has("speaker_person_id") && get(source, "conversation_id").startsWith("group:onebot:")) {
            // 归属只读发言文字，模型原包、逐字引句和存档的 RecordRef 均保留原样。
            String raw = get(source, "content"), words = DreamGroupHistory.textForAttribution(raw).strip();
            if (DreamNameStatements.onlyQuestionOrQuotation(words)) return null;
            if (!raw.equals(words)) {
                source = source.deepCopy();
                source.addProperty("content", words);
            }
            // 明确向群友讲自己的事时，连同旧档再次签发也只能由本人作证。
            String speaker = names.addressedSelfReport(source);
            if (speaker != null) return person.equals(speaker) ? "" : null;
        }
        String id = get(source, "record_id");
        if (previous != null)
            for (var part : previous.parts())
                if (!part.owner().equals(person) && affected(part.body(), Set.of(id))) return people.bridge(part, person);
        String link = names.attribution(person, source);
        if (link == null && previous != null)
            for (var part : previous.parts())
                if (part.owner().equals(person) && affected(part.body(), Set.of(id))) return "";
        return link;
    }

    /** 正文、范围或理由直接使用了学得的称呼时，它也成为展示该版所必需的独立身份依据。 */
    private void rememberNames(String person, String key, JsonObject body, JsonObject signed, JsonArray claims,
            Map<String, JsonObject> offered, Map<String, Set<String>> deps) throws SQLException {
        var used = new LinkedHashSet<String>();
        for (var raw : signed.getAsJsonArray("aliases")) used.add(raw.getAsString());
        if (signed.has("subject")) used.add(get(signed, "subject"));
        for (var raw : claims) {
            var c = raw.getAsJsonObject();
            if (person.equals(get(c, "person_id"))) used.add(get(c, "name"));
        }
        String prose = get(body, "text") + "\n" + get(body, "reason") + "\n" + get(body, "scope");
        for (String name : used) {
            if (!prose.contains(name)) continue;
            for (var raw : body.getAsJsonArray("source_refs")) {
                String scope = get(offered.get(get(raw.getAsJsonObject(), "record_id")), "conversation_id");
                if (names.permitsName(person, name, scope, null))
                    deps.computeIfAbsent(name, n -> new TreeSet<>()).add(scope);
            }
        }
        for (var entry : deps.entrySet())
            for (String scope : entry.getValue()) people.dependency(key, num(body, "version"), entry.getKey(), scope);
    }

    /** 归档正文和手工确认身份后的合并版本遵守相同的发布限制。 */
    static void checkBody(JsonObject profile) {
        if (!text(profile, "text", 2048).startsWith("我")) throw invalid("text");
        text(profile, "scope", 512);
        text(profile, "reason", 1024);
    }

    /** 版本正文和撤回依赖索引必须在同一事务内完成，供普通修订和旧人物归并共用。 */
    static void saveRevision(Store store, JsonObject profile) throws SQLException {
        saveRevision(store, get(profile, "subject"), profile);
    }

    private static void saveRevision(Store store, String subject, JsonObject profile) throws SQLException {
        String kind = get(profile, "kind");
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
        for (var view : people.snapshot().values())
            if (export || people.visible(view, ctx)) profiles.add(view.body());
        for (var row : store.rows(PROFILE_ROWS + " WHERE p.kind='topic' AND p.active=1 ORDER BY p.subject")) {
            var profile = parse(get(row, "body"));
            if (export || people.sourcesVisible(profile, ctx)) profiles.add(profile);
        }
        return obj("profiles", profiles);
    }
}
