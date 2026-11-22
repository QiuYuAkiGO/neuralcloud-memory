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

public final class MemoryMain {
    private final Store store;
    private final Ledger ledger;
    private final Reader reader;
    private final byte[] authorization;

    private MemoryMain(Store store, String token) {
        this.store = store;
        ledger = new Ledger(store);
        reader = new Reader(store);
        authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

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
        if (!settings.stringPropertyNames()
                .equals(
                        Set.of(
                                "port",
                                "dataDirectory",
                                "characterId",
                                "accessTokenEnv",
                                "sourceInstance")))
            throw new IllegalArgumentException(
                    "expected port, dataDirectory, characterId, accessTokenEnv, sourceInstance");
        String character = settings.getProperty("characterId"),
                source = settings.getProperty("sourceInstance");
        String token = System.getenv(settings.getProperty("accessTokenEnv"));
        if (token == null
                || !token.matches("[!-~]{32,256}")
                || !character.matches("[A-Za-z0-9_-]{1,128}")
                || source.isBlank()
                || source.length() > 128
                || settings.getProperty("dataDirectory").isBlank())
            throw new IllegalArgumentException("invalid service configuration or credential");
        int port = Integer.parseInt(settings.getProperty("port"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port");
        // Acquire the database, verify its identity and FTS before exposing the listener.
        var store =
                new Store(
                        config.getParent().resolve(settings.getProperty("dataDirectory")),
                        character,
                        source);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
        server.createContext("/v2/", new MemoryMain(store, token)::handle);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
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
        // The supervisor can close its pipe to request the same orderly shutdown as Ctrl+C.
        if (supervised) {
            System.in.transferTo(OutputStream.nullOutputStream());
            System.exit(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonObject result;
        int status = 200;
        try {
            var header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null
                    || !MessageDigest.isEqual(
                            authorization, header.getBytes(StandardCharsets.UTF_8)))
                throw new Fault(401, "UNAUTHORIZED", "credential");
            String path = exchange.getRequestURI().getPath().substring(4);
            boolean get = Set.of("health", "receipts", "changes").contains(path);
            if (!get
                    && !Set.of("ingest", "candidates", "corrections", "read", "recall")
                            .contains(path)) throw new Fault(404, "NOT_FOUND", "route");
            if (!exchange.getRequestMethod().equals(get ? "GET" : "POST"))
                throw new Fault(405, "METHOD_NOT_ALLOWED", "method");
            JsonObject body = get ? parameters(exchange.getRequestURI()) : body(exchange);
            result =
                    store.transaction(
                            () ->
                                    switch (path) {
                                        case "health" -> {
                                            keys(body, "");
                                            yield store.info();
                                        }
                                        case "receipts" -> {
                                            keys(body, "source_instance event_id");
                                            yield ledger.receipt(
                                                    text(body, "source_instance", 128),
                                                    text(body, "event_id", 256));
                                        }
                                        case "changes" -> reader.changes(body);
                                        case "read" -> reader.read(body);
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
