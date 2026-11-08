## XS-16 页面上限夹具校正

首次软件API30探针的修改前/后包各执行两项，四项均因未真正触发页面上限而失败；不能作为产品缺陷的有效RED证据。Android WAL池允许PRAGMA落在reader连接，现将setMaximumSize和上限读取放入同一shared writer显式事务，绑定实际writer连接；保留全部拒收/回滚/恢复断言。CI88的core/build通过，新增原生矩阵尚排队时由本测试修复提交取代。正确夹具的原生执行结果待核对。

## XS-16 临时库 SQLITE_FULL 拒收诊断

临时缓冲写入新增专门的SQLiteFullException边界：事务结束后返回false，保留共享writer和其它库/媒体票据；只保存固定SQLITE_FULL原因与拒收计数，不保留候选正文、异常消息或自动重试。诊断写入也失败时只保留有界进程内计数，进程重启可能丢失该部分下界。成功接受新观察后清除暂停原因。新增真实SQLite页面上限的原生回滚/恢复测试，以及计数写入失败的独立注入测试。编译、双Lint与JVM/SQLite/DOM本地检查通过；该增量的原生与四API结果由后续证据记录，CI87仅是修改前基线。

## XS-16 本轮云端验收完成

源5b5f9e33e72068269408a60d74db5af5c7812772 / CI87六作业成功；实际四API792/792主项、20/20独立driver、44/44外部nativephase，0失败/error/skip。1406JVM/SQLite/DOM/容量、构建/双Lint通过。精确源码、包/ZIP摘要、原失败及T14边界见[最终证据](XS16_CI87_EVIDENCE.zh-CN.md)。此后只提交文档证据，不改已测生产/测试/workflow源码。

## XS-16 CI86 后的测试同步修复

四API实际792主项/7失败全部留证；PRAGMA问题已实证修复。三个测试修复分别限定预览正文查找、按新曝光身份等待来源、兼容API26/27真实SAF延迟重建并从新实例核验完成；没有跳过断言或修改生产语义。4个旧包driver通过，后16driver待新CI。额外软件TCG诊断39项/11失败独立保留。

## XS-16 原生失败后的边界修复

CI85 API36保留191项/34失败原证据：busy_timeout PRAGMA必须由Android rawQuery消费，已修源码。另补预览TTL/生命周期、明确确认的临时时间域重置、JS读取前票据绑定和实际进程commit→ack中断探针。执行范围见[当前证据](XS16_IMPLEMENTATION_EVIDENCE.zh-CN.md)，不能以CI85/旧CI通过替代新源码验收。

# 当前增量：XS-16 与三项审阅修复

- 规范 `/i/web/status/` 正文观察、解析后的来源/原生home上下文、备份SAF重建的进程内请求保留。
- 独立临时SQLite schema1/FTS、默认OFF/48h/应用100MiB，原生有界可见卡片admission/刷新批次、原生搜索/预览/分页/设置、保存与收藏。
- 实际字节预算、TTL/时钟高水位、独立clear代次/持久pending clear、tombstone删除、可延迟后台回收。
- Archive v6保留性晋升账本/source迁移及backup/merge/旧APK兼容；不制造打开/合格停留，临时内容不进备份。
- CI84失败保留；SAF侧栏重取精确可点击项，重启驱动补真实失败诊断。实际执行、源码范围和现场限制见[本轮证据](XS16_IMPLEMENTATION_EVIDENCE.zh-CN.md)。

# Xs 方案与工程变更记录

本文件记录决策变化，不把文档提交计作功能完成。代码执行与测试结果保留在各执行记录和 CI 中。

## 2026-11-07 — XS-16最近时间线缓冲与新对话交接（仅设计）

用户确认开始新增默认关闭、启用后48小时/应用级100MiB可调的短期时间线找回。新增专项方案、ADR-0005、T01～T14与Issue #6；同步中英文README/产品/实施计划/路线图/架构/资料语义，并重写新Work接手指令。只记录F0，未实现F1～F5运行时，没有新缓冲功能或测试PASS，不以文档代替后续编码。

