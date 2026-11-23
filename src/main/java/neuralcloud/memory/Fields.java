package neuralcloud.memory;

import com.google.gson.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/**
 * 契约边界校验、JSON 编解码、时间归一化与记录标识工具类。
 *
 * <p>集中实现项目守则中的“边界校验、按契约拒绝非法输入、合并同语义失败出口”：
 * 所有字段级校验失败统一抛出 {@link Fault}（HTTP 422），授权失败抛出 403，版本/幂等/流冲突抛出 409。
 */
final class Fields {
    /** 严格模式 Gson：关闭 HTML 转义以保留原始字符，并拒绝尾随垃圾或宽松 JSON 语法。 */
    static final Gson JSON =
            new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    /** 固定 9 位纳秒精度的 UTC RFC 3339 格式器，保证时间字符串可直接按字典序比较先后。 */
    private static final DateTimeFormatter UTC =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    /** 携带 HTTP 状态码与契约错误码的业务异常，在 HTTP 入口统一转换为错误 JSON 响应。 */
    static class Fault extends RuntimeException {
        final int status;
        final String code;

        Fault(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** 构造字段校验失败异常（422 INVALID_FIELD），指明违反契约的字段名。 */
    static Fault invalid(String field) {
        return new Fault(422, "INVALID_FIELD", field);
    }

    /** 构造权限不足异常（403 FORBIDDEN）。 */
    static Fault denied() {
        return new Fault(403, "FORBIDDEN", "operation");
    }

    /** 构造并发、幂等、来源变更或流切换冲突异常（409）。 */
    static Fault conflict(String code) {
        return new Fault(409, code, code);
    }

    /** 按键值交替参数快速组装 JsonObject。 */
    static JsonObject obj(Object... pairs) {
        JsonObject out = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
            out.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
        return out;
    }

    /** 字段白名单校验：拒绝契约未声明的多余字段，避免静默忽略调用方拼写错误。 */
    static void keys(JsonObject o, String allowed) {
        var names = Set.of(allowed.split(" "));
        for (String k : o.keySet()) if (!names.contains(k)) throw invalid(k);
    }

    /** 读取必填的子对象字段。 */
    static JsonObject object(JsonObject o, String key) {
        if (!(o.get(key) instanceof JsonObject v)) throw invalid(key);
        return v;
    }

    /** 读取必填数组字段并校验元素个数范围 [min, max]。 */
    static JsonArray array(JsonObject o, String key, int min, int max) {
        if (!(o.get(key) instanceof JsonArray a) || a.size() < min || a.size() > max)
            throw invalid(key);
        return a;
    }

    /**
     * 读取必填字符串字段（允许为空串），长度按 Unicode 码点计数而非 UTF-16 char 数，
     * 以确保中文与四字节 emoji 的长度上限与契约一致。
     */
    static String str(JsonObject o, String k, int max) {
        if (!(o.get(k) instanceof JsonPrimitive p) || !p.isString()) throw invalid(k);
        String s = p.getAsString();
        if (s.codePointCount(0, s.length()) > max) throw invalid(k);
        return s;
    }

    /** 读取必填非空白字符串字段。 */
    static String text(JsonObject o, String k, int max) {
        String s = str(o, k, max);
        if (s.isBlank()) throw invalid(k);
        return s;
    }

    /** 读取必填枚举字段，值必须精确命中允许列表之一。 */
    static String one(JsonObject o, String k, String... options) {
        String s = text(o, k, 128);
        if (!Arrays.asList(options).contains(s)) throw invalid(k);
        return s;
    }

    /** 读取必填精确整数（拒绝小数、科学计数法截断及字符串数字）并校验闭区间 [min, max]。 */
    static long number(JsonObject o, String k, long min, long max) {
        if (!(o.get(k) instanceof JsonPrimitive p) || !p.isNumber()) throw invalid(k);
        try {
            long n = p.getAsBigDecimal().longValueExact();
            if (n >= min && n <= max) return n;
        } catch (ArithmeticException | NumberFormatException ignored) {
        }
        throw invalid(k);
    }

    /** 读取 64 位小写十六进制记录/授权标识。 */
    static String id(JsonObject o, String k) {
        String s = text(o, k, 64);
        if (!s.matches("[0-9a-f]{64}")) throw invalid(k);
        return s;
    }

    /** 解析带时区偏移的 RFC 3339 时间字符串，并归一化为定长 9 位纳秒 UTC 字符串便于直接比较。 */
    static String time(JsonObject o, String k) {
        try {
            return utc(OffsetDateTime.parse(text(o, k, 64)).toInstant());
        } catch (DateTimeException e) {
            throw invalid(k);
        }
    }

    /** 将 Instant 格式化为定长 UTC 时间戳字符串。 */
    static String utc(Instant i) {
        return UTC.format(i);
    }

    /** 读取已通过契约校验或来自内部表行的字符串字段。 */
    static String get(JsonObject o, String k) {
        return o.get(k).getAsString();
    }

    /** 读取已通过契约校验或来自内部表行的整数列。 */
    static long num(JsonObject o, String k) {
        return o.get(k).getAsLong();
    }

    /**
     * 计算记录或授权的确定性 64 位十六进制 ID：对 `[source_instance,event_id]` 紧凑 JSON 做 SHA-256。
     * 手动转义是为了与 v1 黄金回放样本逐字节一致（包括保留字面 U+2028/U+2029，不转成 \\u2028）。
     */
    static String recordId(String source, String event) {
        // v1 采用紧凑 UTF-8 JSON，包含字面 U+2028 与 U+2029。
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

    /** 按 v1 规范对字符串进行最小化 JSON 转义（仅转义双引号、反斜杠与控制字符）。 */
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

    /** 构造带版本号的记录引用对象（RecordRef）。 */
    static JsonObject ref(String id, long rev) {
        return obj("record_id", id, "revision", rev);
    }

    /**
     * 按 Unicode 码点半开区间 [from, from + length) 截取原文窗口。
     * 避免按 Java char 索引切片导致代理对（emoji / 罕见汉字）被截断为乱码。
     */
    static String excerpt(String text, int from, int length) {
        int size = text.codePointCount(0, text.length());
        int start = Math.min(size, from);
        return text.substring(
                text.offsetByCodePoints(0, start),
                text.offsetByCodePoints(0, start + Math.min(length, size - start)));
    }

    /** 将 JSON 文本解析为 JsonObject。 */
    static JsonObject parse(String s) {
        return JSON.fromJson(s, JsonObject.class);
    }

    private Fields() {}
}
