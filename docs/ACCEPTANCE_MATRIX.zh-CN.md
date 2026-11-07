# Xs 验收矩阵 v2.2

本轮E（XS-02/15）正在接续已通过的D/CI75：BrowserUploadPolicy让picker与返回结果共用原请求的不可变accept MIME/扩展名、单选/多选（最多8项）策略，拒绝空/重复/超量/声明类型不符结果；SPA任何URL变化即取消旧上传，回调前先清旧状态。新增132条JVM已执行，总core1287及SQLite/DOM/20k、512MiB容器32MiB JVM与10项host检查通过；本轮Android构建、原生新增用例和新CI仍PENDING，D的通过不覆盖新改动。详见[本轮E证据](ROUND_E_EVIDENCE.zh-CN.md)。

本轮待运行目标：真实SAF选择5783字节MP4并由WebView FileReader/用户手势播放、两份新PNG的实际SAF多选/逐文件字节核对、敏感SPA路由再返回HOME仍拒旧结果；另有固定旧main f88003c8ec1ee193b75de7267db6ae7654ee77c4 code1/schema2→当前code3/schema5的同job调试签名真实APK覆盖升级driver。默认LegacyUpgradeProbe用例只是current roundtrip；真正旧包升级单独记账。声明主用例624=4×(52+104)、外部driver20=既有16+跨source4，均非已通过数量。

上传MIME来自provider声明，不证明文件内容真实、完整或已上传到X；不套用下载的20/100MiB上限，不增加权限、不由原生全读或转码。旧版本无独立收藏/媒体库，相应迁移为N/A；保留生成文件只证明文件保留。真实X/CDN/聊天App/物理设备/生产签名和live适配关闭边界不变。

更新：2026-11-07。对应 [实施计划](IMPLEMENTATION_PLAN.zh-CN.md)。**云端子项按下列实际报告记账；后面的核心现场用例与接收应用总表仍NOT RUN。没有当前构建证据的范围不记PASS。** 既有基础测试记录继续保留，但不能自动覆盖新语义。

## E轮新增子项（全部原生NOT RUN）

| 验收关联 | 本轮实际源码/验证目标 | 执行状态 |
| --- | --- | --- |
| B04/Q03/S01 | immutable accept MIME/扩展名与单/多选、picker/result一致、空/重复/超量/声明错型拒绝、SPA URL失效与回调先清状态 | 新132项JVM已跑、core1287通过；Android构建与原生新CI PENDING，provider MIME不是内容认证 |
| B04/X02 | 5783字节MP4真实SAF/FileReader/用户手势播放、两NEW PNG真实多选/字节映射、敏感SPA回来HOME拒旧result | 新增原生测试待执行；不能用D轮PNG68B或其他MP4分享测试替代 |
| L01/Q04/G02/G03 | 固定旧main code1/schema2→current3/schema5同job签名实际install-r升级、UID/default/DBpath/rowid/事件分类FTS与未知质量迁移 | 默认LegacyUpgradeProbe只有current roundtrip；真正外部升级4API待运行、内部probe重复不并主XML；旧独立收藏/媒体库N/A |

624项主用例与20项独立driver仅为源码声明，按本轮实际XML/driver再结算；D/CI75通过仍只覆盖其历史源码。

## D轮已验证的离线实现与仪器证据

