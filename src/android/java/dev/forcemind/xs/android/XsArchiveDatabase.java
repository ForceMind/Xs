package dev.forcemind.xs.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import dev.forcemind.xs.core.ArchiveSchema;
import dev.forcemind.xs.core.ArchiveSearch;
import dev.forcemind.xs.core.PostUrls;
import dev.forcemind.xs.core.OwnActionSession;

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
        int version = oldVersion;
        if (version == 1 && newVersion >= 2) {
            for (String sql : ArchiveSchema.MIGRATE_1_TO_2) db.execSQL(sql);
            version = 2;
        }
        if (version == 2 && newVersion >= 3) {
            for (String sql : ArchiveSchema.MIGRATE_2_TO_3) db.execSQL(sql);
            version = 3;
        }
        if (version == 3 && newVersion >= 4) {
            for (String sql : ArchiveSchema.MIGRATE_3_TO_4) db.execSQL(sql);
            version = 4;
        }
        if (version == newVersion) return;
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
                "UPDATE posts SET opened_count=MIN(opened_count+1,2147483647), " +
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

    /** Native navigation identity, scoped by a non-reusable coordinator epoch. */
    public boolean recordObservedOpen(String libraryId, String postUrl, String author,
                                      String text, Long publishedAtMs, long openedAtMs,
                                      String navigationId) {
        String id = PostUrls.extractPostId(postUrl);
        if (!valid(libraryId) || id == null || openedAtMs <= 0 || !validEventKey(navigationId)) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor existing = db.rawQuery("SELECT post_id FROM history_events WHERE " +
                    "account_id=? AND event_type='OPEN_POST' AND event_key=?",
                    new String[]{libraryId,navigationId})) {
                if (existing.moveToFirst() && !id.equals(existing.getString(0))) return false;
            }
            insertPostIfMissing(db, libraryId, id, author, text, publishedAtMs, openedAtMs);
            updateKnownMetadata(db, libraryId, id, author, text, publishedAtMs);
            ContentValues event = new ContentValues();
            event.put("account_id", libraryId);
            event.put("post_id", id);
            event.put("event_type", "OPEN_POST");
            event.put("event_at_ms", openedAtMs);
            event.put("source", "post_detail");
            event.put("event_key", navigationId);
            boolean inserted = db.insertWithOnConflict("history_events", null, event,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
            if (inserted) {
                db.execSQL("UPDATE posts SET opened_count=MIN(opened_count+1,2147483647), " +
                    "last_seen_at_ms=MAX(last_seen_at_ms,?), " +
                    "last_opened_at_ms=MAX(COALESCE(last_opened_at_ms,0),?) " +
                    "WHERE account_id=? AND post_id=?",
                    new Object[]{openedAtMs, openedAtMs, libraryId, id});
            }
            db.setTransactionSuccessful();
            return inserted;
        } finally { db.endTransaction(); }
    }

    /** Enrich an already stored post. Late callbacks cannot create a missing record. */
    public boolean enrichSnapshot(String libraryId, String postUrl, String author,
                                  String text, Long publishedAtMs) {
        String id = PostUrls.extractPostId(postUrl);
        if (!valid(libraryId) || id == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT 1 FROM posts WHERE account_id=? AND post_id=?",
                new String[]{libraryId,id})) {
            if (!c.moveToFirst()) return false;
        }
        updateKnownMetadata(db, libraryId, id, author, text, publishedAtMs);
        return true;
    }

    /** One measured continuous exposure; subsequent samples only extend its duration. */
    public boolean recordMeasuredFeed(String libraryId, String postUrl, String source,
                                      String author, String text, Long publishedAtMs,
                                      long eventAtMs, String exposureId,
                                      long durationMs, long thresholdMs) {
        String id = PostUrls.extractPostId(postUrl);
        if (!valid(libraryId) || id == null || !validEventKey(exposureId) || eventAtMs <= 0 ||
            !dev.forcemind.xs.core.FeedCapturePolicy.supportedSource(source) ||
            (thresholdMs != 1200 && thresholdMs != 3000 && thresholdMs != 5000) ||
            durationMs < thresholdMs || durationMs > 86_400_000L) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Reject collision/replay before touching another post or its metadata.
            try (Cursor c = db.rawQuery("SELECT post_id,source,threshold_ms FROM history_events " +
                    "WHERE account_id=? AND event_type='FEED_VISIBLE' AND event_key=?",
                    new String[]{libraryId,exposureId})) {
                if (c.moveToFirst() && (!id.equals(c.getString(0)) ||
                        !source.equals(c.getString(1)) || c.getLong(2) != thresholdMs)) return false;
            }
            insertPostIfMissing(db, libraryId, id, author, text, publishedAtMs, eventAtMs);
            updateKnownMetadata(db, libraryId, id, author, text, publishedAtMs);
            ContentValues event = new ContentValues();
            event.put("account_id", libraryId);
            event.put("post_id", id);
            event.put("event_type", "FEED_VISIBLE");
            event.put("event_at_ms", eventAtMs);
            event.put("source", source);
            event.put("event_key", exposureId);
            event.put("duration_ms", durationMs);
            event.put("threshold_ms", thresholdMs);
            event.put("duration_quality", durationMs >= 600_000L ?
                "CAPPED_LOWER_BOUND" : "SAMPLED_LOWER_BOUND");
            boolean inserted = db.insertWithOnConflict("history_events", null, event,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
            db.execSQL("UPDATE history_events SET duration_ms=MAX(duration_ms,?), " +
                "duration_quality=CASE WHEN MAX(duration_ms,?)>=600000 THEN 'CAPPED_LOWER_BOUND' " +
                "ELSE 'SAMPLED_LOWER_BOUND' END WHERE " +
                "account_id=? AND event_type='FEED_VISIBLE' AND event_key=?",
                new Object[]{durationMs,durationMs,libraryId,exposureId});
            db.execSQL("UPDATE posts SET last_seen_at_ms=MAX(last_seen_at_ms,?), " +
                "last_feed_seen_at_ms=MAX(COALESCE(last_feed_seen_at_ms,0),?) " +
                "WHERE account_id=? AND post_id=?", new Object[]{eventAtMs,eventAtMs,libraryId,id});
            db.setTransactionSuccessful();
            return inserted;
        } finally { db.endTransaction(); }
    }

    /** App-local favorite; no remote bookmark action and no synthetic history. */
    public boolean favorite(String libraryId, String postUrl, String author, String text,
                             Long publishedAtMs, long savedAtMs) {
        String id = PostUrls.extractPostId(postUrl);
        if (!valid(libraryId) || id == null || savedAtMs <= 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            insertPostIfMissing(db,libraryId,id,author,text,publishedAtMs,savedAtMs);
            updateKnownMetadata(db,libraryId,id,author,text,publishedAtMs);
            ContentValues favorite = new ContentValues();
            favorite.put("account_id", libraryId);
            favorite.put("post_id", id);
            favorite.put("favorited_at_ms", savedAtMs);
            boolean inserted = db.insertWithOnConflict("favorites", null, favorite,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
            db.setTransactionSuccessful();
            return inserted;
        } finally { db.endTransaction(); }
    }

    public boolean removeFavorite(String libraryId, String postId) {
        requireAccount(libraryId);
        return getWritableDatabase().delete("favorites", "account_id=? AND post_id=?",
            new String[]{libraryId,postId}) > 0;
    }

    /** Comma-delimited UI input becomes exact, bounded tags in a separate table. */
    public void putFavoriteDetails(String libraryId, String postId, String note, String tags) {
        requireAccount(libraryId);
        String memo = note == null ? "" : note;
        if (!valid(postId) || memo.length() > 8000 || (tags != null && tags.length() > 1400)) {
            throw new IllegalArgumentException("Favorite details too long");
        }
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        if (tags != null && !tags.trim().isEmpty()) {
            for (String tag : tags.split(",")) {
                String value = tag.trim();
                if (value.length() > 64) throw new IllegalArgumentException("Tag too long");
                if (!value.isEmpty()) normalized.add(value);
            }
        }
        if (normalized.size() > 20) throw new IllegalArgumentException("Too many tags");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("note", memo);
            if (db.update("favorites",values,"account_id=? AND post_id=?",
                    new String[]{libraryId,postId}) != 1) {
                throw new IllegalArgumentException("Favorite required");
            }
            db.delete("post_tags","account_id=? AND post_id=?",new String[]{libraryId,postId});
            for (String tag : normalized) {
                ContentValues row = new ContentValues();
                row.put("account_id",libraryId);
                row.put("post_id",postId);
                row.put("tag",tag);
                db.insertOrThrow("post_tags",null,row);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Clear visit evidence while retaining posts, favorites, notes and categories. */
    public void clearHistory(String libraryId) {
        requireAccount(libraryId);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("history_events","account_id=?",new String[]{libraryId});
            db.execSQL("UPDATE posts SET opened_count=0,last_opened_at_ms=NULL," +
                "last_feed_seen_at_ms=NULL WHERE account_id=?",new Object[]{libraryId});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Preserved old libraries remain selectable; there is no automatic consolidation. */
    public Cursor libraries() {
        return getReadableDatabase().rawQuery("SELECT library_id,label,origin,storage_scope " +
            "FROM libraries ORDER BY CASE WHEN origin='APP' THEN 0 ELSE 1 END,label",null);
    }

    public void ensureLibrary(String libraryId, String label) {
        requireAccount(libraryId);
        ContentValues values = new ContentValues();
        values.put("library_id",libraryId);
        values.put("storage_scope",libraryId);
        values.put("label",valid(label) ? label.trim() : "Xs");
        values.put("origin","APP");
        values.put("created_at_ms",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("libraries",null,values,SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static boolean validEventKey(String key) {
        return valid(key) && key.length() <= 256;
    }

    /** A string-only call cannot prove a successful action or actor. Kept inactive for binary callers. */
    @Deprecated
    public boolean recordPersonalAction(String accountId, String postUrl,
                                        String actionType, String author, String text,
                                        Long publishedAtMs, Long actionAtMs,
                                        long observedAtMs) {
        return false;
    }

    /** Persist only a current native-owned admission. Call through the shared library write queue. */
    public boolean recordConfirmedOwnAction(OwnActionSession.Admission admitted,
                                             String author, String text, Long publishedAtMs) {
        if (admitted == null) return false;
        return admitted.persistIfCurrent(() -> persistConfirmedOwnAction(admitted,author,text,publishedAtMs));
    }

    private boolean persistConfirmedOwnAction(OwnActionSession.Admission admitted,
                                               String author,String text,Long publishedAtMs) {
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            if (!admitted.isCurrent()) return false;
            try (Cursor existing=db.rawQuery("SELECT 1 FROM own_action_events WHERE account_id=? AND event_key=?",
                    new String[]{admitted.libraryId,admitted.eventKey})) {
                if (existing.moveToFirst()) return false;
            }
            if (admitted.reversal) {
                try (Cursor target=db.rawQuery("SELECT post_id,action_type,actor_id,state,actor_provenance,action_at_ms,observed_at_ms FROM own_actions " +
                        "WHERE account_id=? AND action_key=?",new String[]{admitted.libraryId,admitted.actionKey})) {
                    if (!target.moveToFirst() || !admitted.postId.equals(target.getString(0)) ||
                            !"REPOST".equals(target.getString(1)) || !admitted.actorId.equals(target.getString(2)) ||
                            !"ACTIVE".equals(target.getString(3)) || !"NATIVE_FIXTURE".equals(target.getString(4)) ||
                            (!target.isNull(6) && admitted.observedAtMs < target.getLong(6)) ||
                            (!target.isNull(5) && admitted.actionAtMs != null && admitted.actionAtMs < target.getLong(5))) return false;
                }
                ContentValues state=new ContentValues();
                state.put("state","REVERSED");
                if (admitted.actionAtMs == null) state.putNull("state_at_ms");
                else state.put("state_at_ms",admitted.actionAtMs);
                db.update("own_actions",state,"account_id=? AND action_key=?",
                    new String[]{admitted.libraryId,admitted.actionKey});
            } else {
                // A repeated successful result must not become a second creation/active repost.
                try (Cursor prior=db.rawQuery("SELECT 1 FROM own_actions WHERE account_id=? AND post_id=? " +
                        "AND action_type=? AND actor_id=? AND source='native_fixture'" +
                        (admitted.type == OwnActionSession.Type.REPOST ? " AND state='ACTIVE'" : ""),
                        new String[]{admitted.libraryId,admitted.postId,admitted.type.name(),admitted.actorId})) {
                    if (prior.moveToFirst()) return false;
                }
                insertPostIfMissing(db,admitted.libraryId,admitted.postId,author,text,publishedAtMs,admitted.observedAtMs);
                updateKnownMetadata(db,admitted.libraryId,admitted.postId,author,text,publishedAtMs);
                ContentValues action=new ContentValues();
                action.put("account_id",admitted.libraryId); action.put("action_key",admitted.actionKey);
                action.put("post_id",admitted.postId); action.put("related_post_id",admitted.relatedPostId); action.put("action_type",admitted.type.name());
                action.put("actor_id",admitted.actorId); action.put("actor_provenance","NATIVE_FIXTURE");
                action.put("source","native_fixture"); action.put("observed_at_ms",admitted.observedAtMs);
                action.put("state","ACTIVE");
                if (admitted.actionAtMs == null) { action.putNull("action_at_ms"); action.putNull("state_at_ms"); }
                else { action.put("action_at_ms",admitted.actionAtMs); action.put("state_at_ms",admitted.actionAtMs); }
                if (db.insertWithOnConflict("own_actions",null,action,SQLiteDatabase.CONFLICT_IGNORE) == -1) return false;
            }
            ContentValues event=new ContentValues();
            event.put("account_id",admitted.libraryId); event.put("action_key",admitted.actionKey);
            event.put("event_key",admitted.eventKey); event.put("event_type",admitted.reversal ? "REVERSED" : "CONFIRMED");
            if (admitted.actionAtMs == null) event.putNull("event_at_ms"); else event.put("event_at_ms",admitted.actionAtMs);
            event.put("observed_at_ms",admitted.observedAtMs); event.put("source","native_fixture");
            db.insertOrThrow("own_action_events",null,event);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    /** Current entries and append-only provenance/reversal trail; never an identity authority. */
    public Cursor ownActions(String libraryId,String postId) {
        requireAccount(libraryId);
        return getReadableDatabase().rawQuery("SELECT a.action_key,a.post_id,a.action_type,a.actor_id," +
            "a.actor_provenance,a.source,a.action_at_ms,a.observed_at_ms,a.state,a.state_at_ms," +
            "e.event_type,e.event_at_ms,e.observed_at_ms,e.source,a.related_post_id FROM own_actions a " +
            "JOIN own_action_events e ON e.account_id=a.account_id AND e.action_key=a.action_key " +
            "WHERE a.account_id=? AND a.post_id=? ORDER BY e.observed_at_ms DESC,e.event_key DESC",
            new String[]{libraryId,postId});
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

    /**
     * Batch-imports own posts from a user-provided archive file. This updates
     * the local index but never creates synthetic OPEN_POST or FEED_VISIBLE
     * events. Duplicate archive actions retain their first provenance and import time.
     */
    public int importArchivedOwnPosts(String accountId,
                                      List<XsArchiveImporter.Record> rows,
                                      long importedAtMs) {
        requireAccount(accountId);
        if (rows == null || importedAtMs <= 0) {
            throw new IllegalArgumentException("Rows and import time required");
        }
        SQLiteDatabase db = getWritableDatabase();
        int inserted = 0;
        db.beginTransaction();
        try {
            for (XsArchiveImporter.Record row : rows) {
                if (row == null || row.postId == null ||
                    !row.postId.matches("[0-9]{1,20}")) continue;
                insertPostIfMissing(db, accountId, row.postId, null,
                                    row.body, row.publishedAtMs, importedAtMs);
                updateKnownMetadata(db, accountId, row.postId, null,
                                    row.body, row.publishedAtMs);
                db.execSQL("UPDATE posts SET last_seen_at_ms=MAX(last_seen_at_ms, ?) " +
                           "WHERE account_id=? AND post_id=?",
                           new Object[]{importedAtMs, accountId, row.postId});
                // Ambiguous exported text is searchable but not invented own activity.
                if (!validOwnAction(row.actionType)) continue;
                ContentValues action = new ContentValues();
                action.put("account_id", accountId);
                action.put("post_id", row.postId);
                action.put("action_type", row.actionType);
                if (row.actionAtMs == null) action.putNull("action_at_ms");
                else action.put("action_at_ms", row.actionAtMs);
                action.put("source", "local_x_archive");
                db.insertWithOnConflict("user_actions", null, action, SQLiteDatabase.CONFLICT_IGNORE);
                // v3 imports migrated with legacy IDs are already the same archive statement.
                boolean exists;
                try (Cursor old=db.rawQuery("SELECT 1 FROM own_actions WHERE account_id=? AND post_id=? " +
                        "AND action_type=? AND source='local_x_archive' AND action_at_ms" +
                        (row.actionAtMs == null ? " IS NULL" : "=?"),
                        row.actionAtMs == null ? new String[]{accountId,row.postId,row.actionType} :
                            new String[]{accountId,row.postId,row.actionType,Long.toString(row.actionAtMs)})) {
                    exists=old.moveToFirst();
                }
                if (!exists) {
                    String key="archive:" + row.postId + ":" + row.actionType + ":" + row.actionAtMs;
                    ContentValues own=new ContentValues();
                    own.put("account_id",accountId); own.put("action_key",key); own.put("post_id",row.postId);
                    own.put("action_type",row.actionType); own.put("related_post_id",row.relatedPostId); own.putNull("actor_id");
                    own.put("actor_provenance","USER_CONFIRMED_ARCHIVE"); own.put("source","local_x_archive");
                    if (row.actionAtMs == null) own.putNull("action_at_ms"); else own.put("action_at_ms",row.actionAtMs);
                    own.put("observed_at_ms",importedAtMs); own.put("state","UNKNOWN"); own.putNull("state_at_ms");
                    db.insertOrThrow("own_actions",null,own);
                    ContentValues event=new ContentValues();
                    event.put("account_id",accountId); event.put("action_key",key); event.put("event_key",key);
                    event.put("event_type","ARCHIVE_IMPORTED");
                    if (row.actionAtMs == null) event.putNull("event_at_ms"); else event.put("event_at_ms",row.actionAtMs);
                    event.put("observed_at_ms",importedAtMs); event.put("source","local_x_archive");
                    db.insertOrThrow("own_action_events",null,event);
                    inserted++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return inserted;
    }

    /**
     * Explicit local bookmark: not a post open, feed impression or X-server
     * bookmark. Repeating the same save retains the first save time.
     */
    public boolean saveManualLink(String localScope, String postUrl, long savedAtMs) {
        if (localScope == null || !localScope.startsWith("local:") ||
            savedAtMs <= 0) return false;
        String id = PostUrls.extractPostId(postUrl);
        if (id == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            insertPostIfMissing(db, localScope, id, null, null, null, savedAtMs);
            try (Cursor found = db.rawQuery(
                    "SELECT 1 FROM user_actions WHERE account_id=? AND post_id=? " +
                    "AND action_type='LOCAL_SAVE' LIMIT 1", new String[]{localScope,id})) {
                if (found.moveToFirst()) {
                    db.setTransactionSuccessful();
                    return false;
                }
            }
            ContentValues action = new ContentValues();
            action.put("account_id", localScope);
            action.put("post_id", id);
            action.put("action_type", "LOCAL_SAVE");
            action.put("action_at_ms", savedAtMs);
            action.put("source", "native_user_save");
            db.insertOrThrow("user_actions", null, action);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    private static boolean validOwnAction(String type) {
        return "ORIGINAL".equals(type) || "REPLY".equals(type) ||
               "QUOTE".equals(type) || "REPOST".equals(type);
    }

    private static void insertPostIfMissing(SQLiteDatabase db, String accountId, String id,
                                            String author, String text, Long publishedAtMs,
                                            long observedAtMs) {
        ContentValues library = new ContentValues();
        library.put("library_id", accountId);
        library.put("storage_scope", accountId);
        library.put("label", accountId);
        library.put("origin", accountId.startsWith("local:") ? "APP" : "LEGACY");
        db.insertWithOnConflict("libraries", null, library, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues post = new ContentValues();
        post.put("account_id", accountId);
        post.put("post_id", id);
        if (author != null) post.put("author_handle", author);
        if (text != null) post.put("body", text);
        post.put("snapshot_state", text == null || text.isEmpty() ? "LINK_ONLY" : "VISIBLE_TEXT");
        if (publishedAtMs != null) post.put("published_at_ms", publishedAtMs);
        post.put("first_seen_at_ms", observedAtMs);
        post.put("last_seen_at_ms", observedAtMs);
        db.insertWithOnConflict("posts", null, post, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void updateKnownMetadata(SQLiteDatabase db, String accountId, String id,
                                            String author, String text, Long publishedAtMs) {
        ContentValues values = new ContentValues();
        if (author != null && !author.isEmpty()) values.put("author_handle", author);
        if (publishedAtMs != null) values.put("published_at_ms", publishedAtMs);
        if (values.size() != 0) {
            db.update("posts", values, "account_id=? AND post_id=?",
                      new String[]{accountId, id});
        }
        // A later card/partial render must not truncate a longer stored snapshot.
        // Both lengths are measured by SQLite (Unicode code points, not UTF-16 units).
        // This is a conservative retention rule, not proof of snapshot completeness.
        if (text != null && !text.isEmpty()) {
            db.execSQL("UPDATE posts SET body=?,snapshot_state='VISIBLE_TEXT' WHERE account_id=? AND post_id=? " +
                       "AND (body IS NULL OR length(?) >= length(body))",
                       new Object[]{text, accountId, id, text});
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
            "p.author_handle, p.body, p.published_at_ms, e.source, " +
            "e.duration_ms,e.threshold_ms,e.event_key,e.duration_quality " +
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
