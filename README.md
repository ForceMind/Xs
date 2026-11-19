# Xs · 更纯粹、更好用的 X

[简体中文](README.md) | [English](README.en.md)

**最近时间线（XS-16）云端实现与空间补强已验收，真实X采集仍关闭。** 独立默认OFF，48小时、全应用100MiB（1–168小时/25–500MiB可调）；原生离线查找、刷新批次、到期回收、长期保存/收藏及恢复屏障已实现。[CI93](https://github.com/ForceMind/Xs/actions/runs/42519140643)实际通过1406 JVM、四API800/800主项、20/20独立driver与44/44原生阶段，并实测不足1MiB时拒收、释放后恢复和长期资料/下载保留。源码、包摘要与真实失败见[本轮证据](docs/XS16_FILESYSTEM_PRESSURE_EVIDENCE.zh-CN.md)。缓冲不增加打开/合格停留/已读，不保存任意接口原包或自动下载媒体；真实X/手机仍独立验收。

**Xs 是独立研发、本地优先的 X 增强客户端工程。** 在应用中正常使用官方 X 网页，以应用自己的本机资料库保存打开历史、可选时间线停留、独立收藏和媒体，再通过 Android 系统分享选中的实际文件。不修改或重打包官方 X APK，不建设另一套社交后台。

> **当前是内部开发 PoC，未发布正式版。** 本轮源码推进到 SQLite v6（保留 v5 快照版本）、资料批量管理、系统下载调度、口令加密资源备份和持久整库删除；真实 X 自动采集、现场行为确认与网页精简仍关闭。新增源码、测试和限制见 [本轮可靠性与资料管理证据](docs/ROUND_D_EVIDENCE.zh-CN.md)。D轮CI74与文档checkpoint CI75已通过；本轮E已由CI82实际验证624主用例与20独立driver，真实站点与手机门槛不变。


本轮E验证源码 `2c667517e5a812232a8b41e7a3f99166855963e6` / tested tree `e52dfdd3f5f7b51751154bf1cf2601554786ef33` 的[CI82](https://github.com/ForceMind/Xs/actions/runs/42419306946)已通过：1287项JVM、23项host检查、Android构建/双Lint，以及API26/27/30/36共624/624主用例（0失败/错误/跳过）。BrowserInteraction24/24、真实旧APK升级4/4通过；另有20/20独立driver、44次native phase通过，内部重复probe不计入主XML；均为本轮同源fresh attempt1。源码/merge/包摘要与CI76～81真实失败见[本轮E证据](docs/ROUND_E_EVIDENCE.zh-CN.md)；D/CI75仅作历史基线。

已实际验证5783字节合成MP4的真实SAF选择、FileReader完整字节/hash与用户手势播放至ended，两份新PNG的系统多选/逐文件集合核对，以及敏感SPA路由回HOME仍拒旧结果。固定旧main f88003c8ec1ee193b75de7267db6ae7654ee77c4的真实code1/schema2→current3/schema5升级保留UID/DB路径/默认UUID/旧行/分类/FTS/生成文件；默认LegacyUpgradeProbe只算current roundtrip，真正旧包升级另计driver。

上传MIME来自provider声明，不证明文件内容真实、完整或已上传到X；不套用下载的20/100MiB上限，不增加权限、不由原生全读或转码。旧版本无独立收藏/媒体库，相应迁移为N/A；保留生成文件只证明文件保留。真实X/CDN/聊天App/物理设备/生产签名和live适配关闭边界不变。

D轮已验证源码 `0d193e4689d649970d9705818d33e8706b470d8f` / tested tree `c8551cd4a09e4466cd84e7973039aba06a5f87b2`：[CI74](https://github.com/ForceMind/Xs/actions/runs/42386764024) 六项任务全部成功。1155项JVM、构建/双Lint及API26/27/30/36主仪器608/608通过，0失败/错误/跳过；独立force-stop、同job同源debug3→4升级、guest reboot与真实低空间各4/4，共16/16通过，不并入608主XML。

低空间断言以普通应用原UID和实际可用空间为准，验证新任务拒绝、原文件/hash/离线搜索保留及压力文件清理；仅CI可丢弃旧userdebug模拟器的分配器在严格环境/当次quota/UID门后临时root，并已核实finally删除、unroot及shell UID恢复。10项host故障/安全门回归另行通过。完整case、身份/包摘要与CI66～73失败历史见本轮证据；这不是物理断电、用户手机提权或无限后台保证。


历史源码 `4ee242e` 的 [CI 42357220689](https://github.com/ForceMind/Xs/actions/runs/42357220689) 通过 933 条 JVM 断言及 SQLite/DOM 回归、构建/Lint和146项 API30/36合成仪器测试。它仅证明旧构建；[阶段 C 证据](docs/ROUND_C_EVIDENCE.zh-CN.md) 与 [更早的打开/停留/图片证据](docs/IMPLEMENTATION_EVIDENCE.zh-CN.md) 保留。真实账号、真机和目标聊天应用尚未验收。

## 当前实现与边界

| 能力 | 已有源码 | 仍待验证或不支持 |
| --- | --- | --- |
| System WebView 浏览 | 页面保留、受控认证导航、原生菜单收藏链接和主动选媒体；清缓存与清网页登录会话独立 | 真实 X 登录、长期真机使用、各认证提供方 |
| 打开与停留 | 合成页面立即记录打开、延迟补正文、导航去重；独立默认关闭的1.2/3（默认推荐）/5秒连续停留，按时长/未打开离线查询 | 真实网页适配关闭；曝光不是阅读完成 |
| 本机资料库 | v1～v4→v5保留迁移、FTS、分类、备注标签、批量收藏/分类/标签/取消；不随X换号切库 | 真实个人旧资料升级、长期容量需验收；320dp/1.8字号/中英/深浅色配置用例已在合成环境通过；物理设备另验 |
| 快照版本 | 保留各文本版本、来源、收录时间和LINK_ONLY/PARTIAL/COMPLETE/LEGACY_UNKNOWN质量；可搜索历史正文、只读浏览版本 | COMPLETE仅指接受的可见DOM正文完整，不保证服务器全文；不把旧版本浏览写回当前；版本面板每页50条，支持前后翻页和关键词查旧版本 |
| 本人行为 | 合成原生操作身份匹配成功结果后索引原创/回复/引用/转帖及撤销；档案导入保留未知身份/时间 | 现场X动作适配关闭；浏览他人转帖不代表本人动作 |
| 图片与标准视频 | 主动选图/图组或标准直链MP4，校验后落盘、预览和实际文件分享；视频限H.264、可选AAC、≤100MiB/10分钟 | blob、HLS/DASH、片段、加密/DRM及未知格式拒绝；真实X视频/全程播放/接收应用未验收 |
| 下载恢复与调度 | Android JobScheduler；API34+显式用户请求先尝试UIDT，否则普通持久任务；API26～33普通持久任务；保存加密候选URL、取消与有限自动重试 | 每轮最多3次网络尝试（首次+2次自动重试），每次从头下载；系统可能延迟/停止，不承诺及时后台完成或断点续传 |
| 资源库 | 分页离线搜索、类型/状态筛选，多选分类/删除/分享/导出；多文件导出ZIP，每批≤200MiB | 完成文件才能导出/分享；聊天App与真机SAF需单独验收 |
| 备份恢复 | 原明文元数据≤16MiB/20,000条；另有口令分块AES-256-GCM完整资源备份，明文负载≤512MiB/500个已完成资源 | 不含浏览会话/密钥/任务/候选URL/分享租约；忘记口令无法恢复；SQLite/FTS/WAL本身未加密 |
| 保留、配额与统计 | 历史明细默认永久，可选7/30/90/365天；每库媒体默认不限，显示完成/预留用量，预览确认后设置；累计打开事实与保留事件分别统计 | 懒清理不保证后台准时；下调配额可能停止未完成任务，但不删除完成文件；曝光下界总和不是阅读时长 |
| 空间合并与删除 | 明确选择2～4库、≤8MiB/20,000条元数据合并至新库，源与默认库保留；完整删库有持久tombstone、默认UUID轮换和失败重试 | 合并不搬媒体；正式签名、真实容量/多日稳定性与升级回退仍待验收 |
| 网页精简 | 三个默认关闭的推广/会员营销/推荐开关及可恢复合成规则 | 真实浏览器未挂载，不能据夹具通过启用真实X |

## 用户体验与资料语义

**点开即记，停留也可找回（当前为合成观察器）。** 开启打开历史后，主动进入有效详情即保存，无等待秒数或再次保存；正文未加载先存链接。另行开启停留记录后，不打开详情也能保存达标连续曝光，支持“停留≥3秒且从未打开”。后台、锁屏、遮挡与页面代次变化会中断计时，不把多个短区间相加。合成观察器按1秒间隔更新已达标停留，达到10分钟测量上限后停止反复落库，并显示下界/上限质量。

**收藏属于Xs。** 原生收藏、标签、备注和分类不调用X书签/点赞/转帖。退出或切换X账号保留本机资料。取消收藏、清历史、删除所选资源、清浏览缓存、退出X和完整删库各有独立范围。完整删库清理该空间全部快照/事件/收藏/分类/本人行为和下载，其他空间与网页会话保留；旧回调不能复活已删除空间，既有分享副本保留至24小时租约到期。

**保留明细与事实分开。** 过期策略仅移除打开/停留事件明细，保留帖子、快照、收藏、本人行为、文件以及累计打开/最近打开停留事实，“从未打开”不会因淘汰明细变真；主动清历史才重置这些事实。进入资料库/管理页或手动清理时维护。媒体预算计算完成文件与活动预留，未知大小按每图20MiB/视频100MiB保守预留；下调后总量超限会将所有未完成任务停止并标为失败，允许配额足够时显式重试，已完成文件保留。

累计打开次数按每帖MAX(已有累计计数,仍保留的OPEN明细数)求和，以“已知累计打开次数 ≥”显示；它是下界，不把两项相加重复计数。

**分享实际文件。** 系统分享支持链接/正文、1～10张完成图片或一个完整视频。用户选择兼容应用、联系人或群聊并确认发送；不读取通讯录/聊天令牌，不自动发送，不将chooser回调当送达证明。原文件与缓存分开，清缓存不删下载；接收应用可能上传用户主动交给它的内容。

**恢复创建新空间。** 加密备份使用8～1024字符的用户口令；只在本次原生安全窗口中使用，不持久保存。完成认证、元数据/外键/每资源哈希与类型验证后预览，先提交资源、最后登记资料空间，崩溃标记用于清理尚未登记的目标；不覆盖源库/默认库。较短但更新的COMPLETE快照可成为当前正文，各历史版本仍保留；PARTIAL不能覆盖已有COMPLETE。

## 继续研发与测试

[文档总入口](docs/README.md) · [XS-01～XS-15实施计划](docs/IMPLEMENTATION_PLAN.zh-CN.md) · [Work交接指令](docs/WORK_HANDOFF_PROMPT.zh-CN.md) · [产品要求](docs/product.md) · [时间线停留](docs/FEED_DWELL_PLAN.zh-CN.md) · [资料库媒体规范](docs/LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md) · [架构](docs/architecture.md) · [验收矩阵](docs/ACCEPTANCE_MATRIX.zh-CN.md) · [路线图](docs/roadmap.md) · [变更记录](docs/CHANGELOG.md)。

[ChatGPT Studio复用审查](docs/CHATGPT_STUDIO_REUSE.zh-CN.md)提供浏览生命周期、Profile、受控窗口与原生快照的固定源码参考；X观察器独立实现，不复制聊天草稿或自动发送逻辑。[Issue #4](https://github.com/ForceMind/Xs/issues/4)跟踪工程工作包，[Issue #3](https://github.com/ForceMind/Xs/issues/3)跟踪真实浏览验收。顺序为A浏览与自动历史→B早期停留/收藏/图片/分享→C本人行为/视频/精简→D可靠性与交付；离线开发不等待所有真机门槛，但真实启用必须另验收。

```text
app-android/      System WebView客户端及合成集成测试
xs-runtime/       Android资料库、观察器和媒体运行库
dev-harness/      离线合成宿主，不是真实X
src/main/java/    v5策略/查询、观察状态、媒体与备份编解码
src/android/java/ SQLite、原生资料/资源UI、系统任务和受限分享
tests/ scripts/   JVM、SQLite、DOM、签名候选验证与工程检查
```

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
# Android SDK、JDK17、Gradle8.9：
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug :xs-runtime:lintDebug
gradle --no-daemon --no-parallel :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

CI74已实际通过核心/SQLite/DOM压力与Android构建检查；已验证的云端合成范围包括真实原生SAF导出/恢复、Browser真实SAF PNG文件选择、只读URI/68字节FileReader读取与手势子窗口、独立UID文件授权/拒绝越权/删源后延迟读取、320dp/1.8字号/中英深浅配置、500个小资源和约481MiB/30文件的实际原生备份恢复，以及512MiB容器在32MiB JVM逐字节往返。D轮仅验证PNG68B；E轮CI82另验证合成MP4选择/FileReader/本地播放，服务器上传仍未验证。MP4下载/跨UID分享是其他用例。不同规模、平台与driver分别记账，不合并成真实X/手机或聊天App验收。

真实Browser尚未挂载自动打开/停留、本人动作和网页精简的live适配器；开关不能把fixture能力变为现场验收。真实X登录/DOM/媒体CDN、微信/QQ/飞书/Telegram接收、厂商后台与多日电量、物理断电、生产签名升级/真实个人资料迁移仍缺独立证据。如选择切换自定义许可，须另行确定权利范围与条款；当前GPL及历史授权/第三方义务继续有效。 独立UID夹具不是聊天App送达；guest reboot验证应用持有的lease文件/元数据，不保证接收方URI授权跨重启存活。

## 安装、签名与许可

CI调试证书可能随job不同，不保证覆盖旧PoC升级。有资料的旧安装不要为换装直接卸载，先在隔离环境验证备份和恢复。新增手动签名候选workflow只接受已在main祖先链的确切提交，需预配环境secrets和证书SHA-256固定值，输出内部候选及来源摘要，不发布GitHub Release或上传商店。正式密钥/证书未配置，稳定签名和发布验收未完成。

根目录有效许可仍是[GNU GPL v3.0](LICENSE)。所有者允许后续经[权利与历史授权审查](docs/LICENSING.md)选择采用自定义许可；如选择切换，须另行确定权利范围与条款。继续使用当前GPL无需另拟自定义文本，历史授权与第三方义务继续有效。Xs与X Corp.无关联；不绕过身份验证、DRM、访问控制或付费权益，不导出Cookie/Token，不承诺账号零风险或商店批准。
