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
         +-- Android SQLite, app-private data [implemented]
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
- `history_events`: append-only `OPEN_POST` records scoped to account.
- `user_actions`: authored/reply/quote/repost/bookmark events; optional
  real action time, distinct from original post publication and local visit.
- `categories` and `post_categories`: user-defined, account-scoped labels.
- `posts_fts`: trigger-maintained FTS4 index with substring fallback for CJK
  or queries containing punctuation.

ArchiveSearch generates parameterized SQLite queries: limit 1-200,
bounded query length, escaped LIKE, constrained FTS expressions. For FTS,
filter by account as a separate condition to prevent cross-account results.

All database operations must avoid the Android UI thread. Failure in the local
archive should never crash or block the host app. Default event capture is off;
the authenticated account identity must be established explicitly.

## Native library build

Gradle / Android SDK (compileSdk 35, minSdk 26), JDK 17:

```bash
gradle :xs-runtime:assembleDebug
```

CI runs independent Java/SQLite tests and an Android AAR compilation job.
An Android AAR does not contain an installer, an X patcher, or a working hook.

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
