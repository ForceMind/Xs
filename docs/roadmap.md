# Xs delivery roadmap — current A–D plan

Round E (XS-02/15) continues the verified D/CI75 baseline. BrowserUploadPolicy keeps the original immutable accept MIME/extension and single/multiple-selection policy (at most 8 files) for both picker and result, rejecting empty, duplicate, oversized-count or declaration-mismatched results. Every SPA URL change cancels the old upload; old state is detached before callbacks. The 132 new JVM assertions ran, bringing core to 1,287; SQLite/DOM/20k, 512MiB-container/32MiB-JVM and 10 host checks passed. Android builds, new native cases and this increment’s CI remain PENDING; earlier D results do not validate these changes. See [Round E evidence](ROUND_E_EVIDENCE.zh-CN.md).

Pending native targets are real SAF selection of a 5,783-byte MP4 with WebView FileReader/user-gesture playback, actual SAF selection of two new PNGs with per-file byte checks, and rejection of old results after a sensitive SPA route returns HOME. A separate same-job debug-signed driver upgrades an actual fixed old-main f88003c8ec1ee193b75de7267db6ae7654ee77c4 code1/schema2 installation to current code3/schema5. The default LegacyUpgradeProbe test is only a current-version round trip; actual old-APK upgrade is counted separately. Declared totals 624=4×(52+104) main cases and 20 external drivers=existing16+4 cross-source upgrades are not pass counts.

Upload MIME is a provider declaration, not proof of actual contents, completeness or upload to X. Download limits20/100MiB are not applied, and no new permissions, native whole-file reads or transcoding are added. Old versions have no independent favorites/media database, so those migrations are N/A; a generated file canary proves file preservation only. Live X/CDN/chat-app/physical-device/production-signing and disabled-live-adapter boundaries remain unchanged.

当前v5与可靠性源码见 [本轮证据](ROUND_D_EVIDENCE.zh-CN.md)；CI74云端回归全部通过；现场总体验收仍NOT RUN。历史 [阶段C](ROUND_C_EVIDENCE.zh-CN.md) 和 [早期实施记录](IMPLEMENTATION_EVIDENCE.zh-CN.md) 仅覆盖各自提交。
Updated 2026-11-07. Start at [documentation index](README.md). Product authority: [ADR-0002](adr/0002-local-library-media.md); implementation: [ADR-0003](adr/0003-native-pull-delivery.md). The older Web-first plan remains historical/non-conflicting guidance, not a reason to revert app-owned libraries or block offline work.

