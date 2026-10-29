# AGENTS.md — Xs engineering contract

## Accepted route, 2026-10-29

Xs is an independent local-first enhancement of the official X web experience with a native archive. The user accepted Web-first instead of modifying the official Android APK. Follow `docs/WEB_FIRST_PLAN.zh-CN.md` and `docs/adr/0001-web-first.md`; they supersede conflicting APK integration assumptions in historical documents.

Do not build a replacement social network, account service, website deployment or API proxy. Keep Chinese README.md and English README.en.md aligned. Keep this independent implementation; do not vendor another project's patch code or source submodules.

## Existing implementation versus planned work

- `src/main/java`: Java 8-compatible URL parsing, SQLite/query core and capture policy.
- `src/android/java`: native storage, capture bridge, archive UI and bounded posts-JS importer.
- `xs-runtime`: Android AAR, not a complete installed client.
- `dev-harness`: synthetic offline development host, not X.
- Existing JVM/SQLite and Android instrumentation checks remain useful.
- `app-android`, `web-enhancer`, web bridge, login and web filters are planned, not implemented by documentation.
- APK allowlist/validator and old integration contracts are historical. The metadata validator is not an implemented runtime security mechanism.

## Working order

W0 real login/browser feasibility → W1 secure host/scopes → W2 history/search/categories → W3 feed/filtering/import → W4 privacy/data protection/release → W5 platform-specific expansion.

Build a minimal non-instrumented browser first. Do not claim a usable integration from a successful AAR build, mock page, synthetic APK or plan. If the required login fails, stop that route and evaluate a standard-browser extension; never use credential extraction, cookie copying or browser impersonation as a workaround.

## Privacy, identity and trust

- Capture defaults OFF. Open-history, feed and filtering consent are independent.
- OPEN_POST requires deliberate, actually presented detail navigation. FEED_VISIBLE requires confirmed foreground visibility >=60% continuously for >=1200ms and separate opt-in.
- Prefetch, import, DOM rebinding and observing a repost are not user actions.
- Preserve separate publication/action/open/feed timestamps; unknown values stay NULL.
- The legacy `bindAuthenticatedAccount` requires a verified stable host identity. Never pass a scraped handle or local UUID while pretending it is verified. Add explicit native archive scopes and binding confidence.
- Scope/session/permissions are native-controlled. Reject stale epochs and stop on switching, logout or uncertain context.
- Treat all web messages as untrusted, including allowed-origin messages. Validate origin, main frame, route, schema, size/rate and session. No generic SQL/file/Intent/read-all/export/delete/enable-capture bridge.
- Do not collect credentials, cookies, tokens, DMs, drafts, hidden application state or arbitrary network responses. WebView may manage its normal session; Xs must not copy/export/log it.
- Login, verification, security, DM and unknown routes do not receive capture privileges; handle SPA transitions as well as full navigations.
- No servers, analytics, cloud sync or remote executable rules without explicit approval.
- SQLite is not inherently encrypted; never claim otherwise. Review index/WAL/log/backup exposure.
- Official-archive imports require explicit user choice and ownership confirmation, no JS execution, bounded parsing and no invented actions.
- No destructive schema recreation. Test scope migration, erasure, queue draining and restores.

## Verification and release

After relevant implementation changes, run and report exact outcomes for:

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest
```

Android tasks require SDK and emulator/device. Add web schema/DOM tests and real WebView instrumentation with implementation. Keep synthetic and live tests separate. A docs-only change does not claim these commands were run.

Maintain device/OS/WebView/locale/commit compatibility evidence. No blanket Google/Apple/Passkey, push notification, multi-account isolation, account-safety or store-approval claims. Review provider terms and distribution rights separately from technical tests.

Keep the repository public as configured. Do not deploy sites or touch the user's local machine/servers without an explicit request. Keep signing keys, credentials and real private content out of source, CI logs and artifacts.
