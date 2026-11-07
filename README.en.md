# Xs · A cleaner, more useful X

[简体中文](README.md) | [English](README.en.md)

**Xs is an independent, local-first enhancement for using X.** Official X web supplies browsing and social features; an app-owned on-device library provides open history, local favorites, organization, own-activity indexing, image/video downloads and sharing selected resources to other apps. No official APK patching, new social backend or account proxy.

> **Internal development PoC, not a production client.** Schema v3 preserves old namespaces and adds app-owned favorites, notes/tags and dwell queries. Packaged native-pull observation is wired only in synthetic WebViews; live X automatic capture stays disabled. Narrow image tasks, saved-image UI and grant-only actual-file sharing are implemented. Live login, X selectors and recipient applications remain unverified. See the [implementation evidence](docs/IMPLEMENTATION_EVIDENCE.zh-CN.md).

## Current implementation boundary

- Offline: immediate native-confirmed opens, late text enrichment, navigation/exposure deduplication, 1.2/3/5-second continuous dwell, never-opened and duration filters.
- Local library: v1/v2→v3 preserving migrations, separate legacy namespaces, independent favorites/categories/notes/tags and history erasure. Explicit legacy consolidation is still pending.
- Images: explicit current-post candidate selection, bounded downloads and file validation, offline preview/category/export, single/multiple read-only content-URI sharing with 24-hour copies. Interrupted downloads restart after reselecting the source; reliable background scheduling is pending.
- Pending: live X activation, confirmed own-action/reversal capture, complete video, reversible filtering, backup/recovery, stable release signing and device/recipient compatibility. Synthetic success does not complete these gates.


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
| SQLite/FTS, pagination, categories and archive UI | v3 preserves old namespaces and maps them to separate libraries; explicit consolidation pending |
| Manual post-link saving and incoming share-to-Xs | Implemented; link-only is not a body snapshot or automatic history |
| tweets.js/tweet.js import | One selected file/part up to 16 MiB; ownership confirmation required |
| Native-pull observer and strict JSON validation | Packaged implementation and synthetic integration tests; live X activation disabled |
| Opens and dwell | Immediate opens, enrichment, deduplication, threshold selection and offline filters implemented for fixtures; live X pending |
| Own activity | Existing confirmed-ownership archive import retained; real-time success/reversal pending |
| App-native favorites, notes/tags and categories | Implemented; native browser favorites are link-only until live snapshots are validated |
| Images, assets, outbound file sharing | Implemented narrow image pipeline and actual-file grants; actual X downloads and receiver apps unverified |
| Complete video | Not implemented; manifests, blob URLs and fragments rejected |
| Ad/subscription/recommendation cleanup | Reversible rules pending; no entitlement bypass or hiding security prompts |

## Implementation direction

Review ChatGPT Studio's Android lifecycle, Profile, controlled windows and native-initiated observation patterns. Preserve Xs SQLite/FTS/UI and write an X-specific packaged observer. Prefer bounded native-pull results for the first history loop; a generic bidirectional bridge is not a prerequisite. Returned data stays untrusted and must pass context, consent, generation and navigation checks again at write time.

Offline observation, migration, favorite and media implementation can proceed alongside device verification. Do not default-enable unvalidated live capture. The old interpretation that all work must wait for W0 is not the current execution order.

Delivery: **A browsing/immediate open history → early B optional no-click feed dwell plus favorites/images/assets/sharing → C own reposts/videos/cleanup → D dwell performance/retention and release hardening.**

## Available manual flow

Use “Save this post locally” / “My local archive” in the browser menu, or the incoming Android “Save to Xs” action. Native confirmation creates a local space and saves the link without fake reading events. This is a companion entry, not a permanent substitute for automatic history.

## Privacy, migration and deletion

Separate consent for open history, dwell and filtering. No passwords, verification codes, cookies/tokens, DMs, drafts or hidden-state collection. X pages still use their normal network; Xs adds no archive-upload service. Explicitly shared files are then handled by the receiving app, so they are not guaranteed to remain on-device afterward.

Unfavorite, clear history, delete asset, clear cache, X logout and delete library are distinct. Preserve legacy account_id mappings instead of silent merging or destructive recreation. Queued imports/downloads/shares must not revive cleared data. Existing SQLite is not claimed encrypted; webpages get no whole-archive reads, arbitrary SQL, export or erasure capability.

## Layout and checks

```text
app-android/          Browser, native favorites/image selection/sharing, fixture integration
xs-runtime/           Android on-device archive library
dev-harness/          Offline synthetic host, NOT X
src/main/java/        URL/v3 schema/query, capture state, bounded writes, strict JSON, media policy
src/android/java/     SQLite/import/library UI, native pull, images and grant-only sharing
tests/                JVM/SQLite/regression tests
scripts/              Engineering checks
docs/                 Current plans, decisions and acceptance
compat/               Device matrix and legacy APK metadata
```

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
# Android SDK, JDK 17, Gradle 8.9:
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

[W0 execution evidence](docs/W0-EXECUTION.md) · [Device matrix](compat/web/W0-MATRIX.md). A documentation commit is not a new build/test result. Synthetic/emulator passes do not replace actual login, recipient-file compatibility or personal-data upgrade testing.

## License and disclaimer

The operative root text remains [GNU GPL v3.0](LICENSE). The owner permits a custom-license direction; see [LICENSING](docs/LICENSING.md). This plan update does not replace the license, invent commercial restrictions, remove third-party notices or rewrite earlier grants.

Xs is unaffiliated with X Corp. and includes no official package, credentials or subscription entitlements. Process/download only authorized, permitted resources without DRM, payment or access-control bypass. Source licensing does not grant third-party content/service rights; no zero-risk or store-approval promise.
