# Xs delivery roadmap — current A–D plan

当前代码进度以 [本轮实施与验收记录](IMPLEMENTATION_EVIDENCE.zh-CN.md) 为准；下文保留需求基线，待验收项不因源码存在而自动通过。

Updated 2026-11-06. Start at [documentation index](README.md). Product authority: [ADR-0002](adr/0002-local-library-media.md); implementation: [ADR-0003](adr/0003-native-pull-delivery.md). The older Web-first plan remains historical/non-conflicting guidance, not a reason to revert app-owned libraries or block offline work.

## Present baseline

At `eb5aa4dbbb0499fb632202a27aaf24e898718818`, the repository contains a W0 browser host, local SQLite/FTS archive and UI, manual link save, incoming share, posts-JS import, and inactive observation policy/parser tests. New native favorites, automatic X history, media downloads and outbound file sharing are requirements, not delivered features.

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

1. XS-01: verify the current source/toolchain, pinned reuse inventory and licenses without moving private user data or keys.
2. XS-02 and XS-03/04 in parallel: browser lifecycle/window support plus library migration/write invalidation fixtures.
3. XS-05/06: prove a synthetic click → valid detail → one immediate event → late text enrichment → native offline search/category → return loop.
4. XS-14 baseline in parallel with early B: synthetic timeline visible ≥ selected 3s default → FEED_VISIBLE measured duration → offline search including never-opened filter, with background/long-card tests.
5. Bring both loops into an explicitly validated live X context, then complete B/C; do not substitute more manual link-save entry points for the core history goal.

## Historical milestones retained

- 2026-10-29: [ADR-0001](adr/0001-web-first.md) adopted Web-first over official APK modification.
- 2026-11-02: W0 PoC authored; see [execution record](W0-EXECUTION.md) and [device matrix](../compat/web/W0-MATRIX.md).
- 2026-11-05: PR #2 merged as `7b06e5c81da5a34b06266554d61bf1b65896b845`; later baseline `67d2a48d01dbd08e866d0c991ef08bbaa4f09531` added manual links/share and inactive observation checks. Live W0 was not marked passed.
- 2026-11-06: requirements commit `eb5aa4dbbb0499fb632202a27aaf24e898718818` established app-owned favorites/downloads/sharing. This roadmap consolidation records the implementation work still needed.

See [change record](CHANGELOG.md). No calendar promise replaces an actual acceptance result.
