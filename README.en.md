# Xs · A cleaner, more useful X

[简体中文](README.md) | [English](README.en.md)

**Xs is an independent, local-first enhancement project for using X.** As of 2026-10-29, the accepted route is **Web-first**: the official X website provides browsing, while a native local archive adds history, search, categories and personal-content management. Patching the official Android APK is no longer the primary route. There is no new social backend, account service or API proxy.

> **This is a W0 browser feasibility build; real login is not accepted yet.** The new `app-android` system-WebView host and synthetic tests are implemented. Web observation, native bridge, capture and filtering are absent. This is not a complete Xs client. [Execution record](docs/W0-EXECUTION.md) · [Compatibility matrix](compat/web/W0-MATRIX.md)

## Authoritative plan

- [Complete Web-first product and engineering plan (Chinese)](docs/WEB_FIRST_PLAN.zh-CN.md)
- [ADR-0001 (English)](docs/adr/0001-web-first.md)
- [Product](docs/product.md) · [Architecture](docs/architecture.md) · [Roadmap](docs/roadmap.md)

Prove real login and essential browsing before implementing capture. Test each login method separately. External browser sessions do not automatically transfer to WebView; never copy cookies or bypass provider restrictions. If the embedded route fails, evaluate an extension in a real browser rather than assuming another embedded engine fixes authentication.

## Capabilities and actual state

| Capability | State |
| --- | --- |
| History, dated search, categories | Local data layer and native UI exist; web event integration pending |
| Feed observations | Local policy exists: at least 60% visible continuously for 1.2 seconds, separate opt-in; web visibility integration pending |
| My-content import | Official `tweets.js` / `tweet.js` single-file/part importer exists; user confirms ownership |
| Account/archive isolation | Existing core scopes data by account; web binding and browser-session isolation still require design and verification |
| Cleaner interface | Web filters pending; no paid-feature unlocking or hiding security prompts |
| Web-first host and login | W0 merged; explicit native save-post-link flow is implemented, while live login/media require device validation |
| APK | CI supplies an internal W0 PoC, not an accepted Alpha; dev-harness remains synthetic |

## Manual local bookmarking — available for testing

W0 now exposes **Save this post locally** and **My local archive** in the browser menu. You can also use Android's system share sheet from the existing X app to choose **Save to Xs**, then explicitly confirm local saving of the public post link. First use explicitly creates an on-device archive space; this is **not** a verified X account identity. Native search, folders, and post-return navigation work with manually saved links.

Only the public post URL/ID and local save time are stored; **no DOM, cookies, DMs, automatic reading history, or feed tracking**. Automatic observation remains gated on successful real-device W0 login/browser checks. The APK is still an internal PoC, not an accepted production client.

## Principles

Capture defaults off. Deliberate opens and qualified feed exposure are separate; prefetch and import never become reading events. Publication, action and local observation times remain distinct, and unknown times remain unknown. New archive data is not uploaded. X web still uses the network and WebView-managed sessions; Xs does not independently copy, export or log credentials or DMs.

The website cannot read the full archive, execute SQL, access arbitrary files or enable capture. Existing SQLite must not be described as encrypted. Provider terms, account risk, backup exclusions and distribution requirements are separate gates.

## Existing layout

```text
app-android/          W0 system-WebView feasibility host (no enhancements)
xs-runtime/           Android library: archive UI and storage
dev-harness/          Offline synthetic test host (NOT X)
src/main/java/        Platform-independent URL, query, schema and policies
src/android/java/     Android SQLite, bridge and archive UI
tests/                JVM, SQLite and other regressions
scripts/              Project checks
docs/                 Product, architecture, plans and decisions
compat/               Legacy APK metadata checks; not a working web security gate
```

`app-android/` has native manual-save/local-space entry points. `ObservationGate` is an inactive guard foundation, not a WebView bridge. Web observation remains gated on real W0 login evidence.

## Test the existing core

Requires JDK 17+ and Python 3:

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
# Android SDK / JDK 17 / Gradle 8.9 environment:
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

The existing importer handles one user-selected posts-JS file/part of up to 16 MiB, without executing JS or inspecting other ZIP contents. Reimporting does not fabricate reading events. Multipart/ZIP expansion remains future work.

Legacy APK integration documents and the empty allowlist remain historical assets, not prerequisites for Web-first. W0 adds runtime code and CI tests. See the execution record for actual outcomes; synthetic pages do not establish real X login.

## License and disclaimer

[GNU GPL v3.0](LICENSE). Xs is unaffiliated with X Corp. and includes no official X package or credentials. Evaluate applicable law, third-party authorization and terms before use or distribution. Local-only storage does not imply platform permission, zero account risk or store approval.
