package neuralcloud.memory;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

final class Fields {
    static final Gson JSON =
            new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private static final DateTimeFormatter UTC =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    static class Fault extends RuntimeException {
        final int status;
        final String code;

        Fault(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    static Fault invalid(String field) {
        return new Fault(422, "INVALID_FIELD", field);
    }

    static Fault denied() {
        return new Fault(403, "FORBIDDEN", "operation");
    }

    static Fault conflict(String code) {
        return new Fault(409, code, code);
    }

    static JsonObject obj(Object... pairs) {
        JsonObject out = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
            out.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
        return out;
    }

    static void keys(JsonObject o, String allowed) {
        var names = Set.of(allowed.split(" "));
        for (String k : o.keySet()) if (!names.contains(k)) throw invalid(k);
    }

    static JsonObject object(JsonObject o, String key) {
        if (!(o.get(key) instanceof JsonObject v)) throw invalid(key);
        return v;
    }

    static JsonArray array(JsonObject o, String key, int min, int max) {
        if (!(o.get(key) instanceof JsonArray a) || a.size() < min || a.size() > max)
            throw invalid(key);
        return a;
    }

    static String str(JsonObject o, String k, int max) {
        if (!(o.get(k) instanceof JsonPrimitive p) || !p.isString()) throw invalid(k);
        String s = p.getAsString();
        if (s.codePointCount(0, s.length()) > max) throw invalid(k);
        return s;
    }

    static String text(JsonObject o, String k, int max) {
        String s = str(o, k, max);
        if (s.isBlank()) throw invalid(k);
        return s;
    }

    static String one(JsonObject o, String k, String... options) {
        String s = text(o, k, 128);
        if (!Arrays.asList(options).contains(s)) throw invalid(k);
        return s;
    }

    static long number(JsonObject o, String k, long min, long max) {
        if (!(o.get(k) instanceof JsonPrimitive p) || !p.isNumber()) throw invalid(k);
        try {
            long n = p.getAsBigDecimal().longValueExact();
            if (n >= min && n <= max) return n;
        } catch (ArithmeticException | NumberFormatException ignored) {
        }
        throw invalid(k);
    }

    static String id(JsonObject o, String k) {
        String s = text(o, k, 64);
        if (!s.matches("[0-9a-f]{64}")) throw invalid(k);
        return s;
    }

    static String time(JsonObject o, String k) {
        try {
            return utc(OffsetDateTime.parse(text(o, k, 64)).toInstant());
        } catch (DateTimeException e) {
            throw invalid(k);
        }
    }

    static String utc(Instant i) {
        return UTC.format(i);
    }

    static String get(JsonObject o, String k) {
        return o.get(k).getAsString();
    }

    static long num(JsonObject o, String k) {
        return o.get(k).getAsLong();
    }

    static String recordId(String source, String event) {
        // v1 uses compact UTF-8 JSON, including literal U+2028 and U+2029.
        String encoded = "[" + quoteV1(source) + "," + quoteV1(event) + "]";
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String quoteV1(String s) {
        StringBuilder out = new StringBuilder("\"");
        s.codePoints()
                .forEach(
                        c -> {
                            switch (c) {
                                case '"' -> out.append("\\\"");
                                case '\\' -> out.append("\\\\");
                                case '\b' -> out.append("\\b");
                                case '\f' -> out.append("\\f");
                                case '\n' -> out.append("\\n");
                                case '\r' -> out.append("\\r");
                                case '\t' -> out.append("\\t");
                                default -> {
                                    if (c < 32) out.append("\\u%04x".formatted(c));
                                    else out.appendCodePoint(c);
                                }
                            }
                        });
        return out.append('"').toString();
    }

    static JsonObject ref(String id, long rev) {
        return obj("record_id", id, "revision", rev);
    }

    static String excerpt(String text, int from, int length) {
        int size = text.codePointCount(0, text.length());
        int start = Math.min(size, from);
        return text.substring(
                text.offsetByCodePoints(0, start),
                text.offsetByCodePoints(0, start + Math.min(length, size - start)));
    }

    static JsonObject parse(String s) {
        return JSON.fromJson(s, JsonObject.class);
    }

    private Fields() {}
}
