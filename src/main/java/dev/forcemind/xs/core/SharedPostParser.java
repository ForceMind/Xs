package dev.forcemind.xs.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts a user-shared X post link to a canonical, content-free local key. */
public final class SharedPostParser {
    private static final Pattern URL = Pattern.compile(
        "https://(?:www\\.|mobile\\.)?(?:x\\.com|twitter\\.com)/[^\\s<>\"']+",
        Pattern.CASE_INSENSITIVE);
    private static final int MAX_TEXT = 8192;

    private SharedPostParser() {}

    public static String fromSharedText(String text) {
        if (text == null || text.length() > MAX_TEXT) return null;
        Matcher match = URL.matcher(text);
        while (match.find()) {
            String candidate = match.group();
            // Common punctuation introduced by messaging/share applications.
            while (!candidate.isEmpty() && endsInPunctuation(candidate)) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            String canonical = PostUrls.canonicalLink(candidate);
            if (canonical != null) return canonical;
        }
        return null;
    }

    private static boolean endsInPunctuation(String s) {
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == ',' || c == ';' || c == '!' ||
               c == '?' || c == ')' || c == ']' || c == '}' ||
               c == '。' || c == '，' || c == '！' || c == '？' ||
               c == '）' || c == '】' || c == '」';
    }
}
