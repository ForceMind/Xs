#!/usr/bin/env python3
"""Validate the exact Java ArchiveSchema DDL against real SQLite + FTS4."""
import base64
import sqlite3
import sys


def main() -> None:
    lines = [line.strip() for line in sys.stdin if line.strip()]
    ddl = [
        base64.b64decode(line, validate=True).decode("utf-8")
        for line in lines if not line.startswith("MIGRATE:")
    ]
    migration = [
        base64.b64decode(line.partition(":")[2], validate=True).decode("utf-8")
        for line in lines if line.startswith("MIGRATE:")
    ]
    assert len(ddl) >= 10 and len(migration) >= 5
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys = ON")
    for statement in ddl:
        con.executescript(statement + ";")

    post = ("account-A", "42", "alice", "Agent notes AI", 1234, 1600, 2000)
    con.execute(
        "INSERT INTO posts(account_id, post_id, author_handle, body, "
        "published_at_ms, first_seen_at_ms, last_seen_at_ms) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)", post
    )
    con.execute(
        "INSERT INTO posts(account_id, post_id, author_handle, body, "
        "published_at_ms, first_seen_at_ms, last_seen_at_ms) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        ("account-B", "42", "bob", "Coffee journal", None, 1700, 2200),
    )
    con.execute(
        "INSERT INTO history_events(account_id, post_id, event_type, event_at_ms, source) "
        "VALUES (?, ?, ?, ?, ?)",
        ("account-A", "42", "OPEN_POST", 2000, "post_detail"),
    )
    con.execute(
        "INSERT INTO categories(account_id, category_id, label) VALUES (?, ?, ?)",
        ("account-A", "research", "AI Research"),
    )
    con.execute(
        "INSERT INTO post_categories(account_id, post_id, category_id) "
        "VALUES (?, ?, ?)", ("account-A", "42", "research")
    )
    q = "SELECT account_id, post_id FROM posts_fts WHERE posts_fts MATCH ?"
    assert con.execute(q, ("agent*",)).fetchall() == [("account-A", "42")]
    assert con.execute(
        "SELECT account_id, post_id FROM posts WHERE body LIKE ?",
        ("%Agent%",),
    ).fetchall() == [("account-A", "42")]

    # Upstream content changes must rebuild, not duplicate, the FTS row.
    con.execute(
        "UPDATE posts SET body=? WHERE account_id=? AND post_id=?",
        ("New article about Rust", "account-A", "42"),
    )
    assert con.execute(q, ("agent*",)).fetchall() == []
    assert con.execute(q, ("rust*",)).fetchall() == [("account-A", "42")]

    # Different X accounts can share the same public post ID without sharing state.
    assert con.execute(
        "SELECT count(*) FROM posts WHERE post_id='42'"
    ).fetchone()[0] == 2
    try:
        con.execute(
            "INSERT INTO post_categories(account_id, post_id, category_id) "
            "VALUES (?, ?, ?)", ("account-B", "42", "research")
        )
        raise AssertionError("Cross-account category leakage")
    except sqlite3.IntegrityError:
        pass

    con.execute("DELETE FROM posts WHERE account_id=?", ("account-A",))
    assert con.execute(q, ("rust*",)).fetchall() == []
    assert con.execute(
        "SELECT count(*) FROM history_events WHERE account_id='account-A'"
    ).fetchone()[0] == 0
    assert con.execute(
        "SELECT count(*) FROM post_categories WHERE account_id='account-A'"
    ).fetchone()[0] == 0
    assert con.execute(
        "SELECT count(*) FROM posts WHERE account_id='account-B'"
    ).fetchone()[0] == 1
    # Reconstruct an earlier v1 install from the corresponding v2 DDL and
    # verify that migrations preserve every account's posts and event history.
    old = sqlite3.connect(":memory:")
    old.execute("PRAGMA foreign_keys = ON")
    for statement in ddl:
        if any(token in statement for token in (
            "idx_posts_open_time", "idx_posts_feed_time",
            "idx_actions_by_post", "uniq_actions_known", "uniq_actions_unknown"
        )):
            continue
        old_statement = statement.replace(
            "last_opened_at_ms INTEGER, ", ""
        ).replace("last_feed_seen_at_ms INTEGER, ", "")
        old.executescript(old_statement + ";")
    old.execute(
        "INSERT INTO posts(account_id, post_id, body, first_seen_at_ms, last_seen_at_ms) "
        "VALUES ('account-A', '88', 'historical post', 1, 2)"
    )
    old.execute(
        "INSERT INTO history_events(account_id, post_id, event_type, event_at_ms, source) "
        "VALUES ('account-A', '88', 'OPEN_POST', 2, 'post_detail')"
    )
    for _ in range(2):
        old.execute(
            "INSERT INTO user_actions(account_id, post_id, action_type, action_at_ms, source) "
            "VALUES ('account-A', '88', 'REPOST', NULL, 'verified_self_activity')"
        )
    for statement in migration:
        old.executescript(statement + ";")
    assert old.execute(
        "SELECT last_opened_at_ms, opened_count FROM posts "
        "WHERE account_id='account-A' AND post_id='88'"
    ).fetchone() == (2, 0)
    assert old.execute(
        "SELECT count(*) FROM user_actions WHERE account_id='account-A'"
    ).fetchone()[0] == 1
    old.execute(
        "INSERT OR IGNORE INTO user_actions(account_id, post_id, action_type, source) "
        "VALUES ('account-A', '88', 'REPOST', 'verified_self_activity')"
    )
    assert old.execute("SELECT count(*) FROM user_actions").fetchone()[0] == 1
    assert old.execute(
        "SELECT body FROM posts WHERE account_id='account-A' "
        "AND post_id='88'"
    ).fetchone()[0] == 'historical post'
    print("PASS SQLite v2 DDL, deduped actions, v1 migration, FTS and account isolation")


if __name__ == "__main__":
    main()
