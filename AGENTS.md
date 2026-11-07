# AGENTS.md — Xs engineering and continuation contract

## Read first

Xs is an independent Web-first X experience with an app-owned local library. Start with [docs/README.md](docs/README.md), [product](docs/product.md), [implementation plan](docs/IMPLEMENTATION_PLAN.zh-CN.md), [architecture](docs/architecture.md), [reuse review](docs/CHATGPT_STUDIO_REUSE.zh-CN.md) and [acceptance matrix](docs/ACCEPTANCE_MATRIX.zh-CN.md).

ADR-0002 defines app-owned data, immediate opens, favorites/media/sharing. ADR-0003 defines reuse/native-pull and parallel execution. They override conflicting ownership, trigger or implementation-order assumptions in the older Web-first plan and ADR-0001. Keep earlier safeguards where not superseded. Old APK contracts/allowlist are history, not current prerequisites.

Keep this public independent repository and synchronized Chinese/English READMEs. Do not repackage the official APK, build a new social backend/account service/API proxy, deploy a site, or substitute a manual link organizer for the core product.

## Requirements that must not drift

- After explicit opt-in, deliberate entry into a valid post detail creates OPEN_POST immediately: **no dwell threshold or extra save tap**. Save LINK_ONLY if body is pending and enrich later. One native navigation identity, not one render/message, means one open. Failed navigation/prefetch/reload/rotation/restoration are not new deliberate opens.
- Optional timeline dwell is a first-class **no-click history** requirement (see docs/FEED_DWELL_PLAN.zh-CN.md / ADR-0004), separate from immediate OPEN_POST and FAVORITE. OFF by default; 1.2s/3s (recommended default)/5s user-selectable continuous dwell, with 60% normalized readable-area baseline and long-card, overlay, switch/background boundaries. Store duration/provenance, allow `dwell >= N AND never opened` local search. One continuous exposure = one event; updates/DOM rebind do not create duplicates, real re-entry may be another exposure. Exposing a post is NOT proof of reading. XS-14 baseline moves into **early batch B**; extensive optimization stays D. Current fixture runtime and v3 search implement these; live X activation remains gated.
- Confirmed own repost/quote/post/reply activity is separate from viewing a repost. Menu click, cancellation, failed submit or someone else's action is not success. Preserve unknown actor/time and reversal state.
- “Favorite in Xs” and categories/tags/notes are app-local. Never implicitly call X bookmarks/likes/reposts. Repeated favorites are idempotent; category choice may follow successful save.
- History, favorites and assets belong to the native library, not the current X login. X logout/account switch invalidates old observation context but must not delete or silently choose another library.
- Implement image/image-set/video downloads, local asset management and **outbound actual-file sharing**. Incoming Save-to-Xs alone does not satisfy sharing. Manifests, blob URLs and segments are not complete videos.
- WeChat/QQ/Feishu/Telegram are recipient compatibility targets. Users choose application, conversation and sending; no contact/token harvesting or accessibility automation. Chooser callbacks are not delivery receipts.
- Unfavorite, clear history, delete media, clear cache, clear web session and delete library have different scopes. Cache cleanup never deletes user downloads; favorites do not silently download every video.

## Existing versus planned

- `src/main/java`: URL/v4 schema/query, CaptureSession, OwnActionSession, StrictJson, bounded SerialWriteCoordinator, image/standard-MP4 policy, metadata backup/merge codecs and legacy inactive observation gate.
- `src/android/java`: SQLite v4, app-owned library/favorites/own-activity UI, importer, native-pull/cleanup fixture controllers, shared writes, image/video tasks/assets, metadata portability/consolidation and grant-only sharing.
- `xs-runtime`: AAR. `dev-harness`: synthetic offline host, never a real client.
- `app-android`: retained System WebView, manual favorites/links, explicit image selection, outgoing sharing, incoming text target and synthetic instrumentation.
- Prior history/dwell/image proof remains in docs/IMPLEMENTATION_EVIDENCE.zh-CN.md. The current v4 own-action/reversal, direct-MP4, cleanup-fixture, metadata backup/restore and consolidation increment is tracked in docs/ROUND_C_EVIDENCE.zh-CN.md; local Android builds/lint passed, while API 30/36 instrumentation is PENDING until source-specific results are recorded. This is a limited offline increment, not completion of C/D or release acceptance.
- Live automatic capture, live own-action adapters and live cleanup remain disabled. Native browser favorites are link-only; existing local snapshots can be retained. Actual X video sources, physical devices and recipient apps remain unverified. Do not enable real X features merely because fixture tests pass.

Do not make current code appear implemented just by documenting new component names. Maintain the [change record](docs/CHANGELOG.md) and source/test evidence per XS work package.

## Browser and observer implementation

Use the pinned ChatGPT Studio review as a starting point for lifecycle retention, Profile capability checks, controlled child windows and native snapshot calls. Re-read exact source before copying. Do not import the large Activity, chat composer/draft collection, auto-send workflows, all service domains, app identity, credentials or signing keys.

Preserve Xs storage/UI investment. First browser scope may be one session; optional multi-profile support is runtime-detected, not inferred from OS version. Browser profile/cookies, library ownership and own-action actor hints are different entities.

Prefer packaged X-specific observation scripts with native-initiated bounded evaluateJavascript reads for the first loop. No general bidirectional bridge prerequisite. Returned data is untrusted even from the correct origin; same-origin script control is not solved by random window names/tokens. Avoid unrestricted Java interfaces and remote executable rules.

