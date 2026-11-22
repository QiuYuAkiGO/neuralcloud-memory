package neuralcloud.memory;

import java.util.*;
import java.util.regex.*;

final class Terms {
    private static final Pattern RUN =
            Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}_&&[^\\p{IsHan}]]+");

    static Set<String> terms(String text) {
        var result = new LinkedHashSet<String>();
        var matcher = RUN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) != Character.UnicodeScript.HAN) {
                result.add(word);
                continue;
            }
            int[] cp = word.codePoints().toArray();
            for (int i = 0; i < cp.length; i++) {
                result.add(new String(cp, i, 1));
                if (i + 1 < cp.length) result.add(new String(cp, i, 2));
            }
        }
        return result;
    }

    static String expression(String text) {
        return String.join(" OR ", terms(text).stream().map(t -> "\"" + t + "\"").toList());
    }
}
