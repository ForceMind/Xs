#!/usr/bin/env python3
"""Validate the exact Java ArchiveSchema DDL against real SQLite + FTS4."""
import base64
import sqlite3
import sys


def main() -> None:
    lines = [line.strip() for line in sys.stdin if line.strip()]
    ddl = [
        base64.b64decode(line, validate=True).decode("utf-8")
        for line in lines if ":" not in line
    ]
    migration = [
        base64.b64decode(line.partition(":")[2], validate=True).decode("utf-8")
        for line in lines if line.startswith("MIGRATE:")
    ]
    v2 = [base64.b64decode(line.partition(":")[2], validate=True).decode("utf-8")
          for line in lines if line.startswith("V2:")]
    v3 = [base64.b64decode(line.partition(":")[2], validate=True).decode("utf-8")
          for line in lines if line.startswith("V3:")]
    actual_query = next(base64.b64decode(line.partition(":")[2], validate=True).decode("utf-8")
                        for line in lines if line.startswith("QUERY:"))
    actual_args = [base64.b64decode(line.partition(":")[2], validate=True).decode("utf-8")
                   for line in lines if line.startswith("ARG:")]
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
    for statement in v2:
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
    # v1 -> v2 -> v3 keeps exact namespace keys, timestamps, FTS rowids and category links.
    old.execute("INSERT INTO categories VALUES ('account-A','old-folder','Retained')")
    old.execute("INSERT INTO post_categories VALUES ('account-A','88','old-folder')")
    old.execute("INSERT INTO categories VALUES ('empty-legacy','empty','No posts yet')")
    old.execute("INSERT INTO posts(account_id,post_id,body,first_seen_at_ms,last_seen_at_ms) "
                "VALUES ('account-B','88','a separate scope',10,20)")
    old.execute("INSERT INTO history_events(account_id,post_id,event_type,event_at_ms,source) "
                "VALUES ('account-B','88','FEED_VISIBLE',20,'home_for_you')")
    old.execute("INSERT INTO posts(account_id,post_id,first_seen_at_ms,last_seen_at_ms) "
                "VALUES ('local:old','42',3,4)")
    before_posts = old.execute("SELECT rowid,account_id,post_id,body,first_seen_at_ms,last_seen_at_ms FROM posts ORDER BY rowid").fetchall()
    before_events = old.execute("SELECT * FROM history_events ORDER BY id").fetchall()
    before_categories = old.execute("SELECT * FROM post_categories").fetchall()
    for statement in v3:
        old.executescript(statement + ";")
    assert old.execute("SELECT rowid,account_id,post_id,body,first_seen_at_ms,last_seen_at_ms FROM posts ORDER BY rowid").fetchall() == before_posts
    assert [row[:6] for row in old.execute("SELECT * FROM history_events ORDER BY id").fetchall()] == before_events
    assert old.execute("SELECT * FROM post_categories").fetchall() == before_categories
    assert old.execute("SELECT legacy_scope,library_id FROM legacy_scope_mapping ORDER BY legacy_scope").fetchall() == [(key,key) for key in ['account-A','account-B','empty-legacy','local:old']]
    assert old.execute("SELECT account_id,post_id FROM posts_fts WHERE posts_fts MATCH 'historical*'").fetchall() == [('account-A','88')]
    assert old.execute("SELECT duration_ms FROM history_events WHERE event_type='FEED_VISIBLE'").fetchone()[0] is None
    assert old.execute("SELECT snapshot_state FROM posts WHERE account_id='local:old'").fetchone()[0] == 'LINK_ONLY'
    assert old.execute("PRAGMA foreign_key_check").fetchall() == []

    # Duration is nullable for legacy events, measured for new exposures, scoped and deduped.
    for ident in ['101','102','103']:
        con.execute("INSERT INTO posts(account_id,post_id,body,author_handle,first_seen_at_ms,last_seen_at_ms) VALUES ('local:test',?,'robot notes','alice',100,100)",(ident,))
    for ident, duration in [('101',3500),('102',1000),('103',5000)]:
        con.execute("INSERT INTO history_events(account_id,post_id,event_type,event_at_ms,source,event_key,duration_ms,threshold_ms) VALUES ('local:test',?,'FEED_VISIBLE',100,'home_for_you',?,?,3000)",(ident,'exposure:'+ident,duration))
    con.execute("INSERT INTO history_events(account_id,post_id,event_type,event_at_ms,source,event_key) VALUES ('local:test','103','OPEN_POST',100,'post_detail','nav:103')")
    # Also exclude legacy open events if the old counter was inconsistent.
    assert [row[1] for row in con.execute(actual_query,actual_args)] == ['101']
    try:
        con.execute("INSERT INTO history_events(account_id,post_id,event_type,event_at_ms,source,event_key,duration_ms) VALUES ('local:test','101','FEED_VISIBLE',200,'home_for_you','exposure:101',8000)")
        raise AssertionError("Duplicate exposure accepted")
    except sqlite3.IntegrityError:
        pass
    con.execute("UPDATE history_events SET duration_ms=MAX(duration_ms,4000) WHERE event_key='exposure:101'")
    assert con.execute("SELECT count(*),max(duration_ms) FROM history_events WHERE event_key='exposure:101'").fetchone() == (1,4000)
    con.execute("INSERT INTO favorites(account_id,post_id,favorited_at_ms,note) VALUES ('local:test','101',200,'Personal annotation')")
    con.execute("INSERT INTO post_tags VALUES ('local:test','101','AI')")
    con.execute("INSERT INTO post_tags VALUES ('local:test','101','research')")
    meta_query = next(base64.b64decode(line.partition(":")[2],validate=True).decode("utf-8")
                      for line in lines if line.startswith("META_QUERY:"))
    meta_args = [base64.b64decode(line.partition(":")[2],validate=True).decode("utf-8")
                 for line in lines if line.startswith("META_ARG:")]
    assert [row[1] for row in con.execute(meta_query,meta_args)] == ['101']
    con.execute("INSERT OR IGNORE INTO favorites(account_id,post_id,favorited_at_ms) VALUES ('local:test','101',999)")
    assert con.execute("SELECT favorited_at_ms,note FROM favorites").fetchone() == (200,'Personal annotation')
    con.execute("DELETE FROM history_events WHERE account_id='local:test'")
    assert con.execute("SELECT count(*) FROM favorites").fetchone()[0] == 1
    con.execute("DELETE FROM favorites WHERE account_id='local:test' AND post_id='101'")
    assert con.execute("SELECT count(*) FROM post_tags").fetchone()[0] == 0
    assert con.execute("SELECT count(*) FROM posts WHERE account_id='local:test'").fetchone()[0] == 3
    # An old long exposure must not qualify a short exposure on the requested day/source.
    def feed_row(scope, ident, source, when, duration, key):
        con.execute("INSERT OR IGNORE INTO posts(account_id,post_id,body,published_at_ms,first_seen_at_ms,last_seen_at_ms,last_feed_seen_at_ms) VALUES (?,?,'matching exposure',150,50,150,150)",(scope,ident))
        con.execute("INSERT INTO history_events(account_id,post_id,event_type,event_at_ms,source,event_key,duration_ms,threshold_ms,duration_quality) VALUES (?,?,'FEED_VISIBLE',?,?,?,?,3000,'SAMPLED_LOWER_BOUND')",(scope,ident,when,source,key,duration))
    for ident, old_duration, today_duration in [('201',5000,1000),('202',9000,4000),('203',10000,3500)]:
        feed_row('local:dates',ident,'home_for_you',50,old_duration,'old:'+ident)
        feed_row('local:dates',ident,'home_for_you',150,today_duration,'today:'+ident)
    for ident, for_you, following in [('204',5000,1000),('205',9000,4000),('206',10000,3500)]:
        feed_row('local:sources',ident,'home_for_you',150,for_you,'foryou:'+ident)
        feed_row('local:sources',ident,'home_following',160,following,'following:'+ident)
    def query_named(name):
        query=next(base64.b64decode(line.partition(":")[2],validate=True).decode('utf-8')
                   for line in lines if line.startswith(name+'_QUERY:'))
        args=[base64.b64decode(line.partition(":")[2],validate=True).decode('utf-8')
              for line in lines if line.startswith(name+'_ARG:')]
        return con.execute(query,args).fetchall()
    assert [(row[1],row[8],row[9]) for row in query_named('DATE')] == [('202',150,4000),('203',150,3500)]
    assert [(row[1],row[9]) for row in query_named('SOURCE')] == [('205',4000),('206',3500)]
    assert [(row[1],row[8],row[9]) for row in query_named('SOURCE_DATE')] == [('205',160,4000),('206',160,3500)]
    # Choosing published date explicitly continues filtering publication, not exposure time.
    assert [(row[1],row[9]) for row in query_named('PUBLISHED')] == [('203',10000),('202',9000),('201',5000)]
    print("PASS SQLite v3 DDL, v1/v2 preserving migrations, scope mappings, FTS, exposure dedup, dwell/never-opened and same-exposure date/source queries, independent favorites")


if __name__ == "__main__":
    main()
