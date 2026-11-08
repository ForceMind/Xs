package dev.forcemind.xs.core;

/** Independent disposable metadata/FTS schema. Never applied to the long-term Archive database. */
public final class TimelineBufferSchema {
    private TimelineBufferSchema(){}
    public static final int VERSION=1;
    public static final String[] CREATE={
        "CREATE TABLE timeline_state(id INTEGER PRIMARY KEY CHECK(id=1),enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0,1)),"+
            "ttl_hours INTEGER NOT NULL DEFAULT 48 CHECK(ttl_hours BETWEEN 1 AND 168),budget_mib INTEGER NOT NULL DEFAULT 100 CHECK(budget_mib BETWEEN 25 AND 500),"+
            "policy_version INTEGER NOT NULL DEFAULT 0,consent_generation INTEGER NOT NULL DEFAULT 0,logical_now_ms INTEGER NOT NULL DEFAULT 0,"+
            "wall_ms INTEGER NOT NULL DEFAULT 0,elapsed_ms INTEGER NOT NULL DEFAULT 0,boot_id TEXT NOT NULL DEFAULT '',clock_paused INTEGER NOT NULL DEFAULT 0 CHECK(clock_paused IN (0,1)),"+
            "pause_reason TEXT NOT NULL DEFAULT '',dropped_count INTEGER NOT NULL DEFAULT 0)",
        "INSERT INTO timeline_state(id) VALUES(1)",
        "CREATE TABLE timeline_scopes(library_id TEXT PRIMARY KEY NOT NULL,generation INTEGER NOT NULL DEFAULT 0,pending_clear INTEGER NOT NULL DEFAULT 0 CHECK(pending_clear IN (0,1)))",
        "CREATE TABLE timeline_batches(library_id TEXT NOT NULL,batch_id TEXT NOT NULL,created_at_ms INTEGER NOT NULL,reason TEXT NOT NULL,source TEXT NOT NULL,"+
            "PRIMARY KEY(library_id,batch_id),FOREIGN KEY(library_id) REFERENCES timeline_scopes(library_id) ON DELETE CASCADE)",
        "CREATE INDEX timeline_batches_time ON timeline_batches(created_at_ms,library_id,batch_id)",
        "CREATE TABLE timeline_snapshots(id INTEGER PRIMARY KEY,library_id TEXT NOT NULL,post_id TEXT NOT NULL,content_hash TEXT NOT NULL,"+
            "author_handle TEXT,body TEXT NOT NULL,published_at_ms INTEGER,captured_at_ms INTEGER NOT NULL,quality TEXT NOT NULL CHECK(quality IN ('LINK_ONLY','PARTIAL')),"+
            "provenance TEXT NOT NULL,UNIQUE(library_id,post_id,content_hash),UNIQUE(id,library_id,post_id),"+
            "FOREIGN KEY(library_id) REFERENCES timeline_scopes(library_id) ON DELETE CASCADE)",
        "CREATE TABLE timeline_appearances(id INTEGER PRIMARY KEY AUTOINCREMENT,library_id TEXT NOT NULL,batch_id TEXT NOT NULL,post_id TEXT NOT NULL,snapshot_id INTEGER NOT NULL,"+
            "ordinal INTEGER NOT NULL CHECK(ordinal>=0),first_at_ms INTEGER NOT NULL,last_at_ms INTEGER NOT NULL,expires_at_ms INTEGER NOT NULL,"+
            "promotion_id TEXT UNIQUE NOT NULL,saved_at_ms INTEGER,favorited_at_ms INTEGER,UNIQUE(library_id,batch_id,post_id),"+
            "FOREIGN KEY(library_id,batch_id) REFERENCES timeline_batches(library_id,batch_id) ON DELETE CASCADE,"+
            "FOREIGN KEY(snapshot_id,library_id,post_id) REFERENCES timeline_snapshots(id,library_id,post_id))",
        "CREATE INDEX timeline_appearances_expiry ON timeline_appearances(expires_at_ms,id)",
        "CREATE INDEX timeline_appearances_scope_time ON timeline_appearances(library_id,first_at_ms DESC,id DESC)",
        "CREATE INDEX timeline_appearances_batch_order ON timeline_appearances(library_id,batch_id,ordinal,id)",
        "CREATE INDEX timeline_appearances_snapshot ON timeline_appearances(snapshot_id)",
        "CREATE TABLE timeline_seen(library_id TEXT NOT NULL,batch_id TEXT NOT NULL,post_id TEXT NOT NULL,first_at_ms INTEGER NOT NULL,expires_at_ms INTEGER NOT NULL,ordinal INTEGER NOT NULL,"+
            "PRIMARY KEY(library_id,batch_id,post_id),FOREIGN KEY(library_id,batch_id) REFERENCES timeline_batches(library_id,batch_id) ON DELETE CASCADE)",
        "CREATE INDEX timeline_seen_time ON timeline_seen(first_at_ms)",
        "CREATE VIRTUAL TABLE timeline_snapshots_fts USING fts4(library_id,post_id,author_handle,body,tokenize=unicode61)",
        "CREATE TRIGGER timeline_snapshots_ai AFTER INSERT ON timeline_snapshots BEGIN INSERT INTO timeline_snapshots_fts(rowid,library_id,post_id,author_handle,body) "+
            "VALUES(new.id,new.library_id,new.post_id,new.author_handle,new.body); END",
        "CREATE TRIGGER timeline_snapshots_ad AFTER DELETE ON timeline_snapshots BEGIN DELETE FROM timeline_snapshots_fts WHERE rowid=old.id; END",
        "CREATE TRIGGER timeline_snapshots_au AFTER UPDATE OF author_handle,body ON timeline_snapshots BEGIN DELETE FROM timeline_snapshots_fts WHERE rowid=old.id; "+
            "INSERT INTO timeline_snapshots_fts(rowid,library_id,post_id,author_handle,body) VALUES(new.id,new.library_id,new.post_id,new.author_handle,new.body); END"
    };
}
