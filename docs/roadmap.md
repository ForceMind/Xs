# Xs development roadmap

## M0 — independent foundation

- [x] Define original-client enhancement, local-first scope and privacy boundaries.
- [x] Implement Android-independent URL parser, SQLite schema and local query builder.
- [x] Implement Android SQLite data store and recording bridge (no client hooks yet).
- [x] Add JVM, SQL trigger, referential-integrity and account-isolation tests.
- [x] Add CI.
- [x] Provide Chinese and English README.
- [x] Remove all external-source submodules and implement independent core.

## M1 — useful offline archive layer

- [x] Build Android Library AAR in CI.
- [x] Implement native History / Search / Categories Activity.
- [x] Add explicit per-account opt-in capture state and clear-local-data controls.
- [x] Add dated visits/search/category CRUD Android instrumentation tests (CI run pending green confirmation).
- [ ] Verify the database and UI in Android emulator instrumentation (job added; confirmation pending).
- [x] Add opt-in separately captured feed-visible events, SQL v1→v2 migration, accurate event dates, and paged search/history UI.

## M2 — X client integration

- [ ] Research compatible client versions using authorized test builds.
- [ ] Implement independent version-verified post-detail and account lifecycle adapter.
- [ ] Inject runtime and manifest entry into an authorized test app build.
- [ ] Integrate history navigation without replacing the original client.
- [ ] Implement optional UI filtering for ads, recommendations and upsell surfaces.
- [ ] Test client login, browsing, post-open semantics, navigation and upgrades.

## M3 — personal posts and release readiness

- [ ] Record original posts/replies/quotes/reposts separately from visiting.
- [ ] Add advanced date basis and action filters, batch categories and notes.
- [ ] Add secure backups, migrations and retention controls.
- [ ] Add benchmarks, localization and device compatibility matrix.
- [ ] Produce documented reproducible installs and release QA.

Do not mark any APK-, hook-, login-, or on-device milestone complete until tested
against a specific supported build. Core code and AAR alone are not a usable
enhanced X application.
