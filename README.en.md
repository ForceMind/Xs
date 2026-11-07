# Xs · A cleaner, more useful X

[简体中文](README.md) | [English](README.en.md)

**Xs is an independent, local-first enhancement client for X.** Browse official X web normally while an app-owned library keeps open history, optional feed dwell, independent favorites and media. Share selected actual files through Android. No official APK modification, new social backend or account proxy.

> **Internal development PoC; no production release.** This increment adds SQLite v5 snapshot versions, batch organization, system download scheduling, passphrase-encrypted resource backups and persistent whole-library deletion. Live X automatic capture, own-action confirmation and cleanup remain disabled. See [current reliability and library evidence](docs/ROUND_D_EVIDENCE.zh-CN.md). CI68 failed; the next fix CI is pending generation; earlier results do not validate this source.

Previous source `58585977c90307cbf97d0aadafd818bcbdf88793` / tree `595dcb18016bf4bdf2f0e520dce1236ac5fc35a5` failed [CI68](https://github.com/ForceMind/Xs/actions/runs/42374634739): API26/30/36 executed 450 cases, with 415 passed/35 failed/0 errors/0 skipped. API27 failed to start any case because of the official image architecture and is counted separately. The next PR #5 fix commit and CI are pending generation; no PASS is claimed.

The next matrix is API26/27/30/36 (x86 for 27, x86_64 otherwise); 52 harness + 100 client cases/API are declarations, not passes. New guest-reboot/boot-ID/serial checks, real /data pressure down to 28MiB on disposable CI-only 8GB AVDs (preserve originals/reject new tasks; finally remove only the created pressure file), and native streaming backup of ≥480MiB/30 actual assets remain NOT RUN. They do not establish physical-power-loss, live X/chat-app or production-key acceptance.

Historical source `4ee242e` passed [CI 42357220689](https://github.com/ForceMind/Xs/actions/runs/42357220689): 933 JVM assertions plus SQLite/DOM regressions, builds/lint and 146 synthetic API30/36 instrumentation cases. Those results belong to the earlier build. [Round C evidence](docs/ROUND_C_EVIDENCE.zh-CN.md) and [earlier history/dwell/image evidence](docs/IMPLEMENTATION_EVIDENCE.zh-CN.md) remain available. Actual accounts, physical devices and recipient apps are unverified.

## Implementation and boundaries

| Capability | Current source | Remaining limits |
| --- | --- | --- |
| System WebView | Retained pages, controlled authentication navigation, native link favorites and explicit media selection; separate cache/session clearing | Actual X login, authentication providers and sustained physical-device use |
| Opens and dwell | Fixture immediate opens, late text enrichment, deduplication; separate default-off 1.2/3 (recommended default)/5-second continuous dwell and offline duration/never-opened search | Live adapter disabled; exposure does not prove reading |
| Local library | Preserving v1–v4→v5 migration, FTS, categories/notes/tags, batch favorite/category/tag/unfavorite; independent of X login | Personal-data upgrades and sustained capacity need acceptance; 320dp/1.8-font Chinese/English light/dark configuration cases await CI |
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

**Immediate opens, optional no-click dwell.** After opting in, deliberate entry into valid post detail saves a history event without a delay or extra save tap; pending text starts link-only. Independently enabled dwell records qualifying continuous exposures without opening, supporting “dwell ≥3 seconds and never opened.” Background, lock, overlays and page-generation changes interrupt timing rather than joining short exposures. Qualified fixture duration writes are coalesced to one-second intervals and stop at the ten-minute measurement cap, with lower-bound/capped quality displayed.

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

Local checks passed 1,155 JVM assertions, SQLite/DOM/20k stress, and an SDK SQLite standard-FTS reproduction (old multiword query: 0 rows; corrected: 1), plus full Android builds and both lint checks (196 tasks, 17 seconds; runtime: 0 errors/20 warnings, client: 0 errors/12 warnings). The maximum 512MiB encrypted container was streamed and checked byte-for-byte in a 32MiB JVM; this does not validate near-limit Android asset restoration.

CI68 includes API27, an independent-UID receiver, actual browser SAF upload/touch-generated popups, an external ADB force-stop/new-PID recovery driver, and same-job debug-certificate version 3→4 installation upgrades. The next fix run awaits execution; 52 harness + 100 client cases declared per API do not mean 608 passes. CI68 ended with the failures recorded above; the next fix run has not executed. The receiver fixture is not a chat app; debug upgrades are not production-signing/cross-schema acceptance. Physical devices, actual power/multi-day use, X login/media and WeChat/QQ/Feishu/Telegram reception remain NOT RUN. Emulator OS force-stop is cloud-testable; physical power loss and vendor background behavior need device evidence.

## Installation, signing and licensing

CI debug certificates can differ by job and do not guarantee in-place PoC upgrades. Do not uninstall a data-bearing installation just to replace it; first validate backups/restoration in isolation. The new manual signed-candidate workflow accepts an exact reviewed commit on main's ancestry, requires configured environment secrets and a pinned certificate SHA-256, and outputs an internal candidate/provenance artifact. It does not publish a GitHub Release or upload to a store. Production key/certificate configuration and release acceptance remain incomplete.

[GNU GPL v3.0](LICENSE) remains the effective root license. The owner permits a future custom license after [rights and historical-grant review](docs/LICENSING.md); this increment changes neither LICENSE nor previous grants/third-party notices. Xs is unaffiliated with X Corp. No authentication/DRM/access/paywall bypass, Cookie/Token export, zero-account-risk promise or store-approval claim.