接续PR #5，不从旧main丢掉未合并进展。真实已验收基线edf7906/tree7e5e8e65的CI83通过1287 JVM断言、23项host驱动、624主执行、20driver/44外部phase；完整最终账为PR comment6714025860，属于该源码。另明确未解决P2：NativePageCaptureController按整URL contains('/search')否则ForYou推来源，探索页和查询参数可能误分；新对话先验证修复，再实现F1；同时复现规范i/web/status观察器P2与备份选择器重建丢requestTicket的旧P2，后者outdated不代表已修。不得把历史green当无此缺陷。

新缓冲独立临时存储/索引、批次引用到期、实际空间计量/回收和幂等持久提升；不改既有合格停留、打开、收藏和媒体保留。只观察已呈现卡片，不新增raw API记录或隐藏内容采集，不自动下媒体。未解决真实X/W0/手机/CDN/聊天App/生产签名门；本轮未合并main或发布正式版。

## 2026-11-07 — E轮上传策略与真实旧APK升级通过CI82

本轮E验证源码 `2c667517e5a812232a8b41e7a3f99166855963e6` / tested tree `e52dfdd3f5f7b51751154bf1cf2601554786ef33` 的[CI82](https://github.com/ForceMind/Xs/actions/runs/42419306946)已通过：1287项JVM、23项host检查、Android构建/双Lint，以及API26/27/30/36共624/624主用例（0失败/错误/跳过）。BrowserInteraction24/24、真实旧APK升级4/4通过；另有20/20独立driver、44次native phase通过，内部重复probe不计入主XML；均为本轮同源fresh attempt1。源码/merge/包摘要与CI76～81真实失败见[本轮E证据](ROUND_E_EVIDENCE.zh-CN.md)；D/CI75仅作历史基线。

已实际验证5783字节合成MP4的真实SAF选择、FileReader完整字节/hash与用户手势播放至ended，两份新PNG的系统多选/逐文件集合核对，以及敏感SPA路由回HOME仍拒旧结果。固定旧main f88003c8ec1ee193b75de7267db6ae7654ee77c4的真实code1/schema2→current3/schema5升级保留UID/DB路径/默认UUID/旧行/分类/FTS/生成文件；默认LegacyUpgradeProbe只算current roundtrip，真正旧包升级另计driver。

上传MIME来自provider声明，不证明文件内容真实、完整或已上传到X；不套用下载的20/100MiB上限，不增加权限、不由原生全读或转码。旧版本无独立收藏/媒体库，相应迁移为N/A；保留生成文件只证明文件保留。真实X/CDN/聊天App/物理设备/生产签名和live适配关闭边界不变。

历史CI76因host状态行parser在真实old seed后失败，主XML0；CI77实际615/624通过、9失败，旧APK升级4/4与MP4原生读取/播放4/4已过；CI78实际623/624通过，Browser24/24与旧APK升级4/4通过，仅Gov26保存按钮被IME遮挡失败，26后四driver未运行。CI79仍为623/624，仅API30的SPA选择前复合JS条件失败，具体失败子条件未知；26真实SAF保存IME分支和16个Gov用例均通过。CI80的显式SPA激励四API均通过，主623/624唯一API27合并预览按钮被IME缩小视口遮挡；不是合并事务失败。CI81保留了API36首次SDK压缩包下载损坏（0 native）和另一次API26 guest reboot的ADB等待失败原始记录；同源重跑后按各API最新有效job汇总仍623/624，唯一API26元数据在实际网格触摸后未返回，具体激活失败原因未知。API36重跑成功不覆盖其他原始失败；API26重跑是新的UI失败，不能称为重启通过。Browser24/24和旧APK升级4/4持续通过，81仅16个已尝试driver/36次native phase通过，26后四driver未运行。

最终helper修复同时保留：确证IME时单次Back且同form/name/两来源或filename/可见Save断言；真实SAF pending后一次标准evaluateJavascript调用本地已武装function，实际执行敏感路由/HOME的pushState对；固定备份目录的真实List view与相反Grid view确认，以及匹配三官方版本ID的精确标题定位。SPA验收是同DOM路由边界，不是visibility事件时序；两个实际URL、marker、native callback顺序、旧结果拒绝和原navigation/visibility用例均保留。不增加业务时限、不伪造SAF结果，不把未明UI原因归因为重绘。SAF保存IME的已观测触发证据来自CI79；CI82该分支四API均未触发，不冒充本轮分支覆盖。合并IME在CI81已有实际触发，CI82又在API26触发一次；API27/36两类IME均为0。CI82/API26的元数据List规范实际两次、full backup一次；其余条件覆盖按各轮原始日志记账。Browser可选List alias支路未独立记录，不宣称所有分支覆盖。各轮失败不改记PASS，原始证据见E记录。

D轮与文档checkpoint CI75已有实际通过记录，保留如下；本轮已先提交源码/测试/文档执行CI并修复，最终仅做一次文档证据checkpoint，不用静态SHA更新循环代替研发。

## 2026-11-07 — 四版本608项与16个独立系统探针全部通过（历史D/CI74）

D轮当时的验证源码 `0d193e4689d649970d9705818d33e8706b470d8f` / tested tree `c8551cd4a09e4466cd84e7973039aba06a5f87b2`：[CI74](https://github.com/ForceMind/Xs/actions/runs/42386764024) 六项任务全部成功。1155项JVM、构建/双Lint及API26/27/30/36主仪器608/608通过，0失败/错误/跳过；独立force-stop、同job同源debug3→4升级、guest reboot与真实低空间各4/4，共16/16通过，不并入608主XML。

低空间断言以普通应用原UID和实际可用空间为准，验证新任务拒绝、原文件/hash/离线搜索保留及压力文件清理；仅CI可丢弃旧userdebug模拟器的分配器在严格环境/当次quota/UID门后临时root，并已核实finally删除、unroot及shell UID恢复。10项host故障/安全门回归另行通过。完整case、身份/包摘要与CI66～73失败历史见本轮证据；这不是物理断电、用户手机提权或无限后台保证。

真实X自动打开/停留、本人动作与精简live适配仍未挂载；PNG/68字节Browser读取不代表视频上传，MP4播放与跨UID分享另行计证。D轮当时现场手机/聊天App/真实CDN/厂商后台多日电量/物理断电/生产key与跨schema升级均NOT RUN；固定f880 schema2→5的同job debug夹具后由E/CI82验证，生产签名/真实个人资料仍未验收。自定义许可切换可选，当前GPL及历史授权/第三方义务继续有效。

## 2026-11-07 — 四版本主测试与跨进程验证（历史CI72/73）

当时的验证基线为源码 `026245fa8cfa791f85d766d34a90b2822fe5f514` / tested tree `532e0f582df363c16dd8967f96929ad2a2fa80d7`：[CI72](https://github.com/ForceMind/Xs/actions/runs/42383813896) 的1155项JVM、构建/双Lint及API26/27/30/36主仪器608/608通过，0失败/错误/跳过。外部系统driver独立记14/16：force-stop、同job同源debug3→4升级、guest reboot各4/4；低空间30/36通过，26/27在压力构造阶段遇shell配额、尚未执行原生低空间断言，因此整体run仍失败。

后续[CI73](https://github.com/ForceMind/Xs/actions/runs/42385436203)实际607/608通过、1失败、0错误/跳过；API26/27/30的12个外部driver全部通过（含26/27普通UID业务断言、root分配器清理与unroot），API36因Gov侧栏provider选择失败，其四driver未执行，不与前轮拼接16/16。失败发生在SAF目录选择，未抵达ZIP/hash断言；CI74只将该roots_list标题所属直接可点击item改为单次真实ACTION_CLICK，保留文件触摸、toolbar/drawer关闭和实际字节断言。

已验证的云端合成范围包括真实原生SAF导出/恢复、Browser真实SAF PNG文件选择、只读URI/68字节FileReader读取与手势子窗口、独立UID文件授权/拒绝越权/删源后延迟读取、320dp/1.8字号/中英深浅配置、500个小资源和约481MiB/30文件的实际原生备份恢复，以及512MiB容器在32MiB JVM逐字节往返。不同规模、平台与driver分别记账，不合并成真实X/手机或聊天App验收。

真实Browser尚未挂载自动打开/停留、本人动作和网页精简的live适配器；开关不能把fixture能力变为现场验收。真实X登录/DOM/媒体CDN、微信/QQ/飞书/Telegram接收、厂商后台与多日电量、物理断电、生产签名升级/真实个人资料迁移仍缺独立证据。如选择切换自定义许可，须另行确定权利范围与条款；当前GPL及历史授权/第三方义务继续有效。

## 2026-11-07 — 补齐保留/配额/统计与云端端到端验证（历史CI68状态）

上一轮源码 `58585977c90307cbf97d0aadafd818bcbdf88793` / tree `595dcb18016bf4bdf2f0e520dce1236ac5fc35a5` 的 [CI68](https://github.com/ForceMind/Xs/actions/runs/42374634739) 已失败：API26/30/36共450项实际执行，415通过/35失败/0错误/0跳过；API27官方镜像架构配置导致启动失败、0 case，另计。当时后续修复提交与CI尚待生成；后续执行见上节，原失败记录保留。

当时计划的下一轮矩阵为API26/27/30/36（27用x86，其余x86_64）；每API声明52宿主+100客户端仅为待跑数量。新增客体系统reboot/boot-ID与serial核对、仅CI可丢弃8GB AVD上的真实/data低空间至28MiB与原文件保留/新任务拒绝（finally仅清自建压力文件），以及原生≥480MiB/30实际资源流式备份用例，当时实际均NOT RUN；后续执行见上节。它们不代表物理断电、真实X/聊天App或生产key验收。

本地已通过1155项JVM、SQLite/DOM/20k压力、SDK SQLite标准FTS语法复现（旧多词查询0条、修正后1条），以及完整Android构建/双Lint（196 tasks、17秒；运行库0错误/20警告，客户端0错误/12警告）。最大512MiB加密容器已在32MiB JVM流式往返并逐字节核对；这不等于Android近上限实际资源恢复。

- 默认永久/7/30/90/365天事件明细保留、累计与最近打开/停留事实保留；普通writer与实际SQLite锁后policy复验，不撤销媒体票据。默认不限媒体quota计完成文件和活动预留，超限下调停止未完成任务、完成文件不删；双语管理预览/确认与统计明确曝光下界不是阅读时长。累计打开按每帖MAX(已有累计,保留OPEN明细数)求和，是已知下界，不双计数。
- 独立UID实际Intent/ClipData文件授权、未授权/未选/写/数据库拒绝及删库后接收Activity存活时首次延迟hash读取；Browser真实SAF PNG文件选择、只读URI/68字节FileReader读取与触摸子窗口；新增API27，当前矩阵26/27/30/36。
- 外部ADB OS force-stop消失PID/新PID恢复、同job调试证书版本3→4安装升级已有独立驱动，当时执行仍PENDING；不是in-process恢复、生产key或跨schema升级，不证明物理断电/厂商后台。
- SDK标准FTS4多词旧显式AND查询为0条、修正隐式交集为1条，保留词项白名单；双Java builder与原生current/history/Tab/字面OR回归已源码。

[CI67 42372593770](https://github.com/ForceMind/Xs/actions/runs/42372593770) 实际360项、336通过/24失败/0错误或跳过；与下节CI66失败完整保留在本轮证据，不写为PASS。下一轮每API52宿主+100客户端只是声明数量，最终按实际XML及独立驱动报告结算。CI68已实际结束失败（450项中415通过/35失败，0错误/跳过；API27启动0 case另计），下一提交修复，不把失败算作通过。真实X自动采集/动作/精简仍关闭，物理设备/聊天App/真实电量多日/正式key仍待验收；自定义许可切换可选，如选择才另定权利范围与条款，无正式发布。

## 2026-11-07 — v5快照、系统下载、加密资源备份与资料管理增量（首轮CI失败，修正后回归待执行）

接续既有仓库实际修改SQLite/Java/Android/JS和CI，不重建项目。当前源码和精确执行状态统一记录在 [本轮可靠性与资料管理证据](ROUND_D_EVIDENCE.zh-CN.md)。源码50c91ec的 [首轮CI66](https://github.com/ForceMind/Xs/actions/runs/42369802915) core/构建通过，API26/30/36共342项仪器实际执行304通过/38失败/0跳过；问题包含备份sha256字段名校验、fixture provider初始化及观察/最低版本兼容，正在修复。LibraryUiTest和AssetLibraryUiTest三个API共39项全部通过（含206版本分页、批量和整库删除），只作为该源码的局部证据。当时修正后完整回归仍**PENDING**，不将66整体改写为PASS；旧933条JVM/146项API30/36结果仅属下节历史提交。

- SQLite v5新增快照版本、current pointer、质量/来源/收录时间和历史正文全文搜索；原生detail证据支持COMPLETE仅表示接受的可见DOM正文完整，不保证服务器全文。更新但较短的COMPLETE可成为current，PARTIAL不降级；全部版本保留、版本面板每页50条/前后翻页/关键词查询且浏览只读、合并沿用SnapshotPolicy且源/default不覆盖。
- 原生帖子批量收藏/分类/标签追加替换/取消，资源分页文字/类型/状态查询、多选分类/删除/实际文件分享与单项/多项ZIP导出（每批≤200MiB）；历史/收藏/资源与整库的删除范围分开。
- 下载用JobScheduler持久任务；API34+主动请求先尝试UIDT并可fallback普通任务，API26～33普通任务。AndroidKeystore AES-GCM密封URL且绑定task/library/post/kind，job不带URL；每轮最多3次总网络尝试（首次+2次暂时错误重试），均从头下载。进程恢复为PAUSED，已有任务尊重OS调度、缺任务不盲目重排绕过stop；取消/完成清候选，不宣称断点续传或及时后台。
- 新增口令分块AES-256-GCM完整资源备份：PBKDF2-HMAC-SHA256/600k、64KiB认证块/最终空块/EOF、明文负载≤512MiB/500完成资源，内部元数据仍≤16MiB/20,000条，图片/视频各≤20/100MiB。口令8～1024字符不持久保存、遗失不可恢复；完整认证/元数据FK/哈希/媒体验证后预览，新UUID恢复，media-first/archive-last与崩溃markers，源/default保留。明文元数据选项保留；均不导出会话/密钥/任务/候选URL/lease，不代表SQLite加密。
- 完整删库先持久tombstone再shared barrier，阻止旧/未来普通任务复活，事务拿写锁后再检查revocation；仅删除default才轮换UUID；媒体/元数据失败保留pending可手动/启动重试，另库和24小时接收副本租约保留。提供明确的typed确认管理UI。
- 合格停留时长按1秒合并更新、10分钟测量cap后不再反复写，暂停观察器停止JS计时；新增压力与生命周期回归，尚不能据此宣称真实耗电/多日稳定性。
- 增加手动内部签名候选workflow：确切main祖先SHA、环境secrets和证书pin、APK与来源摘要；无GitHub Release/商店发布。生产key/cert未配置，根GPL-3.0、历史授权和第三方声明均不变。

真实X自动采集/现场本人动作/网页精简仍关闭；真机、API27、真实X视频与微信/QQ/飞书/Telegram、真实电量/多日soak、生产签名未验收；GPL及历史/第三方义务有效，自定义许可仅在选择切换时另审，阶段C/D整体与正式发布保持开放。

## 2026-11-07 — 修正 minSdk 视频读取并完成新提交双版本回归

发现 `MediaExtractor.getSampleSize` 仅API28起可用，而项目minSdk26；源码 `4ee242ef1ca542e6bb997acdb3d5629761cbe8dd` 统一改用API16可用的有界读取，并将运行库 `:xs-runtime:lintDebug` 纳入CI。[CI 42357220689](https://github.com/ForceMind/Xs/actions/runs/42357220689) 的core/构建/API30/API36四任务全部通过：933条JVM断言及SQLite/DOM回归、每API36项宿主＋37项客户端，合计146项，0失败/错误/跳过。实际测试merge为 `d5e6bb937dc508786d854dcfb64757a334beea7c`，tree为 `5b26ecca9529948e22637b4eb66fd6b33217fe61`；报告和APK/环境摘要见 [CI执行摘要](evidence/round-c-ci64.json)。

客户端Lint为0 Error/0 NewApi/11项既有Warning；运行库首次纳入CI，同为0 Error/0 NewApi/11 Warning（3 UsableSpace、8 SetTextI18n）。API26/27实际设备与模拟器仍NOT RUN；SAF选择器和新增备份/合并UI未专项自动化，DB/事务/helper通过不代表这些UI/真机通过。合并即时撤销仅允许刚创建、未修改且无媒体/下载任务的新结果；改动或有媒体则拒绝且不使现有任务失效。稳定签名和真实X/接收应用/完整阶段C/D仍待验收，无正式发布。

## 2026-11-07 — 本人行为、标准视频、精简夹具与资料治理增量

在已有 A/早期 B 代码上继续实现 XS-11～13 及 XS-03/15 的有限资料治理范围，未重新创建项目，未发布正式版本。当前源码与报告见 [阶段 C 与资料治理证据](ROUND_C_EVIDENCE.zh-CN.md)；此前源码/CI/验收历史原样保留。

- SQLite v4 保留 v1/v2/v3 数据，新增本人行为与事件轨迹；原生 fixture 操作身份匹配成功结果后才能写入，原创/回复/引用保留新帖与关联原帖，转帖撤销不删收藏/历史。档案与旧来源保留，未知执行者/时间/当前状态不伪造，纯RT文本不推断转帖；真实 X 现场适配仍关闭。
- 显式选择支持的标准直链 MP4，H.264与可选AAC、≤100 MiB/10分钟；校验容器/样本范围、下载长度、轨道/时长并有限解码首尾帧/音频后进入资源库、预览及实际文件分享。blob/清单/分片/DRM仍拒绝；全程播放、实际X与接收应用尚未验收，不宣称断点续传或可靠后台调度。
- 可撤销推广/会员营销/推荐精简具备三默认关闭偏好和合成WebView生命周期控制；关闭/遮挡/离开会恢复，真实浏览器未接入，真实X规则仍关闭。
- 明文元数据备份≤16 MiB/20,000条，严格校验和schema3→4后恢复到独立新库，不含媒体文件/下载任务/网页会话/密钥，校验和不证明身份。显式选择2～4库、≤8 MiB/20,000条元数据，按用户预览快照创建合并新库；源和默认库保留，旧行为同身份来源冲突拒绝；结果对话框仅可撤销刚创建、未修改且无媒体/任务的新库，其他情况拒绝且保留既有任务；预览后普通编辑需重新预览，源清理/epoch失效拒绝提交。
- 共享写入器最多32库，空闲实例可回收；票据同时绑定实例与epoch，回收不恢复旧任务权限。

本地 **933 条 JVM 断言**、真实 SQLite v4/查询、观察/精简 DOM 回归及 Android AAR/客户端/宿主/测试APK构建与Lint通过。首轮 [CI 42356157321](https://github.com/ForceMind/Xs/actions/runs/42356157321) 的API30/36合计146项仪器测试通过；后续兼容修正源码的独立CI见上节，旧CI不覆盖后续修改。阶段C/D整体、真实登录/自动采集/视频/接收应用、完整媒体恢复及正式发布保持未验收。根LICENSE和历史授权不变。

## 2026-11-06 — 离线闭环通过双版本 Android CI

[CI 42348665957](https://github.com/ForceMind/Xs/actions/runs/42348665957) core/build/API30/API36全部通过：443项JVM断言与SQLite/DOM回归、两套模拟器各41项仪器测试（合计82），零失败/跳过。head d76bfb49与PR测试merge4793ea92分开留档。补齐测试APK实际job内的hash/公开证书和配置记录；真实X/真机/目标App仍未验收。详细结果见 [实施记录](IMPLEMENTATION_EVIDENCE.zh-CN.md)。

## 2026-11-06 — 实现应用资料库、合成自动历史/停留与图片分享基础

从 main `f88003c` 接续，实际修改 Java/Android/JS 和测试：Schema v3保留性迁移/旧空间选择、独立收藏备注标签、立即打开/后补正文/导航去重、连续停留档位/时长及未打开检索、有界写入与擦除屏障、旋转页面保留、用户主动选图、校验落盘/资源页/只读文件分享租约。

JVM、真实SQLite和本地DOM测试已通过，Android构建/Lint已通过；本轮CI与模拟器实际结果更新在 [实施证据](IMPLEMENTATION_EVIDENCE.zh-CN.md)。新增真实WebView/SQLite/Provider/UI仪器测试，不能用“测试代码存在”替代“执行通过”。真实 X 自动观察继续关闭；真实登录/图片下载/接收App、本人行为撤销、视频、精简与正式发布仍未完成。根LICENSE不变。

## 2026-11-06 — 新增“时间线停留但未点开也记录”的产品目标

用户明确认可：在 Xs 正常刷 X 时，看到一条帖子停留较久但未点击详情，也应当能在本机找回。新增 [专项规范](FEED_DWELL_PLAN.zh-CN.md) 与 [ADR-0004](adr/0004-feed-dwell.md)，细化 1.2/3（默认推荐）/5 秒独立档位、长帖 60% 可阅读区域、连续单调计时、遮罩/后台失效、同次曝光去重、实测时长、未打开检索、应用本机库与 X 账号解耦。

调整 XS-14 **基础能力至 B 早期**，D 只保留高级性能/容量/统计，增加 D01–D09 验收，双语 README、产品、架构、实施计划、路线图及 AGENTS 同步。**只更新方案目标、工作计划和验收，不代表已实现新的自动抓取、UI 或 Schema**。

## 2026-11-06 — 统一方案、计划与后续开发入口

本次整理前主分支为 `eb5aa4dbbb0499fb632202a27aaf24e898718818`。新增文档导航、XS-01～XS-15 实施工作包、ChatGPT Studio 固定源码借鉴表、功能/安全/接收应用验收矩阵及 ADR-0003；同步架构、路线图、AGENTS 和双语 README。Java/Android/JS 运行时代码、构建配置、Schema 与根 LICENSE 不在本次文档修改范围。

| 旧理解或不足 | 当前决定 |
| --- | --- |
| 继续围绕手机网页壳是否可能展开基础讨论 | 以已评估 ChatGPT Studio 系统 WebView 主线为参考，验证 X 特定登录/导航差异 |
| 全部实现等待 W0，或先搭完整双向桥 | 离线观察/迁移/媒体可并行，首版优先原生主动取回受限观察结果；真实启用仍验收 |
| 主动打开与停留阈值容易混淆 | 点开进入有效详情即记；1200ms/可见比例只用于可选信息流停留 |
| 手动链接保存近似全部目标 | 自动历史为主；LOCAL_SAVE 为补充；新增独立 FAVORITE 交互与正文快照 |
| 本机资料按当前 X 账号拥有/隐式切换 | 应用拥有本机资料，X 退出/换号不删资料；行为执行者只是来源元数据 |
| 下载/分享被排在不明确的远期 | 图片、视频、资源库和向微信/QQ/飞书/Telegram 的系统文件分享进入正式范围 |
| 单向“分享到 Xs”被当成已支持分享 | 区分入站链接接收与出站真实文件；目标应用/会话由用户选择 |
| 许可必须永远固定为 GPL | 所有者允许自定义方向；根 LICENSE 未被本次更换，条款与来源另行落实 |

## 2026-11-06 — 用户新增范围基线

需求提交 `eb5aa4dbbb0499fb632202a27aaf24e898718818` 增加/更新 product、资料库媒体规范、ADR-0002、LICENSING、AGENTS 和双语 README。实现跟踪 [Issue #4](https://github.com/ForceMind/Xs/issues/4)。该提交是需求文档，不是媒体下载功能提交。

## 2026-11-05 — 浏览宿主与手动保存基础

PR #2 合并产生 `7b06e5c81da5a34b06266554d61bf1b65896b845`。后续源码基线 `67d2a48d01dbd08e866d0c991ef08bbaa4f09531` 包含手动链接保存、本机空间、入站分享和未启用的观察校验/解析器。它不是自动历史、站点过滤或完整媒体资源产品。

历史验证见 [W0 执行记录](W0-EXECUTION.md)、[CI 42060787947](https://github.com/ForceMind/Xs/actions/runs/42060787947)；该 CI 不能替代新增范围或真实 X/接收应用验收。

## 2026-10-29 — Web-first 路线

[ADR-0001](adr/0001-web-first.md) 选择官方网页加原生增强，不再以修改/重签官方 X APK 为主线。旧集成契约和 allowlist 作为历史保留，不恢复并行投入。

## 维护规则

需求变更写本文件与对应 ADR，实际实现完成写源提交与验收证据。不要用“已规划”“已建立 Issue”“已有接口”替代“可用功能已验证”。下一开发会话先读 [文档入口](README.md) 和 [实施计划](IMPLEMENTATION_PLAN.zh-CN.md)。
