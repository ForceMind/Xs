# Xs architecture — app-owned library and native-pull plan

Updated 2026-11-06. Read [product](product.md), [ADR-0002](adr/0002-local-library-media.md), [ADR-0003](adr/0003-native-pull-delivery.md), [implementation](IMPLEMENTATION_PLAN.zh-CN.md) and [acceptance](ACCEPTANCE_MATRIX.zh-CN.md). This describes the target and names existing foundations separately; it does not claim live integration.

## 1. Runtime and target boundaries

```text
Official X web in the Xs Android browser
  ├─ BrowserHost / controlled navigation / browser Profile [host exists; refactor planned]
  └─ packaged X-specific observer and reversible styles [planned]
       ↑ native-initiated evaluateJavascript; finite batch/snapshot result
       ↓ untrusted observations, no archive-query/command interface
NativePageController + consent/context/schema/budget checks [planned]
       ↓ library generation + document ID + navigation ID + receipt clock
Unified bounded CaptureCoordinator [planned]
  ├─ SQLite / FTS / snapshots / events / favorites / categories [foundation exists]
  ├─ explicit MediaResolver → DownloadCoordinator → AssetRepository [planned]
  └─ native ShareCoordinator → selected completed files → Android Sharesheet [planned]
```

`app-android` currently hosts a system WebView with manual link save and incoming text sharing. `xs-runtime` contains archive UI/storage/import. `dev-harness` is a synthetic offline host, not a working X client. ObservationGate/Routes/WebObservationParser are inactive foundations. Automatic webpage history, native FAVORITE workflow, media downloader and outbound file provider are not made real by this diagram.

## 2. Browser engineering and reuse

Use reviewed ChatGPT Studio patterns for page ownership independent of Activity, Profile binding before web use, controlled child windows, navigation categories, context reattachment and invalidated callbacks. See the [pinned reuse manifest](CHATGPT_STUDIO_REUSE.zh-CN.md). Do not import all chat workflow features, authentication domains, payloads or signing identity.

Start with one active web session. Runtime capability checks, not OS version assumptions, determine optional profile support. A local library is not a verified X account or a proxy. Controlled authentication navigation must not gain observation privileges. Keep normal browsing available when enhancement is disabled.

Page retention is not capture permission: background/hidden/covered views stop observation timing. Do not rely on foreground-service hacks, fake audio or unbounded retained tabs. Process death means reloading the website; local history is what remains available offline.

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

The v2 schema and its v1 migration exist. Proposed new models include library/legacy mapping, versioned snapshots, independent favorites, download tasks/assets and share leases; they are not existing tables. Final DDL/version must be fixed by migration tests before implementation. Preserve each legacy account_id namespace, counts, times, category links and FTS. Offer explicit consolidation, never a silent global constant or destructive recreation. Failed migration keeps the original data and an actionable error.

## 5. Event and snapshot semantics

| Record | Meaning |
| --- | --- |
| OPEN_POST | Opted-in deliberate entry into a valid detail context; no dwell delay. LINK_ONLY may be stored immediately, then enriched. |
| FEED_VISIBLE | Independent, optional foreground exposure meeting continuous dwell and readable-area rules. Not proof of reading. |
| Own action | Confirmed own post/reply/quote/repost or provenance-checked import; cancellation/failure/seeing a repost is not success. |
| FAVORITE | Xs-native relationship and local time, independent of X server bookmarks/likes. |
| LOCAL_SAVE | Existing manual link import/save; retained as distinct legacy semantics, not silently inflated into reading. |

One navigation ID admits at most one open per library/context; increasing message sequences are not sufficient. Original publication time, action time, open time and feed time remain separate, with unknown values preserved. Snapshots use LINK_ONLY/PARTIAL/DETAIL_VISIBLE and explicit source/version; length-only retention is a legacy safeguard, not a final correctness rule for edited posts.

## 6. Writes, deletion and invalidation

All local writes, import batches, favorite changes, asset completion and destructive operations go through a unified native controller with bounded work. Queued units carry native library/document/generation identity; a clear or cancellation advances invalidation before a storage transaction. Recheck at commit time so a late callback cannot restore deleted records.

Separate unfavorite, history clear, asset deletion, cache clear, browser logout/session clear and complete library deletion. Favoriting must not request an X server mutation. Importing never invents opens. Library clearing must handle downloads and share leases deliberately rather than racing independent Activity executors.

## 7. Media and asset pipeline

Resolve only explicitly selected media from permitted post contexts. Resolver output is an untrusted candidate, not an arbitrary network proxy request. Validate HTTPS destinations/redirects, resource type, bounds, generated filenames, disk quota and retry budget; reject local/private-network probing and unsafe paths. No DRM, paywall or authorization bypass. Avoid generic network-response interception and credential logs.

Download images and available complete videos first. `blob:` identifiers, manifests and fragments are not completed MP4s. If permitted HLS/DASH is actually needed, test resolution, assembly, audio/video synchronization and shareable output separately. Lack of an allowed media source is an explicit unsupported/failure result, not permission to broaden extraction.

Write temporary data, verify actual type/completeness/playback, then atomically register the asset. Resume only with verified range/object consistency; otherwise retry from the start explicitly. Keep user downloads separate from cache. Use an appropriate Android task/notification mechanism after target-version verification; a documentation name does not imply background reliability.

Assets are separate from posts with stable local IDs, content hashes, MIME, size/duration/dimensions, source-post links, local timestamps, state and categories. Source-page disappearance does not delete completed local files. User export to Photos/Files is separate from private app storage and never implicit.

## 8. Outbound sharing

Native ShareCoordinator handles canonical links, explicit text with source, and selected actual completed media. Use ACTION_SEND/ACTION_SEND_MULTIPLE, accurate MIME, content URI/ClipData and temporary read grants. Provider exposure is restricted to chosen shareable assets/copies, never root/library databases, credentials, keys or unfinished files. No write grants.

User selects installed app, conversation and sending. WeChat/QQ/Feishu/Telegram are compatibility targets, not token/SDK/contact integrations. Direct conversation shortcuts depend on the recipient. A chooser completion cannot prove sending/delivery and must not trigger automatic retry.

Protect asynchronous receiver reads with a bounded share copy/lease and documented cleanup; deleting the source or clearing cache must not immediately break an active share. Sharing intentionally passes selected content to another app; no Xs upload service is used, but the receiver may upload it. Preserve originals on target/format/size failure; offer explicit alternatives, not silent quality loss.

## 9. UI and release boundaries

Preserve X web navigation. Add minimal native favorite/download/share/library actions. Native library provides history, favorites, own content and assets with shared search/category/time filters. Optional page-card actions and reversible styles must not block the native path or alter login/security/composers.

Capture and filtering start off and have separate consent; cache is not durable saved media, and SQLite is not currently claimed encrypted. Reuse of device-key patterns does not encrypt existing FTS/WAL/backups automatically. Live login, target-device media, receiver apps, migrations, backups, signing and licensing need their own evidence.

[Roadmap](roadmap.md) and [acceptance matrix](ACCEPTANCE_MATRIX.zh-CN.md) define stage completion. W0 gates enabling unvalidated real-site automation and release, not parallel offline development. Legacy APK integration contracts are retained history. No production release, website deployment or modification of the user's machines is authorized by this plan.
