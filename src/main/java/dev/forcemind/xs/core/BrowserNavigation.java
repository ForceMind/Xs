package dev.forcemind.xs.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Top-level browser policy only; subresources do not gain native privileges. */
public final class BrowserNavigation {
    public enum Target { INTERNAL, EXTERNAL, BLOCKED }
    private BrowserNavigation() {}

    public static Target classify(String value) {
        if (value == null || value.length() > 8192 || value.indexOf('\\') >= 0) return Target.BLOCKED;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i)) || Character.isWhitespace(value.charAt(i))) {
                return Target.BLOCKED;
            }
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null ||
                uri.getHost() == null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return Target.BLOCKED;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("x.com") || host.equals("www.x.com") || host.equals("twitter.com") ||
                host.equals("www.twitter.com") || host.equals("mobile.twitter.com")) {
                return Target.INTERNAL;
            }
            return Target.EXTERNAL;
        } catch (URISyntaxException invalid) {
            return Target.BLOCKED;
        }
    }
}
