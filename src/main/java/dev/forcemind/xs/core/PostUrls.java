package dev.forcemind.xs.core;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Strictly parses public X/Twitter post links. No network calls or credentials. */
public final class PostUrls {
    private static final Pattern ID = Pattern.compile("[0-9]{1,20}");
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_]{1,15}");

    private PostUrls() {}

    public static String extractPostId(String raw) {
        if (raw == null) return null;
        String input = raw.trim();
        if (input.isEmpty()) return null;
        if (input.matches("(?i)^(?:www\\.|mobile\\.)?(?:x|twitter)\\.com/.*")) {
            input = "https://" + input;
        }
        URI uri;
        try {
            uri = URI.create(input);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getRawUserInfo() != null) return null;
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
        String domain = host.toLowerCase(Locale.ROOT);
        if (!("x.com".equals(domain) || "www.x.com".equals(domain) ||
              "mobile.x.com".equals(domain) || "twitter.com".equals(domain) ||
              "www.twitter.com".equals(domain) || "mobile.twitter.com".equals(domain))) {
            return null;
        }
        if (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) return null;
        String path = uri.getRawPath();
        if (path == null) return null;
        String[] part = path.split("/", -1);
        int length = part.length;
        if (length > 1 && part[length - 1].isEmpty()) length--;
        if (length < 4 || !part[0].isEmpty()) return null;
        int idAt = -1;
        if (HANDLE.matcher(part[1]).matches() && "status".equals(part[2])) idAt = 3;
        if ("i".equals(part[1]) && "status".equals(part[2])) idAt = 3;
        if (length >= 5 && "i".equals(part[1]) && "web".equals(part[2]) &&
                "status".equals(part[3])) idAt = 4;
        if (idAt < 0 || length <= idAt || !ID.matcher(part[idAt]).matches()) return null;
        if (length != idAt + 1) {
            if (length != idAt + 3 ||
                    !("photo".equals(part[idAt + 1]) || "video".equals(part[idAt + 1])) ||
                    !ID.matcher(part[idAt + 2]).matches()) return null;
        }
        return part[idAt];
    }

    public static String canonicalLink(String raw) {
        String id = extractPostId(raw);
        return id == null ? null : "https://x.com/i/web/status/" + id;
    }
}
