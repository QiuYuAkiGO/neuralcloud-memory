package neuralcloud.memory;

import static neuralcloud.memory.Fields.*;

import com.google.gson.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * NeuralCloud Memory v2 独立服务入口与回环 HTTP 路由边界。
 *
 * <p>启动顺序与就绪条件：
 * <ol>
 *   <li>严格按白名单校验启动参数、配置文件字段与环境变量中的 Bearer 凭据。</li>
 *   <li>打开 {@link Store} 获取排他文件锁、完成模式迁移、核对角色身份并探测 FTS5 可用性。</li>
 *   <li>仅监听 {@code 127.0.0.1}，注册 {@code /v2/} 路由、虚拟线程池、后台定时卡目录整理任务和优雅停机钩子。</li>
 *   <li>启动 HTTP 监听后输出 {@code TEXT_READY} 行；若带 {@code --stop-on-eof}，等待标准输入关闭后有序退出。</li>
 * </ol>
 *
 * <p>认证边界：普通读写接口使用服务凭据（{@code accessTokenEnv}）；跨会话原作者授权/撤销接口
 * （{@code visibility-grants}）强制使用独立的渠道凭据（{@code grantAccessTokenEnv}）并校验绑定的机器人账号，
 * 防止普通服务凭据或模型提示注入越权颁发授权。
 */
public final class MemoryMain {
    private final Store store;
    private final Ledger ledger;
    private final Reader reader;
    private final Dreaming dreaming;
    private final CardCatalog catalog;
    private final CellSearch.Mode retrievalMode;
    /** 普通 /v2/ 接口使用的服务级 Bearer 凭据字节序列。 */
    private final byte[] authorization;
    /** 跨会话授权命令专用的独立渠道 Bearer 凭据字节序列，未配置跨会话授权时为 null。 */
    private final byte[] channelAuthorization;
    /** 允许提交原作者授权/撤销命令的机器人 QQ 账号。 */
    private final String grantAccount;

    private MemoryMain(
            Store store,
            String token,
            String channelToken,
            String grantAccount,
            CellSearch.Mode retrievalMode,
            Visibility visibility) {
        this.store = store;
        this.retrievalMode = retrievalMode;
        // 召回、卡片和提交派生项时的证据检查共用服务启动时确定的受众策略。
        reader = new Reader(store, retrievalMode, visibility);
        ledger = new Ledger(store, reader);
        dreaming = new Dreaming(store, reader);
        catalog = new CardCatalog(store, reader);
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        channelAuthorization =
                channelToken == null
                        ? null
                        : ("Bearer " + channelToken).getBytes(StandardCharsets.UTF_8);
        this.grantAccount = grantAccount;
    }

