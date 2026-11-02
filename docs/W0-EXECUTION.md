# W0 execution record

Date: 2026-11-02 (Asia/Shanghai). Baseline: `3bf78cbe4c780307a36a178ba8737f39011e9a68`.

## Repository and retained assets

Main was rechecked through GitHub. No open PRs existed; Issue #1 still described the historical
APK route. The five latest CI runs were successful, including baseline run `40837698156`.
Read AGENTS, both READMEs, the complete Web-first plan, ADR, product, architecture and roadmap.
Reviewed the core schema/search/policy, Android storage/capture/import/UI and existing tests.
The local SQLite v2, migration, FTS4/CJK fallback, category UI and importer are retained.
The unbounded legacy capture executor is **not reused** by the W0 host; W1 must replace its
session/queue boundary before connecting website observations. Review found that a later shorter
snapshot replaced longer stored text. This is fixed with a conservative SQLite length check and an
instrumented FTS/scope regression. It preserves existing data without a schema migration; length is
not a completeness score and W2 still needs explicit parsing-quality provenance.

## Implemented

`app-android` is an independent Java Android app sharing the existing Gradle/toolchain and
`xs-runtime` dependency. It has a system WebView, first-use notice, small native toolbar,
loading/failure/retry, home/reload, system back, navigation-state restore, lifecycle callbacks,
renderer-loss recovery, user-confirmed media upload, fullscreen custom view and bilingual strings.
No webpage is loaded before the first-use button. All capture remains absent, rather than merely
hidden behind a setting. The app does not call the legacy authenticated account binding API.

The browser uses an exact parsed HTTPS policy, no credential-bearing authority or custom ports,
no file/content navigation, no permissive TLS handler, no arbitrary Intent forwarding, no native
JavascriptInterface, no automatic web permission grants, no remote scripts and no User-Agent changes.
Debugging is off even for this PoC. Screenshot/task-preview protection and backup exclusion rules
are included; physical backup/transfer behavior is not yet verified.

## Build and tests

Local `bash scripts/check-project.sh`: PASS (6 Python allowlist tests).
Local `bash scripts/test-core.sh`: BLOCKED, `javac` missing. The cloud has a Java 17 runtime,
but no full JDK, Gradle, SDK or emulator. The package manager could not locate the JDK package.
Android builds and emulator tests therefore run in the existing GitHub Actions workflow,
now covering API 30 and API 36 (Android 16) independently. These are synthetic emulators,
not physical-device or live-login evidence.

CI commands added/retained:

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

Initial CI run `41450267872`: JVM/SQLite (73 core assertions), APK/AAR build and lint PASS.
Instrumentation compilation FAILED because Android does not expose the WebResourceError constructor.
The callback test now uses a null unused error payload, retaining its main-frame/subframe assertions;
this is a callback simulation, not a network failure test. Instrumentation compilation is also moved
into the build job so failures surface before emulator boot. A later test initially expected an intercepted synthetic HTTP 503 to emit the network error callback;
the API 30 emulator did not do so. The fixture now loads a successful local page and explicitly invokes
main-frame/subframe HTTP-error callbacks while keeping the retry/status assertions. This is callback
coverage, not an actual offline/TLS/HTTP-network test. Run [41452139505](https://github.com/ForceMind/Xs/actions/runs/41452139505) at source
`a42131bd29464a7f36d98a3e339ae68ac29223ac` passed all jobs: 73 JVM assertions,
SQLite regression, AAR/APK builds, Android lint and 13 tests on each of API 30 and 36
(6 browser + 7 archive tests per emulator). Both browser HTTP and renderer loss cases remain
callback simulations; actual back/reload and HTML/JS run inside WebView with local fixtures.

A follow-up CI metadata step records Android release/API, locale, night-mode setting, exact
WebView provider and built source commit with each emulator report. This closes an evidence gap;
it does not turn synthetic results into live X or physical-device validation. JVM tests include adversarial navigation cases.
New instrumented tests cover secure defaults, Activity recreation, actual synthetic HTML/JS execution,
absence of archive creation, non-top-level navigation, automatic redirect refusal, permission denial,
actual back/reload with intercepted synthetic HTTPS responses, an explicit HTTP-error callback,
main-frame-only error UI and a simulated renderer-loss callback. Simulating a callback is not an actual
OS renderer kill. Existing archive instrumentation remains unchanged.

## Internal installation and validation

1. Open [PR #2](https://github.com/ForceMind/Xs/pull/2) → checks → successful `xs-ci` run.
2. Download `xs-w0-browser-poc`, unzip and install `app-android-debug.apk` on a test Android device (inside `outputs/apk/debug/` in the ZIP).
   With Android platform tools: `adb install -r app-android-debug.apk`.
3. Verify source SHA from the run and compute `sha256sum app-android-debug.apk`. Debug signing is
   ephemeral CI signing, not a production signing identity or reproducible-build proof.
4. Open Xs, read the notice, tap Open X. Menu → Build and compatibility shows Android and WebView.
5. Run [the matrix](../compat/web/W0-MATRIX.md) locally. Do not upload passwords, tokens or private data.

This PoC has application ID `dev.forcemind.xs`, separate from the synthetic harness and official X.
W0 records no history; signing keys are not added to the repository. No GitHub release or store
publication is made. W1/W2 remain blocked on real login feasibility and explicit archive scopes.
