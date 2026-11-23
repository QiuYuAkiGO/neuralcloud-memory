package neuralcloud.memory;

import java.util.*;
import java.util.regex.*;

/**
 * 中英混合分词器，为 SQLite FTS5 全文索引与内存词项覆盖打分提供一致的词元集合。
 *
 * <p>SQLite 内置 unicode61 分词器无法按中文语义切词，因此写入与查询两端均先经过本工具切分：
 * 连续汉字拆为单字（unigram）与相邻双字（bigram），其余字母/数字/下划线连续串作为整词保留。
 */
final class Terms {
    /** 将文本切分为连续汉字段或连续非汉字字母/数字/下划线词段。 */
    private static final Pattern RUN =
            Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}_&&[^\\p{IsHan}]]+");

    /**
     * 提取去重且保序的检索词项集合（统一小写）。
     * 汉字同时输出单字与双字，以便兼顾短词召回和词组精度（打分时单字权重低于多字词）。
     */
    static Set<String> terms(String text) {
        var result = new LinkedHashSet<String>();
        var matcher = RUN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) != Character.UnicodeScript.HAN) {
                result.add(word);
                continue;
            }
            // 按码点切分汉字，避免拓展区汉字（代理对）被拆坏。
            int[] cp = word.codePoints().toArray();
            for (int i = 0; i < cp.length; i++) {
                result.add(new String(cp, i, 1));
                if (i + 1 < cp.length) result.add(new String(cp, i, 2));
            }
        }
        return result;
    }

    /** 将查询文本转为 FTS5 `MATCH` 语法的 OR 表达式，词项加双引号防止触发 FTS 关键字解析。 */
    static String expression(String text) {
        return String.join(" OR ", terms(text).stream().map(t -> "\"" + t + "\"").toList());
    }
}
