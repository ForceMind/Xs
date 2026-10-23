package dev.forcemind.xs.core;

/**
 * App-private SQLite database v1. FTS4 is supported by the Android SQLite stack.
 * China-language substring fallback is provided by ArchiveSearch.
 */
public final class ArchiveSchema {
    public static final int VERSION = 1;

    public static final String[] CREATE = {
        "CREATE TABLE IF NOT EXISTS posts (" +
            "account_id TEXT NOT NULL, post_id TEXT NOT NULL, author_handle TEXT, " +
            "body TEXT, published_at_ms INTEGER, first_seen_at_ms INTEGER NOT NULL, " +
            "last_seen_at_ms INTEGER NOT NULL, opened_count INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(account_id, post_id))",
        "CREATE INDEX IF NOT EXISTS idx_posts_opened ON posts(account_id, last_seen_at_ms DESC)",
        "CREATE INDEX IF NOT EXISTS idx_posts_published ON posts(account_id, published_at_ms DESC)",
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

    private ArchiveSchema() {}
}
