package dev.forcemind.xs.core;

/**
 * App-private SQLite database v4. Legacy namespace keys remain unchanged. FTS4 is supported by the Android SQLite stack.
 * China-language substring fallback is provided by ArchiveSearch.
 */
public final class ArchiveSchema {
    public static final int VERSION = 4;

    public static final String[] CREATE_V2 = {
        "CREATE TABLE IF NOT EXISTS posts (" +
            "account_id TEXT NOT NULL, post_id TEXT NOT NULL, author_handle TEXT, " +
            "body TEXT, published_at_ms INTEGER, first_seen_at_ms INTEGER NOT NULL, " +
            "last_seen_at_ms INTEGER NOT NULL, last_opened_at_ms INTEGER, " +
            "last_feed_seen_at_ms INTEGER, opened_count INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(account_id, post_id))",
        "CREATE INDEX IF NOT EXISTS idx_posts_opened ON posts(account_id, last_seen_at_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_posts_published ON posts(account_id, published_at_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_posts_open_time ON posts(account_id, last_opened_at_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_posts_feed_time ON posts(account_id, last_feed_seen_at_ms DESC)",
        "CREATE TABLE IF NOT EXISTS history_events (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL, " +
            "post_id TEXT NOT NULL, event_type TEXT NOT NULL, event_at_ms INTEGER NOT NULL, " +
            "source TEXT NOT NULL, FOREIGN KEY(account_id, post_id) " +
            "REFERENCES posts(account_id, post_id) ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS idx_history_account_time ON " +
            "history_events(account_id, event_at_ms DESC, id DESC)",
        "CREATE TABLE IF NOT EXISTS user_actions (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL, " +
            "post_id TEXT NOT NULL, action_type TEXT NOT NULL, action_at_ms INTEGER, " +
            "source TEXT NOT NULL, FOREIGN KEY(account_id, post_id) " +
            "REFERENCES posts(account_id, post_id) ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS idx_actions_account_time ON " +
            "user_actions(account_id, action_at_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_actions_by_post ON " +
            "user_actions(account_id, post_id, action_type, action_at_ms DESC)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uniq_actions_known ON " +
            "user_actions(account_id, post_id, action_type, action_at_ms) " +
            "WHERE action_at_ms IS NOT NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS uniq_actions_unknown ON " +
            "user_actions(account_id, post_id, action_type) " +
            "WHERE action_at_ms IS NULL",
        "CREATE TABLE IF NOT EXISTS categories (" +
            "account_id TEXT NOT NULL, category_id TEXT NOT NULL, label TEXT NOT NULL, " +
            "PRIMARY KEY(account_id, category_id))",
        "CREATE TABLE IF NOT EXISTS post_categories (" +
            "account_id TEXT NOT NULL, post_id TEXT NOT NULL, category_id TEXT NOT NULL, " +
            "PRIMARY KEY(account_id, post_id, category_id), " +
            "FOREIGN KEY(account_id, post_id) REFERENCES posts(account_id, post_id) ON DELETE CASCADE, " +
            "FOREIGN KEY(account_id, category_id) REFERENCES categories(account_id, category_id) ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS idx_post_categories_filter ON " +
            "post_categories(account_id, category_id, post_id)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS posts_fts USING " +
            "fts4(account_id, post_id, author_handle, body, tokenize=unicode61)",
        "CREATE TRIGGER IF NOT EXISTS posts_ai AFTER INSERT ON posts BEGIN " +
            "INSERT INTO posts_fts(rowid, account_id, post_id, author_handle, body) " +
            "VALUES(new.rowid, new.account_id, new.post_id, new.author_handle, new.body); END",
        "CREATE TRIGGER IF NOT EXISTS posts_au AFTER UPDATE OF body, author_handle ON posts BEGIN " +
            "DELETE FROM posts_fts WHERE rowid=old.rowid; " +
            "INSERT INTO posts_fts(rowid, account_id, post_id, author_handle, body) " +
            "VALUES(new.rowid, new.account_id, new.post_id, new.author_handle, new.body); END",
        "CREATE TRIGGER IF NOT EXISTS posts_ad AFTER DELETE ON posts BEGIN " +
            "DELETE FROM posts_fts WHERE rowid=old.rowid; END"
    };

