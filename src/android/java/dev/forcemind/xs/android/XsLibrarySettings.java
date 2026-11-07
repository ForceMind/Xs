package dev.forcemind.xs.android;

import android.content.Context;
import android.content.SharedPreferences;

/** Native preferences only. Consent does not bypass the live-site validation gate. */
public final class XsLibrarySettings {
    private static final String PREFS = "xs_library_settings";
    private XsLibrarySettings() {}

    private static SharedPreferences prefs(Context context) {
        if (context == null) throw new IllegalArgumentException("Context required");
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isOpenEnabled(Context context) {
        return prefs(context).getBoolean("open_history", false);
    }

    public static boolean isFeedEnabled(Context context) {
        return prefs(context).getBoolean("feed_dwell", false);
    }

    public static long getDwellThresholdMs(Context context) {
        long value = prefs(context).getLong("dwell_threshold_ms", 3000L);
        return value == 1200L || value == 3000L || value == 5000L ? value : 3000L;
    }

    public static void setOpenEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("open_history", enabled).apply();
    }

    public static void setFeedEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("feed_dwell", enabled).apply();
    }

    public static void setDwellThresholdMs(Context context, long value) {
        if (value != 1200L && value != 3000L && value != 5000L) {
            throw new IllegalArgumentException("Unsupported dwell threshold");
        }
        prefs(context).edit().putLong("dwell_threshold_ms", value).apply();
    }
}