Verified Round D source `0d193e4689d649970d9705818d33e8706b470d8f` / tested tree `c8551cd4a09e4466cd84e7973039aba06a5f87b2`: all six [CI74](https://github.com/ForceMind/Xs/actions/runs/42386764024) jobs succeeded. The 1,155 JVM assertions, builds/both lint checks and 608/608 main API26/27/30/36 cases passed, with 0 failures/errors/skips. Independent force-stop, same-job/same-source debug3→4 upgrades, guest reboot and real low-space drivers each passed 4/4, totaling 16/16; they are not added to the 608 main XML cases.

Low-space assertions use the ordinary app’s original UID and actual available bytes, verifying new-task rejection, preserved original files/hashes/offline search and pressure-file cleanup. Only the disposable CI old-userdebug allocator temporarily uses root after strict environment/current-quota/UID gates; final deletion, unroot and restored shell UID were verified. Ten host failure/safety-gate tests passed separately. Complete cases, identities/package digests and CI66–73 failures remain in the evidence; this is not physical power loss, rooting a user phone or unlimited-background assurance.


## Present baseline

The A/early-B/C offline implementation remains. Current source adds v5 snapshot versions/current-pointer/historical-text search, batch favorites and paged asset management, JobScheduler with sealed pending URLs, passphrase/chunked AES-GCM resource backup and new-scope crash recovery, durable whole-library tombstones/default rotation, and coalesced/capped dwell writes. Retention (forever/7/30/90/365-day event details with lifetime facts preserved), unlimited-by-default per-library media quotas/reservations and scoped known-lifetime-lower-bound/retained statistics are now implemented with native confirmation controls. Quota reductions can stop unfinished tasks but never delete completed files. Governance/SAF, independent-UID sharing, real browser SAF PNG reads/gesture windows and the API27 x86 main suite passed in CI74; all 16 external system drivers also passed in CI74. Historical source `4ee242e` / CI42357220689 passed 933 JVM assertions and 146 API30/36 cases, but does not validate this increment. Physical-device/recipient/multi-day power evidence remains absent; live X capture/actions/cleanup remain disabled, and C/D/release are not complete.
The original requirements baseline `eb5aa4dbbb0499fb632202a27aaf24e898718818` remains historical; it is not a current HEAD assertion. Always inspect current main, open PRs and source-specific CI before continuing.

CI74 actually passed the core/SQLite/DOM stress and Android build checks. Cloud fixture coverage includes actual native SAF export/restore, browser SAF PNG selection, read-only URI/68-byte FileReader reads and gesture windows, independent-UID grant/denial/delayed-read checks, 320dp/1.8-font Chinese/English light/dark configurations, native restore of 500 tiny assets and about 481MiB/30 actual files, plus a 512MiB container checked byte-for-byte in a 32MiB JVM. These scopes are recorded separately and do not establish live-X, physical-device or chat-app acceptance.

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

First finish the Round E Android build and actual new browser/legacy-upgrade cases, fix failures, then record one source-bound evidence checkpoint.624 main cases/20 drivers remain declarations until their real reports. Do not widen live-site enablement from fixture success.

1. Preserve CI74’s 608 main cases and 16 independent system drivers with exact source/artifacts; keep the narrowly scoped CI-only allocator and cleanup/unroot safety gates. Add further tests only for new changes or unresolved risks.
2. Retain v1–v4→v5 migration, snapshot policy/source/current-pointer and full historical-text evidence. COMPLETE means accepted visible DOM text; newer shorter COMPLETE can become current, PARTIAL cannot downgrade it, and all versions remain. Keep source/default scopes unchanged during consolidation.
3. Exercise JobScheduler and sealed pending-source durability under real process/system stop, quota/network/storage pressure and exact OS versions. API34+ tries UIDT for explicit user requests; earlier/fallback jobs are ordinary persisted work. Maximum 3 total network attempts per explicit run, with automatic retries limited to narrow transient errors, each from byte zero. Missing jobs remain paused until explicit retry; no stop-bypass, range-resume or prompt-background guarantee.
4. Preserve the verified 500-tiny and approximately 481MiB/30-file native restores and 512MiB/32MiB-JVM boundary; extend recovery only for new risks: ≤512MiB/500 completed assets, inner metadata≤16MiB/20,000; full authentication and media checks, new UUID, media-first/archive-last and crash markers. Preserve plaintext metadata choice and explicit 2–4-source metadata consolidation≤8MiB/20,000. Neither path exports sessions/keys/tasks/pending URLs/leases; the working database is not encrypted.
5. API27 x86, actual guest reboot, force-stop/new-process and same-job debug3→4 have executed successfully; near-limit native restoration is covered. Keep OS probes distinct from receiver-grant lifetime and field use. Separately obtain physical-device long-scroll/capacity/power/multi-day soak, production-key/cross-schema upgrade/rollback and exact recipient-app playback/receive evidence. v5/batch/tombstone logic and one-second dwell coalescing are source work, not those field results.
6. Configure a reviewed production signing identity only through an explicit acceptance workflow; current manual main-ancestor/certificate-pinned candidate tooling is not a release. Maintain current GPL/rights/notice obligations without removing historical grants. Only if choosing a custom-license switch, separately agree its scope and terms. Validate live X login/selectors/action confirmation/cleanup separately before enabling any live adapter.
Consolidation commits the metadata snapshot shown during user preview. Ordinary edits after preview are not included automatically and need a new preview; source erasure/epoch invalidation rejects commit. Conflicting sources for one legacy-action identity reject consolidation. Source libraries/default stay intact. Immediate undo applies only to the just-created result with unchanged metadata and no media/download tasks; changed or media-bearing results are rejected without invalidating existing tasks.

## Historical milestones retained

- 2026-11-07 CI74 source0d193e4:608/608 main cases and16/16 independent drivers passed on all four APIs; field/release gates remain open.


- Historical CI72 source026245f passed 608 native cases/14 of16 drivers;26/27 failed during pressure allocation. CI73 source7e86d79 then passed607/608 with one API36 SAF root-selection failure and12/12 attempted drivers. These failed runs remain historical evidence; no production release.

- 2026-10-29: [ADR-0001](adr/0001-web-first.md) adopted Web-first over official APK modification.
- 2026-11-02: W0 PoC authored; see [execution record](W0-EXECUTION.md) and [device matrix](../compat/web/W0-MATRIX.md).
- 2026-11-05: PR #2 merged as `7b06e5c81da5a34b06266554d61bf1b65896b845`; later baseline `67d2a48d01dbd08e866d0c991ef08bbaa4f09531` added manual links/share and inactive observation checks. Live W0 was not marked passed.
- 2026-11-07: v4 own-action/reversal, narrow standard-MP4, cleanup fixtures and metadata portability/consolidation added; 933 JVM assertions, SQLite/DOM, builds and 146 API30/36 instrumentation cases pass in CI 42357220689. API26/27, SAF/new UI and real-site acceptance remain NOT RUN. See the new evidence; live and release gates stay open.
- 2026-11-06: requirements commit `eb5aa4dbbb0499fb632202a27aaf24e898718818` established app-owned favorites/downloads/sharing. This roadmap consolidation records the implementation work still needed.

See [change record](CHANGELOG.md). No calendar promise replaces an actual acceptance result.
