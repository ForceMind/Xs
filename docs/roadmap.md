# Xs delivery roadmap — current A–D plan

当前代码范围与限制见 [阶段 C 与资料治理证据](ROUND_C_EVIDENCE.zh-CN.md)：本地构建/Lint通过，Android仪器 **PENDING**。此前 A/早期 B 结果保留在 [既有实施记录](IMPLEMENTATION_EVIDENCE.zh-CN.md)；待验收项不因源码存在而自动通过。

Updated 2026-11-07. Start at [documentation index](README.md). Product authority: [ADR-0002](adr/0002-local-library-media.md); implementation: [ADR-0003](adr/0003-native-pull-delivery.md). The older Web-first plan remains historical/non-conflicting guidance, not a reason to revert app-owned libraries or block offline work.

## Present baseline

The existing A/early-B offline slice includes preserving SQLite migrations, native libraries/favorites, immediate-open/dwell fixtures, image tasks/assets and actual-file sharing. This increment adds v4 own-action/provenance/reversal records, explicit narrow standard-MP4 downloads, reversible cleanup fixtures, metadata backups/restoration and explicit consolidation into a new library. Local 933 JVM assertions, SQLite/observer/cleanup-DOM regressions and Android builds/lint pass; current Android instrumentation is PENDING. Live X capture, own-action adapters and cleanup remain disabled; C/D and release are not complete.

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

1. Complete this increment's API 30/36 instrumentation, inspect source/APK metadata, fix failures and update the [current evidence](ROUND_C_EVIDENCE.zh-CN.md). Keep previous passing builds separate.
2. Extend reliability/portability tests for native writes, v1/v2/v3→v4 migration, plaintext metadata restore, source-preserving consolidation, cancellation, low space and process death. Backup is limited to 16 MiB/20,000 metadata records; consolidation selects 2–4 libraries within 8 MiB/20,000 records. Neither includes media files/tasks or web sessions.
3. Preserve the native own-action boundary: only fixture-issued operation/result proof is currently enabled; archive/restored provenance is metadata, not authenticated actor authority. Keep unknown time/state honest and add a separately validated live adapter before recording actual X success/reversal.
4. Validate supported direct H.264/optional-AAC MP4 resources (≤100 MiB/10 minutes), full playback and target-app reception on exact devices. Current structure/sample checks plus first/tail decode are limited evidence; blob/HLS/DASH/fragments/DRM and unsupported formats stay rejected. Resume/background scheduling remain pending.
5. Validate actual X cleanup selectors and sensitive routes before attaching cleanup to the live browser. Three default-off toggles currently apply only to synthetic fixtures. Bring immediate-open and no-click dwell loops into an explicitly validated live context, retaining W0 and recipient gates.

Consolidation commits the metadata snapshot shown during user preview. Ordinary edits after preview are not included automatically and need a new preview; source erasure/epoch invalidation rejects commit. Conflicting sources for one legacy-action identity reject consolidation. Source libraries/default stay intact, and deleting the new result is the rollback path.

## Historical milestones retained

- 2026-10-29: [ADR-0001](adr/0001-web-first.md) adopted Web-first over official APK modification.
- 2026-11-02: W0 PoC authored; see [execution record](W0-EXECUTION.md) and [device matrix](../compat/web/W0-MATRIX.md).
- 2026-11-05: PR #2 merged as `7b06e5c81da5a34b06266554d61bf1b65896b845`; later baseline `67d2a48d01dbd08e866d0c991ef08bbaa4f09531` added manual links/share and inactive observation checks. Live W0 was not marked passed.
- 2026-11-07: v4 own-action/reversal, narrow standard-MP4, cleanup fixtures and metadata portability/consolidation added; local checks pass, current Android instrumentation PENDING. See the new evidence; live and release gates stay open.
- 2026-11-06: requirements commit `eb5aa4dbbb0499fb632202a27aaf24e898718818` established app-owned favorites/downloads/sharing. This roadmap consolidation records the implementation work still needed.

See [change record](CHANGELOG.md). No calendar promise replaces an actual acceptance result.
