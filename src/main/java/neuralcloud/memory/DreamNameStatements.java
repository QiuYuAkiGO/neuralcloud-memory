package neuralcloud.memory;

import java.util.regex.Pattern;

/** 在已签发的完整原话中核对模型选出的引句。不能核实的表达保留为候选。 */
final class DreamNameStatements {
    private static final Pattern INDIRECT = Pattern.compile(
            "假如|假设|如果|要是|比方说|打个比方|开.{0,3}玩笑|说笑|逗你|骗你的|才怪|冒充|扮演|转述|转发|"
            + "(?:他|她|别人|有人|对方|原文|台词|剧本)(?:们)?(?:曾|在|刚才)?说|告诉我说|据说");
    private static final Pattern LOCAL = Pattern.compile("(?:在|仅限|只在|限于)(?:本|这|这个|我们)?(?:群|场合|里)|群里");
    private static final String SELF = "(?:我(?:自称|(?:也|还|原来|以前|之前|其实|本来|曾经|曾)?叫(?:做|作)?|(?:就|正)?是|"
            + "(?:以前|之前|原来)?的?(?:名字|姓名|昵称|别名|小名|英文名|中文名|曾用名|原名)(?:叫|是))|"
            + "(?:你们?|大家)也?可以叫我|(?:大家|朋友们)(?:平时|以前)?(?:也|都)叫我)";
    private static final String PREFERRED = "(?:(?:以后|今后|往后|从现在起|从今以后)(?:无论在哪里|无论在哪|不管在哪(?:里)?)?(?:都|统一|一律)?(?:请|直接|可以)?(?:大家|你们)?(?:叫|称呼)我|"
            + "请(?:大家|你们)?(?:统一|一律|一直|以后|直接)?(?:叫|称呼)我|我(?:更喜欢|希望|想让)(?:大家|你们?)?(?:以后)?(?:叫|称呼)我|我(?:首选|最喜欢)的称呼是)";
    private static final String END = "(?=\\s*(?:$|[，,。!！；;、]|就(?:好|行|可以)(?=$|[\\s，,。.!！；;])))";
    private static final Pattern DISAVOWED = Pattern.compile("假设|假如|假想|设想|开.{0,3}玩笑|逗你|才怪|骗你的|别当真|不要当真|扮演|转发|转述");
    private static final Pattern THIRD_PERSON = Pattern.compile("^(?:他|她|对方|别人|有人|听说|据说|我(?:的)?(?:朋友|同事|家人|同学)|我在转述)");
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "^(.+(?:[。.!！]|\\R))\\s*(?:那(?:么)?\\s*)?(?:你们?|您|大家)[^。.!！?？\\r\\n]*[？?]+$", Pattern.DOTALL);
    private static final Pattern QUESTION_ONLY = Pattern.compile("^[^。.!！\\r\\n]*[？?]+$");
    private static final Pattern REPORTED_SPEECH = Pattern.compile("^(?:[>“‘「『\"]|(?:我(?:正在|只是|在|来)?|以下是)(?:转述|转发|引用))");

    /** 只认肯定的、以本人为主语的短句；引号中的他人话、问句和名称前缀不会通过。 */
    static boolean declares(String content, String quote, String name, boolean preferred, boolean global) {
        String raw = content.strip();
        if (raw.startsWith("{") || raw.startsWith("[") || raw.startsWith(">") || DISAVOWED.matcher(raw).find()
                || global && locallyScoped(raw)) return false;
        String verb = preferred ? PREFERRED : "(?:" + SELF + "|" + PREFERRED + ")";
        var statement = Pattern.compile("(?:^|[\\s，,、])(" + verb + "\\s*[‘“\"「]?" + Pattern.quote(name)
                + "[’”\"」]?" + END + ")");
        // 模型引句可以短于原消息，但肯定关系必须同时出现在引句和它实际所在的完整语境。
        int offset = 0;
        for (String sentence : content.split("(?<=[。!！?？；;\\n])|(?<=\\.)(?=\\s|$)")) {
            int base = offset;
            offset += sentence.length();
            if (INDIRECT.matcher(sentence).find() || sentence.matches("(?s).*(?:[?？]|[吗么][。！!]?)\\s*$")
                    || global && LOCAL.matcher(sentence).find()) continue;
            var match = statement.matcher(sentence.stripTrailing().replaceFirst("[。.!！；;]$", ""));
            while (match.find()) {
                int start = base + match.start(1), end = base + match.end(1);
                if (quoted(content, start)) continue;
                for (int index = content.indexOf(quote); index >= 0; index = content.indexOf(quote, index + 1))
                    if (index <= start && end <= index + quote.length()) return true;
            }
        }
        return false;
    }

    /** 即使另一个人没有已知姓名，明显在说第三方的话也不能直接认作发言者本人的习惯。 */
    static boolean describesAnother(String content) {
        String raw = content.stripLeading();
        return raw.startsWith("{") || raw.startsWith("[") || raw.startsWith(">") || THIRD_PERSON.matcher(raw).find();
    }

    /** 旧档只有 QQ 可作独立归属时，要求清楚的本人叙述，排除他人的原话及主语不明的转述。 */
    static boolean directSelfReport(String content) {
        String raw = content.stripLeading();
        return raw.startsWith("我") && !describesAnother(raw) && !INDIRECT.matcher(raw).find()
                && !DISAVOWED.matcher(raw).find()
                && !raw.matches("(?s).*(?:[“‘「『\"]\\s*我|别人|对方|[？?]).*")
                && !raw.matches("(?s)^我(?:觉得|认为|听说|听到|看见|看到|发现).+");
    }

    /** 群友先讲自己的事再问听者时，只有问句前完整的本人陈述可提供归属。整段中的引述和玩笑仍有效。 */
    static boolean conversationalSelfReport(String words) {
        if (directSelfReport(words)) return true;
        var followUp = FOLLOW_UP.matcher(words);
        return followUp.matches() && !INDIRECT.matcher(words).find() && !DISAVOWED.matcher(words).find()
                && directSelfReport(followUp.group(1));
    }

    /** 单独向另一人提问，或明确声明自己正在转述，均不能单凭此句给任何一方确认人物事实。 */
    static boolean onlyQuestionOrQuotation(String words) {
        return QUESTION_ONLY.matcher(words).matches() || REPORTED_SPEECH.matcher(words).find();
    }

    static boolean locallyScoped(String content) { return LOCAL.matcher(content).find(); }

    /** 中文称呼不依赖空格分词。仅把有称谓边界、明确语法位置的提及用于有归属后果的桥接。 */
    static boolean refers(String content, String name) {
        return Pattern.compile("(?:^|[\\s，,。.!！?？：:、‘“\"「]|提到|提起|关于|听说|听到|我认识|和|与|跟)" + Pattern.quote(name)
                + "(?=$|[\\s，,。.!！?？：:、’”\"」]|说|讲|认为|觉得|喜欢|希望|想|在|会|是|也|刚|已|今天|明天|昨[天晚]|帮|借|来|带|负责|的|把|给|将|正)")
                .matcher(content).find();
    }

    /** 检查称呼动词之前的引述边界，允许给姓名本身加引号。 */
    private static boolean quoted(String sentence, int end) {
        int nesting = 0;
        boolean ascii = false;
        for (int i = 0; i < end; i++) {
            char c = sentence.charAt(i);
            if ("“‘「『".indexOf(c) >= 0) nesting++;
            else if ("”’」』".indexOf(c) >= 0 && nesting > 0) nesting--;
            else if (c == '"') ascii = !ascii;
        }
        return nesting != 0 || ascii;
    }

    /** 回答须确实肯定了针对姓名的问句，第三人的转述和含否定的答复不参与确认。 */
    static boolean asks(String full, String quote, String name) {
        if (INDIRECT.matcher(full).find() || !quote.contains(name) || full.contains("还是") || full.contains("或者")
                || full.codePoints().filter(c -> c == '？' || c == '?').count() > 1) return false;
        String literal = Pattern.quote(name);
        var question = Pattern.compile("(?:你(?:也|以前|之前|原来|是不是|是否|还)?(?:叫|是)|你的(?:名字|昵称|别称)(?:是|叫))\\s*[‘“\"「]?"
                + literal + "[’”\"」]?(?:[吗么]|[，,]?对(?:吗|不对)|[？?])|" + literal + "是你(?:吗|么|[？?])");
        var matched = question.matcher(full);
        while (matched.find())
            if (quote.contains(matched.group()) && !quoted(full, matched.start())) return true;
        return false;
    }

    static boolean agrees(String full, String quote, String name, boolean global) {
        if (declares(full, quote, name, false, global)) return true;
        if (INDIRECT.matcher(full).find() || global && LOCAL.matcher(full).find()) return false;
        String assent = "(?:是(?:的|我)|对(?:的|啊)|没错|没错是我|就是我|是|对|嗯|嗯嗯)";
        return full.strip().matches(assent + "(?:[，,、\\s]+" + assent + ")*[。.!！]?")
                && quote.strip().matches(assent + "(?:[，,、\\s]+" + assent + ")*[。.!！]?");
    }
}
