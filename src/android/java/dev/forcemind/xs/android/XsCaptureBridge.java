package dev.forcemind.xs.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import dev.forcemind.xs.core.FeedCapturePolicy;
import dev.forcemind.xs.core.PostUrls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Version-neutral entry points for a future verified X app integration adapter.
 *
 * bindAuthenticatedAccount must be called when the authenticated session changes.
 * Recording is OFF until the user explicitly enables it for the bound account.
 */
public final class XsCaptureBridge {
    private static final String TAG = "XsArchive";
    private static final String PREFERENCES = "xs_local_archive";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static final FeedCapturePolicy FEED_POLICY = new FeedCapturePolicy();
    private static volatile XsArchiveDatabase store;
    private static volatile Session session;

    private static final class Session {
        final String accountId;
        final boolean recordingEnabled;
        final boolean feedRecordingEnabled;

        Session(String accountId, boolean recordingEnabled, boolean feedRecordingEnabled) {
            this.accountId = accountId;
            this.recordingEnabled = recordingEnabled;
            this.feedRecordingEnabled = feedRecordingEnabled;
        }
    }

    private XsCaptureBridge() {}

    /**
     * Stable account ID must be verified by the host's authenticated account
     * lifecycle, never inferred from a post's author, URL, or display handle.
     */
    public static synchronized void bindAuthenticatedAccount(Context context, String stableAccountId) {
        if (context == null || stableAccountId == null ||
                stableAccountId.trim().isEmpty() || stableAccountId.length() > 128) {
            session = null;
            return;
        }
        if (store == null) store = new XsArchiveDatabase(context.getApplicationContext());
        SharedPreferences preferences = context.getApplicationContext().getSharedPreferences(
            PREFERENCES, Context.MODE_PRIVATE);
        boolean enabled = preferences.getBoolean(key(stableAccountId), false);
        boolean feedEnabled = preferences.getBoolean(feedKey(stableAccountId), false);
        session = new Session(stableAccountId, enabled, feedEnabled);
    }

    /** Logout/switch gap: stop recording until an authenticated ID is rebound. */
    public static synchronized void unbindAccount() {
        session = null;
    }

    public static String getActiveAccountId() {
        Session current = session;
        return current == null ? null : current.accountId;
    }

    public static boolean isRecordingEnabled() {
        Session current = session;
        return current != null && current.recordingEnabled;
    }

