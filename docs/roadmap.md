# Xs development roadmap — Web-first

Accepted 2026-10-29. [Full plan](WEB_FIRST_PLAN.zh-CN.md) and [ADR-0001](adr/0001-web-first.md) supersede the old APK-patching route.

## Existing foundation

The repository contains an independent Java/SQLite archive, URL/search/schema policies, native Android archive UI, categories, open/feed events, posts-JS import, bilingual README and synthetic/instrumentation tests. Existing code and historical test evidence are not proof of a new live web integration.

Preserve `xs-runtime`, `dev-harness`, core and data migration history. Keep old APK contracts and empty allowlist as legacy assets, not current prerequisites.

## Active phases — all pending at plan adoption

- [ ] W0: build a minimal non-instrumented browser host; validate actual X login methods, session restoration, navigation, media, upload and logout/switching. Record exact device/WebView/build evidence. Stop or narrow the route if this fails.
- [ ] W1: implement secure navigation, runtime feature checks, native archive scopes, bounded origin/main-frame/route/schema/epoch-validated observations and a native-only settings boundary.
- [ ] W2: deliver deliberate-open recording, native offline search/categories, duplicate prevention, persistence and return-to-original-post as one verified loop.
- [ ] W3: separately verify feed visibility, reversible UI filtering and official-archive import integration; never invent repost or reading times.
- [ ] W4: complete privacy review, backup/restore, non-destructive migrations, erasure/queue behavior, performance, real-device compatibility, signing/dependency provenance and distribution review.
- [ ] W5: evaluate standard desktop-browser extension delivery; independently assess other desktop shells and iOS. Do not promise automatic code or login portability.

The detailed WF-01..WF-12 work packages, dependency graph, limits and acceptance matrix are in the full plan. W0 is an internal PoC; W2 may qualify as Alpha, W3 as Beta and W4 as a release candidate only after their gates pass.

No milestone is complete because a document, AAR, mock fixture or synthetic APK exists. Do not claim new CI, emulator or live-login success without actually obtaining the matching result. No time commitment substitutes for a real exit criterion.
