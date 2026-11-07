#!/usr/bin/env python3
"""Real SQLite/FTS capacity and preserving v1->v5 migration, using evaluated runtime SQL.

Run with a JDK on PATH:
  python3 tests/sqlite_capacity_benchmark.py --sizes 10000 100000 --output /tmp/xs-capacity.json

No machine-time acceptance thresholds. Correctness, isolation and planner shape are assertions;
latencies/file sizes describe only this synthetic desktop SQLite run, never phone performance.
Temporary databases are deleted unless --database-dir is explicitly supplied.
"""
import argparse
import base64
import collections
import json
import pathlib
import platform
import sqlite3
import statistics
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
LIBRARY = "library:capacity"
BASE = 1_700_000_000_000


def exported_sql(directory):
    sources = sorted((ROOT / "src/main/java").rglob("*.java"))
    sources.append(ROOT / "tests/java/dev/forcemind/xs/tests/CapacityDump.java")
    subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", str(directory), *map(str, sources)], check=True)
    result = subprocess.run(["java", "-cp", str(directory), "dev.forcemind.xs.tests.CapacityDump"], check=True, text=True, capture_output=True)
    groups = collections.defaultdict(list)
    for line in result.stdout.splitlines():
        key, _, encoded = line.partition(":")
        groups[key].append(base64.b64decode(encoded, validate=True).decode("utf-8"))
    return groups


def measure(fn):
    start = time.perf_counter()
    result = fn()
    return result, round((time.perf_counter() - start) * 1000, 3)


