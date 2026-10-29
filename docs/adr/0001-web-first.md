# ADR-0001: Adopt a conditional Web-first delivery route

Date: 2026-10-29
Status: Accepted product direction; implementation and live feasibility remain unverified.

## Decision

Xs will enhance the official X web experience inside an independently signed Android application, subject to a real login and browser-capability gate. Preserve the existing Java/SQLite archive, native archive UI, importer and tests. Stop treating a patched official Android APK as the primary delivery route.

The implementation authority is [the complete Web-first plan](../WEB_FIRST_PLAN.zh-CN.md). This ADR supersedes conflicting route assumptions in the legacy `integration.md`, `integration-contract.md` and APK host allowlist. Keep those assets as history; do not pretend their metadata validator is an implemented runtime gate.

## Non-negotiable boundaries

- No new social backend, API proxy, account service, telemetry, credential collection or remote executable rule updates.
- Capture remains opt-in. Opened posts and qualified feed exposure remain separate events.
- Native storage, settings, erasure and export are not remotely callable from the website.
- A DOM handle is not an authenticated stable account ID. Introduce explicit local archive scopes; do not weaken or misuse `bindAuthenticatedAccount`.
- WebView origin checks do not authenticate the particular same-origin script. Treat every message as untrusted input.
- Google OAuth in an embedded user agent is constrained by provider policy. External browser login does not automatically populate WebView cookies. No cookie copying, browser impersonation or bypass.
- If Android WebView fails the required real login path, evaluate a standard desktop browser extension. Another embedded Chromium container is not a guaranteed fix.
- Content extraction and filtering require platform/rights review before public distribution; local-only does not imply authorization or zero account risk.

## Sequence

W0: pure browser feasibility → W1: secure host and scoped observations → W2: history/search/categories closed loop → W3: feed/filtering/import integration → W4: data protection and release evidence → W5: separate platform ports.

All W phases start pending. A build, a synthetic fixture, a document or an AAR is not a verified X integration. New native and web modules are proposed, not present merely because this ADR names them.

## Consequences

Android local code remains useful. Browser extraction logic can later be shared, but native UI/storage/session behavior cannot be assumed portable. The project trades APK reverse engineering for web compatibility and container-login maintenance. No promise of native-equivalent notifications, blanket browser-login support, store acceptance or zero account risk.