    /** Apply inside SQLiteOpenHelper's migration transaction. Never drop user history. */
    public static final String[] MIGRATE_1_TO_2 = {
        "ALTER TABLE posts ADD COLUMN last_opened_at_ms INTEGER",
        "ALTER TABLE posts ADD COLUMN last_feed_seen_at_ms INTEGER",
        "UPDATE posts SET last_opened_at_ms=(" +
            "SELECT MAX(e.event_at_ms) FROM history_events e " +
            "WHERE e.account_id=posts.account_id AND e.post_id=posts.post_id " +
            "AND e.event_type='OPEN_POST')",
        "CREATE INDEX IF NOT EXISTS idx_posts_open_time ON posts(account_id, last_opened_at_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_posts_feed_time ON posts(account_id, last_feed_seen_at_ms DESC)",
        "DELETE FROM user_actions WHERE id NOT IN (" +
            "SELECT MIN(id) FROM user_actions GROUP BY account_id, post_id, action_type, action_at_ms)",
        "CREATE INDEX IF NOT EXISTS idx_actions_by_post ON " +
            "user_actions(account_id, post_id, action_type, action_at_ms DESC)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uniq_actions_known ON " +
            "user_actions(account_id, post_id, action_type, action_at_ms) " +
            "WHERE action_at_ms IS NOT NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS uniq_actions_unknown ON " +
            "user_actions(account_id, post_id, action_type) " +
            "WHERE action_at_ms IS NULL"
    };

    /** v2 -> v3 is additive. No old scope, row ID, category or FTS row is rewritten. */
    public static final String[] MIGRATE_2_TO_3 = {
        "CREATE TABLE IF NOT EXISTS libraries (library_id TEXT PRIMARY KEY NOT NULL, " +
            "storage_scope TEXT UNIQUE NOT NULL, label TEXT NOT NULL, origin TEXT NOT NULL, " +
            "created_at_ms INTEGER)",
        "CREATE TABLE IF NOT EXISTS legacy_scope_mapping (legacy_scope TEXT PRIMARY KEY NOT NULL, " +
            "library_id TEXT NOT NULL UNIQUE, FOREIGN KEY(library_id) " +
            "REFERENCES libraries(library_id) ON DELETE CASCADE)",
        "INSERT OR IGNORE INTO libraries(library_id, storage_scope, label, origin) " +
            "SELECT account_id, account_id, account_id, 'LEGACY' FROM posts UNION " +
            "SELECT account_id, account_id, account_id, 'LEGACY' FROM categories UNION " +
            "SELECT account_id, account_id, account_id, 'LEGACY' FROM history_events UNION " +
            "SELECT account_id, account_id, account_id, 'LEGACY' FROM user_actions",
        "INSERT OR IGNORE INTO legacy_scope_mapping(legacy_scope, library_id) " +
            "SELECT library_id, library_id FROM libraries WHERE origin='LEGACY'",
        "ALTER TABLE posts ADD COLUMN snapshot_state TEXT NOT NULL DEFAULT 'LINK_ONLY'",
        "UPDATE posts SET snapshot_state='VISIBLE_TEXT' WHERE body IS NOT NULL AND length(body)>0",
        "ALTER TABLE history_events ADD COLUMN event_key TEXT",
        "ALTER TABLE history_events ADD COLUMN duration_ms INTEGER CHECK(duration_ms IS NULL OR duration_ms>=0)",
        "ALTER TABLE history_events ADD COLUMN threshold_ms INTEGER",
        "ALTER TABLE history_events ADD COLUMN duration_quality TEXT",
        "CREATE UNIQUE INDEX IF NOT EXISTS uniq_history_observation ON " +
            "history_events(account_id,event_type,event_key) WHERE event_key IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS idx_history_dwell ON " +
            "history_events(account_id,post_id,event_type,duration_ms)",
        "CREATE TABLE IF NOT EXISTS favorites (account_id TEXT NOT NULL, post_id TEXT NOT NULL, " +
            "favorited_at_ms INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', " +
            "PRIMARY KEY(account_id,post_id), FOREIGN KEY(account_id,post_id) " +
            "REFERENCES posts(account_id,post_id) ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS idx_favorites_time ON favorites(account_id,favorited_at_ms DESC)",
        "CREATE TABLE IF NOT EXISTS post_tags (account_id TEXT NOT NULL, post_id TEXT NOT NULL, " +
            "tag TEXT NOT NULL, PRIMARY KEY(account_id,post_id,tag), " +
            "FOREIGN KEY(account_id,post_id) REFERENCES favorites(account_id,post_id) ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS idx_tags_filter ON post_tags(account_id,tag,post_id)"
    };

