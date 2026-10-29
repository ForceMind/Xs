# Xs · A cleaner, more useful X

[简体中文](README.md) | [English](README.en.md)

**Xs is an independent, local-first enhancement project for using X.** As of 2026-10-29, the accepted route is **Web-first**: the official X website provides browsing, while a native local archive adds history, search, categories and personal-content management. Patching the official Android APK is no longer the primary route. There is no new social backend, account service or API proxy.

> **This is an accepted direction, not a completed web client.** The repository contains the Java/SQLite core, Android archive UI, offline importer and tests. A WebView host, web observers, real login and end-to-end web integration remain unimplemented/unverified. There is no complete installable Xs client yet.

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
| Web-first host and login | Pending W0 live validation; browser compatibility documentation is not login proof |
| Usable client APK | Not available; dev-harness is a synthetic-data development host |

## Principles

Capture defaults off. Deliberate opens and qualified feed exposure are separate; prefetch and import never become reading events. Publication, action and local observation times remain distinct, and unknown times remain unknown. New archive data is not uploaded. X web still uses the network and WebView-managed sessions; Xs does not independently copy, export or log credentials or DMs.

The website cannot read the full archive, execute SQL, access arbitrary files or enable capture. Existing SQLite must not be described as encrypted. Provider terms, account risk, backup exclusions and distribution requirements are separate gates.

## Existing layout

```text
xs-runtime/           Android library: archive UI and storage
dev-harness/          Offline synthetic test host (NOT X)
src/main/java/        Platform-independent URL, query, schema and policies
src/android/java/     Android SQLite, bridge and archive UI
tests/                JVM, SQLite and other regressions
scripts/              Project checks
docs/                 Product, architecture, plans and decisions
compat/               Legacy APK metadata checks; not a working web security gate
```

Proposed `app-android/` and `web-enhancer/` modules still need implementation.

## Test the existing core

Requires JDK 17+ and Python 3:

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
# Android SDK / JDK 17 / Gradle 8.9 environment:
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest
```

The existing importer handles one user-selected posts-JS file/part of up to 16 MiB, without executing JS or inspecting other ZIP contents. Reimporting does not fabricate reading events. Multipart/ZIP expansion remains future work.

Legacy APK integration documents and the empty allowlist remain historical assets, not prerequisites for Web-first. This documentation migration adds no runtime functionality and is not evidence of a new build or live test.

## License and disclaimer

[GNU GPL v3.0](LICENSE). Xs is unaffiliated with X Corp. and includes no official X package or credentials. Evaluate applicable law, third-party authorization and terms before use or distribution. Local-only storage does not imply platform permission, zero account risk or store approval.
