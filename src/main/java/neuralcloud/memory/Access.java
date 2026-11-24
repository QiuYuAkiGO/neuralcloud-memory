package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

/**
 * 单次请求的调用上下文与身份边界，由 Harness 依据渠道与调度状态构造并传入。
 *
 * <p>职责：核对目标角色主体（character_id）、合法角色枚举及 QQ 私聊上下文的一致性；
 * 所有读写与受众过滤均以该不可变对象作为唯一身份凭据，禁止由模型自由改写。
 *
 * @param scope   结果所属或将进入的会话标识（conversation_id）
 * @param session 当前运行会话标识（session_id）
 * @param actor   发起本次操作的主体标识（actor_id）
 * @param role    调用方角色（ROUTER / MASTER / CHAT_WORKER / TASK_WORKER / ORCHESTRATOR）
 * @param account 可信 QQ 渠道绑定的机器人账号，非 QQ 私聊上下文时为 null
 * @param peer    可信 QQ 私聊入站的对端用户账号，非 QQ 私聊上下文时为 null
 * @param memberGroups 本次 QQ 或移动渠道已核验的群会话，仅该成员的私聊上下文持有
 */
record Access(
        String scope,
        String session,
        String actor,
        String role,
        String account,
        String peer,
        java.util.List<String> memberGroups) {
    /**
     * 在请求边界解析并校验 context 对象。
     * 业务约束：角色 ID 必须与当前 Memory 实例绑定的一致；凡以 c2c:onebot: 开头的私聊会话
     * 必须同时携带匹配的 qq(account_id, peer_id)，防止冒用私聊会话标识绕过本人历史或授权校验。
     */
    static Access from(Store store, JsonObject request) {
        var ctx = object(request, "context");
        keys(ctx, "character_id conversation_id session_id actor_id role qq mobile_member");
        if (!text(ctx, "character_id", 128).equals(store.character)) throw denied();
        String scope = text(ctx, "conversation_id", 256), session = text(ctx, "session_id", 256);
        String actor = text(ctx, "actor_id", 128);
        String role =
                one(ctx, "role", "ROUTER", "MASTER", "CHAT_WORKER", "TASK_WORKER", "ORCHESTRATOR");
        String account = null, peer = null;
        var groups = new java.util.ArrayList<String>();
        if (ctx.has("qq")) {
            var qq = object(ctx, "qq");
            keys(qq, "account_id peer_id member_group_ids");
            account = qqId(qq, "account_id");
            peer = qqId(qq, "peer_id");
            // QQ 上下文只能用于对应的单聊会话，避免将私聊身份错误绑定到群聊或其他控制台会话。
            if (!scope.equals("c2c:onebot:" + account + ":" + peer)) throw denied();
            // 只有受信渠道可填本次 OneBot 核验的群号，群归属始终按已认证机器人账号拼接。
            if (qq.has("member_group_ids")) {
                var ids = new java.util.HashSet<String>();
                for (var element : array(qq, "member_group_ids", 1, 256)) {
                    if (!(element instanceof JsonPrimitive id)
                            || !id.isString()
                            || !id.getAsString().matches("[1-9][0-9]{4,15}")
                            || !ids.add(id.getAsString())) throw invalid("member_group_ids");
                    groups.add("group:onebot:" + account + ":" + id.getAsString());
                }
            }
        }
        // 私聊会话禁止省略 qq 身份段，否则无法区分本人发言和受众隐私边界。
        if (scope.startsWith("c2c:onebot:") && peer == null) throw denied();
        // 移动渠道在校验群成员凭据后签入身份。单个 MistyAI 服务实例拥有一个共享群。
        String mobileGroup = null;
        if (ctx.has("mobile_member")) {
            var member = object(ctx, "mobile_member");
            keys(member, "instance member_id");
            String instance = mobileInstance(member, "instance"), id = mobileId(member, "member_id");
            if (!scope.equals("c2c:mistyai:" + instance + ":" + id)) throw denied();
            mobileGroup = "group:mistyai:" + instance;
            groups.add(mobileGroup);
        }
        if (scope.startsWith("c2c:mistyai:") && mobileGroup == null) throw denied();
        return new Access(
                scope, session, actor, role, account, peer, java.util.List.copyOf(groups));
    }

    /** 实例名与成员、消息的正整数 ID 均由移动服务分配，字符集保证会话各段无歧义。 */
    static String mobileInstance(JsonObject object, String key) {
        String value = text(object, key, 64);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,63}")) throw invalid(key);
        return value;
    }

    static String mobileId(JsonObject object, String key) {
        String value = text(object, key, 19);
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid(key);
        return value;
    }

    /** 已认证的外部私聊受众共用敏感正文限制。 */
    boolean externalAudience() {
        return peer != null || !memberGroups.isEmpty();
    }

    /** 校验 QQ 号格式：5~16 位十进制数字且不以 0 开头。 */
    static String qqId(JsonObject o, String key) {
        String value = text(o, key, 16);
        if (!value.matches("[1-9][0-9]{4,15}")) throw invalid(key);
        return value;
    }

    /** 校验写入权限：仅允许统一写入口（ROUTER）和编排器（ORCHESTRATOR）变更账本，Worker/Master 只读。 */
    void write() {
        if (!role.equals("ROUTER") && !role.equals("ORCHESTRATOR")) throw denied();
    }

    /** 要求当前请求具备经入站渠道认证的 QQ 私聊身份（用于本人历史查询、原作者授权与私聊渠道校验）。 */
    void requireQq() {
        if (peer == null) throw denied();
    }
}
