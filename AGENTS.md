# AGENTS.md — Xs engineering contract

## Product

Xs is an **independent local-first enhancement for the existing X Android client**.
Do not turn it into a stand-alone social network, new account service, website, or API proxy.
Keep Chinese README.md and English README.en.md aligned whenever behavior changes.
Do not add external upstream source submodules or copy another project's patch implementation.

## Current implementation

- `src/main/java`: pure Java post URL parser, search SQL, v2 SQLite schema,
  feed exposure throttling; Java 8 source compatibility.
- `src/android/java`: Android SQLite data, capture bridge, native archive screen,
  and user-provided posts-JS importer.
- `xs-runtime`: Android AAR, **not** an installed client.
- `dev-harness`: synthetic, offline Android APK for development and tests.
- `tests`: JVM regression, genuine SQLite DDL/migration tests.
- CI runs Android AAR/APK builds and a real emulator instrumentation suite.

## Privacy and integrity

- Capture defaults **off**, stored separately per verified authenticated account.
- OPEN_POST means a deliberate post-detail navigation event only.
- FEED_VISIBLE means confirmed foreground visibility (>=60% for >=1200ms),
  is a separate opt-in, and must never inflate the open counter.
- Do not infer a read event from an imported record, timeline prefetch, or a repost.
- Publication, repost/action, read, and feed visibility timestamps are distinct.
- Unknown timestamps remain NULL. No fabricated dates.
- Local archive import may only read a user-selected post export file after
  confirmation it belongs to the currently bound account. No eval/JS execution.
- Never collect credentials, cookies, session tokens, DMs, background traffic,
  arbitrary HTTP responses or unrelated apps' data.
- No servers, analytics upload, or third-party cloud backups without explicit approval.
- No blanket database recreation on schema updates. Use verified migrations.

## Verification

At a minimum, after relevant edits:
```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest
```
Android tasks require an SDK and emulator or device. CI links and exact status
should appear in issue/PR summaries, and failures must be resolved before declaring
a milestone complete.

## Client integration limits

No compatible official X build or runtime fingerprint is currently checked in.
The version-specific post-open hook, authenticated account lifecycle binding,
navigation entry and clean-UI filtering are **not yet verified**.
Never publish a patched X binary or claim a working enhanced X APK until a legally
obtained, explicitly versioned test app is integrated and its login, navigation,
recording and upgrade behavior are validated on a real device.

Keep the repository public as currently configured; do not deploy a website or
touch any user's local machine or remote server without their explicit request.
