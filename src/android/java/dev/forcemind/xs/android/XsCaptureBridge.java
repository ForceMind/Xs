package dev.forcemind.xs.android;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Host-facing bridge. Hook this only after verifying a genuine post-detail
 * navigation callback; feeding timeline render or prefetch events is wrong.
 */
public final class XsCaptureBridge {
    private static final String TAG = "XsArchive";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile XsArchiveDatabase store;
    private static volatile String accountId;
    private static volatile boolean enabled = false;

    private XsCaptureBridge() {}

    /** Caller must supply verified stable account ID, not handle/display name. */
    public static synchronized void initialize(Context context, String stableAccountId,
                                               boolean recordingEnabled) {
        if (context == null || stableAccountId == null ||
                stableAccountId.trim().isEmpty()) {
            enabled = false;
            accountId = null;
            return;
        }
        if (store == null) store = new XsArchiveDatabase(context);
        accountId = stableAccountId;
        enabled = recordingEnabled;
    }

    public static void enableRecording(boolean recordingEnabled) {
        enabled = recordingEnabled;
    }

    /** Fire once per deliberate opening, not per render or background fetch. */
    public static void onPostOpened(String postUrl, String authorHandle,
                                    String visibleText, Long publishedAtMs,
                                    long openedAtMs) {
        final XsArchiveDatabase db = store;
        final String actor = accountId;
        if (!enabled || db == null || actor == null) return;
        IO.execute(() -> {
            try {
                db.recordOpened(actor, postUrl, authorHandle, visibleText,
                                publishedAtMs, openedAtMs);
            } catch (RuntimeException error) {
                // Do not log user content, post URL, credentials or the message.
                Log.w(TAG, "Local archive write failed; X navigation unaffected");
            }
        });
    }
}
