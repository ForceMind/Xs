# Xs · A cleaner, more useful X

[简体中文](README.md) | [English](README.en.md)

**Xs is an independent, local-first enhancement for using X.** Official X web supplies browsing and social functions; an app-owned on-device library provides history, local favorites, categories and personal-content indexing, with image/video downloads, asset management and outbound system sharing now in the accepted scope. No official X APK patching, new social backend or account proxy.

> **Still a W0 feasibility build, not a complete client.** Browser hosting, native archive, manual link saving and incoming “Save to Xs” sharing have code. Automatic web capture, full favorite snapshots, media downloading and outbound file sharing are not delivered. Real X login/media and physical-device compatibility remain acceptance gates.

## Authoritative scope

- [Product requirements](docs/product.md) · [Local library/media implementation specification (Chinese)](docs/LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md)
- [ADR-0002: app-owned library and media](docs/adr/0002-local-library-media.md) — overrides conflicting ownership, trigger and media assumptions in earlier plans.
- [Web-first baseline plan](docs/WEB_FIRST_PLAN.zh-CN.md) · [ADR-0001](docs/adr/0001-web-first.md)
- [Existing architecture](docs/architecture.md) · [Historical roadmap](docs/roadmap.md) · [Inactive observation contract](docs/contracts/observation-v1.md)
- [Custom-license direction](docs/LICENSING.md)

## Confirmed product goals

**Record deliberate opens immediately:** after opt-in, entering a post detail inside Xs records history without another save tap or a dwell threshold. Feed exposure/dwell has its own optional event. Refresh, rotation and content enrichment must not inflate counts. Own repost actions require confirmed success or an ownership-confirmed import.

**Favorites belong to Xs:** a distinct “Favorite in Xs” control saves visible content, link and provenance with local organization. It does not create an X server bookmark, like or repost. The library belongs to the application, not the currently authenticated X account; X logout/switching must not delete or switch local favorites, history and assets.

**Downloads and assets:** explicit image, image-set and video downloads, local preview, source links, organization, task management and deletion. Streaming formats, resumability and quality support require implementation and actual testing. Caches, manifests or partial files are not complete downloads.

**Share actual files:** send selected links, text, images or videos through the Android Sharesheet to WeChat, QQ, Feishu, Telegram or other installed apps that support the MIME/size. The user chooses a conversation and confirms sending. Direct Share targets depend on the receiving app. Xs neither reads messaging credentials nor automates sending, and cannot infer delivery from opening a chooser.

## Capabilities versus implementation

| Capability | Current status |
| --- | --- |
| Android WebView host | Implemented; live X login and sustained physical-device usage pending |
| SQLite/FTS, date search, categories, native archive | Foundation exists; preserving account-scope to app-library migration pending |
| Manual post-link saving and incoming share to Xs | Implemented; a saved link is not a body snapshot or automatic history |
| Official tweets.js/tweet.js import | One user-selected part up to 16 MiB; ownership confirmation required |
| Automatic opens, optional feed dwell, own repost observation | Local event foundations exist; live web observation and end-to-end acceptance pending |
| App-native favorite button and complete local snapshot workflow | Confirmed requirement; not equivalent to existing link-only saving |
| Image/video downloader, asset manager, outbound file sharing | Accepted scope; implementation and recipient-app validation pending |
| Ad/subscription promotion cleanup | Reversible rules pending; no paid-entitlement bypass or hiding security prompts |

## Available manual testing flow

Use the browser menu's “Save this post locally” / “My local archive”, or Android's incoming “Save to Xs” share target. First use explicitly creates a local space, not a verified X account. Repeated saves do not fabricate read events.

This mode does not inspect page DOM, cookies or DMs and does not automatically track the feed. It complements the core product; it is not a permanent substitute for automatic history inside Xs.

## Privacy and data behavior

Capture defaults off with separate consent. Only allowed post contexts are observed, never login, verification, DMs or composers. No Xs cloud upload/sync; normal X pages still use the network. Explicitly sharing to another app transfers selected content to that app, which may upload it according to its own behavior.

Unfavorite, clear history, delete saved media, clear cache and X logout are separate actions. Preserve legacy scopes and content during migration rather than silently merging or recreating storage. Maintain truthful timestamps/provenance/snapshot quality. Existing SQLite is not claimed to be encrypted. Webpages cannot query the whole archive, execute SQL, export, erase or enable capture.

## Existing layout and checks

```text
app-android/          W0 host, native manual link saving and incoming share
xs-runtime/           Android local-archive library
dev-harness/          Offline synthetic test host (NOT X)
src/main/java/        Java URL/query/schema and inactive observation policy
src/android/java/     SQLite/import/archive UI and observation-parser foundations
tests/                JVM / SQLite / other regressions
scripts/              Engineering checks
docs/                 Plans, decisions and execution evidence
compat/               Browser acceptance matrix and legacy APK metadata
```

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
# Android SDK, JDK 17, Gradle 8.9:
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

[W0 execution evidence](docs/W0-EXECUTION.md) · [Live-device matrix](compat/web/W0-MATRIX.md). A requirements update is not a new compile/test run. Synthetic tests do not establish real X login or actual file delivery to messaging apps. Offline development may run alongside device checks; production automation remains acceptance-gated.

## License and disclaimer

The current operative text is [GNU GPL v3.0](LICENSE). The owner allows considering a custom source license; see [the licensing decision](docs/LICENSING.md). This requirements update does not replace LICENSE, revoke historical/third-party grants, or invent new commercial restrictions.

Xs is unaffiliated with X Corp. and includes no official X package or credentials. Download only authorized, permitted resources; do not bypass DRM, login challenges or paid entitlements. Source licensing does not grant third-party content/service rights. Local storage does not imply provider permission, zero account risk or store approval.
