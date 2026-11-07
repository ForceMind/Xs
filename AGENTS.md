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

- `src/main/java`: URL/v5 schema/query, CaptureSession, OwnActionSession, StrictJson, bounded SerialWriteCoordinator, image/standard-MP4 policy, metadata backup/merge codecs and legacy inactive observation gate.
- `src/android/java`: SQLite v5, app-owned library/favorites/own-activity UI, importer, native-pull/cleanup fixture controllers, shared writes, image/video tasks/assets, metadata portability/consolidation and grant-only sharing.
- `xs-runtime`: AAR. `dev-harness`: synthetic offline host, never a real client.
- `app-android`: retained System WebView, manual favorites/links, explicit image selection, outgoing sharing, incoming text target and synthetic instrumentation.
- Prior history/dwell/image proof remains in docs/IMPLEMENTATION_EVIDENCE.zh-CN.md. Historical source `4ee242e` passed CI 42357220689 (933 JVM assertions plus SQLite/DOM, builds and 146 API30/36 instrumentation cases); docs/ROUND_C_EVIDENCE.zh-CN.md describes that earlier v4 increment. Those are historical results, not validation of the current source.
- Previous source `58585977c90307cbf97d0aadafd818bcbdf88793` / tree `595dcb18016bf4bdf2f0e520dce1236ac5fc35a5` failed [CI68](https://github.com/ForceMind/Xs/actions/runs/42374634739): API26/30/36 executed 450 cases, with 415 passed/35 failed/0 errors/0 skipped. API27 failed to start any case because of the official image architecture and is counted separately. The next PR #5 fix commit and CI are pending generation; no PASS is claimed.
- The next matrix is API26/27/30/36 (x86 for 27, x86_64 otherwise); 52 harness + 100 client cases/API are declarations, not passes. New guest-reboot/boot-ID/serial checks, real /data pressure down to 28MiB on disposable CI-only 8GB AVDs (preserve originals/reject new tasks; finally remove only the created pressure file), and native streaming backup of ≥480MiB/30 actual assets remain NOT RUN. They do not establish physical-power-loss, live X/chat-app or production-key acceptance. Physical devices and recipient apps remain NOT RUN.
- Local checks passed 1,155 JVM assertions, SQLite/DOM/20k stress, and an SDK SQLite standard-FTS reproduction (old multiword query: 0 rows; corrected: 1), plus full Android builds and both lint checks (196 tasks, 17 seconds; runtime: 0 errors/20 warnings, client: 0 errors/12 warnings). The maximum 512MiB encrypted container was streamed and checked byte-for-byte in a 32MiB JVM; this does not validate near-limit Android asset restoration.
- CI debug certificates may vary by job. Do not promise in-place upgrades or ask users to uninstall data-bearing installations. Full resource backups now exist in source but need current execution evidence and a tested isolated restore. The manual signed-candidate workflow requires a reviewed exact main-ancestor commit, configured signing environment secrets and certificate pin. It does not publish a release. Production signing identity remains unconfigured.
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

Metadata backups remain bounded plaintext (16 MiB / 20,000 records); validate exact schema/fields/FKs/checksum before restoring into a new scope. Schema-3/4 imports upgrade to v5 without invented actors or observation times. Checksums do not authenticate authorship. Backups exclude media files/tasks, browser storage and keys. Explicit 2–4-library consolidation uses a reviewed metadata snapshot within 8 MiB / 20,000 records, preserves sources/default and rejects conflicting legacy-action provenance. Later ordinary edits require another preview; erasure/epoch invalidation rejects commit. Immediate undo is limited to the just-created, unchanged result without media/download tasks. Reject changed/media-bearing results without invalidating existing tasks; never delete sources or the default. Consolidation does not copy media. Passphrase-encrypted full backups are a separate path, not a device/session recovery system.

Use one bounded controller for captures, imports, favorite changes, download completion and erasure. Its 32-library registry may retire idle writers, but tickets must bind the writer instance as well as its epoch; retirement must not revive stale work. Recheck invalidation at write time; old callbacks may not revive cleared records. Disable relevant observation before destructive operations and serialize the storage barrier. Share copies/leases must support receiver read lifetime while avoiding permanent whole-library exposure.

## Web, media and sharing safety

Never collect passwords, verification codes, exported Cookie/Token, DMs, drafts, hidden app state or arbitrary traffic responses. Exclude login/security/settings/composer/private-message and uncertain contexts, including SPA transitions. Do not impersonate browsers or copy external sessions to bypass provider restrictions.

X itself may use its normal network; no Xs archive cloud/analytics without explicit approval. Explicit sharing hands chosen content to another app, which can upload it; do not promise it remains on-device afterward.

Media resolution is scoped to explicit authorized post resources, not an arbitrary URL proxy. Validate destinations/redirects, filenames, type, size, disk capacity, retry/queue limits and permitted access. No DRM/payment/access-control bypass. Signed media URLs and authentication state must not leak into logs, captions or public evidence. Do not add a credential-export workaround when resolution fails.

The current video scope is a selected direct standard MP4, ≤100 MiB/10 minutes, one H.264 track and optionally one AAC track. Container/sample-range and transfer checks plus limited first/tail video/audio decode do not prove all-frame playback. HLS/DASH, blob URLs, fragments, DRM/encryption and unsupported formats remain rejected. Pending URLs are sealed using AndroidKeystore AES-256-GCM with task/library/post/kind AAD; plaintext URLs never enter jobs, captions or evidence. JobScheduler tries user-initiated jobs for explicit API34+ requests, falls back to regular persisted jobs, and uses regular jobs on API26–33. At most three network attempts per explicit run (initial plus two automatic retries for narrow transient errors), each from byte zero. A system stop is respected; startup does not blindly reschedule missing jobs. Persisted jobs can run when Android allows them; PAUSED/FAILED saved sources may be explicitly retried, canceled secrets are removed. Do not claim range resume, timely completion or immunity to force stop/quota/network restrictions. Cleanup has three independent default-off preferences and reversible packaged fixture rules; NativePageCleanupController is not attached to the live BrowserActivity.

Complete and validate downloads before marking/share. Resume only when verified; otherwise explain restart. FileProvider/equivalent exposes only selected completed assets or limited copies, never database/session/key/root directories or unfinished files; use temporary read-only grants. No automatic message sending, retry on chooser return, silent transcode/watermark or loss of the original on a target error.

SQLite/FTS/WAL is not encrypted. The separate full backup format uses 64KiB AES-256-GCM chunks, a passphrase-derived key (PBKDF2-HMAC-SHA256, 600,000 iterations), authenticated framing/final empty chunk/EOF, and a bounded payload of 512MiB / 500 completed assets. The nested metadata limit stays 16MiB / 20,000 records; image/video limits remain 20/100MiB. Passphrases are 8–1024 characters, never persisted; forgetting one prevents recovery. Complete authentication precedes private staging/preview, followed by metadata/FK/hash/media validation. Restore only into a new scope: media first and registered archive last; durable markers recover unpublished targets after process death. Exclude tasks, candidate URLs, keys, browser state and share leases. Do not conflate this with encryption at rest or a tested whole-device recovery.

Snapshot v5 keeps all accepted versions with source, capture time and LINK_ONLY/PARTIAL/COMPLETE/LEGACY_UNKNOWN quality. COMPLETE means the accepted visible DOM body, not a claim to hidden/server-full text. Native detail evidence may issue COMPLETE; feed/favorite legacy claims cannot. SnapshotPolicy may select a newer, shorter COMPLETE version; PARTIAL cannot replace COMPLETE. Historical text search still displays current text; browsing an old version never changes the current pointer. Merge preserves source/default scopes and all versions, applying SnapshotPolicy instead of longest-text-only selection.

Retention defaults to forever, optionally 7/30/90/365 days. Prune only event details; preserve posts/snapshots/favorites/own activity/media and cumulative/latest open/dwell facts. A previous open remains excluded from never-opened queries until explicit history clear. Maintenance is lazy on library/management entry or manual action, not scheduled background deletion. Retention uses the ordinary shared writer, with policy/cutoff rechecked inside the actual SQLite transaction; do not invalidate in-flight media tickets for pruning. Show known lifetime opens as a lower bound, summing each post’s maximum of its stored count and retained open-detail count; do not double count them. Keep this separate from retained event counts; overlapping exposure lower-bound sums are not reading time.

Per-library media limits default to unlimited. Include completed bytes and active reservations, conservatively 20MiB/image or 100MiB/video for unknown length. If lowering the limit leaves completed plus reserved bytes over budget, stop/fail all unfinished tasks and remove partial files/jobs while retaining sealed sources for explicit retry. Never delete completed files to meet a quota. The native management UI previews/confirms retention and confirms limit changes.

Whole-library deletion durably records a tombstone before the shared deletion barrier, revokes old/future ordinary tickets, rotates only a deleted default UUID, purges scoped metadata/media and leaves failed cleanup pending for explicit/startup retry. Archive mutation APIs recheck deleted state after acquiring the SQLite write transaction; media batch helpers require the same shared writer. Preserve other scopes, browser sessions and the 24-hour lifetime of already-created receiver copies. Clear history/unfavorite/selected-asset deletion remain independent operations. Qualified feed duration writes coalesce to one-second intervals and stop after the ten-minute lower-bound cap; paused packaged observers stop their JS timers. Synthetic stress evidence is not real-device power or multi-day reliability.

## Work order and proof

Follow XS-01–XS-15 and A–D batches, using Issue #4 as implementation tracker and Issue #3 for live browser acceptance. Offline observation/migration/media/share implementation proceeds alongside device checks. W0 is a real-site enablement/release gate, not a prohibition on useful offline work. Never enable unvalidated capture in a real-site build or call synthetic success live acceptance.

After relevant runtime edits:

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug :xs-runtime:lintDebug
gradle --no-daemon --no-parallel :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

Record source/APK hash, exact device/OS/WebView/recipient-app versions, MIME/size, test scenario and outcome. Independent-UID receiver fixtures test Android grants, not chat-app delivery. External emulator force-stop/new-process and same-job version3→4 debug upgrades are cloud-testable; they do not prove physical power loss, production-key or cross-schema upgrades. Do not label an in-process repository restart as an OS process kill. Keep NOT RUN distinct from PASS. Docs-only commits do not claim compilation or device tests. Build artifacts, an AAR or a synthetic harness are not production releases; no unsupported universal login/sharing/background/store claims.

Owner allows a custom-license direction; root LICENSE remains authoritative until separate finalization. Follow docs/LICENSING.md and a per-file rights/notice inventory. Do not invent commercial restrictions, revoke historical grants, erase third-party notices or call a restrictive custom license OSI open source.

Do not change the user's computer/servers, upload private content/keys, publish production binaries or deploy websites without an appropriate explicit request. Document real blockers without promising asynchronous work or inflating progress.