Native code owns library, epoch, document/navigation IDs, clocks, foreground/overlay state, per-feature consent and budgets. Validate raw payload size/types/commands, current top-level context, sensitive-route exclusion and replay before expensive work and again at actual persistence. increasing sequence numbers alone do not prevent duplicate navigation events. Epochs cannot be reused after pause/clear. Open and feed consent must be distinct.

Do not miss quick open-and-return by sampling only the current URL periodically. Use tested navigation observation and bounded event flushing; body parsing is incremental, not a full-document high-frequency scan. Page retention does not authorize background capture.

## Identity, migration and concurrency

Default to one explicit app library; optional private libraries may be added later. Preserve legacy account_id namespaces with mappings and explicit consolidation, never a global constant, silent merge or empty recreation. Verify counts, links, dates and FTS and retain original data on failure.

The legacy bindAuthenticatedAccount API must not accept a scraped username/local UUID as verified identity. Local public-content favorites need no verified X account. Social action attribution requires provenance or remains unknown.

OwnActionSession's enabled factory is explicitly native/offline-fixture only. It issues non-reusable operation identities, matches the native result's actor/type/new post or repost target, and linearizes persistence with invalidation. ORIGINAL/REPLY/QUOTE must index the newly created post; replies/quotes retain their related parent. A page-reported success/author/menu, restored provenance string or old string-only action API cannot issue an Admission. Keep fixture provenance visible and the live factory disabled until a real adapter is validated. Archive ownership confirmation does not verify a browser login; actor/current platform state and unsupported action times remain unknown. Ambiguous `RT @` text alone is not a confirmed repost.

Metadata backups are bounded plaintext (16 MiB / 20,000 records); validate exact schema/fields/FKs/checksum before restoring into a new scope. Schema-3 imports upgrade to v4 without invented actors or observation times. Checksums do not authenticate authorship. Backups exclude media files/tasks, browser storage and keys. Explicit 2–4-library consolidation uses a reviewed metadata snapshot within 8 MiB / 20,000 records, preserves sources/default and rejects conflicting legacy-action provenance. Later ordinary edits require another preview; erasure/epoch invalidation rejects commit. Deleting the new result provides rollback without deleting sources. Never call this a complete device/media recovery system.

Use one bounded controller for captures, imports, favorite changes, download completion and erasure. Its 32-library registry may retire idle writers, but tickets must bind the writer instance as well as its epoch; retirement must not revive stale work. Recheck invalidation at write time; old callbacks may not revive cleared records. Disable relevant observation before destructive operations and serialize the storage barrier. Share copies/leases must support receiver read lifetime while avoiding permanent whole-library exposure.

## Web, media and sharing safety

Never collect passwords, verification codes, exported Cookie/Token, DMs, drafts, hidden app state or arbitrary traffic responses. Exclude login/security/settings/composer/private-message and uncertain contexts, including SPA transitions. Do not impersonate browsers or copy external sessions to bypass provider restrictions.

X itself may use its normal network; no Xs archive cloud/analytics without explicit approval. Explicit sharing hands chosen content to another app, which can upload it; do not promise it remains on-device afterward.

Media resolution is scoped to explicit authorized post resources, not an arbitrary URL proxy. Validate destinations/redirects, filenames, type, size, disk capacity, retry/queue limits and permitted access. No DRM/payment/access-control bypass. Signed media URLs and authentication state must not leak into logs, captions or public evidence. Do not add a credential-export workaround when resolution fails.

The current video scope is a selected direct standard MP4, ≤100 MiB/10 minutes, one H.264 track and optionally one AAC track. Container/sample-range and transfer checks plus limited first/tail video/audio decode do not prove all-frame playback. HLS/DASH, blob URLs, fragments, DRM/encryption and unsupported formats remain rejected. Interrupted work requires explicit source reselection; do not claim verified resume or background scheduling. Cleanup has three independent default-off preferences and reversible packaged fixture rules; NativePageCleanupController is not attached to the live BrowserActivity.

Complete and validate downloads before marking/share. Resume only when verified; otherwise explain restart. FileProvider/equivalent exposes only selected completed assets or limited copies, never database/session/key/root directories or unfinished files; use temporary read-only grants. No automatic message sending, retry on chooser return, silent transcode/watermark or loss of the original on a target error.

SQLite is not currently claimed encrypted. Device-key reuse does not by itself protect FTS/WAL/backups. Data migration, exports, recovery and deletion require separate tests.

## Work order and proof

Follow XS-01–XS-15 and A–D batches, using Issue #4 as implementation tracker and Issue #3 for live browser acceptance. Offline observation/migration/media/share implementation proceeds alongside device checks. W0 is a real-site enablement/release gate, not a prohibition on useful offline work. Never enable unvalidated capture in a real-site build or call synthetic success live acceptance.

After relevant runtime edits:

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug :xs-runtime:lintDebug
gradle --no-daemon --no-parallel :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

Record source/APK hash, exact device/OS/WebView/recipient-app versions, MIME/size, test scenario and outcome. Keep NOT RUN distinct from PASS. Docs-only commits do not claim compilation or device tests. Build artifacts, an AAR or a synthetic harness are not production releases; no unsupported universal login/sharing/background/store claims.

Owner allows a custom-license direction; root LICENSE remains authoritative until separate finalization. Follow docs/LICENSING.md and a per-file rights/notice inventory. Do not invent commercial restrictions, revoke historical grants, erase third-party notices or call a restrictive custom license OSI open source.

Do not change the user's computer/servers, upload private content/keys, publish production binaries or deploy websites without an appropriate explicit request. Document real blockers without promising asynchronous work or inflating progress.
