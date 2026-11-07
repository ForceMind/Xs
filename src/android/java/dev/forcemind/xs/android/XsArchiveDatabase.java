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
import dev.forcemind.xs.core.SnapshotPolicy;
import java.util.UUID;

import java.util.ArrayList;
import java.util.List;

/**
 * Android SQLite archive stored in the containing application's private storage.
 * This is source code for an Android library, not a hook or patched X APK.
 */
public final class XsArchiveDatabase extends SQLiteOpenHelper {
    private final Context applicationContext;
    public XsArchiveDatabase(Context context) {
        super(context.getApplicationContext(), "xs-local-history.db",
              null, ArchiveSchema.VERSION);
        applicationContext=context.getApplicationContext();
        setWriteAheadLoggingEnabled(true);
    }

    Context applicationContext() { return applicationContext; }

    /** Read only after beginTransaction acquired the SQLite writer lock, beside revocation checks. */
    private boolean acceptsHistoryTime(String library,long eventAtMs) {
        int days=XsRetentionSettings.getDays(this,library);
        return days==0 || eventAtMs>=XsRetentionSettings.cutoffMs(days,System.currentTimeMillis());
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
        if (version == 4 && newVersion >= 5) {
            for (String sql : ArchiveSchema.MIGRATE_4_TO_5) db.execSQL(sql);
            version = 5;
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
            if(XsLibraryWrites.isDeleted(accountId))return false;
            if(!acceptsHistoryTime(accountId,openedAtMs))return false;
            insertPostIfMissing(db, accountId, id, author, text, publishedAtMs, openedAtMs);
            db.execSQL(
                "UPDATE posts SET opened_count=MIN(opened_count+1,2147483647), " +
                "last_seen_at_ms=MAX(last_seen_at_ms, ?), " +
                "last_opened_at_ms=MAX(COALESCE(last_opened_at_ms, 0), ?) " +
                "WHERE account_id=? AND post_id=?",
                new Object[]{openedAtMs, openedAtMs, accountId, id});
            updateKnownMetadata(db, accountId, id, author, text, publishedAtMs, openedAtMs, SnapshotPolicy.Source.LEGACY_API, SnapshotPolicy.Quality.PARTIAL);
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
            if(XsLibraryWrites.isDeleted(libraryId))return false;
            if(!acceptsHistoryTime(libraryId,openedAtMs))return false;
            try (Cursor existing = db.rawQuery("SELECT post_id FROM history_events WHERE " +
                    "account_id=? AND event_type='OPEN_POST' AND event_key=?",
                    new String[]{libraryId,navigationId})) {
                if (existing.moveToFirst() && !id.equals(existing.getString(0))) return false;
            }
            insertPostIfMissing(db, libraryId, id, author, text, publishedAtMs, openedAtMs);
            updateKnownMetadata(db, libraryId, id, author, text, publishedAtMs, openedAtMs, SnapshotPolicy.Source.POST_DETAIL, SnapshotPolicy.Quality.PARTIAL);
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
        return recordSnapshot(libraryId,postUrl,author,text,publishedAtMs,System.currentTimeMillis(),
            SnapshotPolicy.Source.LEGACY_API,SnapshotPolicy.Quality.PARTIAL);
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
            if(XsLibraryWrites.isDeleted(libraryId))return false;
            if(!acceptsHistoryTime(libraryId,eventAtMs))return false;
            // Reject collision/replay before touching another post or its metadata.
            try (Cursor c = db.rawQuery("SELECT post_id,source,threshold_ms FROM history_events " +
                    "WHERE account_id=? AND event_type='FEED_VISIBLE' AND event_key=?",
                    new String[]{libraryId,exposureId})) {
                if (c.moveToFirst() && (!id.equals(c.getString(0)) ||
                        !source.equals(c.getString(1)) || c.getLong(2) != thresholdMs)) return false;
            }
            insertPostIfMissing(db, libraryId, id, author, text, publishedAtMs, eventAtMs);
            updateKnownMetadata(db, libraryId, id, author, text, publishedAtMs, eventAtMs, SnapshotPolicy.Source.FEED_VISIBLE, SnapshotPolicy.Quality.PARTIAL);
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
            if(XsLibraryWrites.isDeleted(libraryId))return false;
            insertPostIfMissing(db,libraryId,id,author,text,publishedAtMs,savedAtMs);
            updateKnownMetadata(db,libraryId,id,author,text,publishedAtMs,savedAtMs,SnapshotPolicy.Source.FAVORITE,SnapshotPolicy.Quality.PARTIAL);
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
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{if(XsLibraryWrites.isDeleted(libraryId))return false;boolean removed=db.delete("favorites","account_id=? AND post_id=?",new String[]{libraryId,postId})>0;db.setTransactionSuccessful();return removed;}
        finally{db.endTransaction();}
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
            if(XsLibraryWrites.isDeleted(libraryId))throw new IllegalStateException("Library deleted");
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
            if(XsLibraryWrites.isDeleted(libraryId))throw new IllegalStateException("Library deleted");
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
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{if(XsLibraryWrites.isDeleted(libraryId))throw new IllegalStateException("Library deleted");db.insertWithOnConflict("libraries",null,values,SQLiteDatabase.CONFLICT_IGNORE);db.setTransactionSuccessful();}
        finally{db.endTransaction();}
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
            if(XsLibraryWrites.isDeleted(admitted.libraryId))return false;
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
                updateKnownMetadata(db,admitted.libraryId,admitted.postId,author,text,publishedAtMs,admitted.observedAtMs,SnapshotPolicy.Source.OWN_ACTION,SnapshotPolicy.Quality.PARTIAL);
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
            if(XsLibraryWrites.isDeleted(accountId))return false;
            if(!acceptsHistoryTime(accountId,eventAtMs))return false;
            insertPostIfMissing(db, accountId, id, author, text, publishedAtMs, eventAtMs);
            db.execSQL(
                "UPDATE posts SET last_seen_at_ms=MAX(last_seen_at_ms, ?), " +
                "last_feed_seen_at_ms=MAX(COALESCE(last_feed_seen_at_ms, 0), ?) " +
                "WHERE account_id=? AND post_id=?",
                new Object[]{eventAtMs, eventAtMs, accountId, id});
            updateKnownMetadata(db, accountId, id, author, text, publishedAtMs, eventAtMs, SnapshotPolicy.Source.LEGACY_API, SnapshotPolicy.Quality.PARTIAL);
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

    /** Estimate only: execution uses its own wall-clock cutoff. Does not change any data. */
    public long retentionPreview(String libraryId,int days,long nowMs) {
        requireAccount(libraryId);long cutoff=XsRetentionSettings.cutoffMs(days,nowMs);
        if(days==0)return 0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM history_events WHERE account_id=? AND event_at_ms<?",
                new String[]{libraryId,Long.toString(cutoff)})){c.moveToFirst();return c.getLong(0);}
    }

