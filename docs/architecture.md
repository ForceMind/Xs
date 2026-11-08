# Xs architecture — app-owned library and native-pull plan

## Planned XS-16 temporary timeline boundary

[ADR-0005](adr/0005-timeline-buffer.md) and the [buffer specification](TIMELINE_BUFFER_PLAN.zh-CN.md) add a separate opt-in recent-timeline store (default 48 hours, app-wide 100 MiB). This is a design, not existing runtime. Temporary snapshots/FTS, expiry and disk accounting must not enter the Archive v5 lifetime-retention path or existing backups. Reuse library ownership, bounded native-pull validation and shared write barriers; add a buffer generation without unnecessarily revoking media tickets. Durable promotion needs stable idempotency and an archive-first commit, not a fictitious cross-database atomic transaction. Preserve v5 data; any necessary archive-format change requires an explicit preserving migration and codec/import/consolidation regressions. No raw traffic interception, hidden-state capture or live enablement is authorized by this plan.

First fix [reviewed feed-source classification](https://github.com/ForceMind/Xs/pull/5#discussion_r4504042361) before reusing it for the new batches. CI83 passed the old source; it did not establish this bug was absent or test XS-16.


Round E source `2c667517e5a812232a8b41e7a3f99166855963e6` / tested tree `e52dfdd3f5f7b51751154bf1cf2601554786ef33` passed [CI82](https://github.com/ForceMind/Xs/actions/runs/42419306946): 1,287 JVM assertions, 23 host checks, Android builds and both lint checks, and 624/624 main cases across API26/27/30/36, with 0 failures, errors or skips. BrowserInteraction passed 24/24 and actual old-APK upgrades passed 4/4. Separately, 20/20 independent drivers and 44 native phases passed in this same-source fresh attempt1; repeated probes are excluded from main XML. Exact merge and package provenance and the actual CI76–81 failures remain in [Round E evidence](ROUND_E_EVIDENCE.zh-CN.md); D/CI75 is historical.

Native cases verified real SAF selection of the 5,783-byte synthetic MP4, complete FileReader bytes/hash and playback to ended after a user gesture; two new PNGs chosen through real system multi-select with per-file set checks; and rejection of old results after a sensitive SPA route returns HOME. The actual fixed old-main f88003c8ec1ee193b75de7267db6ae7654ee77c4 code1/schema2→current3/schema5 upgrade preserved UID, DB path, default UUID, original rows, categories, FTS and a generated file. Default LegacyUpgradeProbe is only a current-version round trip; the real old-APK upgrade is counted as a separate driver.

Upload MIME is a provider declaration, not proof of actual contents, completeness or upload to X. The 20 MiB image and 100 MiB video download limits do not apply to uploads. No new permissions, native whole-file reads or transcoding are added. Old versions have no independent favorites/media database, so those migrations are N/A; a generated file canary proves file preservation only. Live X/CDN/chat-app/physical-device/production-signing and disabled-live-adapter boundaries remain unchanged.

当前v5与可靠性源码见 [本轮证据](ROUND_D_EVIDENCE.zh-CN.md)，CI74云端回归全部通过；现场总体验收仍NOT RUN。历史 [阶段C](ROUND_C_EVIDENCE.zh-CN.md) 与 [A/early-B](IMPLEMENTATION_EVIDENCE.zh-CN.md) 报告不覆盖新代码；下文区分实现和未启用的现场目标。

Updated 2026-11-07. Read [product](product.md), [ADR-0002](adr/0002-local-library-media.md), [ADR-0003](adr/0003-native-pull-delivery.md), [implementation](IMPLEMENTATION_PLAN.zh-CN.md) and [acceptance](ACCEPTANCE_MATRIX.zh-CN.md). This describes the target and names existing foundations separately; it does not claim live integration.

Verified Round D source `0d193e4689d649970d9705818d33e8706b470d8f` / tested tree `c8551cd4a09e4466cd84e7973039aba06a5f87b2`: all six [CI74](https://github.com/ForceMind/Xs/actions/runs/42386764024) jobs succeeded. The 1,155 JVM assertions, builds/both lint checks and 608/608 main API26/27/30/36 cases passed, with 0 failures/errors/skips. Independent force-stop, same-job/same-source debug3→4 upgrades, guest reboot and real low-space drivers each passed 4/4, totaling 16/16; they are not added to the 608 main XML cases.

Low-space assertions use the ordinary app’s original UID and actual available bytes, verifying new-task rejection, preserved original files/hashes/offline search and pressure-file cleanup. Only the disposable CI old-userdebug allocator temporarily uses root after strict environment/current-quota/UID gates; final deletion, unroot and restored shell UID were verified. Ten host failure/safety-gate tests passed separately. Complete cases, identities/package digests and CI66–73 failures remain in the evidence; this is not physical power loss, rooting a user phone or unlimited-background assurance.


CI74 actually passed the core/SQLite/DOM stress and Android build checks. Cloud fixture coverage includes actual native SAF export/restore, browser SAF PNG selection, read-only URI/68-byte FileReader reads and gesture windows, independent-UID grant/denial/delayed-read checks, 320dp/1.8-font Chinese/English light/dark configurations, native restore of 500 tiny assets and about 481MiB/30 actual files, plus a 512MiB container checked byte-for-byte in a 32MiB JVM. These scopes are recorded separately and do not establish live-X, physical-device or chat-app acceptance.

## 1. Runtime and target boundaries

```mermaid
flowchart TD
  W["System WebView"] -->|"explicit native selection"| M["Validated images / direct MP4"]
  F["Offline WebView fixtures"] -->|"bounded native pull"| Q["Library queue / generation"]
  Q --> D["SQLite v5 / FTS / versions / favorites"]
  M --> A["Saved assets / read-only share leases"]
```

`app-android` hosts retained System WebView with manual link favorites, selected-post media downloads and outbound file sharing. `xs-runtime` provides library storage/UI, fixtures, strict backups and explicit metadata consolidation. `dev-harness` is synthetic and offline. The packaged observer, reversible cleanup and own-action admission have no enabled real-X adapter; diagrams do not grant those privileges. Media support is bounded standard H.264/AAC MP4, with container/sample validation and representative decoding, not all-format or whole-film decode certification.

## 2. Browser engineering and reuse

Use reviewed ChatGPT Studio patterns for page ownership independent of Activity, Profile binding before web use, controlled child windows, navigation categories, context reattachment and invalidated callbacks. See the [pinned reuse manifest](CHATGPT_STUDIO_REUSE.zh-CN.md). Do not import all chat workflow features, authentication domains, payloads or signing identity.

Start with one active web session. Runtime capability checks, not OS version assumptions, determine optional profile support. A local library is not a verified X account or a proxy. Controlled authentication navigation must not gain observation privileges. Keep normal browsing available when enhancement is disabled.

Page retention is not capture permission: background/hidden/covered views stop observation timing. Do not rely on foreground-service hacks, fake audio or unbounded retained tabs. Process death means reloading the website; local history is what remains available offline.

Cache clearing and web-session clearing are separate confirmed native operations. Session clear destroys the old DOM and clears Cookie/WebStorage/HTTP-auth/form state while preserving native libraries/assets/leases. Persistent session epochs/pending state and upload identities reject stale callbacks and old saved navigation. Real SAF PNG selection, read-only URI/68-byte FileReader reads and touch-generated window.open fixtures are implemented; execution results belong to their exact source-specific CI. These real native fixture interactions passed in CI74 on all four APIs. Round E CI82 additionally exercised synthetic MP4 selection/FileReader/local playback and two-PNG selection; this does not prove a real-server upload. Download playback/cross-UID sharing remain separate cases. Actual X authentication/upload remains unverified.

BrowserUploadPolicy accepts only supported media declarations and open/open-multiple modes. The same immutable policy sets picker filters and checks returned provider MIME/count/duplicate URIs, preserving one file for single mode or up to 8 for multiple mode. URI ownership, requesting WebView, exact page, native epoch and current permitted route remain native checks. doUpdateVisitedHistory cancels pending uploads on any URL change, including sensitive-route-and-back SPA navigation; result/cancel paths detach old state before reentrant callbacks. MIME filtering does not inspect or authenticate bytes and is independent of saved-download size/type validation. Round E CI82 verified MP4/FileReader/local playback, multi-PNG and sensitive-SPA rejection; prior D browser proof remains PNG68-byte FileReader only. No real server upload occurred.

## 3. Native-pull observation contract

Prefer local scripts packaged with the signed app and native-controlled calls that retrieve finite event batches/snapshots. Native code controls the target WebView, allowed current route/origin, foreground/overlay state, consent, library generation and navigation reason. Do not expose a general Java object or any SQL/path/Intent/export/delete operation to pages.

Returned JSON remains untrusted: same-origin scripts/DOM can affect content, and a random window key/token is not identity authentication. Validate both before queuing and immediately before persistence. Reject stale document/navigation/space callbacks, oversized payloads, unknown fields/commands and sensitive contexts. Review strict parser behavior, duplicate keys, trailing content, nesting, byte limits and unsupported numeric forms before activation; a JSONObject constructor alone is not a security proof.

Current limits in the older policy are starting budgets, not performance results: <=64 KiB envelope, <=16,000 body characters, <=100 author characters, 20 accepted observations/second with a burst budget of 40. Define a bounded pending queue (initial target <=128 events) and overload pause/recovery behavior during implementation. Invalid-message floods must also be bounded before expensive parsing. No unbounded executor is accepted as the final write controller.

Rapid open-and-return must not be missed by polling the current URL every few seconds. Maintain bounded page observations and native navigation identity with tested flush/transition behavior. Refresh, back/forward, reload, restored state and actual deliberate new entry have distinct reasons. Content updates enrich a post; they are not new reading events.

The [existing inactive observation envelope](contracts/observation-v1.md) is a starting point. A future origin-scoped listener is optional, not required to ship the first native-pull loop; it must preserve all current permissions and non-sensitive-route checks.

## 4. Library ownership and preserving migration

Default library belongs to the application, not to the active X login. Distinguish:

- `library_id`: native local data ownership; optional private libraries may be user-created later.
- `browser_profile_id` / session context: browser cookies and website storage; never exported with the library.
- `actor_hint` / provenance: optional metadata for confirmed own social actions, not library ownership.

A website account switch invalidates observation context and unconfirmed social attribution; it does not delete or implicitly switch local favorites/assets. Do not pass a local UUID or scraped handle to the legacy `bindAuthenticatedAccount` as if authenticated.

SQLite v5 preserves v1–v4 namespaces, library mappings, counts, times, category links and FTS. Separate own-action and action-event tables retain actor provenance, unknown time, related posts and reversal state. Favorites and local media/share leases remain independent. `snapshot_versions` retains accepted text versions and `posts.current_snapshot_key` identifies the current one; historical-text search is optional and returns current-post rows, with a separate read-only version browser. Its panel retains at most 50 rows, with previous/next pages and debounced version-text search; bodies load only after selection. Paged browsing reaches versions beyond the newest 200 without accumulating unbounded UI rows. Explicit consolidation creates another space and preserves all sources; failed transactions keep original data. Browser sessions and library ownership are separate.


## 5. Event and snapshot semantics

| Record | Meaning |
| --- | --- |
| OPEN_POST | Opted-in deliberate entry into a valid detail context; no dwell delay. LINK_ONLY may be stored immediately, then enriched. |
| FEED_VISIBLE | Independent, opt-in **timeline exposure without opening the post**, 1.2s/3s (default)/5s continuous visible dwell, normalized readable area and measured duration. Not proof of reading and does not increase opened_count. |
| Own action | Confirmed own post/reply/quote/repost or provenance-checked import; cancellation/failure/seeing a repost is not success. |
| FAVORITE | Xs-native relationship and local time, independent of X server bookmarks/likes. |
| LOCAL_SAVE | Existing manual link import/save; retained as distinct legacy semantics, not silently inflated into reading. |

One navigation ID admits at most one open per library/context; increasing message sequences are not sufficient. Original publication time, action time, open time and feed time remain separate, with unknown values preserved. Snapshot quality is LINK_ONLY/PARTIAL/COMPLETE/LEGACY_UNKNOWN with explicit source/capture time. COMPLETE establishes only accepted visible DOM text, never hidden/server-full content. Native detail evidence controls that quality; a newer shorter COMPLETE snapshot can become current, while PARTIAL cannot overwrite COMPLETE. Preserve all prior versions and use SnapshotPolicy for consolidation; version browsing never switches the current pointer.

### Retention and statistics

Per-library history retention defaults to forever, optionally 7/30/90/365 days. Only expired event details (event time, not publication time) are pruned; posts, versions, favorites, own actions, media and cumulative/latest open/dwell facts remain. Never-opened queries therefore retain the deliberate-open fact. Explicit Clear history separately resets those facts. Retention is lazy on library/management entry or manual cleanup, not a timed background promise. Changes have a count preview/confirmation. Ordinary shared-queue execution rechecks policy/cutoff inside the SQLite write lock without invalidating media tickets.

HistoryStats reads one scoped SQLite snapshot and separates known lifetime open lower bounds/ever-opened posts from retained open/feed counts. Known lifetime opens sum MAX(stored cumulative count, retained OPEN detail count) per post, so legacy counter-zero/detail-present records are visible without adding both counts. Duration sums/maxima represent retained exposure lower bounds; exposures can overlap and do not establish reading time/completion. Management controls use a bounded worker; 320dp/1.8-font Chinese/English/light/dark layout cases passed using isolated Configuration contexts; they do not establish physical-device acceptance.

### Feed dwell design target (XS-14 baseline moves into B)

See [feed dwell specification](FEED_DWELL_PLAN.zh-CN.md) / [ADR-0004](adr/0004-feed-dwell.md). A locally packaged X-specific observer will verify actual timeline cards, foreground visibility and readable-area ratio using bounded page observation; native code owns selected library, feature consent, current route, generation and monotonic timing. Thresholds are **1.2, 3 (recommended default), 5 seconds**; no threshold applies to deliberate OPEN_POST.

Each continuous qualified exposure uses a stable `exposure_id`, persists FEED_VISIBLE separately from opening/favoriting and updates the same event duration as it continues. A real subsequent exposure may form a new event. The duration/threshold/quality fields and v5 preserving migration are implemented for fixtures; live DOM adaptation remains pending. Offline filters must support duration range and “never opened” in the local library without inferring server read status. Long cards normalize against actually readable viewport content; background, overlay, sensitive route and idle constraints require test fixtures and real-device evidence. Native preferences implement the three thresholds and SQLite indexes measured duration separately. Duration is a sampled lower bound: qualified updates coalesce to one-second intervals, with one final capped lower bound at ten minutes and no repeated cap writes. Pausing the packaged observer clears its JS timer; resuming creates fresh timing context. Synthetic DOM/heap stress does not establish actual battery use or multi-day stability.


## 6. Writes, deletion and invalidation

All local writes, import batches, favorite changes, asset completion and destructive operations go through a unified native controller with bounded work. Queued units carry native library/document/generation identity; a clear or cancellation advances invalidation before a storage transaction. Recheck at commit time so a late callback cannot restore deleted records.

Separate unfavorite, history clear, asset deletion, cache clear, browser logout/session clear and complete library deletion. Favoriting must not request an X server mutation. Importing never invents opens. Whole-library deletion persists a tombstone before its queue barrier, blocks ordinary old/new tickets, and checks revocation inside archive database write transactions. Media batch helpers require that same shared library writer. Only a deleted default UUID rotates. Erase scoped media before metadata; preserve a failed cleanup as pending and retry it at startup or from the management UI. Other scopes/browser sessions and existing share leases remain. Deletion is not undone by restored callbacks or stale intents.

## 7. Media and asset pipeline

Resolve only explicitly selected media from permitted post contexts. Resolver output is an untrusted candidate, not an arbitrary network proxy request. Validate HTTPS destinations/redirects, resource type, bounds, generated filenames, disk quota and retry budget; reject local/private-network probing and unsafe paths. No DRM, paywall or authorization bypass. Avoid generic network-response interception and credential logs.

Download images and available complete videos first. `blob:` identifiers, manifests and fragments are not completed MP4s. If permitted HLS/DASH is actually needed, test resolution, assembly, audio/video synchronization and shareable output separately. Lack of an allowed media source is an explicit unsupported/failure result, not permission to broaden extraction.

Write temporary data, verify actual type/completeness/playback, then atomically register the asset. Resume only with verified range/object consistency; otherwise retry from the start explicitly. Keep user downloads separate from cache. JobScheduler provides actual persisted task lifetime: explicit API34+ requests first try user-initiated transfer jobs, then regular jobs if refused; API26–33 use regular jobs. Network/storage constraints and exponential backoff remain under Android control. Candidate URLs are AndroidKeystore AES-256-GCM ciphertext bound by AAD to task/library/canonical post/kind; job extras hold only task/library identities. Automatic transient retries are at most three total attempts per explicit run, each from byte zero. onStopJob preserves a paused task and respects OS rescheduling. Startup keeps a missing job paused for explicit retry instead of bypassing user/system stop. Completion/cancel clears the sealed candidate; no range-resume or prompt-background guarantee.

Assets are separate from posts with stable local IDs, content hashes, MIME, size/duration/dimensions, source-post links, local timestamps, state and categories. Source-page disappearance does not delete completed local files. User export to Photos/Files is separate from private app storage and never implicit.

Per-library media quotas default to unlimited and account for completed bytes plus active reservations. Unknown lengths conservatively reserve 20MiB/image or 100MiB/video; validated lengths can reduce that reservation. Native controls offer unlimited/100MiB/500MiB/1GiB and preserve a current custom value. Lowering below completed+reserved stops/fails all unfinished tasks, clears parts/jobs and preserves sealed candidates for later explicit retry; it never deletes completed files. Quota changes use the same shared writer/SQLite lock and roll back failed durable preference commits.

## 8. Outbound sharing

Native ShareCoordinator handles canonical links, explicit text with source, and selected actual completed media. Use ACTION_SEND/ACTION_SEND_MULTIPLE, accurate MIME, content URI/ClipData and temporary read grants. Provider exposure is restricted to chosen shareable assets/copies, never root/library databases, credentials, keys or unfinished files. No write grants.

User selects installed app, conversation and sending. WeChat/QQ/Feishu/Telegram are compatibility targets, not token/SDK/contact integrations. Direct conversation shortcuts depend on the recipient. A chooser completion cannot prove sending/delivery and must not trigger automatic retry.

Protect asynchronous receiver reads with a bounded share copy/lease and documented cleanup; deleting the source or clearing cache must not immediately break an active share. Sharing intentionally passes selected content to another app; no Xs upload service is used, but the receiver may upload it. Preserve originals on target/format/size failure; offer explicit alternatives, not silent quality loss.

An independent-UID fixture receiver now exercises actual Intent/ClipData read grants, rejection of ungranted/unselected/write/database access, and first delayed hash-verified reads after deletion while its Activity remains alive. This tests Android grant boundaries, not WeChat/QQ/Feishu/Telegram. The five CrossUidSharingTest cases passed on each API in CI74; receiver URI grants across reboot are outside this test.

## 9. UI and release boundaries

Preserve X web navigation. Add minimal native favorite/download/share/library actions. Native library provides history, favorites, own content and assets with search/category/time filters. Posts support batch favorites/categories/tag append-or-replace/unfavorite (up to 200); asset search is paged and filtered by keyword/type/state, with selection/category/delete/share and single-file or multi-file ZIP export (up to 200MiB). Only completed actual files export/share; these mutations use shared library writers. Optional page-card actions and reversible styles must not block the native path or alter login/security/composers.

The real browser does not attach live automatic open/dwell, own-action or cleanup adapters; preferences cannot turn fixture capability into field acceptance. Actual X login/DOM/media-CDN behavior, WeChat/QQ/Feishu/Telegram reception, vendor background/multi-day power, physical power loss, production-key upgrades and real personal-data migrations still need independent evidence. A custom-license switch is optional and requires separate rights/scope/terms review; the current GPL, historical grants and third-party obligations remain effective. Capture and filtering start off and have separate consent; cache is not durable saved media, and SQLite is not currently claimed encrypted. Reuse of device-key patterns does not encrypt existing FTS/WAL/backups automatically. Live login, target-device media, receiver apps, migrations, backups, signing and licensing need their own evidence.

[Roadmap](roadmap.md) and [acceptance matrix](ACCEPTANCE_MATRIX.zh-CN.md) define stage completion. W0 gates enabling unvalidated real-site automation and release, not parallel offline development. Legacy APK integration contracts are retained history. No production release, website deployment or modification of the user's machines is authorized by this plan.

## 10. Explicit portability and consolidation

Metadata JSONL is bounded to 16 MiB / 20,000 records, schema 3/4/5, strict UTF-8/types/columns/references and a corruption checksum. Import creates a new UUID scope in one transaction, checks FKs and FTS, preserves sources/default, and never grants a browser identity from stored claims. Files are plaintext; media/downloads/web sessions/keys are excluded.

Consolidation snapshots 2–4 registered sources through shared queues, validates an immutable preview, and copies that exact snapshot into a new scope after confirmation. Total 8 MiB / 20,000 source records; normal edits after preview require another preview, erasure invalidates it. Conflicting legacy action provenance or overflowing notes abort rather than discard content. Idle writer recycling uses instance-bound epoch tickets; all-busy capacity failure occurs before target rows are published. See evidence for policies and limitations.


### Passphrase-encrypted resource backups

This is a separate format from plaintext metadata JSONL. `EncryptedLibraryBackup` frames bounded metadata and completed media by ordinal, descriptor, length and SHA-256; filenames never act as restore paths. AES-256-GCM authenticates 64KiB chunks, header/ordinal AAD, a final empty chunk and EOF. PBKDF2-HMAC-SHA256 uses 600,000 iterations and a random salt; a fresh nonce base is used per backup. The 8–1024-character user passphrase is not persisted, and forgetting it prevents recovery.

The plaintext payload is at most 512MiB / 500 completed assets, including metadata ≤16MiB / 20,000 records; images remain ≤20MiB and videos ≤100MiB. Overflow rejects the operation rather than truncating content. Tasks, pending URL ciphertext/candidates, browser Profiles/sessions, keys and share leases are excluded. All authentication completes into private staging before payload parsing and preview; strict schema/FK/source checks plus per-resource hash and native image/MP4 checks precede commit.

Restore allocates a new UUID and never overwrites a source/default. Media files and directory state commit first; archive registration commits last. Private durable pending markers allow startup to discard an unregistered target after interruption while preserving a registered result. This does not encrypt the working SQLite/FTS/WAL or establish whole-device recovery. Dedicated core/Android/SAF UI evidence belongs to the current source, not the earlier metadata-only CI. The maximum 536,870,912-byte container has passed streaming encryption/decryption and byte-for-byte checking under a 32MiB JVM; native restore of approximately 481MiB/30 real files and a separate500-tiny-asset case also passed. Physical power-loss recovery remains unverified. Marker retirement requires a complete real WAL checkpoint, or the supported rollback-journal durability level; busy/incomplete checkpoints retain the marker for retry.

### Candidate signing boundary

The manual `xs-signed-candidate` workflow takes an exact reviewed commit on main ancestry, configured environment secrets and a pinned certificate SHA-256. It builds/verifies an internal candidate and provenance artifact without publishing a GitHub Release/store build. External ADB drivers now prove a live target PID, force-stop, process absence and a different recovery PID; a separate same-job debug-certificate version3→4 install driver is implemented. Both passed on all four APIs in CI74. Separate guest-reboot probes verified changed boot IDs and recovered native metadata/files/Keystore/app-owned lease data; this does not establish recipient URI grants surviving reboot, physical power loss, production-key or cross-schema migration. Production key/certificate configuration is absent; physical upgrade/rollback and release acceptance remain open. The root GPL-3.0 license and third-party obligations remain effective. A custom-license switch is optional and would require separate rights/scope/terms review; no new custom text is needed to continue under GPL.
