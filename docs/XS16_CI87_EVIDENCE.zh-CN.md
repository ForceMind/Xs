# XS-16｜CI87 合成验收与产物证据

本轮代码及修复已在四个 Android API 上实际通过。验证范围为离线合成页面、原生 SQLite/SAF 与可丢弃模拟器；真实 X、手机和正式发布不在本次 PASS 范围。

## 精确源码与结果

- 已验收的代码/测试提交（后续文档 checkpoint 不改这些输入）：`5b5f9e33e72068269408a60d74db5af5c7812772`。
- 实际测试的 merge：`a149da3c834f31fd94cbcae1642cbd1eb982d4ea`；tree：`703f02613b4f89b1ccc2cb3eb9be7f9c77aba313`。
- [CI87 / run 42463984353](https://github.com/ForceMind/Xs/actions/runs/42463984353)，fresh attempt 1，六个 job 全部 SUCCESS，没有 rerun。
- **792/792 主 XML 用例通过，0 failures / 0 errors / 0 skips。** 独立系统 driver 为 **20/20**，实际原生命令阶段为 **44/44**，两类不并入主 XML。
- 1406 项 JVM 断言、25 项临时 SQLite/FTS4 断言、29 项 driver host 测试、6 项 allowlist host 测试、10k/100k 保留性迁移与查询容量、Android 构建和双 Lint 实际通过。
- 桌面临时 SQLite 检查记录实际字节 `5,402,624 → 151,552`。原生另有实际数据库/FTS/WAL 回收及持有 reader 的用例；不将桌面数字冒充原生设备测量。
- [完整 JSON 台账](evidence/xs16-ci87-results.json)保留每条主用例、每个 driver、原生 phase 原始文件摘要/状态行、设备信息、ZIP 和 APK 摘要。

## 每个系统的独立执行

| API / ABI | Android | 实际 WebView 包及版本 | 主 XML | 独立 driver / native phase |
| --- | --- | --- | --- | --- |
| [26 / x86_64](https://github.com/ForceMind/Xs/actions/runs/42463984353/job/128679448396) | 8.0.0 | `com.android.chrome` 69.0.3497.100 | app133 + harness65 = 198 PASS | 5 / 11 PASS |
| [27 / x86](https://github.com/ForceMind/Xs/actions/runs/42463984353/job/128679448363) | 8.1.0 | `com.android.chrome` 69.0.3497.100 | app133 + harness65 = 198 PASS | 5 / 11 PASS |
| [30 / x86_64](https://github.com/ForceMind/Xs/actions/runs/42463984353/job/128679448395) | 11 | `com.google.android.webview` 83.0.4103.106 | app133 + harness65 = 198 PASS | 5 / 11 PASS |
| [36 / x86_64](https://github.com/ForceMind/Xs/actions/runs/42463984353/job/128679448367) | 16 | `com.google.android.webview` 133.0.6943.137 | app133 + harness65 = 198 PASS | 5 / 11 PASS |

每 API 的五项 driver 为固定旧 main APK schema2→当前 schema6、实际 force-stop、新源码同 job debug version3→4 升级、实际 guest reboot、真实 `/data` 低空间。准备/安装 fixture 的 summary 单独记录，不算第六项 driver。
旧 APK driver 每 API 有 seed / verify / cleanup 三个原生命令；其余四项每项两个，共 11。`.txt` 与 `.result.json` 若是同一命令的两种表示只计一次，主套件内重复 probe 也不追加到 792。

## 三项交接审阅与本轮真实失败

| 审阅问题 | 本轮四 API 实际证据 |
| --- | --- |
| 规范 `/i/web/status/<id>` 正文观察 | `canonicalDetailsEnrichDelayedBodiesWithoutAnotherOpen`：先记打开、迟到可见正文补全，仍只有原来的一条 OPEN。敏感路由仍排除。 |
| 时间线来源误分类 | `actualFeedRoutesAndNativeContextsDetermineExposureSource`：用真实路由、原生确认的首页 context 和特定新 post/exposure 的已持久化行验来源；首页 query 不冒充搜索，explore/未知不默认 For You。 |
| SAF 期间 Activity 重建丢请求 | 三个 `pending*Saf*` 用例真实 export/import/取消/失效 barrier；实际不同 Activity、新实例完成结果、旧实例销毁，saved-state 审计不含口令。 |

Android26/27 可以把停止中的 Activity 重建延至系统选择器返回。当前 fixture 在 SAF 请求期间发出重建请求，持续观察到真正的新实例并在新实例验证完成/取消；没有伪造 `onActivityResult`，也没有因平台推迟而跳过这些系统。

先前失败按原来源保留：

- [CI85 首次尝试](evidence/xs16-ci85-first-attempt-results.json)：API30/36 各 191 条主用例中 34 失败；API26/27 在新源码推送后取消，只有已完结 XML 计入。根因 `busy_timeout` 赋值返回结果行却通过 `execSQL` 执行，已改为读取 `rawQuery` 的返回行。
- [CI86 四 API 失败台账](evidence/xs16-ci86-failure-ledger.json)：792 主用例有 7 个实际 fixture 失败；预览查询输入框被误当旧正文、先前 HOME exposure 完结释放新 SEARCH latch、旧系统推迟 Activity 重建。修复的是准确定位和实际生命周期/持久化栅栏；保留原有语义断言。
- 这些失败没有删除或改成 PASS；CI87 是包含修复的新源码完整执行，不是挑选旧运行中的成功项。

## T01–T13 已验证的合成子范围

下表关联 [T01–T14 规范](TIMELINE_BUFFER_PLAN.zh-CN.md)。PASS 只适用于这里列出的实际合成子场景。

| 验收 | 实际覆盖 | 边界 |
| --- | --- | --- |
| T01 | 默认独立 OFF、48h/100MiB、1–168h/25–500MiB 上下界、独立 consent 和设置 UI。 | 不是现场开通 live 观察。 |
| T02 | 1 秒卡片只进缓冲；3.5 秒可同时有缓冲和符合原门槛的 FEED；部分入视口可捕获，缓冲无 OPEN/收藏/已读。 | 观察遗漏不承诺全量覆盖。 |
| T03 | 原生刷新、换流、本地批次/顺序、疑似结构刷新、断网列表与 CJK/作者/批次查找。 | 不恢复 X 推荐状态或接口游标。 |
| T04 | 同批重绘/补文幂等、独立 appearance TTL、正文版本复用、过期收据不重插、稳定 promotion identity。 | 批次和字段均有界。 |
| T05 | 精确查询到期、列表/FTS/计数/预览一致；可控时钟回拨/前跳/模拟 reboot、显式全临时库 reset 并保持 OFF；打开中的预览到期/清理后自动关闭。 | 48h/时钟边界主要为可控时钟模型，不是两天实机长测。 |
| T06 | 原生正文/FTS/WAL 实际回收、持有 reader 时明确待回收、释放 reader 后完成。 | 不是安全擦除或锁屏后准点物理回收。 |
| T07 | 两 library 并发共用实际物理预算，50→25MiB 降限淘汰/暂停，写入/整理预留和实际侧文件计量。 | 既有真实低空间 driver 主要验证媒体 admission、Archive/完成文件；新缓冲在实际 `<1MiB` 文件系统余量的 admission 分支未单独实测。 |
| T08 | 长期保存/收藏幂等、取消再收藏、质量/时间/来源保留、原生 guard 回滚；独立 OS process/guest reboot/debug upgrade 下 Archive 已提交但缓冲未 ack，重试保持一个 ledger 并完成 ack。 | 提交前 guard 回滚是 SQLite 模型，没有另外声称提交前 OS kill。两库不构成一个跨 WAL 事务。 |
| T09 | 缓冲清理保留长期收藏/正文、既存文件/媒体 ticket 与其他 library；长期资源治理回归。 | 已有分享副本按原策略处理。 |
| T10 | 旧观察/queued read、clear/reset、consent、后台/overlay、销毁及 tombstone 栅栏；锁顺序二线程回归；维护走已有有界 writer 且可停止。 | 真实 X 账号切换适配未启用。 |
| T11 | 原生 DB reopen/损坏隔离、真实进程/guest 重启后的临时正文与 commit→ack 恢复、旧 APK schema2→6、v5→6 additive migration/备份/合并。 | 不是物理断电、生产签名或真实个人库迁移。 |
| T12 | 分页/CJK/作者/批次、部分正文、设置、保存/收藏、覆盖/暂停提示；320dp/1.8 字号/中英/深浅配置和生命周期。 | 配置与无障碍操作不替代手机体验验收。 |
| T13 | 打包 observer/虚拟列表、20k 压力、字段/候选/批次/队列有界及丢弃提示；只保存已观察的可见元数据。 | 不存 raw API/凭据/隐藏内容或自动下载；未呈现/未采样内容不保证捕获。 |

## 产物与公开摘要

四份 report ZIP 均已从 GitHub 下载并校验实际字节；每 API 的 5 份可下载 APK 副本另用本地 `aapt` / `apksigner` 校验。

| API | report artifact | ZIP SHA-256 |
| --- | --- | --- |
| 26 | [13611327144](https://github.com/ForceMind/Xs/actions/runs/42463984353/artifacts/13611327144) | `e70f42f84155e680e5fad2bcea606f989fd491094f3148129600afb246ba1bbc` |
| 27 | [13611412275](https://github.com/ForceMind/Xs/actions/runs/42463984353/artifacts/13611412275) | `8ce1f968c21dcc5973ab19462586c048684b3a5ca995eb9379baff9cbb99f7ff` |
| 30 | [13610694215](https://github.com/ForceMind/Xs/actions/runs/42463984353/artifacts/13610694215) | `19d0ce4e9ce69637c6f4111616e3e3c4e9eb9799577f310c4d7fd653eb3d1338` |
| 36 | [13611051769](https://github.com/ForceMind/Xs/actions/runs/42463984353/artifacts/13611051769) | `9da78d73deb512088e078de5a1b5dfe5587447ac98a7d4eec4b978742aecfd03` |

全部角色台账是 40 份 APK role copy、28 个不同字节摘要、12 份重复副本。独立下载检查是 20 份 APK 副本、16 个不同字节摘要；两种口径分开。主 provenance 的 20 个角色由 job 本地记录并验签，其中有实际下载副本的角色按 SHA 对齐；没有把未记录的 test/receiver versionCode 填成源码默认值。
CI87 构建 job 的 AAR、宿主、浏览器 PoC 和 core/capacity 四个 ZIP 另有摘要，位于 JSON 的 `build_artifacts`；不并入四 API 的20份 APK副本。不同 job 的 debug 证书可能不同，这不是正式更新或发布包。

## 仍然独立的现场门槛

**T14 / Issue #3 真实 X/W0、真实账号、手机长滚动/耗电/厂商后台、聊天应用接收与生产签名升级仍未验收。** 当前保留 live 观察/自动行为适配关闭，不发布正式版。
软件 TCG 的39个 targeted cases、28 PASS/11 FAIL 及其安装/设备/文件摘要边界在原记录中独立保留；不以加速 CI 的 PASS 覆盖软件 lane 的失败，也不将其预安装 host hash 自动当成已安装字节证明。
本轮证据不承诺后台准时运行、精确物理擦除、完整48小时覆盖、全帧媒体播放或聊天送达。
