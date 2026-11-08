# Xs · A cleaner, more useful X

[简体中文](README.md) | [English](README.en.md)

**Recent timeline (XS-16) and storage-pressure hardening are cloud-validated; live X capture stays disabled.** Independent default OFF / 48h / app-wide100MiB (1–168h / 25–500MiB), native offline search/refresh batches, expiry/reclamation and explicit durable saves/favorites. [CI93](https://github.com/ForceMind/Xs/actions/runs/42519140643) passed1,406JVM assertions,800/800main cases acrossAPI26/27/30/36,20/20independent drivers and44/44native phases. Each API measured actual buffer rejection below1MiB, pressure-file removal and resumed capture, preserving durable data/downloads. [Evidence](docs/XS16_FILESYSTEM_PRESSURE_EVIDENCE.zh-CN.md) records exact source/packages and retained failures. Buffering creates no open/qualified-dwell/read facts, records no raw API archive and downloads no media automatically. Real X/phones remain a separate gate.

**Xs is an independent, local-first enhancement client for X.** Browse official X web normally while an app-owned library keeps open history, optional feed dwell, independent favorites and media. Share selected actual files through Android. No official APK modification, new social backend or account proxy.

> **Internal development PoC; no production release.** This increment adds SQLite v6 snapshot versions, batch organization, system download scheduling, passphrase-encrypted resource backups and persistent whole-library deletion. Live X automatic capture, own-action confirmation and cleanup remain disabled. See [current reliability and library evidence](docs/ROUND_D_EVIDENCE.zh-CN.md). Round D CI74 and documentation checkpoint CI75 passed; Round E passed its own CI82 with 624 main cases and 20 independent drivers; live/device gates remain open.


Round E source `2c667517e5a812232a8b41e7a3f99166855963e6` / tested tree `e52dfdd3f5f7b51751154bf1cf2601554786ef33` passed [CI82](https://github.com/ForceMind/Xs/actions/runs/42419306946): 1,287 JVM assertions, 23 host checks, Android builds and both lint checks, and 624/624 main cases across API26/27/30/36, with 0 failures, errors or skips. BrowserInteraction passed 24/24 and actual old-APK upgrades passed 4/4. Separately, 20/20 independent drivers and 44 native phases passed in this same-source fresh attempt1; repeated probes are excluded from main XML. Exact merge and package provenance and the actual CI76–81 failures remain in [Round E evidence](docs/ROUND_E_EVIDENCE.zh-CN.md); D/CI75 is historical.

Native cases verified real SAF selection of the 5,783-byte synthetic MP4, complete FileReader bytes/hash and playback to ended after a user gesture; two new PNGs chosen through real system multi-select with per-file set checks; and rejection of old results after a sensitive SPA route returns HOME. The actual fixed old-main f88003c8ec1ee193b75de7267db6ae7654ee77c4 code1/schema2→current3/schema5 upgrade preserved UID, DB path, default UUID, original rows, categories, FTS and a generated file. Default LegacyUpgradeProbe is only a current-version round trip; the real old-APK upgrade is counted as a separate driver.

Upload MIME is a provider declaration, not proof of actual contents, completeness or upload to X. The 20 MiB image and 100 MiB video download limits do not apply to uploads. No new permissions, native whole-file reads or transcoding are added. Old versions have no independent favorites/media database, so those migrations are N/A; a generated file canary proves file preservation only. Live X/CDN/chat-app/physical-device/production-signing and disabled-live-adapter boundaries remain unchanged.

Verified Round D source `0d193e4689d649970d9705818d33e8706b470d8f` / tested tree `c8551cd4a09e4466cd84e7973039aba06a5f87b2`: all six [CI74](https://github.com/ForceMind/Xs/actions/runs/42386764024) jobs succeeded. The 1,155 JVM assertions, builds/both lint checks and 608/608 main API26/27/30/36 cases passed, with 0 failures/errors/skips. Independent force-stop, same-job/same-source debug3→4 upgrades, guest reboot and real low-space drivers each passed 4/4, totaling 16/16; they are not added to the 608 main XML cases.

Low-space assertions use the ordinary app’s original UID and actual available bytes, verifying new-task rejection, preserved original files/hashes/offline search and pressure-file cleanup. Only the disposable CI old-userdebug allocator temporarily uses root after strict environment/current-quota/UID gates; final deletion, unroot and restored shell UID were verified. Ten host failure/safety-gate tests passed separately. Complete cases, identities/package digests and CI66–73 failures remain in the evidence; this is not physical power loss, rooting a user phone or unlimited-background assurance.


Historical source `4ee242e` passed [CI 42357220689](https://github.com/ForceMind/Xs/actions/runs/42357220689): 933 JVM assertions plus SQLite/DOM regressions, builds/lint and 146 synthetic API30/36 instrumentation cases. Those results belong to the earlier build. [Round C evidence](docs/ROUND_C_EVIDENCE.zh-CN.md) and [earlier history/dwell/image evidence](docs/IMPLEMENTATION_EVIDENCE.zh-CN.md) remain available. Actual accounts, physical devices and recipient apps are unverified.

## Implementation and boundaries

| Capability | Current source | Remaining limits |
| --- | --- | --- |
| System WebView | Retained pages, controlled authentication navigation, native link favorites and explicit media selection; separate cache/session clearing | Actual X login, authentication providers and sustained physical-device use |
| Opens and dwell | Fixture immediate opens, late text enrichment, deduplication; separate default-off 1.2/3 (recommended default)/5-second continuous dwell and offline duration/never-opened search | Live adapter disabled; exposure does not prove reading |
| Local library | Preserving v1–v5→v6 migration, FTS, categories/notes/tags, batch favorite/category/tag/unfavorite; independent of X login | Personal-data upgrades and sustained capacity need acceptance; 320dp/1.8-font Chinese/English light/dark fixture cases passed; physical-device layout remains separate |
| Snapshot versions | Text versions with source/capture time and LINK_ONLY/PARTIAL/COMPLETE/LEGACY_UNKNOWN quality, historical-text search and read-only version browsing | COMPLETE means the accepted visible DOM text, not guaranteed server-full content; browsing never replaces current text; the version panel keeps 50 rows per page with previous/next and keyword search |
| Own activity | Native fixture operation/result matching for original/reply/quote/repost/reversal; imports preserve unknown actors/times | Live X action adapter disabled; seeing another person's repost is not an own action |
| Images and standard video | Explicit image/image-set or direct standard MP4 selection, validation, offline preview and actual-file sharing; H.264/optional AAC, ≤100MiB/10 minutes | Reject blob/HLS/DASH/fragments/encryption/DRM/unknown formats; actual X video, entire playback and recipient apps unverified |
| Download durability | JobScheduler; API34+ explicit requests first try user-initiated transfer jobs, otherwise regular persisted jobs; API26–33 regular persisted jobs; sealed source URLs, cancel and bounded retries | At most 3 network attempts per explicit run (initial + 2 automatic retries), each from byte zero; system delay/stop is respected; no timely-background or range-resume guarantee |
| Asset library | Paged offline search, type/state filters, multiple selection/category/delete/share/export; multiple exports become ZIP, ≤200MiB per batch | Only completed files can export/share; physical-device SAF and recipient compatibility need separate evidence |
| Backup/restore | Existing plaintext metadata ≤16MiB/20,000 records; additional passphrase/chunked AES-256-GCM resource backup with plaintext payload ≤512MiB/500 completed assets | Excludes browser sessions, keys, tasks, candidate URLs and share leases; lost passphrases cannot be recovered; SQLite/FTS/WAL itself is not encrypted |
| Retention, limits and statistics | Event details default to forever, optionally 7/30/90/365 days; per-library media limits default to unlimited, with completed/reserved usage and confirmation; known lifetime lower bounds are separate from retained events | Lazy cleanup has no background deadline; lowering limits can stop unfinished tasks but never deletes completed files; exposure lower-bound sums are not reading time |
| Consolidation/deletion | Explicit 2–4-library metadata merge to a new library, ≤8MiB/20,000 records, preserving sources/default; persistent deletion tombstones, default UUID rotation and retryable cleanup | Consolidation excludes media; production signing, real capacity, multi-day reliability and upgrade/rollback need acceptance |
| Cleanup | Three default-off promotion/subscription/recommendation controls and reversible fixture rules | Not attached to the live browser; synthetic success does not enable live X |

## Experience and data semantics

**Immediate opens, optional no-click dwell (fixture observer).** After opting in, deliberate entry into valid post detail saves a history event without a delay or extra save tap; pending text starts link-only. Independently enabled dwell records qualifying continuous exposures without opening, supporting “dwell ≥3 seconds and never opened.” Background, lock, overlays and page-generation changes interrupt timing rather than joining short exposures. Qualified fixture duration writes are coalesced to one-second intervals and stop at the ten-minute measurement cap, with lower-bound/capped quality displayed.

**Favorites belong to Xs.** Native favorites, tags, notes and folders never call X bookmarks/likes/reposts. X logout/account changes preserve the library. Unfavorite, history clear, asset deletion, browser cache clear, X logout and whole-library deletion have different scopes. Whole-library deletion erases that scope's snapshots/events/favorites/folders/own activity/downloads, preserves other scopes/web sessions and prevents stale callback revival. Existing receiver copies remain until their 24-hour lease expires.

**Event details and known lifetime facts differ.** Retention removes only expired open/dwell event details, preserving posts, snapshots, favorites, own activity, files and cumulative/latest open/dwell facts. Pruned opens never become “never opened”; explicit history clearing resets those facts. Maintenance runs on library/management entry or manual cleanup. Media budgets include completed files and active reservations, conservatively 20MiB/image or 100MiB/video for unknown lengths. If a lower limit is exceeded, all unfinished tasks stop and fail, with explicit retry allowed when capacity permits; completed files remain.

Known lifetime open counts sum each post’s maximum of its stored cumulative count and retained open-detail count, avoiding double counting; this remains a known lower bound.

**Actual-file sharing.** Share canonical links/text, 1–10 completed images or one complete video. The user selects a compatible app, conversation and sending. No contacts/chat tokens, automatic sending or delivery claim from chooser callbacks. Downloads are separate from cache. A receiver can upload content deliberately shared with it.

**Restore creates a new scope.** The 8–1024-character passphrase is used in a secure native window and never persisted. Authentication, metadata/FK and per-resource hash/type validation precede preview. Resources commit first; library registration commits last. Private crash markers clean an unregistered target; sources/default are not overwritten. A newer shorter COMPLETE version can become current while all prior versions remain; PARTIAL cannot replace COMPLETE.

## Development and checks

[Documentation index](docs/README.md) · [XS-01–XS-15 implementation plan](docs/IMPLEMENTATION_PLAN.zh-CN.md) · [Work handoff](docs/WORK_HANDOFF_PROMPT.zh-CN.md) · [Product](docs/product.md) · [Feed dwell](docs/FEED_DWELL_PLAN.zh-CN.md) · [Library/media](docs/LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md) · [Architecture](docs/architecture.md) · [Acceptance matrix](docs/ACCEPTANCE_MATRIX.zh-CN.md) · [Roadmap](docs/roadmap.md) · [Changelog](docs/CHANGELOG.md).

The [pinned ChatGPT Studio review](docs/CHATGPT_STUDIO_REUSE.zh-CN.md) informs browser lifecycle, Profiles, controlled windows and native snapshots. X observation is implemented separately, excluding chat drafts/automatic-send logic. [Issue #4](https://github.com/ForceMind/Xs/issues/4) tracks work packages; [Issue #3](https://github.com/ForceMind/Xs/issues/3) tracks actual browsing. Order: A browser/automatic opens → B early dwell/favorites/images/sharing → C own activity/video/cleanup → D reliability/delivery. Offline engineering continues alongside device checks; enabling live features needs its own evidence.

```text
app-android/      System WebView client and synthetic integration tests
xs-runtime/       Android library, observer and media runtime
dev-harness/      Offline synthetic host, not X
src/main/java/    v5 policies/queries, observation state, media/backup codecs
src/android/java/ SQLite, native library/assets UI, system jobs, scoped sharing
tests/ scripts/   JVM, SQLite, DOM, signed-candidate and engineering checks
```

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
# Android SDK, JDK17, Gradle8.9:
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug :xs-runtime:lintDebug
gradle --no-daemon --no-parallel :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

CI74 actually passed the core/SQLite/DOM stress and Android build checks. Cloud fixture coverage includes actual native SAF export/restore, browser SAF PNG selection, read-only URI/68-byte FileReader reads and gesture windows, independent-UID grant/denial/delayed-read checks, 320dp/1.8-font Chinese/English light/dark configurations, native restore of 500 tiny assets and about 481MiB/30 actual files, plus a 512MiB container checked byte-for-byte in a 32MiB JVM. D verified PNG68-byte reads; Round E CI82 separately verified synthetic MP4 selection/FileReader/local playback. Server upload remains unverified; MP4 downloads/cross-UID sharing are separate cases. These scopes are recorded separately and do not establish live-X, physical-device or chat-app acceptance.

The real browser does not attach live automatic open/dwell, own-action or cleanup adapters; preferences cannot turn fixture capability into field acceptance. Actual X login/DOM/media-CDN behavior, WeChat/QQ/Feishu/Telegram reception, vendor background/multi-day power, physical power loss, production-key upgrades and real personal-data migrations still need independent evidence. A custom-license switch is optional and requires separate rights/scope/terms review; the current GPL, historical grants and third-party obligations remain effective. The receiver fixture is not chat-app delivery; reboot recovery proves app-owned lease files/metadata, not receiver URI grants surviving reboot.

## Installation, signing and licensing

CI debug certificates can differ by job and do not guarantee in-place PoC upgrades. Do not uninstall a data-bearing installation just to replace it; first validate backups/restoration in isolation. The new manual signed-candidate workflow accepts an exact reviewed commit on main's ancestry, requires configured environment secrets and a pinned certificate SHA-256, and outputs an internal candidate/provenance artifact. It does not publish a GitHub Release or upload to a store. Production key/certificate configuration and release acceptance remain incomplete.

[GNU GPL v3.0](LICENSE) remains the effective root license. The owner permits an optional custom-license switch after [rights and historical-grant review](docs/LICENSING.md), with scope and terms separately agreed. Continuing under the current GPL requires no new custom text; historical grants and third-party obligations remain effective. Xs is unaffiliated with X Corp. No authentication/DRM/access/paywall bypass, Cookie/Token export, zero-account-risk promise or store-approval claim.
