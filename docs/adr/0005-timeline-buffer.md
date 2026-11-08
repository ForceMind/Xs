# ADR-0005: Independent temporary timeline buffer

Date: 2026-11-07
Status: Accepted and implemented in source; execution evidence is tracked separately. Live adapters remain OFF.

## Context

X may refresh its timeline before a person can find an earlier card. The existing immediate-open and qualified-dwell history intentionally exclude many quickly passed cards.
The owner confirmed an independent, bounded recent-timeline buffer in [Issue #6](https://github.com/ForceMind/Xs/issues/6), continuing the existing PR #5 rather than replacing its architecture.
The accepted baseline is head `edf7906eee3c034d26631531feca822d15437ccc`, tree `7e5e8e6560fd766a51bb2d4c621d53ecab97fe64`; CI83 validates that baseline, not this new feature.
Archive v5 preserves accepted snapshots, while its retention removes only event detail. Reusing that retention would leave supposedly expired temporary text and FTS behind.

## Decision

1. Add a separate **recent timeline** feature, independently OFF by default. Default TTL is 48 hours, selectable 24/48/72 hours or seven days, custom integer 1–168 hours.
2. Start F1 with an independent temporary SQLite schema1 and FTS, preserving Archive v5 and persistent retention. If later promotion identity/source needs Archive changes, revise this ADR and use an explicit preserving schema migration with backup/import/consolidation and upgrade regression; never add unversioned fields/tables or unknown source values to schema5.
3. Apply one application-wide budget, default 100 MiB and configurable 25–500 MiB. Count actual DB, index, WAL/SHM, maintenance logs and temporary compaction files, reserving write/compaction headroom; also bound fields, records, batches and queues.
4. Admit only allowed, foreground timeline cards actually intersecting the viewport and observed by the bounded DOM adapter. There is no dwell minimum for this feature; hidden/prefetched/unobserved cards are outside the coverage promise.
5. Keep the existing 60% readable-area and continuous 1.2/3/5-second FEED criteria. Buffer admission creates neither FEED nor OPEN, FAVORITE, own-action or read-completion facts.
6. Store projected post metadata and visible-text snapshots only. No media downloads, raw network responses, headers, credentials, cursors, DMs, drafts or hidden state. Any future network-response adapter is a separate scope decision and validation.
7. Native code controls library, tickets, clocks, document/session/batch identities and observation order. Capture provenance, verified timeline source and batch reason are distinct. Batch identity denotes local browsing/refresh provenance, not a server response. DOM-inferred refresh remains explicitly uncertain.
8. Deduplicate one post within a native batch; enrich without changing first position or renewing its TTL. A new batch may hold a new reference to the same content version. Each reference expires from its own first capture time under the current TTL policy. Fix the expired set under the old policy before a settings change; extending TTL cannot revive already logically expired entries.
9. Every query, search, count and preview excludes expired references immediately. GC separately removes references, unreferenced text/FTS and physical storage. Evict oldest entries under pressure, then pause additions if necessary; disclose actual coverage and drops.
10. Reuse `XsLibraryWrites`, its instance-bound tickets and durable library tombstones. Add buffer-specific invalidation and ordered erasure without needlessly invalidating other feature tickets. Validate again at actual writes and serialize global budget decisions.
11. Explicit long-term save/favorite uses stable promotion identity and the existing persistent semantics. Commit the Archive result before acknowledging the temporary row; recover/retry idempotently. Do not claim an atomic transaction across separate WAL databases.
12. Preserve captured provenance and partial/link-only quality during promotion. A local preview does not open a post; promoting a buffer entry cannot assert detail evidence or invent measured dwell. Existing snapshot selection policy still applies.
13. Exclude temporary contents from existing backups, restores and consolidation. Clearing the buffer does not clear long-term history, favorites or media; whole-library deletion covers both stores and cannot be revived by old callbacks or restart.
14. Background cleanup is deferrable under Android scheduling. Expiry visibility is immediate on query; physical deletion after sleep/force-stop is not punctual or secure erasure. Account switching invalidates observation without changing native ownership.

## Consequences and implementation order

The first useful result is finding an actually captured card after refresh while offline. It does not restore X recommendation state or promise every server-returned post.
Temporary storage needs genuine text/index/WAL reclamation and shared quota tests; an event-row DELETE count is not sufficient evidence.
Cross-store promotion needs failure recovery and deduplication, not a rewrite of the existing library or a new generic bridge/network proxy.
Reuse the packaged X-specific observer/native-pull pattern, route filtering, native lifecycle and write barriers; add independent buffer admission without changing qualified-dwell meaning.
Capacity eviction may shorten the configured time window, so the UI must show actual coverage, dropped entries and paused reasons rather than promise complete 48-hour coverage.

Implement **F1** policy/store/FTS/TTL/bytes, **F2** synthetic DOM/native batches, **F3** native list/settings/promotion, **F4** GC/pressure/erasure recovery, then **F5** four-API regression and evidence.
[T01–T14 and concrete exits](../TIMELINE_BUFFER_PLAN.zh-CN.md) are authoritative for this work package. F0 docs do not satisfy any runtime exit.
Issue #3 real-X/W0 and physical-device checks remain an independent live-enablement gate. Synthetic success does not authorize real capture or production publication.

## Alternatives rejected for this slice

- Treat every card as `FEED_VISIBLE`: corrupts continuous-dwell filters and reading semantics.
- Save each original API response: exceeds the approved metadata/DOM boundary and captures unrelated hidden state.
- Put temporary snapshots in Archive v5 and prune history rows: fails actual text/FTS expiry and changes persistent retention.
- Keep an unbounded WebView/JS-only list: loses restart/offline search, native ownership, durable erasure and measurable quota control.

## Implementation amendment: versioned durable promotion

Archive schema6 adds only an additive `timeline_promotions` ledger and the explicit `native_timeline_buffer` snapshot source. v5 data/FTS is preserved; schema3/4/5 backup normalization, metadata consolidation and fixed old schema2 APK upgrade target6 are covered by new regressions. The ledger stores stable promotion identity, original capture time/source/batch/provenance/quality and separate explicit save/favorite times. It creates LOCAL_SAVE/favorite only; no OPEN/FEED or fabricated detail evidence. Buffer schema1 remains separate and excluded from backups. Archive commit precedes temporary acknowledgement; a missing acknowledgement never undoes a successful permanent save. See [execution evidence](../XS16_IMPLEMENTATION_EVIDENCE.zh-CN.md).
