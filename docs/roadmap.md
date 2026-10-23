# Roadmap and delivery gates

## M0 — foundation (this commit)

- [x] Confirm upstream Piko v3.10.0 / X 12.19.1-release.0 and architecture.
- [x] Pin Piko upstream revision; preserve license and attribution.
- [x] Implement standalone post URL parser, local search query builder and schema.
- [x] Implement Android SQLite recording and account-scoped search bridge source.
- [x] Add no-network Java regression tests and CI configuration.
- [ ] Verify Android runtime against actual Android SDK; no device available.

## M1 — first real enhanced APKM

- [ ] Integrate Xs Android runtime into Piko's extension packaging.
- [ ] Use an authorized test install to identify version-stable post-detail hook.
- [ ] Use that hook to call XsCaptureBridge.onPostOpened exactly once per open.
- [ ] Add user setting, app lifecycle initialization and account switch handling.
- [ ] Integrate history page and search into X settings/sidebar.
- [ ] Build Morphe patch bundle, install and complete actual device tests.
- [ ] Validate removal of ads and Premium upsell through upstream patches.

## M2 — personal content management

- [ ] Index own posts/replies/quotes/reposts as separate action types.
- [ ] Add folders, tags, notes and batch categorization in native UI.
- [ ] Add search filters for publish/repost/open time and type.
- [ ] Add opt-in encrypted export/import and clear-history controls.
- [ ] FTS Chinese tokenizer research and large archive performance benchmarks.

## M3 — reliability

- [ ] Explicit compatibility table and repeatable build/checksums.
- [ ] Schema upgrades, storage quota and retention settings.
- [ ] CI with Android instrumentation and no-account fixture tests.
- [ ] Crash recovery, per-account isolation and permission security review.

Completion means tested code and a real build, not merely documents or a patch
description. Do not mark M1 complete from a stub or unverified fingerprint.
