# AGENTS.md — Xs engineering and continuation contract

## Read first

Xs is an independent Web-first X experience with an app-owned local library. Start with [docs/README.md](docs/README.md), [product](docs/product.md), [implementation plan](docs/IMPLEMENTATION_PLAN.zh-CN.md), [architecture](docs/architecture.md), [reuse review](docs/CHATGPT_STUDIO_REUSE.zh-CN.md) and [acceptance matrix](docs/ACCEPTANCE_MATRIX.zh-CN.md).

ADR-0002 defines app-owned data, immediate opens, favorites/media/sharing. ADR-0003 defines reuse/native-pull and parallel execution. They override conflicting ownership, trigger or implementation-order assumptions in the older Web-first plan and ADR-0001. Keep earlier safeguards where not superseded. Old APK contracts/allowlist are history, not current prerequisites.

Keep this public independent repository and synchronized Chinese/English READMEs. Do not repackage the official APK, build a new social backend/account service/API proxy, deploy a site, or substitute a manual link organizer for the core product.

## Requirements that must not drift

- After explicit opt-in, deliberate entry into a valid post detail creates OPEN_POST immediately: **no dwell threshold or extra save tap**. Save LINK_ONLY if body is pending and enrich later. One native navigation identity, not one render/message, means one open. Failed navigation/prefetch/reload/rotation/restoration are not new deliberate opens.
- Optional timeline dwell is a first-class **no-click history** requirement (see docs/FEED_DWELL_PLAN.zh-CN.md / ADR-0004), separate from immediate OPEN_POST and FAVORITE. OFF by default; 1.2s/3s (recommended default)/5s user-selectable continuous dwell, with 60% normalized readable-area baseline and long-card, overlay, switch/background boundaries. Store duration/provenance, allow `dwell >= N AND never opened` local search. One continuous exposure = one event; updates/DOM rebind do not create duplicates, real re-entry may be another exposure. Exposing a post is NOT proof of reading. XS-14 baseline moves into **early batch B**; extensive optimization stays D. Current runtime does not yet implement the new selector/measurement/search.
- Confirmed own repost/quote/post/reply activity is separate from viewing a repost. Menu click, cancellation, failed submit or someone else's action is not success. Preserve unknown actor/time and reversal state.
- “Favorite in Xs” and categories/tags/notes are app-local. Never implicitly call X bookmarks/likes/reposts. Repeated favorites are idempotent; category choice may follow successful save.
- History, favorites and assets belong to the native library, not the current X login. X logout/account switch invalidates old observation context but must not delete or silently choose another library.
- Implement image/image-set/video downloads, local asset management and **outbound actual-file sharing**. Incoming Save-to-Xs alone does not satisfy sharing. Manifests, blob URLs and segments are not complete videos.
- WeChat/QQ/Feishu/Telegram are recipient compatibility targets. Users choose application, conversation and sending; no contact/token harvesting or accessibility automation. Chooser callbacks are not delivery receipts.
- Unfavorite, clear history, delete media, clear cache, clear web session and delete library have different scopes. Cache cleanup never deletes user downloads; favorites do not silently download every video.

## Existing versus planned

- `src/main/java`: Java URL/query/schema/capture policy and inactive observation gate.
- `src/android/java`: SQLite, native archive UI, one-part posts-JS importer, manual local space and dormant parser/bridge foundations.
- `xs-runtime`: AAR. `dev-harness`: synthetic offline host, never a real client.
- `app-android`: W0 System WebView host, manual link save and incoming text-share target.
- Automatic live capture, full FAVORITE snapshots/UI, app-library migration, downloads/assets/outbound provider and filtering remain implementation work unless a later commit includes verification.

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

Use one bounded controller for captures, imports, favorite changes, download completion and erasure. Recheck invalidation at write time; old callbacks may not revive cleared records. Disable relevant observation before destructive operations and serialize the storage barrier. Share copies/leases must support receiver read lifetime while avoiding permanent whole-library exposure.

## Web, media and sharing safety

Never collect passwords, verification codes, exported Cookie/Token, DMs, drafts, hidden app state or arbitrary traffic responses. Exclude login/security/settings/composer/private-message and uncertain contexts, including SPA transitions. Do not impersonate browsers or copy external sessions to bypass provider restrictions.

X itself may use its normal network; no Xs archive cloud/analytics without explicit approval. Explicit sharing hands chosen content to another app, which can upload it; do not promise it remains on-device afterward.

Media resolution is scoped to explicit authorized post resources, not an arbitrary URL proxy. Validate destinations/redirects, filenames, type, size, disk capacity, retry/queue limits and permitted access. No DRM/payment/access-control bypass. Signed media URLs and authentication state must not leak into logs, captions or public evidence. Do not add a credential-export workaround when resolution fails.

Complete and validate downloads before marking/share. Resume only when verified; otherwise explain restart. FileProvider/equivalent exposes only selected completed assets or limited copies, never database/session/key/root directories or unfinished files; use temporary read-only grants. No automatic message sending, retry on chooser return, silent transcode/watermark or loss of the original on a target error.

SQLite is not currently claimed encrypted. Device-key reuse does not by itself protect FTS/WAL/backups. Data migration, exports, recovery and deletion require separate tests.

## Work order and proof

Follow XS-01–XS-15 and A–D batches, using Issue #4 as implementation tracker and Issue #3 for live browser acceptance. Offline observation/migration/media/share implementation proceeds alongside device checks. W0 is a real-site enablement/release gate, not a prohibition on useful offline work. Never enable unvalidated capture in a real-site build or call synthetic success live acceptance.

After relevant runtime edits:

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

Record source/APK hash, exact device/OS/WebView/recipient-app versions, MIME/size, test scenario and outcome. Keep NOT RUN distinct from PASS. Docs-only commits do not claim compilation or device tests. Build artifacts, an AAR or a synthetic harness are not production releases; no unsupported universal login/sharing/background/store claims.

Owner allows a custom-license direction; root LICENSE remains authoritative until separate finalization. Follow docs/LICENSING.md and a per-file rights/notice inventory. Do not invent commercial restrictions, revoke historical grants, erase third-party notices or call a restrictive custom license OSI open source.

Do not change the user's computer/servers, upload private content/keys, publish production binaries or deploy websites without an appropriate explicit request. Document real blockers without promising asynchronous work or inflating progress.
