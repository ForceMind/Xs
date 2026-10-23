# Xs technical architecture

## Boundary

Original X APKM + compatible Morphe/Piko patches
                  |
       verified post-detail event hook (NOT YET IMPLEMENTED)
                  |
           XsCaptureBridge [Java]
                  | single background executor
            XsArchiveDatabase
                  |
          app-private Android SQLite
          [posts / history_events / user_actions /
           categories / post_categories / posts_fts]
                  |
        future X-integrated History/Search UI

The hook is intentionally missing until its exact app version and lifecycle
are validated. Xs's Java core and Android persistence are implemented independently
of obfuscated X classes, minimizing breakage across upstream updates.

## Storage model

- posts: composite (account_id, post_id), last_seen and first_seen, optional
  body/author and original publication timestamp, open_count.
- history_events: append-only account-scoped opening events. Event time is not
  a post publication time.
- user_actions: optional self-post/reply/quote/repost/bookmark events; action_at_ms
  may be NULL when X's source does not provide the historical action timestamp.
- categories: per-account locally named user categories.
- post_categories: unique mapping with composite foreign keys.
- posts_fts: FTS4 index synchronized by triggers. FTS results never replace
  canonical rows; Unicode CJK substring search falls back to parameterized LIKE.
- No tables for tokens, sessions, private messages or general network captures.

## Account boundary and operation

The caller must provide a trusted stable authenticated account ID. This is a
security-sensitive boundary and cannot safely be guessed from the post author,
handle or device username. The bridge will not record until initialized with
that value. Record calls are always explicitly scoped to that account.

Android data lives in the host app's private database directory. This is
sandbox storage, **not end-to-end encryption** or automatically a private export.
A future encrypted export needs a separate key-management plan.

A single-threaded executor avoids synchronous disk writes on the UI thread.
The database transaction upserts one post, increments open_count and appends
one view event atomically. DB errors must not crash the host X application.

All queries are parameterized. Search accepts a bounded limit/offset and
rejects invalid ranges. Non-Latin/punctuation search uses escaped LIKE terms.
English FTS terms are generated as safe prefix tokens, not arbitrary raw MATCH
syntax. This is a v1 tradeoff, not multilingual semantic indexing.

## Build/integration boundary

- src/main/java compiles with Java 8 language features and no dependencies.
- src/android/java imports Android SQLite APIs and cannot be validated with
  the pure JVM test alone.
- Injecting the bridge requires both a runtime extension inclusion and a
  separately version-verified Morphe fingerprint/hook.
- Piko is fetched as an external pinned upstream revision; GPLv3 and NOTICE
  obligations apply to any derivative distribution. We do not ship an APK.
- Android privacy: disable automatic backup for the archive or explicitly
  document where Android backup includes it, before a release.

## Version upgrade checklist

1. Verify Piko target APKM version and source SHA.
2. Confirm X post-detail enter/exit methods on a user-owned test installation.
3. Confirm callback fires exactly once; test account changes.
4. Confirm SQLite migrations preserve events and categories.
5. Validate post open, offline query, category, deletion and logout.
6. Validate ad/up-sell filters separately; do not weaken authentication.