    /**
     * 服务主入口：解析 {@code --config <properties> [--stop-on-eof]}，完成配置/凭据边界校验并启动服务。
     */
    public static void main(String[] args) throws Exception {
        boolean supervised = args.length == 3 && args[2].equals("--stop-on-eof");
        if ((args.length != 2 && !supervised) || !args[0].equals("--config"))
            throw new IllegalArgumentException(
                    "usage: java -jar memory.jar --config memory.properties [--stop-on-eof]");
        Path config = Path.of(args[1]).toAbsolutePath();
        var settings = new Properties();
        try (var file = Files.newBufferedReader(config)) {
            settings.load(file);
        }
        // 配置项白名单校验：先剔除允许的可选配置项，再检查剩余键集合必须与必填项完全一致。
        var fields = new HashSet<>(settings.stringPropertyNames());
        fields.remove("retrievalMode");
        fields.remove("organizeIntervalSeconds");
        fields.remove("sessionIsolation");
        var visibility = new Visibility(switch (settings.getProperty("sessionIsolation", "false")) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("sessionIsolation must be true or false");
        });
        var mode = CellSearch.Mode.valueOf(settings.getProperty("retrievalMode", "CELLS"));
        int interval = Integer.parseInt(settings.getProperty("organizeIntervalSeconds", "30"));
        if (interval < 0 || interval > 86400)
            throw new IllegalArgumentException("organizeIntervalSeconds");
        boolean grants = fields.removeAll(Set.of("grantAccountId", "grantAccessTokenEnv"));
        if (!fields.equals(
                Set.of("port", "dataDirectory", "characterId", "accessTokenEnv", "sourceInstance")))
            throw new IllegalArgumentException(
                    "expected port, dataDirectory, characterId, accessTokenEnv, sourceInstance");
        String character = settings.getProperty("characterId"),
                source = settings.getProperty("sourceInstance");
        String token = System.getenv(settings.getProperty("accessTokenEnv"));
        String account = settings.getProperty("grantAccountId", "");
        String channelEnv = settings.getProperty("grantAccessTokenEnv");
        String channelToken =
                channelEnv == null || channelEnv.isBlank() ? null : System.getenv(channelEnv);
        // 若启用了跨会话授权配置，账号与渠道凭据必须成对提供，且渠道凭据不得复用普通服务凭据。
        if (grants
                && (!settings.containsKey("grantAccessTokenEnv")
                        || !account.matches("[1-9][0-9]{4,15}")
                        || channelToken == null
                        || !channelToken.matches("[!-~]{32,256}")
                        || channelToken.equals(token)))
            throw new IllegalArgumentException(
                    "grantAccountId and a distinct grantAccessTokenEnv are required");
        if (token == null
                || !token.matches("[!-~]{32,256}")
                || !character.matches("[A-Za-z0-9_-]{1,128}")
                || source.isBlank()
                || source.length() > 128
                || settings.getProperty("dataDirectory").isBlank())
            throw new IllegalArgumentException("invalid service configuration or credential");
        int port = Integer.parseInt(settings.getProperty("port"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port");
        // 先获取数据库锁并校验角色身份与 FTS5 可用性，确认存储就绪后再暴露 HTTP 监听。
        var store =
                new Store(
                        config.getParent().resolve(settings.getProperty("dataDirectory")),
                        character,
                        source);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        var service = new MemoryMain(store, token, channelToken, account, mode, visibility);
        server.createContext("/v2/", service::handle);
        // 后台单线程定时追赶人物/事项卡目录并清理已到期指针；interval=0 时关闭定时任务（仍可显式调 /v2/organize）。
        var organizer = Executors.newSingleThreadScheduledExecutor();
        if (interval > 0)
            organizer.scheduleWithFixedDelay(
                    () -> {
                        try {
                            store.transaction(() -> service.catalog.advance(16));
                        } catch (SQLException failure) {
                            // 水位随事务回滚；记录失败，下一次从同一水位重试。
                            failure.printStackTrace();
                        }
                    },
                    interval,
                    interval,
                    java.util.concurrent.TimeUnit.SECONDS);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        // 有序停机顺序：先停后台整理，再停接新请求并关闭执行器，最后释放数据库连接与目录排他锁。
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    organizer.shutdownNow();
                                    server.stop(1);
                                    executor.close();
                                    try {
                                        store.close();
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }));
        server.start();
        System.out.printf(
                "Memory v2 TEXT_READY character=%s stream=%s port=%d pid=%d%n",
                character, store.stream, port, ProcessHandle.current().pid());
        // 受管模式：父进程（如启动器或验收脚本）关闭标准输入管道时，触发与 Ctrl+C 相同的有序退出。
        if (supervised) {
            System.in.transferTo(OutputStream.nullOutputStream());
            System.exit(0);
        }
    }

    /**
     * 统一 HTTP 请求处理入口：依次完成凭据认证、路由与方法核对、参数解析、
     * 单事务业务分发以及契约错误码映射。
     */
    private void handle(HttpExchange exchange) throws IOException {
        JsonObject result;
        int status = 200;
        try {
            var header = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath().substring(4);
            boolean grant = Visibility.isCommand(path);
            byte[] presented =
                    header == null ? new byte[0] : header.getBytes(StandardCharsets.UTF_8);
            // 使用常量时间比较防止凭据计时侧信道；未携带合法凭据返回 401，持错凭据访问受限路由返回 403。
            boolean service = MessageDigest.isEqual(authorization, presented);
            boolean channel =
                    channelAuthorization != null
                            && MessageDigest.isEqual(channelAuthorization, presented);
            if (!service && !channel) throw new Fault(401, "UNAUTHORIZED", "credential");
            if (grant ? !channel : !service) throw denied();
            boolean get = Set.of("health", "receipts", "changes").contains(path);
            if (!get
                    && !grant
                    && !Set.of(
                                    "ingest",
                                    "candidates",
                                    "corrections",
                                    "read",
                                    "recall",
                                    "cards",
                                    "organize", "dreaming/batch", "dreaming/commit", "dreaming/profiles", "dreaming/receipt")
                            .contains(path)) throw new Fault(404, "NOT_FOUND", "route");
            if (!exchange.getRequestMethod().equals(get ? "GET" : "POST"))
                throw new Fault(405, "METHOD_NOT_ALLOWED", "method");
            JsonObject body = get ? parameters(exchange.getRequestURI()) : body(exchange);
            // 跨会话授权命令除独立凭据外，还必须在边界核对 context.qq.account_id 与已配置的机器人账号一致。
            if (grant && !grantAccount.equals(Access.from(store, body).account())) throw denied();
            result =
                    store.transaction(
                            () ->
                                    switch (path) {
                                        case "health" -> {
                                            keys(body, "");
                                            var info = store.info();
                                            info.getAsJsonArray("capabilities").add("dreaming");
                                            // 根据本实例启用的检索模式与渠道授权配置，动态宣告可用能力集合。
                                            info.getAsJsonArray("capabilities")
                                                    .add("audience_reads");
                                            info.getAsJsonArray("capabilities")
                                                    .add("mobile_member_notices");
                                            info.getAsJsonArray("capabilities")
                                                    .add("qq_member_notices");
                                            info.addProperty(
                                                    "retrieval_mode", retrievalMode.name());
                                            info.addProperty("session_isolation", reader.visibility.sessionIsolation());
                                            info.getAsJsonArray("capabilities").add("identity_lookup");
                                            info.getAsJsonArray("capabilities").add("qq_author_history");
                                            info.getAsJsonArray("capabilities")
                                                    .add("audience_cards");
                                            if (channelAuthorization != null)
                                                info.getAsJsonArray("capabilities")
                                                        .add("author_grants");
                                            yield info;
                                        }
                                        case "receipts" -> {
                                            keys(body, "source_instance event_id");
                                            yield ledger.receipt(
                                                    text(body, "source_instance", 128),
                                                    text(body, "event_id", 256));
                                        }
                                        case "changes" -> reader.changes(body);
                                        case "read" -> reader.read(body);
                                        case "cards" -> catalog.cards(body);
                                        case "organize" -> catalog.organize(body);
                                        case "dreaming/batch" -> dreaming.batch(body);
                                        case "dreaming/commit" -> dreaming.commit(body);
                                        case "dreaming/receipt" -> dreaming.receipt(body);
                                        case "dreaming/profiles" -> dreaming.profiles(body);
                                        case "recall" -> reader.recall(body);
                                        default -> ledger.write(path, body);
                                    });
        } catch (Fault e) {
            status = e.status;
            result = obj("code", e.code, "field", e.getMessage());
        } catch (SQLException e) {
            e.printStackTrace();
            status = 503;
            result = obj("code", "STORAGE_UNAVAILABLE");
        } catch (RuntimeException e) {
            e.printStackTrace();
            status = 500;
            result = obj("code", "INTERNAL_ERROR");
        }
        byte[] response = JSON.toJson(result).getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    /** 读取并解析 POST 请求的 JSON 正文：禁止附带查询串，上限 128 KiB（多读 1 字节用于判定 413 超限）。 */
    private static JsonObject body(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getRawQuery() != null) throw invalid("query");
        byte[] bytes = exchange.getRequestBody().readNBytes(131073);
        if (bytes.length > 131072) throw new Fault(413, "TOO_LARGE", "body");
        try {
            var body = parse(new String(bytes, StandardCharsets.UTF_8));
            if (body != null) return body;
        } catch (JsonParseException e) {
            throw invalid("body");
        }
        throw invalid("body");
    }

    /** 解析 GET 请求的查询参数：拒绝重复键或缺少等号的片段，并将分页数字参数转为整数。 */
    private static JsonObject parameters(URI uri) {
        var out = new JsonObject();
        if (uri.getRawQuery() == null) return out;
        for (String part : uri.getRawQuery().split("&", -1)) {
            var kv = part.split("=", 2);
            if (kv.length != 2 || out.has(kv[0])) throw invalid("query");
            String v = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            if (Set.of("after", "through", "limit").contains(kv[0])) {
                try {
                    out.addProperty(kv[0], Long.parseLong(v));
                } catch (NumberFormatException e) {
                    throw invalid(kv[0]);
                }
            } else out.addProperty(kv[0], v);
        }
        return out;
    }
}
