package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;

record Access(
        String scope, String session, String actor, String role, String account, String peer) {
    static Access from(Store store, JsonObject request) {
        var ctx = object(request, "context");
        keys(ctx, "character_id conversation_id session_id actor_id role qq");
        if (!text(ctx, "character_id", 128).equals(store.character)) throw denied();
        String scope = text(ctx, "conversation_id", 256), session = text(ctx, "session_id", 256);
        String actor = text(ctx, "actor_id", 128);
        String role =
                one(ctx, "role", "ROUTER", "MASTER", "CHAT_WORKER", "TASK_WORKER", "ORCHESTRATOR");
        String account = null, peer = null;
        if (ctx.has("qq")) {
            var qq = object(ctx, "qq");
            keys(qq, "account_id peer_id");
            account = qqId(qq, "account_id");
            peer = qqId(qq, "peer_id");
            if (!scope.equals("c2c:onebot:" + account + ":" + peer)) throw denied();
        }
        if (scope.startsWith("c2c:onebot:") && peer == null) throw denied();
        return new Access(scope, session, actor, role, account, peer);
    }

    static String qqId(JsonObject o, String key) {
        String value = text(o, key, 16);
        if (!value.matches("[1-9][0-9]{4,15}")) throw invalid(key);
        return value;
    }

    void write() {
        if (!role.equals("ROUTER") && !role.equals("ORCHESTRATOR")) throw denied();
    }

    void requireQq() {
        if (peer == null) throw denied();
    }
}
