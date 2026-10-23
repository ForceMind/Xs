package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.PostUrls;

/**
 * Android-only storage inside the existing X app's private sandbox.
 * Not an X hook: integration into the tested target APKM is a separate task.
 */
public final class XsArchiveDatabase extends SQLiteOpenHelper {
    public XsArchiveDatabase(Context context) {
        super(context.getApplicationContext(), "xs-local-history.db", null,
              ArchiveSchema.VERSION);
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
        // Never destroy user history via destructive automatic recreation.
        throw new IllegalStateException("Xs schema migration required: " + oldVersion +
                                        " -> " + newVersion);
    }

    /** One transaction: distinct post row + increment visit count + append visit. */
    public boolean recordOpened(String accountId, String postUrl, String author,
                                String text, Long publishedAtMs, long openedAtMs) {
        String id = PostUrls.extractPostId(postUrl);
        if (!valid(accountId) || id == null || openedAtMs <= 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues post = new ContentValues();
            post.put("account_id", accountId);
            post.put("post_id", id);
            if (author != null) post.put("author_handle", author);
            if (text != null) post.put("body", text);
            if (publishedAtMs != null) post.put("published_at_ms", publishedAtMs);
            post.put("first_seen_at_ms", openedAtMs);
            post.put("last_seen_at_ms", openedAtMs);
            db.insertWithOnConflict("posts", null, post, SQLiteDatabase.CONFLICT_IGNORE);

            db.execSQL("UPDATE posts SET opened_count=opened_count+1, " +
                        "last_seen_at_ms=MAX(last_seen_at_ms, ?) " +
                        "WHERE account_id=? AND post_id=?",
                       new Object[] {openedAtMs, accountId, id});
            ContentValues patch = new ContentValues();
            if (author != null && !author.isEmpty()) patch.put("author_handle", author);
            if (text != null && !text.isEmpty()) patch.put("body", text);
            if (publishedAtMs != null) patch.put("published_at_ms", publishedAtMs);
            if (patch.size() != 0) {
                db.update("posts", patch, "account_id=? AND post_id=?",
                          new String[]{accountId, id});
            }
            ContentValues visit = new ContentValues();
            visit.put("account_id", accountId);
            visit.put("post_id", id);
            visit.put("event_type", "OPEN_POST");
            visit.put("event_at_ms", openedAtMs);
            visit.put("source", "post_detail");
            db.insertOrThrow("history_events", null, visit);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public Cursor search(String accountId, ArchiveSearch.Filter filter) {
        ArchiveSearch.Query query = ArchiveSearch.build(accountId, filter);
        return getReadableDatabase().rawQuery(query.sql, query.args);
    }

    public Cursor timeline(String accountId, int limit) {
        if (!valid(accountId)) throw new IllegalArgumentException("account ID required");
        return getReadableDatabase().rawQuery(
            "SELECT e.id, e.post_id, e.event_at_ms, e.event_type, " +
            "p.author_handle, p.body, p.published_at_ms " +
            "FROM history_events e JOIN posts p ON e.account_id=p.account_id " +
            "AND e.post_id=p.post_id WHERE e.account_id=? " +
            "ORDER BY e.event_at_ms DESC, e.id DESC LIMIT ?",
            new String[]{accountId, Integer.toString(Math.max(1, Math.min(200, limit)))});
    }

    public void putCategory(String accountId, String categoryId, String label) {
        if (!valid(accountId) || !valid(categoryId) || !valid(label)) {
            throw new IllegalArgumentException("category scope, ID and label required");
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("account_id", accountId);
        values.put("category_id", categoryId);
        values.put("label", label);
        db.insertWithOnConflict("categories", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues update = new ContentValues();
        update.put("label", label);
        db.update("categories", update, "account_id=? AND category_id=?",
                  new String[]{accountId, categoryId});
    }

    public void assignCategory(String accountId, String postId, String categoryId) {
        if (!valid(accountId) || !valid(postId) || !valid(categoryId)) {
            throw new IllegalArgumentException("category and post required");
        }
        ContentValues values = new ContentValues();
        values.put("account_id", accountId);
        values.put("post_id", postId);
        values.put("category_id", categoryId);
        getWritableDatabase().insertWithOnConflict(
            "post_categories", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void clearAccount(String accountId) {
        if (!valid(accountId)) throw new IllegalArgumentException("account ID required");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Cascading delete removes visits, actions and associations;
            // FTS triggers remove corresponding index entries.
            db.delete("posts", "account_id=?", new String[]{accountId});
            db.delete("categories", "account_id=?", new String[]{accountId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static boolean valid(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
