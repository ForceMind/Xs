# Xs architecture — app-owned library and native-pull plan

当前代码进度以 [后续离线闭环记录](ROUND_C_EVIDENCE.zh-CN.md) 和 [A / early-B 记录](IMPLEMENTATION_EVIDENCE.zh-CN.md) 为准；下文保留需求基线，待验收项不因源码存在而自动通过。

Updated 2026-11-06. Read [product](product.md), [ADR-0002](adr/0002-local-library-media.md), [ADR-0003](adr/0003-native-pull-delivery.md), [implementation](IMPLEMENTATION_PLAN.zh-CN.md) and [acceptance](ACCEPTANCE_MATRIX.zh-CN.md). This describes the target and names existing foundations separately; it does not claim live integration.

## 1. Runtime and target boundaries

```mermaid
flowchart TD
  W["System WebView"] -->|"explicit native selection"| M["Validated images / direct MP4"]
  F["Offline WebView fixtures"] -->|"bounded native pull"| Q["Library queue / generation"]
  Q --> D["SQLite v4 / FTS / events / favorites"]
  M --> A["Saved assets / read-only share leases"]
```

`app-android` hosts retained System WebView with manual link favorites, selected-post media downloads and outbound file sharing. `xs-runtime` provides library storage/UI, fixtures, strict backups and explicit metadata consolidation. `dev-harness` is synthetic and offline. The packaged observer, reversible cleanup and own-action admission have no enabled real-X adapter; diagrams do not grant those privileges. Media support is bounded standard H.264/AAC MP4, with container/sample validation and representative decoding, not all-format or whole-film decode certification.

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

SQLite v4 preserves v1/v2/v3 namespaces, library mappings, counts, times, category links and FTS. Separate own-action and action-event tables retain actor provenance, unknown time, related posts and reversal state. Favorites and local media/share leases exist; a separate versioned snapshot table remains future work. Explicit consolidation creates another space and preserves all sources; failed transactions keep original data. Browser sessions and library ownership are separate.


## 5. Event and snapshot semantics

| Record | Meaning |
| --- | --- |
| OPEN_POST | Opted-in deliberate entry into a valid detail context; no dwell delay. LINK_ONLY may be stored immediately, then enriched. |
| FEED_VISIBLE | Independent, opt-in **timeline exposure without opening the post**, 1.2s/3s (default)/5s continuous visible dwell, normalized readable area and measured duration. Not proof of reading and does not increase opened_count. |
| Own action | Confirmed own post/reply/quote/repost or provenance-checked import; cancellation/failure/seeing a repost is not success. |
| FAVORITE | Xs-native relationship and local time, independent of X server bookmarks/likes. |
| LOCAL_SAVE | Existing manual link import/save; retained as distinct legacy semantics, not silently inflated into reading. |

One navigation ID admits at most one open per library/context; increasing message sequences are not sufficient. Original publication time, action time, open time and feed time remain separate, with unknown values preserved. Snapshots use LINK_ONLY/PARTIAL/DETAIL_VISIBLE and explicit source/version; length-only retention is a legacy safeguard, not a final correctness rule for edited posts.

### Feed dwell design target (XS-14 baseline moves into B)

See [feed dwell specification](FEED_DWELL_PLAN.zh-CN.md) / [ADR-0004](adr/0004-feed-dwell.md). A locally packaged X-specific observer will verify actual timeline cards, foreground visibility and readable-area ratio using bounded page observation; native code owns selected library, feature consent, current route, generation and monotonic timing. Thresholds are **1.2, 3 (recommended default), 5 seconds**; no threshold applies to deliberate OPEN_POST.

Each continuous qualified exposure uses a stable `exposure_id`, persists FEED_VISIBLE separately from opening/favoriting and updates the same event duration as it continues. A real subsequent exposure may form a new event. The v3 duration/threshold/quality fields and v4 preserving migration are implemented for fixtures; live DOM adaptation remains pending. Offline filters must support duration range and “never opened” in the local library without inferring server read status. Long cards normalize against actually readable viewport content; background, overlay, sensitive route and idle constraints require test fixtures and real-device evidence. The 1.2-second minimum currently enforced by FeedCapturePolicy is not by itself a shipped 3-second selector or measured duration index.


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

## 10. Explicit portability and consolidation

Metadata JSONL is bounded to 16 MiB / 20,000 records, schema 3 or 4, strict UTF-8/types/columns/references and a corruption checksum. Import creates a new UUID scope in one transaction, checks FKs and FTS, preserves sources/default, and never grants a browser identity from stored claims. Files are plaintext; media/downloads/web sessions/keys are excluded.

Consolidation snapshots 2–4 registered sources through shared queues, validates an immutable preview, and copies that exact snapshot into a new scope after confirmation. Total 8 MiB / 20,000 source records; normal edits after preview require another preview, erasure invalidates it. Conflicting legacy action provenance or overflowing notes abort rather than discard content. Idle writer recycling uses instance-bound epoch tickets; all-busy capacity failure occurs before target rows are published. See evidence for policies and limitations.
