#!/usr/bin/env python3
"""Run the exact Java temporary schema on real SQLite/FTS4 and measure reclaimed bytes."""
import sqlite3
import sys
import tempfile
from pathlib import Path

statements = [line.strip() for line in sys.stdin if line.strip()]
assert len(statements) >= 10
checks = 0

def yes(condition):
    global checks
    checks += 1
    assert condition, checks

def rejected(con, sql, args=()):
    global checks
    try:
        con.execute(sql, args)
    except sqlite3.IntegrityError:
        checks += 1
        return
    raise AssertionError("accepted invalid schema operation")

with tempfile.TemporaryDirectory(prefix="xs-timeline-schema-") as directory:
    path = Path(directory) / "timeline.db"
    con = sqlite3.connect(path)
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("PRAGMA journal_mode=WAL")
    for sql in statements:
        con.execute(sql)
    yes(con.execute("SELECT enabled,ttl_hours,budget_mib,consent_generation FROM timeline_state").fetchone() == (0, 48, 100, 0))
    for sql in ("UPDATE timeline_state SET ttl_hours=0", "UPDATE timeline_state SET ttl_hours=169",
                "UPDATE timeline_state SET budget_mib=24", "UPDATE timeline_state SET budget_mib=501",
                "UPDATE timeline_state SET enabled=2"):
        rejected(con, sql)
    for library in ("local:a", "local:b"):
        con.execute("INSERT INTO timeline_scopes(library_id) VALUES (?)", (library,))
        for batch, at in (("old", 1000), ("new", 2000)):
            con.execute("INSERT INTO timeline_batches VALUES (?,?,?,?,?)", (library, batch, at, "native_navigation", "home_unknown"))
    def snapshot(library, post, digest, body):
        return con.execute("INSERT INTO timeline_snapshots(library_id,post_id,content_hash,author_handle,body,published_at_ms,captured_at_ms,quality,provenance) VALUES (?,?,?,?,?,?,?,?,?)",
                           (library, post, digest, "alice", body, None, 1000, "PARTIAL", "native_fixture")).lastrowid
    a = snapshot("local:a", "42", "body-a", "offline robot 机器人研究 %_!")
    b = snapshot("local:b", "42", "body-b", "other library secret")
    rejected(con, "INSERT INTO timeline_snapshots(library_id,post_id,content_hash,body,captured_at_ms,quality,provenance) VALUES ('local:a','42','body-a','duplicate',1000,'PARTIAL','native_fixture')")
    rejected(con, "INSERT INTO timeline_snapshots(library_id,post_id,content_hash,body,captured_at_ms,quality,provenance) VALUES ('local:a','43','bad-quality','not complete',1000,'COMPLETE','native_fixture')")
    def appearance(library, batch, post, version, ordinal, first, expires, promotion):
        return con.execute("INSERT INTO timeline_appearances(library_id,batch_id,post_id,snapshot_id,ordinal,first_at_ms,last_at_ms,expires_at_ms,promotion_id) VALUES (?,?,?,?,?,?,?,?,?)",
                           (library, batch, post, version, ordinal, first, first, expires, promotion)).lastrowid
    old_id = appearance("local:a", "old", "42", a, 0, 1000, 2000, "promotion-old")
    con.execute("INSERT INTO timeline_seen VALUES (?,?,?,?,?,?)", ("local:a", "old", "42", 1000, 2000, 0))
    rejected(con, "INSERT INTO timeline_seen VALUES ('local:a','old','42',1001,3000,9)")
    rejected(con, "INSERT INTO timeline_seen VALUES ('local:a','missing-batch','42',1000,2000,0)")
    rejected(con, "UPDATE timeline_scopes SET pending_clear=2")
    new_id = appearance("local:a", "new", "42", a, 2, 2000, 3000, "promotion-new")
    appearance("local:b", "old", "42", b, 0, 1000, 3000, "promotion-other")
    rejected(con, "INSERT INTO timeline_appearances(library_id,batch_id,post_id,snapshot_id,ordinal,first_at_ms,last_at_ms,expires_at_ms,promotion_id) VALUES ('local:a','old','42',?,4,1001,1001,5000,'duplicate')", (a,))
    rejected(con, "INSERT INTO timeline_appearances(library_id,batch_id,post_id,snapshot_id,ordinal,first_at_ms,last_at_ms,expires_at_ms,promotion_id) VALUES ('local:a','new','99',?,4,1001,1001,5000,'cross-library')", (b,))
    # Query-time expiry applies equally to FTS results; the old reference is hidden at equality.
    query = "SELECT a.id FROM timeline_appearances a JOIN timeline_snapshots s ON a.snapshot_id=s.id WHERE a.library_id=? AND a.expires_at_ms>? AND s.id IN (SELECT rowid FROM timeline_snapshots_fts WHERE timeline_snapshots_fts MATCH ?) ORDER BY a.first_at_ms,a.id"
    yes(con.execute(query, ("local:a", 1999, "robot*")).fetchall() == [(old_id,), (new_id,)])
    yes(con.execute(query, ("local:a", 2000, "robot*")).fetchall() == [(new_id,)])
    yes(con.execute(query, ("local:a", 3000, "robot*")).fetchall() == [])
    yes(con.execute(query, ("local:b", 2000, "robot*")).fetchall() == [])
    yes(con.execute("SELECT a.id FROM timeline_appearances a JOIN timeline_snapshots s ON a.snapshot_id=s.id WHERE a.library_id=? AND a.expires_at_ms>? AND s.body LIKE ? ESCAPE '!'", ("local:a", 2000, "%机器人%")).fetchall() == [(new_id,)])
    yes(con.execute("SELECT a.id FROM timeline_appearances a JOIN timeline_snapshots s ON a.snapshot_id=s.id WHERE a.library_id=? AND a.expires_at_ms>? AND s.body LIKE ? ESCAPE '!'", ("local:a", 2000, "%!%!_!!%")).fetchall() == [(new_id,)])
    # Removing an old reference must preserve the shared version used by the new batch.
    con.execute("DELETE FROM timeline_appearances WHERE expires_at_ms<=?", (2000,))
    con.execute("DELETE FROM timeline_snapshots WHERE NOT EXISTS (SELECT 1 FROM timeline_appearances a WHERE a.snapshot_id=timeline_snapshots.id)")
    yes(con.execute("SELECT count(*) FROM timeline_snapshots WHERE id=?", (a,)).fetchone()[0] == 1)
    # A clear affects only its scope and FTS triggers actually remove the text.
    con.execute("DELETE FROM timeline_batches WHERE library_id='local:a'")
    con.execute("DELETE FROM timeline_snapshots WHERE NOT EXISTS (SELECT 1 FROM timeline_appearances a WHERE a.snapshot_id=timeline_snapshots.id)")
    yes(con.execute("SELECT count(*) FROM timeline_snapshots_fts WHERE timeline_snapshots_fts MATCH 'robot*'").fetchone()[0] == 0)
    yes(con.execute("SELECT count(*) FROM timeline_appearances WHERE library_id='local:b'").fetchone()[0] == 1)
    # Disposable large temporary text and FTS are physically reclaimable, not only hidden.
    for i in range(300):
        version = snapshot("local:b", str(1000 + i), "bulk-" + str(i), "bulk" + str(i) + " " + "temporary metadata " * 400)
        appearance("local:b", "new", str(1000 + i), version, i, 2000, 3000, "bulk-promotion-" + str(i))
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    before = sum(p.stat().st_size for p in Path(directory).iterdir())
    con.execute("DELETE FROM timeline_appearances")
    con.execute("DELETE FROM timeline_snapshots")
    con.execute("DELETE FROM timeline_batches")
    con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.execute("VACUUM")
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    after = sum(p.stat().st_size for p in Path(directory).iterdir())
    yes(after < before // 2)
    yes(con.execute("SELECT count(*) FROM timeline_snapshots_fts").fetchone()[0] == 0)
    yes(con.execute("PRAGMA foreign_key_check").fetchall() == [])
    con.close()
print(f"PASS {checks} temporary SQLite/FTS4 assertions; actual bytes {before} -> {after}")
