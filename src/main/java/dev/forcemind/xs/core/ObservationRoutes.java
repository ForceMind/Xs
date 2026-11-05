package dev.forcemind.xs.core;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Capture permission is narrower than BrowserNavigation. Browser navigation
 * may be allowed without granting any native observation privileges.
 */
public final class ObservationRoutes {
    public enum Kind { POST_DETAIL, FEED, DENIED }
    private ObservationRoutes() {}

    public static boolean trustedOrigin(String raw) {
        if (raw == null || raw.length() > 256) return false;
        try {
            URI u = new URI(raw);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo() != null ||
                u.getHost() == null || (u.getPort() != -1 && u.getPort() != 443) ||
                !("x.com".equalsIgnoreCase(u.getHost()) ||
                  "www.x.com".equalsIgnoreCase(u.getHost())) ||
                u.getRawQuery() != null || u.getRawFragment() != null) return false;
            return u.getRawPath() == null || u.getRawPath().isEmpty() ||
                   "/".equals(u.getRawPath());
        } catch (URISyntaxException invalid) { return false; }
    }

    public static Kind classify(String raw) {
        if (raw == null || raw.length() > 8192 || raw.indexOf('\\') >= 0) return Kind.DENIED;
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i)) || Character.isWhitespace(raw.charAt(i))) {
                return Kind.DENIED;
            }
        }
        try {
            URI u = new URI(raw);
            if (!trustedOrigin(u.getScheme() + "://" + u.getRawAuthority()) ||
                u.getRawPath() == null || u.getRawPath().contains("%") ||
                u.getRawPath().contains(";")) return Kind.DENIED;
            String path = u.getRawPath();
            if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
            if (path.equals("/home") || path.equals("/search") || path.equals("/explore"))
                return Kind.FEED;
            if (PostUrls.extractPostId(raw) != null) return Kind.POST_DETAIL;
            // Login, DMs, drafts, security, composer and unknown URLs all denied.
            return Kind.DENIED;
        } catch (URISyntaxException invalid) { return Kind.DENIED; }
    }
}
