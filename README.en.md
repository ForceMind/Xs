# Xs · A cleaner, more useful X

[简体中文](README.md) | [English](README.en.md)

**Xs is an independently developed, Android-first local enhancement project.** It is designed for the existing X (formerly Twitter) client. It is not a replacement social network, account system, feed backend, or new X client.

Our goal is to retain the familiar experience of reading, posting, replying, reposting, messaging, and notifications while giving users better ways to organize and find content.

> Early development. The local archive core, Android storage layer, and tests exist. **There is not yet an installable modified X APK**, nor a verified integration with X or on-device end-to-end test.

## Planned capabilities

| Feature | Experience | Current status |
| --- | --- | --- |
| Cleaner UI | Reduce promoted content, noisy recommendations and repetitive subscription prompts | Planned; not integrated |
| Reading history | Record each deliberate post open with its own timestamp | Storage implemented; client event hook pending |
| Visit timeline | Revisit posts by the time they were opened | Storage implemented; UI in progress |
| Fast local search | Search text, author, dates and categories | Query core implemented; UI in progress |
| My content | Distinguish original posts, replies, quotes and reposts | Data model prepared; client hook pending |
| Local folders | User-defined categories that work offline | Storage implemented; UI in progress |
| Account isolation | Separate local indexes for different X accounts | Core implemented; device verification pending |

## Principles

- **Enhance the original client** rather than build a parallel social application.
- **Local-first**: new history, organization and indexes remain on the device.
- **Precise events**: post-detail opens differ from feed impressions or background prefetch.
- **Honest timestamps**: post publication, repost actions and local visits are distinct. Unknown times stay unknown.
- **Privacy by default**: capture is opt-in; no credentials, session tokens, DMs, or arbitrary traffic capture.
- **Independent codebase**: Xs maintains its own models, storage, UI and future version adapters.

## Repository layout

```text
xs-runtime/           Android library (native history UI + local database)
src/main/java/        Android-independent URL, query and schema core
src/android/java/     Android SQLite, capture bridge and UI
tests/                JVM and SQLite regression tests
scripts/              Developer test tooling
docs/                 Requirements, architecture and roadmap
.github/workflows/    Continuous integration
```

## Run core tests

Requires JDK 17+ and Python 3:

```bash
git clone https://github.com/ForceMind/Xs.git
cd Xs
bash scripts/test-core.sh
```

See [architecture](docs/architecture.md) for the Android library build. An Android Library is not an installable patched X APK.

## Development

- [Product requirements](docs/product.md)
- [Architecture](docs/architecture.md)
- [Roadmap](docs/roadmap.md)
- [M1 integration tracker](https://github.com/ForceMind/Xs/issues/1)

First deliver the on-device archive UI, search and categories; then validate a version-specific client event integration and independent patching adapter on a test installation. No release claim before actual device validation.

## License and disclaimer

Licensed under [GNU GPL v3.0](LICENSE). Xs is independent and unaffiliated with X Corp. X names and trademarks belong to their respective owners. Users must comply with applicable laws, authorization, and service terms when modifying or distributing third-party software. This repository contains no official X APK or account credentials.