def benchmark(count, groups, directory, compare_legacy):
    path = directory / f"capacity-{count}.sqlite"
    if path.exists():
        raise ValueError(f"Refusing to overwrite existing benchmark database: {path}")
    con = sqlite3.connect(path)
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("PRAGMA journal_mode=WAL")
    # Reconstruct the shipped v1 shape, not the latest empty schema.
    for ddl in groups["V2"]:
        if any(token in ddl for token in ("idx_posts_open_time", "idx_posts_feed_time", "idx_actions_by_post", "uniq_actions_known", "uniq_actions_unknown")):
            continue
        con.execute(ddl.replace("last_opened_at_ms INTEGER, ", "").replace("last_feed_seen_at_ms INTEGER, ", ""))
    def populate():
        with con:
            con.executemany("INSERT INTO posts(account_id,post_id,author_handle,body,published_at_ms,first_seen_at_ms,last_seen_at_ms,opened_count) VALUES (?,?,?,?,?,?,?,?)", (
                (LIBRARY,str(i),f"user{i % 100}",f"capacity {'needle' if i % 97 == 0 else 'ordinary'} {'容量' if i % 89 == 0 else 'notes'} index {i}",BASE+i*1000,BASE,BASE+i*1000,int(i % 10 == 0)) for i in range(1,count+1)))
            con.executemany("INSERT INTO history_events(account_id,post_id,event_type,event_at_ms,source) VALUES (?,?,'FEED_VISIBLE',?,?)", (
                (LIBRARY,str(i),BASE+i*1000,"home_following" if i % 2 == 0 else "home_for_you") for i in range(1,count+1)))
            con.executemany("INSERT INTO history_events(account_id,post_id,event_type,event_at_ms,source) VALUES (?,?,'OPEN_POST',?,'post_detail')", (
                (LIBRARY,str(i),BASE+i*1000) for i in range(1,count+1) if i % 10 == 0 or i % 17 == 0))
            con.execute("INSERT INTO categories VALUES (?,'research','Research')",(LIBRARY,))
            con.executemany("INSERT INTO post_categories VALUES (?,?,'research')",((LIBRARY,str(i)) for i in range(1,count+1) if i % 13 == 0))
            # Same IDs in another scope must never enter capacity-library search/FTS.
            con.executemany("INSERT INTO posts(account_id,post_id,body,first_seen_at_ms,last_seen_at_ms) VALUES ('library:other',?,'needle superseded cross-scope',1,2)",((str(i),) for i in range(1,101)))
    _, populate_ms = measure(populate)
    expected_posts = con.execute("SELECT rowid,account_id,post_id,author_handle,body,published_at_ms,first_seen_at_ms,last_seen_at_ms,opened_count FROM posts ORDER BY rowid").fetchall()
    expected_events = con.execute("SELECT * FROM history_events ORDER BY id").fetchall()
    expected_categories = con.execute("SELECT * FROM post_categories").fetchall()
    lookup = "SELECT MAX(event_at_ms) FROM history_events WHERE account_id=? AND post_id=? AND event_type='OPEN_POST'"
    legacy_plan = [r[3] for r in con.execute("EXPLAIN QUERY PLAN "+lookup,(LIBRARY,"100"))]
    legacy_ms = None
    # Optional comparison runs the old repeated lookup on 10k only, never a 100k quadratic loop.
    if compare_legacy and count <= 10000:
        _, legacy_ms = measure(lambda: con.execute("SELECT (SELECT MAX(e.event_at_ms) FROM history_events e WHERE e.account_id=p.account_id AND e.post_id=p.post_id AND e.event_type='OPEN_POST') FROM posts p WHERE p.account_id=?",(LIBRARY,)).fetchall())
    migration_ms = {}
    for version in range(2,6):
        def migrate():
            with con:
                for ddl in groups[f"M{version}"]:
                    con.execute(ddl)
        _, migration_ms[f"v{version-1}_to_v{version}"] = measure(migrate)
    assert con.execute("SELECT rowid,account_id,post_id,author_handle,body,published_at_ms,first_seen_at_ms,last_seen_at_ms,opened_count FROM posts ORDER BY rowid").fetchall() == expected_posts
    assert [r[:6] for r in con.execute("SELECT * FROM history_events ORDER BY id")] == expected_events
    assert con.execute("SELECT * FROM post_categories").fetchall() == expected_categories
    assert con.execute("SELECT count(*) FROM snapshot_versions WHERE captured_at_ms IS NOT NULL").fetchone()[0] == 0
    assert con.execute("SELECT count(*) FROM snapshot_versions WHERE quality='LEGACY_UNKNOWN'").fetchone()[0] == count+100
    indexed_plan = [r[3] for r in con.execute("EXPLAIN QUERY PLAN "+lookup,(LIBRARY,"100"))]
    assert any("idx_history_post_type_time" in detail for detail in indexed_plan)
    with con:
        con.execute("UPDATE history_events SET event_key='feed:'||post_id,duration_ms=1200+(CAST(post_id AS INTEGER)%10)*500,threshold_ms=1200,duration_quality='SAMPLED_LOWER_BOUND' WHERE event_type='FEED_VISIBLE'")
        con.execute("UPDATE posts SET last_feed_seen_at_ms=last_seen_at_ms WHERE account_id=?",(LIBRARY,))
        con.executemany("INSERT INTO favorites(account_id,post_id,favorited_at_ms,note) VALUES (?,?,?,'saved note')",((LIBRARY,str(i),BASE+i*1000) for i in range(1,count+1) if i % 7 == 0))
        con.executemany("INSERT INTO post_tags VALUES (?,?,'capacity')",((LIBRARY,str(i)) for i in range(1,count+1) if i % 7 == 0))
        con.executemany("INSERT INTO snapshot_versions(account_id,post_id,snapshot_key,body,captured_at_ms,source,quality) VALUES (?,?,'old','superseded archived text',?,'native_post_detail','PARTIAL')",((LIBRARY,str(i),BASE+i*1000) for i in range(1,count+1) if i % 101 == 0))
    assert con.execute("PRAGMA foreign_key_check").fetchall() == []
    assert con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    # Explicitly preserve and validate existing FTS rowid identity for uncorrelated membership.
    assert con.execute("SELECT count(*) FROM posts p LEFT JOIN posts_fts f ON f.rowid=p.rowid WHERE f.account_id!=p.account_id OR f.post_id!=p.post_id OR f.rowid IS NULL").fetchone()[0] == 0
    def eligible(name, i):
        if name == "LATIN": return i % 97 == 0
        if name == "CJK": return i % 89 == 0
        if name == "AUTHOR": return i % 100 == 7
        if name == "VERSION_HISTORY": return i % 101 == 0
        if name == "FAVORITE_CATEGORY": return i % 7 == 0 and i % 13 == 0
        if name.startswith("DWELL"):
            return i % 2 == 0 and i % 10 != 0 and i % 17 != 0 and 1200+(i % 10)*500 >= 3000 and (name != "DWELL_DATE_SOURCE" or 250 <= i <= 750)
        return True
    queries = {}
    for key in sorted(k for k in groups if k.startswith("QUERY_")):
        name = key[6:]; sql = groups[key][0]; args = groups["ARG_"+name]
        plan = [r[3] for r in con.execute("EXPLAIN QUERY PLAN "+sql,args)]
        rows, first_ms = measure(lambda: con.execute(sql,args).fetchall())
        timings = [first_ms]
        for _ in range(2):
            _, elapsed = measure(lambda: con.execute(sql,args).fetchall());timings.append(elapsed)
        expected = sum(eligible(name,i) for i in range(1,count+1))
        assert len(rows) == min(50,expected),(name,len(rows),expected)
        assert len({r[1] for r in rows}) == len(rows)
        assert all(r[0] == LIBRARY and eligible(name,int(r[1])) for r in rows)
        if name in ("LATIN","VERSION_HISTORY"):
            # A materialized list is independent of each outer post; no repeated MATCH loop.
            assert any("LIST SUBQUERY" in detail for detail in plan)
        queries[name] = {"matchedPosts":expected,"returnedRows":len(rows),"runsMs":timings,
                         "medianMs":round(statistics.median(timings),3),"explainQueryPlan":plan}
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    result = {"posts":count,"historyEvents":len(expected_events),"snapshotVersions":con.execute("SELECT count(*) FROM snapshot_versions").fetchone()[0],
              "populateMs":populate_ms,"migrationMs":migration_ms,"legacyLookupPlan":legacy_plan,
              "indexedLookupPlan":indexed_plan,"legacyBackfillSelectMs":legacy_ms,"databaseBytes":path.stat().st_size,
              "ftsRowidIdentity":"PASS","foreignKeys":"PASS","integrity":"PASS","retainedOriginalRows":"PASS","queries":queries}
    con.close();return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes",nargs="+",type=int,default=[10000,100000])
    parser.add_argument("--output",type=pathlib.Path)
    parser.add_argument("--database-dir",type=pathlib.Path)
    parser.add_argument("--compare-legacy",action="store_true")
    args=parser.parse_args()
    if any(n<100 or n>1000000 for n in args.sizes):parser.error("sizes must be 100..1000000")
    with tempfile.TemporaryDirectory(prefix="xs-capacity-") as temporary:
        temp=pathlib.Path(temporary);classes=temp/"classes";classes.mkdir()
        groups=exported_sql(classes)
        databases=args.database_dir or temp
        databases.mkdir(parents=True,exist_ok=True)
        result={"result":"PASS","scope":"synthetic desktop SQLite; no Android/real-device timing claim",
                "python":platform.python_version(),"sqlite":sqlite3.sqlite_version,"platform":platform.platform(),
                "workloads":[benchmark(n,groups,databases,args.compare_legacy) for n in args.sizes]}
        rendered=json.dumps(result,ensure_ascii=False,indent=2)
        if args.output:args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(rendered+"\n",encoding="utf-8")
        print(rendered)


if __name__ == "__main__":main()
