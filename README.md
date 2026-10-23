# Xs — X Local Enhancement

Android-first, local-first enhancement project for the existing X client.
**Xs is not a separate social-network client** and does not host an alternative X backend.

## Product direction

Preserve familiar X login, feed, posting, messaging and navigation. Add an
opt-in, per-account local history and knowledge-management layer:

- Hide promotional clutter and unnecessary membership upsell UI through a
  version-checked upstream patch configuration.
- Record **deliberately opened** posts as events, not just a list of unique URLs.
- Search locally by keywords, author and date. Keep post-publication time separate
  from visit time and repost/action time.
- Organize saved posts, original posts, replies and reposts with local categories.
- Keep content and history on the device; no new remote backend or credential export.

## Project status — M0 foundation

**Implemented in this repository:**
- Piko/Morphe architecture and GPLv3 attribution research, pinned upstream revision.
- A pure Java URL normalizer, parameterized local-search SQL builder and SQLite schema.
- Android SQLite persistence/bridge source code designed for inclusion in a patch extension.
- Minimal dependency-free Java regression tests and CI workflow.

**Not implemented or verified yet:** hook to X's in-app post-detail navigation,
a visible X-integrated history screen, end-to-end Morphe patch compilation, an APKM,
device testing, or actual ad filtering in Xs. Upstream Piko already has separately
documented ad and Premium-removal patches. Do not infer we ship them in this commit.

## Layout

- docs/research/piko.md — actual Piko structures and migration map.
- docs/product.md — confirmed scope, event semantics and UX.
- docs/architecture.md — schema, integration layers and privacy.
- docs/roadmap.md — prioritized development gates.
- upstream/piko.lock — exact upstream revision.
- scripts/fetch-piko.sh — retrieve upstream source separately.
- src/main/java — testable standalone Java core, no Android dependency.
- src/android/java — Android-specific SQLite storage and event bridge.
- tests/java — standalone regression tests.
- .github/workflows — Java core smoke-test CI.

## Core tests

    ./scripts/test-core.sh

Requires a JDK with javac (Java 17+ recommended). Does not require an APK,
Android SDK, X account, third-party API, or network.

## Upstream and licensing

Reference: https://github.com/crimera/piko (GPL-3.0).
Pinned upstream commit: a0ed9a5ae782fe66e02294e5a49d235e2f139b24 (release v3.10.0).
Only independent Xs sources and the standard GPLv3 license text are stored here;
Piko's Java/Kotlin patch sources are **not copied**. Any future incorporation of
Piko source must retain attribution/NOTICE and comply with GPLv3, including its
additional section 7(b) attribution notice. See upstream/NOTICE.md.

For research/development on software you are permitted to inspect.
X is a trademark of its respective owner; Xs is independent and unaffiliated.
Patched APKs, account tokens and user content must never be checked into Git.

## Reproducible Piko source checkout

Piko is referenced as a pinned Git submodule at third_party/piko. To retrieve
its complete source tree into a normal Xs checkout:

    git submodule update --init --recursive

Or run scripts/fetch-piko.sh from the repository root. The submodule is
upstream GPL-3.0 source, not a bundled proprietary X APKM. Its source code
and history remain independently maintained and attributed.
