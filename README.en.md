# Xs · A cleaner, more useful X

[简体中文](README.md) | [English](README.en.md)

**Xs is an independent, local-first enhancement for using X.** Official X web supplies browsing and social features; an app-owned on-device library provides open history, local favorites, organization, own-activity indexing, image/video downloads and sharing selected resources to other apps. No official APK patching, new social backend or account proxy.

> **Internal development PoC, not a production client.** The existing history/dwell, favorites and image-sharing slice now has schema v4 own-action/reversal indexing, narrow standard-MP4 downloads/sharing, reversible cleanup fixtures, metadata backup/restore and explicit library consolidation. Live X automatic capture, own-action confirmation and cleanup remain disabled. [CI 42357220689](https://github.com/ForceMind/Xs/actions/runs/42357220689) passed core/build and API 30/36, with **146 synthetic instrumentation cases** (36 harness + 37 client per API, zero failures/errors/skips); actual login, X media sources and recipient apps need separate acceptance. See [this increment's evidence and limits](docs/ROUND_C_EVIDENCE.zh-CN.md). Prior offline results remain in the [existing implementation evidence](docs/IMPLEMENTATION_EVIDENCE.zh-CN.md).

Validated source: `4ee242e`; 933 JVM assertions and SQLite/observer/cleanup-DOM regressions passed. Client/runtime lint each report 0 errors, 0 NewApi and 11 warnings. API 26/27 execution remains NOT RUN. Backup/consolidation database and transaction tests passed; the SAF picker and new backup/consolidation UI have no dedicated automation and remain NOT RUN on physical devices. See the [CI summary](docs/evidence/round-c-ci64.json).

CI debug certificates may differ by job, so upgrading an existing PoC installation is not guaranteed. Do not uninstall an old Xs containing data just to install a new debug APK: current backups exclude media. Prefer a blank or isolated test environment; stable signing remains pending.

## Current implementation boundary

- Offline history: immediate native-confirmed opens, late text enrichment, navigation/exposure deduplication, 1.2/3/5-second continuous dwell, never-opened and duration filters.
- Local data: v1/v2/v3→v4 preserving migrations, separate libraries, independent favorites/categories/notes/tags and history erasure. Own-action records keep actor provenance, unknown times/current state and reversal history. Native fixture operations require a matching successful result; page claims and restored provenance strings cannot issue confirmation proofs.
- Media: explicit current-post image/standard-MP4 selection, bounded downloads, validation, offline preview/category/export and read-only actual-file sharing with 24-hour copies. Interrupted downloads require reselecting the source; resumable transfers and reliable background scheduling remain pending.
- Portability: plaintext metadata backups up to 16 MiB/20,000 records, schema-3→4 restore into a separate library, and explicit consolidation of 2–4 libraries into a new library within 8 MiB/20,000 records. Media files, download tasks and web sessions are excluded.
- Pending: API 26/27 execution, SAF/new backup/consolidation UI acceptance, live X enablement and selectors, real own-action adapters, X video downloads, recipient apps, long-running reliability, full media recovery and release signing. These additions do not complete batches C/D or their live gates.

## Continue development here

- **[Documentation index](docs/README.md)**: authority, current state and tracking.
- **[Implementation plan v2.1](docs/IMPLEMENTATION_PLAN.zh-CN.md)**: XS-01–XS-15 work packages, dependencies, A–D delivery batches and next steps (Chinese).
- **[Work development handoff prompt](docs/WORK_HANDOFF_PROMPT.zh-CN.md)**: instructions for implementing, testing, committing, and validating the existing project in a new Work session (Chinese).
- **[Product requirements](docs/product.md)** · [Local library/media specification](docs/LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md).
- **[ChatGPT Studio reuse review](docs/CHATGPT_STUDIO_REUSE.zh-CN.md)**: pinned sources, browser lifecycle, native snapshots and exclusions.
- **[Architecture](docs/architecture.md)** · [Roadmap](docs/roadmap.md) · [Acceptance matrix](docs/ACCEPTANCE_MATRIX.zh-CN.md) · [Change record](docs/CHANGELOG.md).
- [ADR-0002: app-owned library/media](docs/adr/0002-local-library-media.md) · [ADR-0003: native-pull and parallel delivery](docs/adr/0003-native-pull-delivery.md) · [Licensing decision](docs/LICENSING.md).

[Issue #4](https://github.com/ForceMind/Xs/issues/4) tracks this scope; [Issue #3](https://github.com/ForceMind/Xs/issues/3) retains live browser evidence. The older [Web-first plan](docs/WEB_FIRST_PLAN.zh-CN.md) and [ADR-0001](docs/adr/0001-web-first.md) remain history and non-conflicting safeguards; use the documentation index to resolve conflicts.

**[Timeline dwell specification (Chinese)](docs/FEED_DWELL_PLAN.zh-CN.md)** · [ADR-0004](docs/adr/0004-feed-dwell.md) · [D01–D09 acceptance](docs/ACCEPTANCE_MATRIX.zh-CN.md): stop on a post in the timeline without opening it, and optionally remember that visible exposure locally. Baseline dwell moves into early batch B; large-scale optimization stays in D.

## Confirmed experience

**Record deliberate opens immediately—and remember qualifying feed dwell without opening.** After opt-in, entering valid post detail creates history without a delay or extra save tap. Independently opt in to feed exposure with **1.2s / 3s (recommended default) / 5s** continuous-visible thresholds, allowing local search for posts seen for ≥5 seconds but **never opened**. This measures visibility, not proven reading. Reload, rotation and content enrichment do not create fake opens; own repost activity requires confirmed success.

**Favorites belong to Xs.** A distinct “Favorite in Xs” button, categories, tags and notes do not invoke X bookmarks, likes or reposts. Logging out or switching X accounts must not delete or implicitly switch local history/favorites/assets. Browser Profile, library ownership and social actor attribution are different concepts.

**Downloads belong in an asset library.** Explicit image, image-set and video download supports offline preview, source links, time/category organization, retry, cancel, export and deletion. Favoriting does not silently download every attachment. Browser cache is not a saved download, and clearing cache must not delete saved assets.

**Share actual files.** Select a link, text, one/multiple images or video through the Android Sharesheet to WeChat, QQ, Feishu, Telegram or another installed compatible app; the user chooses a conversation and sends. Direct conversation targets depend on the receiver. No messaging tokens, automated sending or delivery claim from opening a chooser.

## Capabilities versus implementation

| Capability | Current state |
| --- | --- |
| Android System WebView host | Implemented; live login, media and sustained physical-device acceptance pending |
| SQLite/FTS, pagination, categories and archive UI | v4 preserves v1/v2/v3 data and separate scopes; own-activity index and provenance trail added |
| Manual post-link saving and incoming share-to-Xs | Implemented; link-only is not a body snapshot or automatic history |
| tweets.js/tweet.js import | One file/part up to 16 MiB; user-confirmed ownership does not verify a web login or actor; ambiguous RT text stays searchable without an invented repost |
| Native-pull observer and strict JSON validation | Packaged implementation and synthetic integration tests; live X activation disabled |
| Opens and dwell | Immediate opens, enrichment, deduplication, threshold selection and offline filters implemented for fixtures; live X pending |
| Own activity | Native fixture operation/result confirmation, four types, reversal trail and archive provenance implemented; live adapter disabled; unknown actor/time/current state preserved |
| App-native favorites, notes/tags and categories | Implemented; native browser favorites are link-only until live snapshots are validated |
| Images, assets, outbound file sharing | Implemented narrow image pipeline and actual-file grants; actual X downloads and receiver apps unverified |
| Video downloads/sharing | Explicit direct standard-MP4 selection, validation, assets/preview/sharing implemented in source; H.264 with optional AAC, ≤100 MiB/10 minutes; synthetic download/decode/Provider regression passed; actual X/receivers unverified |
| Ad/subscription/recommendation cleanup | Three independent default-off switches, reversible rules and lifecycle control in synthetic fixtures; not attached to the live browser |
| Metadata backup/restore and consolidation | Plaintext backup ≤16 MiB/20,000 records, schema-3→4 new-library restore; combine 2–4 libraries into another library, ≤8 MiB/20,000 records; sources/default preserved; no media files/tasks/web sessions |

Video validation checks MP4 structure/sample ranges, transferred length, tracks and duration, plus limited decoding of first/tail video frames and optional AAC audio. It does not prove every frame plays or validate recipient apps. Blob URLs, HLS/DASH, fragments, encrypted and unknown formats are rejected; browser session material is not exported to obtain downloads.

Backup files are plaintext and go to the document app the user selects. Checksums detect damage, not authorship or account identity; restoring provenance does not establish native action authority. Consolidation uses the metadata snapshot shown in the preview: later ordinary edits require a new preview, while source erasure or epoch invalidation rejects commit. Conflicting sources for the same legacy action identity reject consolidation. The result dialog can undo only the just-created result whose metadata is unchanged and which has no media/download tasks. Changed results or results with media are rejected without invalidating existing tasks; sources and the default stay intact. Neither operation moves media files.

## Implementation direction

Review ChatGPT Studio's Android lifecycle, Profile, controlled windows and native-initiated observation patterns. Preserve Xs SQLite/FTS/UI and write an X-specific packaged observer. Prefer bounded native-pull results for the first history loop; a generic bidirectional bridge is not a prerequisite. Returned data stays untrusted and must pass context, consent, generation and navigation checks again at write time.

Offline observation, migration, favorite and media implementation can proceed alongside device verification. Do not default-enable unvalidated live capture. The old interpretation that all work must wait for W0 is not the current execution order.

Delivery: **A browsing/immediate open history → early B optional no-click feed dwell plus favorites/images/assets/sharing → C own reposts/videos/cleanup → D dwell performance/retention and release hardening.**

## Available manual flow

The browser menu can favorite the current post link, explicitly select images or a supported direct MP4, and open the asset library. Completed files support preview/export/system sharing. The native library provides opens, dwell, saved items, own-activity indexing, offline search, “Backup / restore” and explicit consolidation from the library chooser. Incoming “Save to Xs” remains a link-only companion path without fake reading events. A page without a supported direct resource reports it unavailable; a playing blob is not a finished download.

## Privacy, migration and deletion

Separate consent for open history, dwell and filtering. No passwords, verification codes, cookies/tokens, DMs, drafts or hidden-state collection. X pages still use their normal network; Xs adds no archive-upload service. Explicitly shared files are then handled by the receiving app, so they are not guaranteed to remain on-device afterward.

Unfavorite, clear history, delete asset, clear cache, X logout and delete library are distinct. Preserve legacy account_id mappings instead of silent merging or destructive recreation. Queued imports/downloads/shares must not revive cleared data. Existing SQLite is not claimed encrypted; webpages get no whole-archive reads, arbitrary SQL, export or erasure capability.

## Layout and checks

```text
app-android/          Browser, native favorites/image and video selection/sharing, fixture integration
xs-runtime/           Android on-device archive library
dev-harness/          Offline synthetic host, NOT X
src/main/java/        URL/v4 schema/query, action admission/capture state, bounded writes, media/backup/merge policy
src/android/java/     SQLite/import/library UI, native pull, media and grant-only sharing
tests/                JVM/SQLite/regression tests
scripts/              Engineering checks
docs/                 Current plans, decisions and acceptance
compat/               Device matrix and legacy APK metadata
```

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
# Android SDK, JDK 17, Gradle 8.9:
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug :xs-runtime:lintDebug
gradle --no-daemon --no-parallel :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

[W0 execution evidence](docs/W0-EXECUTION.md) · [Device matrix](compat/web/W0-MATRIX.md). A documentation commit is not a new build/test result. Synthetic/emulator passes do not replace actual login, recipient-file compatibility or personal-data upgrade testing.

## License and disclaimer

The operative root text remains [GNU GPL v3.0](LICENSE). The owner permits a custom-license direction; see [LICENSING](docs/LICENSING.md). This plan update does not replace the license, invent commercial restrictions, remove third-party notices or rewrite earlier grants.

Xs is unaffiliated with X Corp. and includes no official package, credentials or subscription entitlements. Process/download only authorized, permitted resources without DRM, payment or access-control bypass. Source licensing does not grant third-party content/service rights; no zero-risk or store-approval promise.