    /** Preserve pre-v4 data without asserting an unknown actor or current platform state. */
    public static final String[] MIGRATE_3_TO_4 = {
        "CREATE TABLE IF NOT EXISTS own_actions (account_id TEXT NOT NULL, action_key TEXT NOT NULL, " +
            "post_id TEXT NOT NULL, related_post_id TEXT, action_type TEXT NOT NULL CHECK(action_type IN ('ORIGINAL','REPLY','QUOTE','REPOST')), " +
            "actor_id TEXT, actor_provenance TEXT NOT NULL, source TEXT NOT NULL, action_at_ms INTEGER, " +
            "observed_at_ms INTEGER, state TEXT NOT NULL CHECK(state IN ('ACTIVE','REVERSED','UNKNOWN')), " +
            "state_at_ms INTEGER, PRIMARY KEY(account_id,action_key), FOREIGN KEY(account_id,post_id) " +
            "REFERENCES posts(account_id,post_id) ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS idx_own_actions_post ON own_actions(account_id,post_id,action_type,action_at_ms)",
        "CREATE INDEX IF NOT EXISTS idx_own_actions_actor ON own_actions(account_id,actor_id,state)",
        "CREATE TABLE IF NOT EXISTS own_action_events (account_id TEXT NOT NULL, action_key TEXT NOT NULL, " +
            "event_key TEXT NOT NULL, event_type TEXT NOT NULL CHECK(event_type IN " +
            "('CONFIRMED','REVERSED','ARCHIVE_IMPORTED','LEGACY_RECORDED')), event_at_ms INTEGER, " +
            "observed_at_ms INTEGER, source TEXT NOT NULL, PRIMARY KEY(account_id,event_key), " +
            "FOREIGN KEY(account_id,action_key) REFERENCES own_actions(account_id,action_key) ON DELETE CASCADE)",
        "CREATE INDEX IF NOT EXISTS idx_own_events_action ON own_action_events(account_id,action_key,observed_at_ms)",
        "INSERT OR IGNORE INTO own_actions(account_id,action_key,post_id,action_type,actor_id,actor_provenance," +
            "source,action_at_ms,observed_at_ms,state,state_at_ms) SELECT a.account_id,'legacy:'||a.id," +
            "a.post_id,a.action_type,NULL,CASE WHEN a.source='local_x_archive' THEN 'USER_CONFIRMED_ARCHIVE' " +
            "ELSE 'LEGACY_UNVERIFIED' END,a.source,a.action_at_ms,NULL,'UNKNOWN',NULL " +
            "FROM user_actions a JOIN posts p ON p.account_id=a.account_id AND p.post_id=a.post_id " +
            "WHERE a.action_type IN ('ORIGINAL','REPLY','QUOTE','REPOST')",
        "INSERT OR IGNORE INTO own_action_events(account_id,action_key,event_key,event_type,event_at_ms," +
            "observed_at_ms,source) SELECT account_id,action_key,action_key,CASE WHEN source='local_x_archive' " +
            "THEN 'ARCHIVE_IMPORTED' ELSE 'LEGACY_RECORDED' END,action_at_ms,observed_at_ms,source FROM own_actions"
    };

    public static final String[] CREATE_V3 = concatenate(CREATE_V2, MIGRATE_2_TO_3);
    public static final String[] CREATE = concatenate(CREATE_V3, MIGRATE_3_TO_4);

    private static String[] concatenate(String[] first, String[] second) {
        String[] joined = new String[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private ArchiveSchema() {}
}
