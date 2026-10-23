#!/usr/bin/env python3
"""Validate the exact Java ArchiveSchema DDL against real SQLite + FTS4."""
import base64
import sqlite3
import sys


def main() -> None:
    lines = [line.strip() for line in sys.stdin if line.strip()]
    assert len(lines) >= 10, "Unexpected missing schema statements"
    con = sqlite3.connect(":memory:")
    con.execute("PRAGMA foreign_keys = ON")
    for encoded in lines:
        ddl = base64.b64decode(encoded, validate=True).decode("utf-8")
        con.executescript(ddl + ";")

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
    print("PASS SQLite DDL, FTS triggers, foreign keys and account isolation")


if __name__ == "__main__":
    main()