本轮源码与精确报告统一见 [可靠性与资料管理证据](ROUND_D_EVIDENCE.zh-CN.md)。**CI74主仪器608项与16个独立driver通过。现场总体验收仍NOT RUN。** D轮已验证源码 `0d193e4689d649970d9705818d33e8706b470d8f` / tested tree `c8551cd4a09e4466cd84e7973039aba06a5f87b2`：[CI74](https://github.com/ForceMind/Xs/actions/runs/42386764024) 六项任务全部成功。1155项JVM、构建/双Lint及API26/27/30/36主仪器608/608通过，0失败/错误/跳过；独立force-stop、同job同源debug3→4升级、guest reboot与真实低空间各4/4，共16/16通过，不并入608主XML。

低空间断言以普通应用原UID和实际可用空间为准，验证新任务拒绝、原文件/hash/离线搜索保留及压力文件清理；仅CI可丢弃旧userdebug模拟器的分配器在严格环境/当次quota/UID门后临时root，并已核实finally删除、unroot及shell UID恢复。10项host故障/安全门回归另行通过。完整case、身份/包摘要与CI66～73失败历史见本轮证据；这不是物理断电、用户手机提权或无限后台保证。


CI74已实际通过核心/SQLite/DOM压力与Android构建检查；已验证的云端合成范围包括真实原生SAF导出/恢复、Browser真实SAF PNG文件选择、只读URI/68字节FileReader读取与手势子窗口、独立UID文件授权/拒绝越权/删源后延迟读取、320dp/1.8字号/中英深浅配置、500个小资源和约481MiB/30文件的实际原生备份恢复，以及512MiB容器在32MiB JVM逐字节往返。不同规模、平台与driver分别记账，不合并成真实X/手机或聊天App验收。 主XML为每API52宿主+100客户端，608项与外部driver独立记账。

历史源码4ee242e的 [CI42357220689](https://github.com/ForceMind/Xs/actions/runs/42357220689) 已通过933条JVM、SQLite/DOM、构建/Lint及146项API30/36仪器（每API36宿主+37客户端），仅覆盖旧v4提交；历史 [阶段C摘要](evidence/round-c-ci64.json) 和 [更早记录](IMPLEMENTATION_EVIDENCE.zh-CN.md) 保留，不能覆盖下面新增语义。

| 对应验收 | 已实际执行的云端子场景 | 结果与边界 |
| --- | --- | --- |
| L01/L03、H04 | ArchiveIntegrationTest、SnapshotVersionIntegrationTest及LibraryUiTest：v5迁移、current/质量/历史FTS、206版本分页至最旧/搜索且current不变；合并保留source/default | CI74四API PASS；真实个人资料升级/真实X正文现场仍NOT RUN |
| F02/F03、M04、U01/U02 | LibraryUiTest、AssetLibraryUiTest、GovernanceUiTest：批量收藏/分类/标签/取消，分页资源/多选删除/SAF ZIP，scope隔离；StorageGovernanceUiTest的320dp/1.8字号/中英深浅配置 | CI74四API PASS；配置布局测量不等于真机 |
| M03、Q03/Q04 | MediaDurabilityTest/MediaDownloadEngineTest：密封任务URL、JobScheduler、有限重试、取消/系统stop；外部force-stop探针验证PID消失/新进程恢复 | CI74四API PASS；厂商后台/真实网络电量另验，不宣称续传或准时后台 |
| G03、Q03/Q04、X04 | FullBackupIntegrationTest11项/系统SAF口令UI：认证/坏文件拒绝、media-first/archive-last/崩溃marker；500真实tiny PNG与约481MiB/30实际文件分别恢复并逐项hash | CI74四API PASS；另有32MiB JVM下512MiB容器通过；物理断电/手机恢复仍NOT RUN，忘记口令不可恢复 |
| Q03/Q04、F03、X04 | AssetLibraryUiTest与CrossUidSharingTest：typed删库、tombstone/default轮换、旧回调拒绝、失败pending重试、其他scope/已发share copy保留 | CI74四API PASS；受限接收Activity存活期间延迟读取，不承诺接收grant跨reboot |
| D03/D05、Q02 | NativeCaptureIntegrationTest、DOM/CaptureSession压力：1秒合并/10分钟cap/暂停JS、有界候选与20k虚拟列表 | CI74 PASS；模拟时钟/桌面压力不是多日真实电量 |
| Q03/Q04、D07/D08、U01/U02 | RetentionIntegrationTest10项、MediaQuotaIntegrationTest7项、StorageGovernanceUiTest3项：仅淘汰明细/累计事实与neverOpened、quota预留/完成文件保留、已知下界统计和实际确认UI | CI74四API PASS；lazy非后台定时保证，曝光下界可重叠、不是阅读时长 |
| X01/X02/X04、B03/B04 | CrossUidSharingTest5项独立UID实际grant/拒未授权未选写入DB/删源后hash；BrowserInteractionIntegrationTest真实SAF PNG文件选择、只读URI/68字节FileReader读取与触摸window.open | CI74四API PASS；不含Browser视频上传；MP4播放/跨UID分享另验，不是聊天App送达/真实X认证上传 |
| Q04/G02/G03 | 独立force-stop、同job同源debug3→4覆盖安装、guest reboot/boot-ID变更和元数据/文件/Keystore/app-owned lease恢复 | CI74各4/4 PASS；不并入608主XML，不代表production-key/跨schema升级或物理断电 |
| M03/Q04/G04 | 独立真实/data低空间拒绝新图任务、原PNG/hash/FTS保留、压力文件清理 | CI74四API PASS；26/27严格CI-only分配器与普通UID原生断言、清理/unroot均已核实 |
| G02/G04 | 手动内部签名候选main祖先/secret/证书pin与APK摘要；临时测试key构建/校验 | 工程夹具已有证据；正式key未配置，生产候选/升级/分发NOT RUN |

完整加密备份不等于工作SQLite/FTS/WAL加密；不包含网页登录/密钥/任务候选URL/lease。标准MP4有限首尾解码和合成本地播放不证明真实X来源或每个聊天App全程播放。下面保留的是**现场总体验收**，未满足真实环境条件的整项仍NOT RUN；云端子项PASS不将整个阶段或发布标为完成。

## 记录规则

每个用例附：源提交/APK 摘要、构建类型、设备或模拟器、Android/API、WebView 包/版本、语言/主题/字号、步骤、预期、实际和证据位置。状态采用 NOT RUN / PASS / FAIL / BLOCKED / UNSUPPORTED；失败或不支持应说明范围。合成、模拟器、真实账号和接收应用实机分开记。

不上传密码、验证码、Cookie/Token、私信、私人截图或可重用媒体凭证。只有明确授权、脱敏素材进入公共仓库。

## 核心现场用例（整体NOT RUN）

| ID | 场景与通过条件 | 当前状态 |
| --- | --- | --- |
| B01 | X 目标登录方式、2FA、重启/过期/退出分别验证；页面加载不等于已登录 | NOT RUN |
| B02 | 从信息流进入资料库再返回，页面与阅读位置合理保留；旋转/重建不误增历史 | NOT RUN |
| B03 | 正常认证子窗口/返回可用，第三方页无采集权限；异常来源/协议拒绝 | NOT RUN |
| B04 | 原有浏览/发帖/媒体/上传和无增强模式正常；关闭增强无需清理网页会话 | NOT RUN |
| H01 | 开启后主动点开有效详情，停留不足 1.2 秒仍有一条 OPEN_POST；无需另点保存 | NOT RUN |
| H02 | 同一导航五次 DOM 更新、补载、刷新、旋转/恢复只保持原事件；离开再开有新事件 | NOT RUN |
| H03 | 点击失败/预加载/后台页面无虚假打开；离开时的迟到回调不得写入新页面上下文 | NOT RUN |
| H04 | 正文延迟：先 LINK_ONLY，后补可见正文/作者/时间；同条事件不重复；编辑/截断质量如实，历史版本/COMPLETE较短更新不丢失，旧正文可全文查找且不切换current | NOT RUN |
| H05 | 随机序号不能伪造新导航；页面/会话/资料清理后过期事件全部拒绝 | NOT RUN |
| L01 | 旧库迁移前后帖子/事件/分类/FTS 对齐，旧空间不丢失、不静默合并，失败保留原库 | NOT RUN |
| L02 | X 退出/换号不删除或隐式切换本机历史/收藏/资源；网页会话与 library 分离 | NOT RUN |
| L03 | 断网/重启后按关键词、作者、分类、动作和不同日期依据查询；未知时间不补假值 | NOT RUN |
| L04 | 用户选择合并旧空间时冲突/重复处理可解释可回退；未经选择不合并 | NOT RUN |
| F01 | 原生「收藏到 Xs」只写本机关系；不调用 X 书签/点赞/转帖；立即反馈后可选分类 | NOT RUN |
| F02 | 重复收藏幂等；同帖多分类/标签、备注、批量整理可持久化 | NOT RUN |
| F03 | 取消收藏保留阅读历史和下载；清历史保留收藏；删除资源不删帖子 | NOT RUN |
| F04 | 仅链接与正文快照区分；默认分享不泄露备注或内部来源标识 | NOT RUN |
| A01 | 本人转帖/引用/回复只有确认成功才记；菜单点击、取消、失败、别人转帖均不误记 | NOT RUN |
| A02 | 撤销转帖更新行为状态但不抹去收藏/原始历史；未知执行者/时间如实保留未知 | NOT RUN |
| A03 | 官方导入归属由用户确认，重复导入不虚增阅读/行为；本人类型与发布时间分开 | NOT RUN |
| M01 | 图片/图组候选仅来自允许内容；拒绝任意本机/私网地址、危险重定向和路径 | NOT RUN |
| M02 | 完整文件先临时下载、验型/容量/完整性后原子登记；中断不显示完成，不把 HTML 当图片 | NOT RUN |
| M03 | 取消/重试/存储满/进程退出可恢复或明确从头开始；不能重复登记孤立文件 | NOT RUN |
| M04 | 资源库离线预览、来源、筛选/分类/删除/导出正确；清缓存不删主动下载 | NOT RUN |
| M05 | URL 相同但内容变化与相同字节不同 URL 的去重合理；签名 URL 不进入分享标题或日志 | NOT RUN |
| X01 | 单图/多图/视频通过实际 content URI 和正确 MIME 分享，接收方能读取；不只是发链接 | NOT RUN |
| X02 | 只授权选中完成文件；Provider 不暴露数据库/会话/根目录/密钥/未完成下载，无写权限 | NOT RUN |
| X03 | 用户选择应用/会话并发送；取消、选中目标、返回均不被记录为“已送达” | NOT RUN |
| X04 | 接收方延迟读取期间删除源资源/清缓存/整库不使已有接收副本立即失效；副本/租约按策略回收 | NOT RUN |
| X05 | 不支持 MIME/多图/大视频时明确降级，保留原件，不自动压缩转码、水印或重发 | NOT RUN |
| V01 | 下载标准完整视频后本地及目标应用可播，时长/音轨/方向正确 | NOT RUN |
| V02 | 仅在需要并允许时验证 HLS/DASH 合并；播放列表、blob、片段不冒充完整视频 | NOT RUN |
| V03 | 无法取得允许的完整资源时清晰失败；不绕过 DRM/付费/访问控制 | NOT RUN |
| C01 | 广告/会员营销/推荐开关独立；不开启时不修改页面；关闭恢复原样 | NOT RUN |
| C02 | 普通内容、登录、验证码、安全提醒、编辑器和主动付费页不被误隐藏 | NOT RUN |
| C03 | 结构未知只停对应规则；滚动位置、正文快照与停留计时不受隐藏节点污染 | NOT RUN |
| D01 | 停留开关独立于 OPEN_POST 和 FAVORITE、默认关闭；档位 1.2/3（推荐默认）/5 秒切换生效，默认 3 秒时 2.9 秒无记录、3.0 秒达标，最低 1.2 秒档的 1199ms 不写事件 | NOT RUN |
| D02 | 长卡片采用视口内可阅读区域归一化；60% 可见阈值，59% 不达标；多条同屏独立计时，遮罩/后台/锁屏/切页/未知路由暂停 | NOT RUN |
| D03 | 连续滚动、节点重用/移除/重绘只产生每连续区间一条事件；监听器、事件队列、CPU/内存有界，抖动去重不丢真实再次停留 | NOT RUN |
| D04 | **未点开**一条帖子，默认档位下连续可见 ≥3 秒后留下 FEED_VISIBLE；其 opened_count = 0、无 FAVORITE，且无额外保存点击 | NOT RUN |
| D05 | 同帖合格后持续停留，更新实际有效 duration_ms/质量状态而不是新建事件；不同连续区间分别记录，单调时钟与本地时间语义正确 | NOT RUN |
| D06 | 切换档位仅影响后续曝光；两次分别为 1 秒和 4 秒、其中间断的时间不能拼成单次 5 秒；不把已达标的真实再次曝光无声抹掉 | NOT RUN |
| D07 | 离线搜索“昨天停留 ≥5秒、从未主动打开、包含关键词”得到正确来源与可见快照，后来再点开会新增 OPEN_POST 但保留原 FEED_VISIBLE | NOT RUN |
| D08 | 停留事件进入 app-owned library 而非 X 登录账号；退出/换号不删库，旧回调、进程死亡、清库竞态不会复活内容；v2→新模式迁移不丢历史/分类/FTS | NOT RUN |
| D09 | 真实 X 帖子/引用/视频卡片、长滚动、夜间/中英文/不同 WebView 版本可测且仅在已验证页面启用，停留结果不宣传为眼球跟踪/已读证明 | NOT RUN |
| Q01 | 同文档刷新/重放、iframe、错误来源、敏感路由、伪造空间拒绝；实际落库前二次校验 | NOT RUN |
| Q02 | 超大/畸形/重复字段/尾随 JSON、嵌套/洪泛有限制；无任意 SQL/文件/Intent 操作 | NOT RUN |
| Q03 | 保存/分享/导入/下载完成与清库并发后不复活旧数据；各删除范围正确，完整删库持久拒绝旧/未来普通回调，默认UUID轮换；失败pending可重试，不影响其他库/已有接收租约 | NOT RUN |
| Q04 | 存储满/升级失败/进程死亡与恢复不破坏原文件；取消与重试不会重复用户操作 | NOT RUN |
| S01 | 原生主动取回或未来消息通道都不读取密码/验证码/私信/草稿/隐藏状态；不向页面回传私库 | NOT RUN |
| U01 | 手机窄屏、中文/英文、深浅色、大字号下历史/收藏/搜索/分类操作可达 | NOT RUN |
| U02 | 资源页文件大小/状态/失败原因与实际文件一致；保存和分享时来源明确 | NOT RUN |
| G01 | 自有复用文件的来源/有效GPL/第三方声明齐全，与README一致；仅如选择切换自定义许可才另定权利范围和条款 | NOT RUN |
| G02 | 固定测试/发布签名、包名、来源 SHA、依赖与包摘要可追溯；debug 不冒充正式更新 | NOT RUN |
| G03 | 备份恢复范围不含网页登录凭证；升级/恢复失败不覆盖原库；加密边界明确 | NOT RUN |
| G04 | 真实设备性能/容量/后台边界有记录；无未关闭的串库、泄漏或数据丢失问题才发布 | NOT RUN |

## 接收应用矩阵

下列为目标，不是保证所有已安装版本支持。每项分别验证链接、正文加出处、单图、多图、完整视频、取消、延迟读取、超限/格式失败；补记目标 App 版本、MIME、字节大小、选择会话路径和接收端实际结果。

| 接收方 | 设备/版本 | 链接/文字 | 单图 | 多图 | 完整视频 | 会话入口/取消/延迟读取 |
| --- | --- | --- | --- | --- | --- | --- |
| 微信 | 未记录 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| QQ | 未记录 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| 飞书 | 未记录 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Telegram | 未记录 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| 其他支持目标类型的已安装应用 | 按应用新增 | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

系统会话捷径由接收方提供；没有捷径时进入 App 选会话是有效路径。一次系统 chooser 回调不能证明用户已经发送，更不能证明送达。

## 发布阻断项

凭证/私信泄漏、越权读取/写入、跨库串写、旧任务在清理后复活、错误完整下载标记、未经选择的大范围文件授权、无法恢复的数据丢失、无法关闭的页面破坏，任一存在均不能标正式版。

证据模板：`用例 ID / source SHA / artifact SHA256 / environment / fixture or live / steps / expected / actual / result / evidence / remaining limits`。参考 [W0 实机矩阵](../compat/web/W0-MATRIX.md)，不要覆盖其历史结果。
