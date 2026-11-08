# Xs 文档与执行入口

**XS-16 云端源码与空间补强已验收。** 测试源码 `9e74ac85d96dcfc9f87a47c1e193d3fe85bd8f5d` 通过 [CI93](https://github.com/ForceMind/Xs/actions/runs/42519140643)：1406 JVM、四API800/800主用例（0失败/错误/跳过）、20/20独立driver、44/44外部native phase、34项driver host与6项allowlist，以及构建/双Lint/SQLite/DOM/容量检查。四API还在普通app UID下实测不足1MiB时缓冲拒收、压力文件清理与恢复。见[精确证据](XS16_FILESYSTEM_PRESSURE_EVIDENCE.zh-CN.md)。T14真实X/手机/接收应用/耗电/生产签名仍NOT RUN；live保持关闭，无main合并或正式发布。

## XS-16 最近时间线：云端验收完成

用户已确认增加独立临时缓冲：默认关闭、48小时/应用级100MiB可调、只保存允许时间线已呈现且被观察到的帖子字段与本地批次。0.5–1秒快划可暂存，但不因此产生合格停留、打开、收藏或已读；原1.2/3/5秒和60%可读FEED规则不变。接口预取/未呈现/未采样项目不承诺覆盖。临时正文和FTS必须真正可回收，不直接复用只清历史事件的retention。

当前已实现独立临时SQLite/FTS、TTL/配额/回收、合成观察器原生缓冲批次、原生查找/设置与Archive v6幂等晋升源码。三项P2已补修复与回归源码。实际构建、四API/driver结果和残留失败统一见[当前证据](XS16_IMPLEMENTATION_EVIDENCE.zh-CN.md)，不以旧CI83替代新功能验证。跟踪[Issue #6](https://github.com/ForceMind/Xs/issues/6)，真实X/真机T14保持独立。


交接时记录的规范i/web/status正文观察、时间线来源和SAF请求重建三项P2，已在CI87对应源码上实际修复并通过四API回归，见上述证据。历史outdated状态本身不算修复证明；后续审阅仍须核对当前源码与执行结果。密码只在进程内保留，不进入saved state或持久存储。

本轮E验证源码 `2c667517e5a812232a8b41e7a3f99166855963e6` / tested tree `e52dfdd3f5f7b51751154bf1cf2601554786ef33` 的[CI82](https://github.com/ForceMind/Xs/actions/runs/42419306946)已通过：1287项JVM、23项host检查、Android构建/双Lint，以及API26/27/30/36共624/624主用例（0失败/错误/跳过）。BrowserInteraction24/24、真实旧APK升级4/4通过；另有20/20独立driver、44次native phase通过，内部重复probe不计入主XML；均为本轮同源fresh attempt1。源码/merge/包摘要与CI76～81真实失败见[本轮E证据](ROUND_E_EVIDENCE.zh-CN.md)；D/CI75仅作历史基线。

已实际验证5783字节合成MP4的真实SAF选择、FileReader完整字节/hash与用户手势播放至ended，两份新PNG的系统多选/逐文件集合核对，以及敏感SPA路由回HOME仍拒旧结果。固定旧main f88003c8ec1ee193b75de7267db6ae7654ee77c4的真实code1/schema2→current3/schema5升级保留UID/DB路径/默认UUID/旧行/分类/FTS/生成文件；默认LegacyUpgradeProbe只算current roundtrip，真正旧包升级另计driver。

上传MIME来自provider声明，不证明文件内容真实、完整或已上传到X；不套用下载的20/100MiB上限，不增加权限、不由原生全读或转码。旧版本无独立收藏/媒体库，相应迁移为N/A；保留生成文件只证明文件保留。真实X/CDN/聊天App/物理设备/生产签名和live适配关闭边界不变。

D轮已验证基线见 [可靠性与资料管理证据](ROUND_D_EVIDENCE.zh-CN.md)；CI74云端回归全部通过；现场总体验收仍NOT RUN。历史 [阶段C记录](ROUND_C_EVIDENCE.zh-CN.md) 和 [A / early-B记录](IMPLEMENTATION_EVIDENCE.zh-CN.md) 不覆盖新提交，待验收项不因源码或测试存在自动通过。

D轮已验证源码 `0d193e4689d649970d9705818d33e8706b470d8f` / tested tree `c8551cd4a09e4466cd84e7973039aba06a5f87b2`：[CI74](https://github.com/ForceMind/Xs/actions/runs/42386764024) 六项任务全部成功。1155项JVM、构建/双Lint及API26/27/30/36主仪器608/608通过，0失败/错误/跳过；独立force-stop、同job同源debug3→4升级、guest reboot与真实低空间各4/4，共16/16通过，不并入608主XML。

低空间断言以普通应用原UID和实际可用空间为准，验证新任务拒绝、原文件/hash/离线搜索保留及压力文件清理；仅CI可丢弃旧userdebug模拟器的分配器在严格环境/当次quota/UID门后临时root，并已核实finally删除、unroot及shell UID恢复。10项host故障/安全门回归另行通过。完整case、身份/包摘要与CI66～73失败历史见本轮证据；这不是物理断电、用户手机提权或无限后台保证。


更新：2026-11-07。当前开发延续既有仓库与未合并工作；下方规范仍为产品目标，执行结果按证据页区分源码、模拟器和真实站点。

## 一句话目标

在 Xs 内正常使用 X 网页：点开即留下可找回的历史；应用自己的收藏与分类不依附 X 账号；图片和视频下载到本机资源库，再把选中的真实文件分享给已安装应用。

## 当前权威文档

| 文档 | 用途 |
| --- | --- |
| [产品要求](product.md) | 用户确认的范围、点开/停留/转帖/收藏语义与资料所有权 |
| [本机资料库、下载与分享规范](LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md) | 详细交互、下载和分享约束 |
| [最近时间线滚动缓冲](TIMELINE_BUFFER_PLAN.zh-CN.md) | XS-16：48小时/容量上限、刷新找回、临时与长期数据分离、T01～T14 |
| [时间线停留记录专项方案](FEED_DWELL_PLAN.zh-CN.md) | 不打开帖子也能记录，1.2/3/5秒档、时长、去重、未打开检索和 XS-14 验收 |
| [完整实施计划](IMPLEMENTATION_PLAN.zh-CN.md) | XS-01～XS-16 工作包、依赖、阶段交付和下一步 |
| [Work 持续研发交接指令](WORK_HANDOFF_PROMPT.zh-CN.md) | 新 Work 会话直接复制；要求实际代码、测试、提交和证据 |
| [架构](architecture.md) | 浏览底座、原生主动获取、统一写入、媒体与分享边界 |
| [ChatGPT Studio 借鉴与迁移清单](CHATGPT_STUDIO_REUSE.zh-CN.md) | 已审阅源码基线、可复用部分、不应照搬部分和许可检查 |
| [验收矩阵](ACCEPTANCE_MATRIX.zh-CN.md) | 功能、并发、真机、接收应用与发布门槛 |
| [路线图](roadmap.md) | A～D 产品交付顺序及与旧 W0～W5 的映射 |
| [决策与变更记录](CHANGELOG.md) | 从旧方案到当前方案的关键变化，区分文档和代码交付 |
| [许可决策](LICENSING.md) | 可采用自定义许可；有效文本仍以根 LICENSE 为准 |

## 决策优先级

1. 用户新确认的要求及 [ADR-0002](adr/0002-local-library-media.md)：应用拥有资料，点开无停留门槛，独立收藏、下载和对外分享。
2. [ADR-0003](adr/0003-native-pull-delivery.md)：借鉴已有浏览底座，优先原生主动取回受限观察结果；离线实现与真机验证并行。
3. [ADR-0004](adr/0004-feed-dwell.md)：可选时间线停留（未点开也能记录）、默认 3 秒和基础阶段提前到 B，优先于旧“全部留到 D”的顺序。
4. [ADR-0005](adr/0005-timeline-buffer.md)：新增临时时间线，与合格停留/打开/收藏独立；不放宽流量/敏感内容禁区。
5. 本目录的产品要求、实施计划、架构与验收矩阵，按各自职责执行。
6. [原 Web-first 计划](WEB_FIRST_PLAN.zh-CN.md) 和 [ADR-0001](adr/0001-web-first.md)：保留原始决策与未被覆盖的安全要求，不再把旧账号归属、严格串行开发顺序或消息桥选型当作新要求的前置。
7. `integration.md`、`integration-contract.md`、旧 APK allowlist 为历史资产，不恢复官方 APK 修改路线。

文档有冲突时先按以上优先级解释并修正文档，不擅自改变产品目标。源码实现现状不因文档优先级变化自动改变。

## 当前状态，不是进度百分比

已有源码：System WebView、v5保留性迁移、应用独立资料库、快照版本与旧正文全文搜索、收藏/分类/标签/备注批量操作、原生停留筛选、图片/标准直链MP4与分页资源库/实际文件分享、合成本人行为与精简、明文元数据备份及显式合并。本轮另有JobScheduler和密封候选URL、口令分块AES-GCM资源备份、新空间恢复/崩溃清理、持久tombstone整库删除和默认UUID轮换。新增默认永久/7/30/90/365天的事件明细保留、默认不限的每库媒体配额，以及区分累计事实/保留事件/重叠曝光下界的统计；管理页双语预览与确认。已知累计打开数按每帖MAX(已有累计计数,保留OPEN明细数)求和并显示下界，避免旧计数缺失和双计数。清理明细不重置曾打开事实、不删收藏/正文/资源，也不撤销媒体任务票据；降低配额超限则停止所有未完成任务，完成文件保留。

验证分层：旧4ee242e的933条JVM/146项仅作历史；CI74的608主用例与16/16外部driver有实际报告。完整资源备份限512MiB/500完成资源、元数据16MiB/20,000；不是SQLite加密或会话恢复。

已执行的云端检查：CI74已实际通过核心/SQLite/DOM压力与Android构建检查；已验证的云端合成范围包括真实原生SAF导出/恢复、Browser真实SAF PNG文件选择、只读URI/68字节FileReader读取与手势子窗口、独立UID文件授权/拒绝越权/删源后延迟读取、320dp/1.8字号/中英深浅配置、500个小资源和约481MiB/30文件的实际原生备份恢复，以及512MiB容器在32MiB JVM逐字节往返。不同规模、平台与driver分别记账，不合并成真实X/手机或聊天App验收。

未完成：真实Browser尚未挂载自动打开/停留、本人动作和网页精简的live适配器；开关不能把fixture能力变为现场验收。真实X登录/DOM/媒体CDN、微信/QQ/飞书/Telegram接收、厂商后台与多日电量、物理断电、生产签名升级/真实个人资料迁移仍缺独立证据。如选择切换自定义许可，须另行确定权利范围与条款；当前GPL及历史授权/第三方义务继续有效。 API27 x86已实际执行；模拟器强停/进程恢复可云执行，不能归为只能真机验证。独立UID不等于聊天App，同源debug3→4不等于生产key升级；固定f880跨source/schema2→5由E轮独立driver验证，不泛化为真实个人库升级。HLS/DASH仍不支持，下载每次重下而非断点续传；Android任务可能被系统延迟/停止。手动签名候选workflow只生成受main祖先/secret/证书pin约束的内部候选，不自动发布。
历史自动化证据见 [W0 执行记录](W0-EXECUTION.md)；真实登录见 [设备矩阵](../compat/web/W0-MATRIX.md)。历史 CI 成功不证明本次新需求已通过。

## 跟踪入口

- [Issue #4：本机资料库、媒体与分享](https://github.com/ForceMind/Xs/issues/4)：实施总跟踪，以 XS 工作包和验收编号更新，不另建重复总计划。
- [Issue #3：真实登录和核心浏览](https://github.com/ForceMind/Xs/issues/3)：保持独立的真机验证证据。
- [AGENTS.md](../AGENTS.md)：后续开发会话必须遵守的约束。

每项交付至少记录源提交、实际执行命令、测试环境、结果、未验证项。需求文件、合成测试、模拟器和真实账号验证分别标记，不能互相替代。
