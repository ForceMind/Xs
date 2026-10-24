# Xs — independent technical architecture

## Scope and state

Xs owns its entire Java and Android implementation. No third-party patch source,
application archive, branding, or proprietary client code is vendored.

```text
Existing X Android client (user-authorized test installation)
  |
  +-- Independent, version-verified integration adapter [NOT IMPLEMENTED]
  |      - exact post-detail open event
  |      - trusted authenticated account identity
  |      - optional navigation entry and UI filtering
  |
  +-- XsCaptureBridge [implemented; invocation hook missing]
         |
         +-- Android SQLite v2, app-private data [implemented]
         |      posts, visits, actions, categories, FTS4
         +-- XsHistoryActivity [implemented as library; client integration pending]
                history, local search, categories, privacy controls
```

The GitHub repository's `xs-runtime` Gradle module builds a standalone
**Android library AAR** for later inclusion in an authorized test client build;
that AAR is not a complete client and does not by itself patch X.

## Data architecture

- `posts`: (account_id, post_id) canonical key, optional author/body, optional
  publication time, first/last local encounter, visit count.
- `history_events`: append-only `OPEN_POST` and `FEED_VISIBLE` events,
  filtered and paged separately. A feed view never increases opened_count.
- `user_actions`: authored/reply/quote/repost/bookmark events; optional
  real action time, distinct from original post publication and local visit.
- `categories` and `post_categories`: user-defined, account-scoped labels.
- `posts.last_opened_at_ms` and `posts.last_feed_seen_at_ms`: source-specific
  timestamps, added in version 2 with a non-destructive migration from v1.
- `posts_fts`: trigger-maintained FTS4 index with substring fallback for CJK
  or queries containing punctuation.

ArchiveSearch generates parameterized SQLite queries: limit 1-200,
bounded query length, escaped LIKE, constrained FTS expressions. For FTS,
filter by account as a separate condition to prevent cross-account results.

Client adapter must only call onFeedPostVisible on verified foreground visibility
(at least 60% on screen for 1.2 seconds), with a separate user opt-in. The
process-local deduplicator suppresses repeat observations from the same source
for two minutes, without replacing deliberate post-open events.

All database operations must avoid the Android UI thread. Failure in the local
archive should never crash or block the host app. Default event capture is off;
the authenticated account identity must be established explicitly.

## Native library build

Gradle / Android SDK (compileSdk 35, minSdk 26), JDK 17:

```bash
gradle :xs-runtime:assembleDebug :dev-harness:assembleDebug
```

CI runs independent Java/SQLite tests, Android AAR compilation and emulator
instrumentation against the synthetic development host, without an X account.
The independent AAR does not contain an installer, X patcher, or working hook.
The separate `dev-harness` APK is strictly a development fixture that seeds
offline synthetic posts and exercises the Activity and database without X login.
It is not a second social client and must not be distributed as an enhanced X.

## Safety and privacy

No credential, cookie, token, private message, or arbitrary traffic recording.
SQLite lives in app-private storage and is not inherently encrypted. A future
shipping integration must verify Android backup exclusion, account switching,
migration behavior, export encryption and deletion semantics.

## Separate build gates

1. Build independent runtime AAR.
2. Design and validate a version-scoped adapter on a permitted test install.
3. Integrate AAR, manifest and resources into the host application.
4. Verify native archive entry points, complete event semantics and installation.
5. Verify UI filtering independently from recording and other client features.
6. Publish an installable artifact only after device tests and license review.