    /**
     * Shared-writer operation. Only event details expire; lifetime post summaries survive.
     * event_at_ms is native open/qualification time, never a post's publication time.
     * Legacy migrations can have an OPEN event without a counter: reconcile its real
     * evidence before deletion so the post cannot become a false "never opened" hit.
     */
    public long pruneHistory(String libraryId,int days,long nowMs) {
        requireAccount(libraryId);long cutoff=XsRetentionSettings.cutoffMs(days,nowMs);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            if(XsLibraryWrites.isDeleted(libraryId))throw new IllegalStateException("Library deleted");
            if(days==0){db.setTransactionSuccessful();return 0;}
            String events=" FROM history_events e WHERE e.account_id=posts.account_id AND e.post_id=posts.post_id";
            db.execSQL("UPDATE posts SET opened_count=MAX(opened_count,(SELECT COUNT(*)"+events+" AND e.event_type='OPEN_POST')),"+
                "last_opened_at_ms=NULLIF(MAX(COALESCE(last_opened_at_ms,0),COALESCE((SELECT MAX(e.event_at_ms)"+events+" AND e.event_type='OPEN_POST'),0)),0),"+
                "last_feed_seen_at_ms=NULLIF(MAX(COALESCE(last_feed_seen_at_ms,0),COALESCE((SELECT MAX(e.event_at_ms)"+events+" AND e.event_type='FEED_VISIBLE'),0)),0) "+
                "WHERE account_id=? AND post_id IN (SELECT post_id FROM history_events WHERE account_id=? AND event_at_ms<?)",
                new Object[]{libraryId,libraryId,cutoff});
            long deleted=db.delete("history_events","account_id=? AND event_at_ms<?",new String[]{libraryId,Long.toString(cutoff)});
            db.setTransactionSuccessful();return deleted;
        } finally {db.endTransaction();}
    }

    /** Counts of stored evidence, not proof of reading. Dwell sums may overlap across cards. */
    public static final class HistoryStats {
        public final long postCount,snapshotCount,favoriteCount,openEventCount,feedEventCount;
        public final long distinctOpenPosts,distinctFeedPosts,neverOpenedPostCount;
        public final long lifetimeOpenedCount,everOpenedPostCount;
        public final long totalDwellLowerBoundMs,maxDwellLowerBoundMs,unknownDwellCount,cappedDwellCount;
        private HistoryStats(long[] p,long[] e,long snapshots,long favorites) {
            postCount=p[0];lifetimeOpenedCount=p[1];everOpenedPostCount=p[2];neverOpenedPostCount=p[3];
            snapshotCount=snapshots;favoriteCount=favorites;openEventCount=e[0];feedEventCount=e[1];
            distinctOpenPosts=e[2];distinctFeedPosts=e[3];totalDwellLowerBoundMs=e[4];
            maxDwellLowerBoundMs=e[5];unknownDwellCount=e[6];cappedDwellCount=e[7];
        }
    }

    /** Read on a worker. All fields come from one consistent library-scoped SQLite snapshot. */
    public HistoryStats historyStats(String libraryId) {
        requireAccount(libraryId);SQLiteDatabase db=getReadableDatabase();db.beginTransactionNonExclusive();
        try {
            String opened="(p.opened_count>0 OR EXISTS (SELECT 1 FROM history_events e WHERE e.account_id=p.account_id AND e.post_id=p.post_id AND e.event_type='OPEN_POST'))";
            // Legacy imports can have real OPEN evidence without a populated counter.
            // Both are lower bounds on the same opens: use the larger per post, never add them.
            String knownOpens="MAX(p.opened_count,(SELECT COUNT(*) FROM history_events e WHERE e.account_id=p.account_id AND e.post_id=p.post_id AND e.event_type='OPEN_POST'))";
            long[] posts=statsRow(db,"SELECT COUNT(*),COALESCE(SUM("+knownOpens+"),0),COALESCE(SUM(CASE WHEN "+opened+" THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN NOT "+opened+" THEN 1 ELSE 0 END),0) FROM posts p WHERE p.account_id=?",libraryId,4);
            long[] events=statsRow(db,"SELECT COALESCE(SUM(CASE WHEN event_type='OPEN_POST' THEN 1 ELSE 0 END),0),"+
                "COALESCE(SUM(CASE WHEN event_type='FEED_VISIBLE' THEN 1 ELSE 0 END),0),"+
                "COUNT(DISTINCT CASE WHEN event_type='OPEN_POST' THEN post_id END),"+
                "COUNT(DISTINCT CASE WHEN event_type='FEED_VISIBLE' THEN post_id END),"+
                "COALESCE(SUM(CASE WHEN event_type='FEED_VISIBLE' THEN duration_ms END),0),"+
                "COALESCE(MAX(CASE WHEN event_type='FEED_VISIBLE' THEN duration_ms END),0),"+
                "COALESCE(SUM(CASE WHEN event_type='FEED_VISIBLE' AND (duration_ms IS NULL OR duration_quality IS NULL) THEN 1 ELSE 0 END),0),"+
                "COALESCE(SUM(CASE WHEN event_type='FEED_VISIBLE' AND duration_quality='CAPPED_LOWER_BOUND' THEN 1 ELSE 0 END),0) "+
                "FROM history_events WHERE account_id=?",libraryId,8);
            long snapshots=statsRow(db,"SELECT COUNT(*) FROM snapshot_versions WHERE account_id=?",libraryId,1)[0];
            long favorites=statsRow(db,"SELECT COUNT(*) FROM favorites WHERE account_id=?",libraryId,1)[0];
            db.setTransactionSuccessful();return new HistoryStats(posts,events,snapshots,favorites);
        } finally {db.endTransaction();}
    }
    private static long[] statsRow(SQLiteDatabase db,String sql,String library,int count) {
        long[] row=new long[count];try(Cursor c=db.rawQuery(sql,new String[]{library})){c.moveToFirst();for(int i=0;i<count;i++)row[i]=c.getLong(i);}return row;
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
            if(XsLibraryWrites.isDeleted(accountId))return 0;
            for (XsArchiveImporter.Record row : rows) {
                if (row == null || row.postId == null ||
                    !row.postId.matches("[0-9]{1,20}")) continue;
                insertPostIfMissing(db, accountId, row.postId, null,
                                    row.body, row.publishedAtMs, importedAtMs);
                updateKnownMetadata(db, accountId, row.postId, null,
                                    row.body, row.publishedAtMs, importedAtMs, SnapshotPolicy.Source.ARCHIVE,
                                    row.snapshotComplete ? SnapshotPolicy.Quality.COMPLETE : SnapshotPolicy.Quality.PARTIAL);
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
            if(XsLibraryWrites.isDeleted(localScope))return false;
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
        post.put("snapshot_state", "LINK_ONLY");
        if (publishedAtMs != null) post.put("published_at_ms", publishedAtMs);
        post.put("first_seen_at_ms", observedAtMs);
        post.put("last_seen_at_ms", observedAtMs);
        db.insertWithOnConflict("posts", null, post, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void updateKnownMetadata(SQLiteDatabase db, String libraryId, String id,
            String author,String text,Long publishedAtMs,long capturedAtMs,
            SnapshotPolicy.Source source,SnapshotPolicy.Quality quality) {
        if(text==null||text.isEmpty())return;
        try {
            persistSnapshot(db,libraryId,id,new SnapshotPolicy.Version(text,author,publishedAtMs,capturedAtMs,source,quality));
        }catch(IllegalArgumentException invalid){/* Keep the link/event even when visible text is invalid. */}
    }

    /** Native accepted visible text only; missing posts are never resurrected by enrichment. */
    public boolean recordSnapshot(String libraryId,String postUrl,String author,String text,
            Long publishedAtMs,long capturedAtMs,SnapshotPolicy.Source source,SnapshotPolicy.Quality quality) {
        String id=PostUrls.extractPostId(postUrl);
        if(!valid(libraryId)||id==null||capturedAtMs<=0||quality==SnapshotPolicy.Quality.LEGACY_UNKNOWN||
                source==SnapshotPolicy.Source.MIGRATION)return false;
        final SnapshotPolicy.Version candidate;
        try { candidate=new SnapshotPolicy.Version(text,author,publishedAtMs,capturedAtMs,source,quality); }
        catch(IllegalArgumentException invalid){return false;}
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {if(XsLibraryWrites.isDeleted(libraryId))return false;boolean result=persistSnapshot(db,libraryId,id,candidate);db.setTransactionSuccessful();return result;}
        finally{db.endTransaction();}
    }
    private static boolean persistSnapshot(SQLiteDatabase db,String libraryId,String id,SnapshotPolicy.Version candidate) {
        String currentKey;
        try(Cursor post=db.rawQuery("SELECT current_snapshot_key FROM posts WHERE account_id=? AND post_id=?",new String[]{libraryId,id})) {
            if(!post.moveToFirst())return false;currentKey=post.getString(0);
        }
        SnapshotPolicy.Version current=null,last=null;
        if(currentKey!=null)try(Cursor version=db.rawQuery("SELECT body,author_handle,published_at_ms,captured_at_ms,source,quality " +
                "FROM snapshot_versions WHERE account_id=? AND post_id=? AND snapshot_key=?",new String[]{libraryId,id,currentKey})) {
            if(version.moveToFirst())current=readVersion(version);
        }
        try(Cursor version=db.rawQuery("SELECT body,author_handle,published_at_ms,captured_at_ms,source,quality " +
                "FROM snapshot_versions WHERE account_id=? AND post_id=? ORDER BY rowid DESC LIMIT 1",new String[]{libraryId,id})) {
            if(version.moveToFirst())last=readVersion(version);
        }
        SnapshotPolicy.Decision decision=SnapshotPolicy.decide(current,last,candidate);
        if(!decision.record) {
            if(current!=null&&current.body.equals(candidate.body)&&
                    (current.capturedAtMs==null||(candidate.capturedAtMs!=null&&candidate.capturedAtMs>=current.capturedAtMs)))
                return enrichMissingSnapshotMetadata(db,libraryId,id,currentKey,candidate);
            return false;
        }
        String key=UUID.randomUUID().toString();ContentValues values=new ContentValues();
        values.put("account_id",libraryId);values.put("post_id",id);values.put("snapshot_key",key);
        values.put("author_handle",candidate.author);values.put("body",candidate.body);
        if(candidate.publishedAtMs==null)values.putNull("published_at_ms");else values.put("published_at_ms",candidate.publishedAtMs);
        if(candidate.capturedAtMs==null)values.putNull("captured_at_ms");else values.put("captured_at_ms",candidate.capturedAtMs);
        values.put("source",candidate.source.value);values.put("quality",candidate.quality.name());
        values.put("content_hash",SnapshotPolicy.hash(candidate.body));db.insertOrThrow("snapshot_versions",null,values);
        if(decision.selectCurrent) {
            ContentValues selected=new ContentValues();selected.put("body",candidate.body);selected.put("current_snapshot_key",key);
            selected.put("snapshot_state","VISIBLE_TEXT");
            if(candidate.author!=null&&!candidate.author.isEmpty())selected.put("author_handle",candidate.author);
            if(candidate.publishedAtMs!=null)selected.put("published_at_ms",candidate.publishedAtMs);
            db.update("posts",selected,"account_id=? AND post_id=?",new String[]{libraryId,id});
        }
        return true;
    }
    /** Supplement stable missing metadata without manufacturing another text change/capture time. */
    private static boolean enrichMissingSnapshotMetadata(SQLiteDatabase db,String libraryId,String id,String key,SnapshotPolicy.Version candidate) {
        int changed=0;
        for(String table:new String[]{"posts","snapshot_versions"}) {
            String where="account_id=? AND post_id=?"+(table.equals("snapshot_versions")?" AND snapshot_key=?":"");
            String[] args=table.equals("snapshot_versions")?new String[]{libraryId,id,key}:new String[]{libraryId,id};
            if(candidate.author!=null&&!candidate.author.isEmpty()) {
                ContentValues author=new ContentValues();author.put("author_handle",candidate.author);
                changed+=db.update(table,author,where+" AND (author_handle IS NULL OR length(author_handle)=0)",args);
            }
            if(candidate.publishedAtMs!=null) {
                ContentValues time=new ContentValues();time.put("published_at_ms",candidate.publishedAtMs);
                changed+=db.update(table,time,where+" AND published_at_ms IS NULL",args);
            }
        }
        return changed>0;
    }
    private static SnapshotPolicy.Version readVersion(Cursor version) {
        return new SnapshotPolicy.Version(version.getString(0),version.getString(1),version.isNull(2)?null:version.getLong(2),
            version.isNull(3)?null:version.getLong(3),SnapshotPolicy.Source.parse(version.getString(4)),
            SnapshotPolicy.Quality.valueOf(version.getString(5)));
    }
    public Cursor listSnapshots(String libraryId,String postId){return searchSnapshots(libraryId,postId,null,200,0);}
    public Cursor listSnapshots(String libraryId,String postId,int limit,int offset){return searchSnapshots(libraryId,postId,null,limit,offset);}
    public Cursor searchSnapshots(String libraryId,String postId,String keyword,int limit,int offset) {
        ArchiveSearch.Query query=ArchiveSearch.buildSnapshots(libraryId,postId,keyword,limit,offset);
        return getReadableDatabase().rawQuery(query.sql,query.args);
    }
    public Cursor getSnapshot(String libraryId,String postId,String snapshotKey) {
        requireAccount(libraryId);
        return getReadableDatabase().rawQuery(ArchiveSearch.SNAPSHOT_COLUMNS + " WHERE v.account_id=? AND v.post_id=? AND v.snapshot_key=?",
            new String[]{libraryId,postId,snapshotKey});
    }
    /** Caller must first invalidate this scope and purge its independent media through native controls. */
    public void deleteLibraryScope(String libraryId) {
        requireAccount(libraryId);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{clearAccount(libraryId);db.delete("libraries","storage_scope=?",new String[]{libraryId});db.setTransactionSuccessful();}
        finally{db.endTransaction();}
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
        db.beginTransaction();
        try {
        if(XsLibraryWrites.isDeleted(accountId))throw new IllegalStateException("Library deleted");
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
        db.setTransactionSuccessful();
        }finally{db.endTransaction();}
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
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{if(XsLibraryWrites.isDeleted(accountId))return false;boolean inserted=db.insertWithOnConflict("post_categories",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1;db.setTransactionSuccessful();return inserted;}
        finally{db.endTransaction();}
    }

    public void deleteCategory(String accountId, String categoryId) {
        requireAccount(accountId);
        if (!valid(categoryId)) throw new IllegalArgumentException("Folder ID is required");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{if(XsLibraryWrites.isDeleted(accountId))throw new IllegalStateException("Library deleted");db.delete("categories","account_id=? AND category_id=?",new String[]{accountId,categoryId});db.setTransactionSuccessful();}
        finally{db.endTransaction();}
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
