package dev.forcemind.xs.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defensive, process-local de-duplication of confirmed feed visibility events.
 * No post content, URL, token or display name is stored in the policy.
 */
public final class FeedCapturePolicy {
    public static final double MIN_VISIBLE_FRACTION = 0.60;
    public static final long MIN_DWELL_MS = 1200;
    public static final long SUPPRESS_REPEAT_MS = 120_000;
    private static final int MAX_ENTRIES = 4096;

    private final LinkedHashMap<String, Long> recent =
        new LinkedHashMap<String, Long>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> first) {
                return size() > MAX_ENTRIES;
            }
        };

    public static boolean supportedSource(String source) {
        return "home_for_you".equals(source) ||
               "home_following".equals(source) ||
               "profile".equals(source) ||
               "search_results".equals(source);
    }

    public static boolean confirmedVisible(boolean foreground, double visibleFraction,
                                           long dwellMs, String source, long eventMs) {
        return foreground && !Double.isNaN(visibleFraction) &&
               visibleFraction >= MIN_VISIBLE_FRACTION &&
               visibleFraction <= 1.0 && dwellMs >= MIN_DWELL_MS &&
               supportedSource(source) && eventMs > 0;
    }

    /**
     * Must only be called after foreground visibility and dwell are verified
     * in the host UI. Repeated list binds within a short time are suppressed.
     */
    public synchronized boolean shouldSave(String accountId, String postId, String source,
                                           boolean foreground, double visibleFraction,
                                           long dwellMs, long eventMs) {
        if (accountId == null || accountId.isEmpty() ||
            postId == null || postId.isEmpty() ||
            !confirmedVisible(foreground, visibleFraction, dwellMs, source, eventMs)) {
            return false;
        }
        String key = accountId + '\u0000' + source + '\u0000' + postId;
        Long previous = recent.get(key);
        if (previous != null) {
            long delta = eventMs - previous;
            if (delta >= 0 && delta < SUPPRESS_REPEAT_MS) return false;
        }
        recent.put(key, eventMs);
        return true;
    }

    /** Prevent a stale in-memory throttle from suppressing events after erasure. */
    public synchronized void reset() {
        recent.clear();
    }
}
