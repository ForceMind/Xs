# Xs architecture — accepted Web-first target

Decision date: 2026-10-29. See [ADR-0001](adr/0001-web-first.md) and the [complete implementation plan](WEB_FIRST_PLAN.zh-CN.md). This document distinguishes the existing archive from an unimplemented target; it does not claim a working web client.

## Target boundary

```text
Official X web in Android WebView [W0 feasibility pending]
  -> packaged web observer / reversible UI rules [planned]
  -> origin + main-frame + route + schema + session gate [planned]
  -> native capture controller and bounded queue [refactor planned]
  -> existing native SQLite / FTS / categories / archive UI [exists]
```

The remote website is untrusted. Even an allowed-origin message may originate from a same-origin script other than ours. Website-to-native communication accepts bounded observations only and exposes no archive reads, arbitrary SQL, filesystem, export, erasure or consent changes.

Native code owns archive scopes, epochs, receipt times and user controls. Login/DM/security/unknown routes do not receive capture privileges. WebView feature detection must fail closed for enhancement, not fall back to a generic unrestricted bridge. Ordinary browsing remains available where the provider supports it.

## Existing assets retained

`posts`, `history_events`, `user_actions`, `categories`, `post_categories` and the trigger-maintained FTS4 index already form the local archive. The v2 model distinguishes open/feed timestamps and supports a non-destructive v1 migration. CJK/punctuation queries use the existing substring fallback; no new tokenizer is implied.

`xs-runtime` builds an AAR. `dev-harness` is an offline synthetic APK, not X. `XsHistoryActivity` provides native archive management. The posts-JS importer currently handles one user-selected file/part of up to 16 MiB.

The existing `bindAuthenticatedAccount` contract cannot be satisfied merely by a webpage handle. Add explicit archive scopes and binding confidence; preserve old records with tested migrations. Do not silently reinterpret unverified local identifiers as authenticated identities.

## Planned modules

`app-android/`: independent browser host and native navigation.
`web-enhancer/`: TypeScript observation and reversible style capabilities, packaged locally.
`contracts/`: message and portable-export schemas.
`tests/web-fixtures/`: synthetic or authorized redacted fixtures.
`compat/web/`: tested capabilities, provider versions, language/theme and release evidence.

These paths are planned. Keep current Java/storage/UI modules rather than performing a gratuitous rewrite.

## Login and platform gates

Real X login is a prerequisite, with each login method tested independently. Google OAuth embedded-user-agent restrictions are not solved by an embedded Chromium swap. A Custom Tab/browser login does not automatically move the session into WebView. No credential or cookie transfer workaround.

If the required Android path fails, evaluate a real-browser extension. Share observation logic, contracts and fixtures; do not assume Android native UI/SQLite is directly portable. Desktop and iOS are separate delivery gates.

## Privacy and release

Capture defaults off; open and feed semantics stay separate. No credential/DM/arbitrary traffic capture, backend or telemetry. X web itself still performs normal network requests. SQLite is not inherently encrypted. Verify cloud/D2D backup exclusions, scope isolation, migration, deletion, queue invalidation, restore and dependency/signing provenance before release.

Legacy `integration.md`, `integration-contract.md` and APK host allowlist remain historical references, superseded for the active route. Metadata validation alone is not a working runtime gate. The first implementation work is a minimal, non-instrumented login/browser proof, not additional APK patch scaffolding.
