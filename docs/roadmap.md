# Xs delivery roadmap — current A–D plan

当前v5与可靠性源码见 [本轮证据](ROUND_D_EVIDENCE.zh-CN.md)；API26/30/36 CI与新增UI验证当前PENDING。历史 [阶段C](ROUND_C_EVIDENCE.zh-CN.md) 和 [早期实施记录](IMPLEMENTATION_EVIDENCE.zh-CN.md) 仅覆盖各自提交。
Updated 2026-11-07. Start at [documentation index](README.md). Product authority: [ADR-0002](adr/0002-local-library-media.md); implementation: [ADR-0003](adr/0003-native-pull-delivery.md). The older Web-first plan remains historical/non-conflicting guidance, not a reason to revert app-owned libraries or block offline work.

## Present baseline

The A/early-B/C offline implementation remains. Current source adds v5 snapshot versions/current-pointer/historical-text search, batch favorites and paged asset management, JobScheduler with sealed pending URLs, passphrase/chunked AES-GCM resource backup and new-scope crash recovery, durable whole-library tombstones/default rotation, and coalesced/capped dwell writes. New SAF/backup/consolidation/batch/deletion UI and API26/30/36 tests are implemented; their source-specific execution is PENDING. Historical source `4ee242e` / CI42357220689 passed 933 JVM assertions and 146 API30/36 cases, but does not validate this increment. API27/physical-device/recipient/multi-day power evidence remains absent; live X capture/actions/cleanup remain disabled, and C/D/release are not complete.
The original requirements baseline `eb5aa4dbbb0499fb632202a27aaf24e898718818` remains historical; it is not a current HEAD assertion. Always inspect current main, open PRs and source-specific CI before continuing.

Do not replace this status with a percentage. Use the [XS-01–XS-15 work packages](IMPLEMENTATION_PLAN.zh-CN.md) and [acceptance IDs](ACCEPTANCE_MATRIX.zh-CN.md), with evidence for each completed scope. Docs-only updates do not rerun or pass runtime tests.

## Execution order

| Batch | Delivery | Work packages | Exit evidence |
| --- | --- | --- | --- |
| A | Browser foundation + app-owned library + immediate open history + searchable snapshots | XS-01–06 | No dwell gate, no duplicate reload/rotation events, preserving migration, offline/restart retrieval and real-site browser acceptance |
| B | **Baseline optional feed dwell without opening**, native favorites/categories + images + assets + outbound sharing | XS-14 (baseline), XS-07–10 | Opt-in 1.2/3(default)/5s dwell, long card, duration/non-opened offline search, no fake opens; real files/sharing and independent deletion |
| C | Confirmed own reposts + standard video + reversible promotional cleanup | XS-11–13 | Successful actions not menu clicks; complete playable files; restore-original toggle; sensitive UI unaffected |
| D | Dwell performance/retention/analytics hardening + necessary advanced media + privacy/storage/release | XS-14 (hardening)/15 and tested extensions | Long-scroll/overlay/low-memory and storage/quota performance, migration/restore, stable signing and real compatibility scope |

A and B do not wait for a general bridge platform or full multi-account service. XS-02 browser work and XS-03/04 storage/write work can proceed in parallel; observer and share/download fixtures can be developed before live deployment. Small reversible filter fixtures can also be built early; C is their acceptance batch, not a prohibition on earlier work.

## Live gates versus development gates