    /** User-facing opt-in control, persisted independently per account. */
    public static synchronized boolean setRecordingEnabled(Context context, boolean enabled) {
        Session active = session;
        if (context == null || active == null) return false;
        context.getApplicationContext()
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(active.accountId), enabled)
            .apply();
        session = new Session(active.accountId, enabled, active.feedRecordingEnabled);
        return true;
    }

    /** Independent per-account opt-in for visible feed impressions. */
    public static boolean isFeedRecordingEnabled() {
        Session current = session;
        return current != null && current.feedRecordingEnabled;
    }

    public static synchronized boolean setFeedRecordingEnabled(Context context, boolean enabled) {
        Session current = session;
        if (context == null || current == null) return false;
        context.getApplicationContext()
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(feedKey(current.accountId), enabled)
            .apply();
        session = new Session(current.accountId, current.recordingEnabled, enabled);
        if (!enabled) FEED_POLICY.reset();
        return true;
    }

    /**
     * Adapter-facing API for confirmed timeline visibility. Never call this
     * on prefetches, list binding, background activity or partial flashes.
     * A separate user opt-in is required, even if opening history is enabled.
     */
    public static synchronized void onFeedPostVisible(String postUrl, String source,
            String authorHandle, String visibleText, Long publishedAtMs,
            boolean appInForeground, double visibleFraction, long dwellMs, long seenAtMs) {
        Session active = session;
        final XsArchiveDatabase db = store;
        if (active == null || !active.feedRecordingEnabled || db == null) return;
        String id = PostUrls.extractPostId(postUrl);
        if (!FEED_POLICY.shouldSave(active.accountId, id, source, appInForeground,
                                    visibleFraction, dwellMs, seenAtMs)) return;
        final String body = trim(visibleText, 16000);
        final String author = trim(authorHandle, 100);
        IO.execute(() -> {
            try {
                db.recordFeedVisible(active.accountId, postUrl, source, author, body,
                                     publishedAtMs, seenAtMs);
            } catch (RuntimeException error) {
                Log.w(TAG, "Local feed archive failed; original app unaffected");
            }
        });
    }

    /**
     * Invoke ONLY after a deliberate transition to a post detail, once for each
     * intentional open; never during timeline bind, impression or prefetch.
     */
    public static synchronized void onPostOpened(String postUrl, String authorHandle,
                                    String visibleText, Long publishedAtMs,
                                    long openedAtMs) {
        final Session active = session;
        final XsArchiveDatabase database = store;
        if (active == null || !active.recordingEnabled || database == null) return;
        final String body = trim(visibleText, 16000);
        final String author = trim(authorHandle, 100);
        IO.execute(() -> {
            try {
                database.recordOpened(active.accountId, postUrl, author, body,
                                      publishedAtMs, openedAtMs);
            } catch (RuntimeException error) {
                // Never log actual user content, post URL, credentials or raw DB errors.
                Log.w(TAG, "Local archive operation failed; original app unaffected");
            }
        });
    }

    /**
     * Reserved for verified self-content events. Action time must be a real
     * observed timestamp or null; visiting a repost is NOT a repost action.
     */
    public static synchronized void onPersonalAction(String postUrl, String actionType,
                                        String authorHandle, String visibleText,
                                        Long publishedAtMs, Long actionAtMs,
                                        long observedAtMs) {
        final Session active = session;
        final XsArchiveDatabase database = store;
        if (active == null || !active.recordingEnabled || database == null) return;
        IO.execute(() -> {
            try {
                database.recordPersonalAction(active.accountId, postUrl, actionType,
                    trim(authorHandle, 100), trim(visibleText, 16000),
                    publishedAtMs, actionAtMs, observedAtMs);
            } catch (RuntimeException error) {
                Log.w(TAG, "Local action archive failed; original app unaffected");
            }
        });
    }

    public interface ClearCallback {
        void onComplete(boolean success);
    }

    /**
     * Serialize account erasure with all already-enqueued capture events.
     * Stops future recording first. Completes on the main thread.
     */
    public static synchronized boolean clearActiveAccount(Context context,
                                                           ClearCallback callback) {
        final Session active = session;
        final XsArchiveDatabase database = store;
        if (context == null || active == null || database == null) return false;
        setRecordingEnabled(context, false);
        setFeedRecordingEnabled(context, false);
        FEED_POLICY.reset();
        IO.execute(() -> {
            boolean result;
            try {
                database.clearAccount(active.accountId);
                result = true;
            } catch (RuntimeException error) {
                Log.w(TAG, "Local archive erase failed");
                result = false;
            }
            final boolean ok = result;
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(ok));
            }
        });
        return true;
    }

    /**
     * Refuses a pending erase if the active account changed while the UI was
     * processing a prior import or category edit.
     */
    public static synchronized boolean clearAccountIfActive(Context context,
                                                            String expectedAccountId,
                                                            ClearCallback callback) {
        Session current = session;
        return current != null && current.accountId.equals(expectedAccountId) &&
               clearActiveAccount(context, callback);
    }

    /** To be called from a future internal menu/settings integration. */
    public static void openLocalArchive(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, XsHistoryActivity.class);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    private static String trim(String input, int maxChars) {
        if (input == null) return null;
        return input.length() > maxChars ? input.substring(0, maxChars) : input;
    }

    private static String feedKey(String stableAccountId) {
        return "feed_" + key(stableAccountId);
    }

    private static String key(String stableAccountId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                stableAccountId.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("record_");
            for (int i = 0; i < 16; i++) {
                out.append(Character.forDigit((digest[i] >> 4) & 15, 16));
                out.append(Character.forDigit(digest[i] & 15, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }
}
