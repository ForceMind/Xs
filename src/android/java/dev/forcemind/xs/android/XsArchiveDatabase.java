package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.PostUrls;

import java.util.ArrayList;
import java.util.List;

/**
 * Android SQLite archive stored in the containing application's private storage.
 * This is source code for an Android library, not a hook or patched X APK.
 */
public final class XsArchiveDatabase extends SQLiteOpenHelper {
    public XsArchiveDatabase(Context context) {
        super(context.getApplicationContext(), "xs-local-history.db",
              null, ArchiveSchema.VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        for (String statement : ArchiveSchema.CREATE) db.execSQL(statement);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            for (String sql : ArchiveSchema.MIGRATE_1_TO_2) db.execSQL(sql);
            return;
        }
        // Fail safely rather than deleting existing history for an unsupported version.
        throw new IllegalStateException("Xs migration required: " +
                                        oldVersion + " -> " + newVersion);
    }

    /** One transaction: upsert visible post metadata and append distinct visit. */
    public boolean recordOpened(String accountId, String postUrl, String author,
                                String text, Long publishedAtMs, long openedAtMs) {
        String id = PostUrls.extractPostId(postUrl);
        if (!valid(accountId) || id == null || openedAtMs <= 0) return false;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            insertPostIfMissing(db, accountId, id, author, text, publishedAtMs, openedAtMs);
            db.execSQL(
                "UPDATE posts SET opened_count=opened_count+1, " +
                "last_seen_at_ms=MAX(last_seen_at_ms, ?), " +
                "last_opened_at_ms=MAX(COALESCE(last_opened_at_ms, 0), ?) " +
                "WHERE account_id=? AND post_id=?",
                new Object[]{openedAtMs, openedAtMs, accountId, id});
            updateKnownMetadata(db, accountId, id, author, text, publishedAtMs);
            ContentValues event = new ContentValues();
            event.put("account_id", accountId);
            event.put("post_id", id);
            event.put("event_type", "OPEN_POST");
            event.put("event_at_ms", openedAtMs);
            event.put("source", "post_detail");
            db.insertOrThrow("history_events", null, event);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Record *verified* self-content activity separately from visits.
     * No synthetic action timestamp; unknown action time stays NULL.
     */
    public boolean recordPersonalAction(String accountId, String postUrl,
                                        String actionType, String author, String text,
                                        Long publishedAtMs, Long actionAtMs,
                                        long observedAtMs) {
        String id = PostUrls.extractPostId(postUrl);
        if (!valid(accountId) || id == null || !validAction(actionType) ||
                observedAtMs <= 0 || (actionAtMs != null && actionAtMs <= 0)) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            insertPostIfMissing(db, accountId, id, author, text, publishedAtMs, observedAtMs);
            updateKnownMetadata(db, accountId, id, author, text, publishedAtMs);
            db.execSQL("UPDATE posts SET last_seen_at_ms=MAX(last_seen_at_ms, ?) " +
                       "WHERE account_id=? AND post_id=?",
                       new Object[]{observedAtMs, accountId, id});
            ContentValues values = new ContentValues();
            values.put("account_id", accountId);
            values.put("post_id", id);
            values.put("action_type", actionType);
            if (actionAtMs == null) values.putNull("action_at_ms");
            else values.put("action_at_ms", actionAtMs);
            values.put("source", "verified_self_activity");
            // Idempotent when refreshing an existing action: keep its original timestamp.
            db.insertWithOnConflict("user_actions", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Opt-in timeline capture, distinct from deliberate OPEN_POST history.
     * The caller has already verified foreground visibility and dwell duration.
     */
    public boolean recordFeedVisible(String accountId, String postUrl, String source,
                                     String author, String text, Long publishedAtMs,
                                     long eventAtMs) {
        String id = PostUrls.extractPostId(postUrl);
        if (!valid(accountId) || id == null ||
            !dev.forcemind.xs.core.FeedCapturePolicy.supportedSource(source) ||
            eventAtMs <= 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            insertPostIfMissing(db, accountId, id, author, text, publishedAtMs, eventAtMs);
            db.execSQL(
                "UPDATE posts SET last_seen_at_ms=MAX(last_seen_at_ms, ?), " +
                "last_feed_seen_at_ms=MAX(COALESCE(last_feed_seen_at_ms, 0), ?) " +
                "WHERE account_id=? AND post_id=?",
                new Object[]{eventAtMs, eventAtMs, accountId, id});
            updateKnownMetadata(db, accountId, id, author, text, publishedAtMs);
            ContentValues event = new ContentValues();
            event.put("account_id", accountId);
            event.put("post_id", id);
            event.put("event_type", "FEED_VISIBLE");
            event.put("event_at_ms", eventAtMs);
            event.put("source", source);
            db.insertOrThrow("history_events", null, event);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private static boolean validAction(String type) {
        return "ORIGINAL".equals(type) || "REPLY".equals(type) ||
               "QUOTE".equals(type) || "REPOST".equals(type) ||
               "BOOKMARK".equals(type);
    }

    private static void insertPostIfMissing(SQLiteDatabase db, String accountId, String id,
                                            String author, String text, Long publishedAtMs,
                                            long observedAtMs) {
        ContentValues post = new ContentValues();
        post.put("account_id", accountId);
        post.put("post_id", id);
        if (author != null) post.put("author_handle", author);
        if (text != null) post.put("body", text);
        if (publishedAtMs != null) post.put("published_at_ms", publishedAtMs);
        post.put("first_seen_at_ms", observedAtMs);
        post.put("last_seen_at_ms", observedAtMs);
        db.insertWithOnConflict("posts", null, post, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void updateKnownMetadata(SQLiteDatabase db, String accountId, String id,
                                            String author, String text, Long publishedAtMs) {
        ContentValues values = new ContentValues();
        if (author != null && !author.isEmpty()) values.put("author_handle", author);
        if (text != null && !text.isEmpty()) values.put("body", text);
        if (publishedAtMs != null) values.put("published_at_ms", publishedAtMs);
        if (values.size() != 0) {
            db.update("posts", values, "account_id=? AND post_id=?",
                      new String[]{accountId, id});
        }
    }

    public Cursor search(String accountId, ArchiveSearch.Filter filter) {
        ArchiveSearch.Query q = ArchiveSearch.build(accountId, filter);
        return getReadableDatabase().rawQuery(q.sql, q.args);
    }

    /** Older API returns deliberate opens only, never mixed with feed impressions. */
    public Cursor timeline(String accountId, Long afterMs, int requestedLimit) {
        return timeline(accountId, afterMs, requestedLimit, "OPEN_POST");
    }

    /**
     * Query one explicitly selected event type. Never silently mix screen opens
     * and feed impressions: they carry different user meaning.
     */
    public Cursor timeline(String accountId, Long afterMs, int requestedLimit,
                           String eventType) {
        return timeline(accountId, afterMs, requestedLimit, eventType, 0);
    }

    public Cursor timeline(String accountId, Long afterMs, int requestedLimit,
                           String eventType, int offset) {
        if (offset < 0) throw new IllegalArgumentException("Negative timeline offset");
        if (!"OPEN_POST".equals(eventType) && !"FEED_VISIBLE".equals(eventType)) {
            throw new IllegalArgumentException("Unsupported history event type");
        }
        requireAccount(accountId);
        StringBuilder sql = new StringBuilder(
            "SELECT e.id, e.post_id, e.event_at_ms, e.event_type, " +
            "p.author_handle, p.body, p.published_at_ms, e.source " +
            "FROM history_events e JOIN posts p ON e.account_id=p.account_id " +
            "AND e.post_id=p.post_id WHERE e.account_id=? AND e.event_type=?");
        List<String> args = new ArrayList<>();
        args.add(accountId);
        args.add(eventType);
        if (afterMs != null) {
            sql.append(" AND e.event_at_ms>=?");
            args.add(Long.toString(afterMs));
        }
        sql.append(" ORDER BY e.event_at_ms DESC, e.id DESC LIMIT ? OFFSET ?");
        args.add(Integer.toString(Math.max(1, Math.min(200, requestedLimit))));
        args.add(Integer.toString(offset));
        return getReadableDatabase().rawQuery(sql.toString(),
                                               args.toArray(new String[0]));
    }

    public Cursor categories(String accountId) {
        requireAccount(accountId);
        return getReadableDatabase().rawQuery(
            "SELECT category_id, label FROM categories WHERE account_id=? " +
            "ORDER BY label COLLATE NOCASE, category_id", new String[]{accountId});
    }

    /** Create or rename local category within the active account namespace. */
    public void putCategory(String accountId, String categoryId, String label) {
        requireAccount(accountId);
        if (!valid(categoryId) || !valid(label)) {
            throw new IllegalArgumentException("Category ID and label are required");
        }
        String name = label.trim();
        if (name.length() > 120) throw new IllegalArgumentException("Folder name too long");
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("account_id", accountId);
        values.put("category_id", categoryId);
        values.put("label", name);
        // REPLACE first deletes the old row and cascades away all folder assignments.
        // INSERT IGNORE + UPDATE preserves the existing category primary key and links.
        db.insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues rename = new ContentValues();
        rename.put("label", name);
        db.update("categories", rename, "account_id=? AND category_id=?",
                  new String[]{accountId, categoryId});
    }

    public boolean assignCategory(String accountId, String postId, String categoryId) {
        requireAccount(accountId);
        if (!valid(postId) || !valid(categoryId)) {
            throw new IllegalArgumentException("Post and folder IDs are required");
        }
        ContentValues values = new ContentValues();
        values.put("account_id", accountId);
        values.put("post_id", postId);
        values.put("category_id", categoryId);
        return getWritableDatabase().insertWithOnConflict(
            "post_categories", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public void deleteCategory(String accountId, String categoryId) {
        requireAccount(accountId);
        if (!valid(categoryId)) throw new IllegalArgumentException("Folder ID is required");
        getWritableDatabase().delete("categories", "account_id=? AND category_id=?",
                                     new String[]{accountId, categoryId});
    }

    /** Erases only the specified account's *local* Xs data. */
    public void clearAccount(String accountId) {
        requireAccount(accountId);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Foreign keys cascade to visits/actions/categories; triggers clear FTS.
            db.delete("posts", "account_id=?", new String[]{accountId});
            db.delete("categories", "account_id=?", new String[]{accountId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void requireAccount(String accountId) {
        if (!valid(accountId)) throw new IllegalArgumentException("Account ID required");
    }

    private static boolean valid(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