- W0 / [Issue #3](https://github.com/ForceMind/Xs/issues/3): actual required X login and core browsing; methods/results are independent. A working ChatGPT shell is a reusable engineering reference, not automatic proof for every X flow.
- New [Issue #4](https://github.com/ForceMind/Xs/issues/4): app-owned history/favorites/media/sharing, tracked by XS task and acceptance ID.
- Automatic X observation stays disabled in unvalidated real-site builds. Offline implementation is allowed and expected; no login bypass or copied session state.
- A direct-login conditional pass may define a limited test-build scope, not a claim that Google/Apple/Passkey all work. Real failures must distinguish provider refusal from incomplete window/navigation handling.
- Release requires real-device and exact recipient-app evidence, not just a generated APK, CI green check or synthetic webpage.

## Mapping the previous W0–W5 labels

W0 remains browser feasibility; W1 security/library ownership is implemented within A; W2 usable history expands across A/B; W3 social/feed/filters/media maps to B/C/D; W4 privacy/upgrade/release maps to D. W5 remains a later independent platform decision. Do not restart the project or run the obsolete APK-patching track in parallel.

The primary client remains Android. Standard desktop browser extensions are a potential alternative/next platform if evidence calls for it; do not simultaneously adopt multiple browser engines and four desktop/mobile stacks.

## Next executable slice

1. Execute current core/SQLite/DOM/build/lint plus API26/30/36 suites, including actual SAF/new backup/consolidation/batch/deletion UI. Fix failures and record exact source/artifacts. Do not treat compilation or earlier API30/36 success as current UI acceptance.
2. Retain v1–v4→v5 migration, snapshot policy/source/current-pointer and full historical-text evidence. COMPLETE means accepted visible DOM text; newer shorter COMPLETE can become current, PARTIAL cannot downgrade it, and all versions remain. Keep source/default scopes unchanged during consolidation.
3. Exercise JobScheduler and sealed pending-source durability under real process/system stop, quota/network/storage pressure and exact OS versions. API34+ tries UIDT for explicit user requests; earlier/fallback jobs are ordinary persisted work. Maximum 3 total transient attempts, each from byte zero. Missing jobs remain paused until explicit retry; no stop-bypass, range-resume or prompt-background guarantee.
4. Validate passphrase full-resource recovery in isolation: ≤512MiB/500 completed assets, inner metadata≤16MiB/20,000; full authentication and media checks, new UUID, media-first/archive-last and crash markers. Preserve plaintext metadata choice and explicit 2–4-source metadata consolidation≤8MiB/20,000. Neither path exports sessions/keys/tasks/pending URLs/leases; the working database is not encrypted.
5. Add API27 and physical-device long-scroll/capacity/power/multi-day soak, upgrade/rollback and exact recipient-app playback/receive evidence. v5/batch/tombstone logic and one-second dwell coalescing are source work, not those field results.
6. Configure a reviewed production signing identity only through an explicit acceptance workflow; current manual main-ancestor/certificate-pinned candidate tooling is not a release. Finish rights/notice review without removing historical GPL grants. Validate live X login/selectors/action confirmation/cleanup separately before enabling any live adapter.
Consolidation commits the metadata snapshot shown during user preview. Ordinary edits after preview are not included automatically and need a new preview; source erasure/epoch invalidation rejects commit. Conflicting sources for one legacy-action identity reject consolidation. Source libraries/default stay intact. Immediate undo applies only to the just-created result with unchanged metadata and no media/download tasks; changed or media-bearing results are rejected without invalidating existing tasks.

## Historical milestones retained

- 2026-11-07 current increment: v5 versions, system download durability, encrypted resources, batch management, tombstone deletion and signed-candidate tooling implemented; current CI PENDING, no production release.

- 2026-10-29: [ADR-0001](adr/0001-web-first.md) adopted Web-first over official APK modification.
- 2026-11-02: W0 PoC authored; see [execution record](W0-EXECUTION.md) and [device matrix](../compat/web/W0-MATRIX.md).
- 2026-11-05: PR #2 merged as `7b06e5c81da5a34b06266554d61bf1b65896b845`; later baseline `67d2a48d01dbd08e866d0c991ef08bbaa4f09531` added manual links/share and inactive observation checks. Live W0 was not marked passed.
- 2026-11-07: v4 own-action/reversal, narrow standard-MP4, cleanup fixtures and metadata portability/consolidation added; 933 JVM assertions, SQLite/DOM, builds and 146 API30/36 instrumentation cases pass in CI 42357220689. API26/27, SAF/new UI and real-site acceptance remain NOT RUN. See the new evidence; live and release gates stay open.
- 2026-11-06: requirements commit `eb5aa4dbbb0499fb632202a27aaf24e898718818` established app-owned favorites/downloads/sharing. This roadmap consolidation records the implementation work still needed.

See [change record](CHANGELOG.md). No calendar promise replaces an actual acceptance result.
