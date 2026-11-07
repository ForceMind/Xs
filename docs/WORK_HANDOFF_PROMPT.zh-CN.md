# Xs｜持续研发 Work 交接指令

本轮E验证源码 `2c667517e5a812232a8b41e7a3f99166855963e6` / tested tree `e52dfdd3f5f7b51751154bf1cf2601554786ef33` 的[CI82](https://github.com/ForceMind/Xs/actions/runs/42419306946)已通过：1287项JVM、23项host检查、Android构建/双Lint，以及API26/27/30/36共624/624主用例（0失败/错误/跳过）。BrowserInteraction24/24、真实旧APK升级4/4通过；另有20/20独立driver、44次native phase通过，内部重复probe不计入主XML；均为本轮同源fresh attempt1。源码/merge/包摘要与CI76～81真实失败见[本轮E证据](ROUND_E_EVIDENCE.zh-CN.md)；D/CI75仅作历史基线。

已实际验证5783字节合成MP4的真实SAF选择、FileReader完整字节/hash与用户手势播放至ended，两份新PNG的系统多选/逐文件集合核对，以及敏感SPA路由回HOME仍拒旧结果。固定旧main f88003c8ec1ee193b75de7267db6ae7654ee77c4的真实code1/schema2→current3/schema5升级保留UID/DB路径/默认UUID/旧行/分类/FTS/生成文件；默认LegacyUpgradeProbe只算current roundtrip，真正旧包升级另计driver。

上传MIME来自provider声明，不证明文件内容真实、完整或已上传到X；不套用下载的20/100MiB上限，不增加权限、不由原生全读或转码。旧版本无独立收藏/媒体库，相应迁移为N/A；保留生成文件只证明文件保留。真实X/CDN/聊天App/物理设备/生产签名和live适配关闭边界不变。

> 本文件是可直接粘贴到 ChatGPT Work 的执行指令；**不是功能完成报告**。所有最新决定以仓库当前 main 和文档索引为准，不能仅凭本文静态 SHA 认定仓库仍处于旧版本。

你现在接管 **Xs** 的后续产品、Android Web-first 客户端、网页观察/精简、独立本地资料库、媒体下载与分享、测试、CI 及 GitHub 维护工作。**这是已有项目的持续研发，不是重新制定计划，不是重写仓库，也不是一次性审查。**

## 仓库与约束

- 仓库：https://github.com/ForceMind/Xs
- 默认分支：`main`；GPL-3.0 目前仍为有效根目录许可，用户允许但不要求后续经权利/历史授权审查选择自定义许可；如切换才另定范围和条款，**不得擅自将已有代码换成未确认的限制性许可**。
- 项目保持独立、公开，中英文 README 同步；保留现有代码和用户数据迁移路径。
- 参考自有 ChatGPT Studio 的浏览容器、Profile、受控窗口、页面保留、原生主动获取与加密恢复经验，但不要盲目复制大 Activity、聊天草稿处理、签名或登录信息。
- 不使用用户电脑或服务器，不在本机/远端擅自部署，不上传凭证/真实私人内容，不为了开发方便绕过登录、DRM、付费/安全控制。

## 首轮必须读取（并核对真实 main SHA）

1. `AGENTS.md`、`README.md`、`README.en.md`、`docs/README.md`
2. `docs/product.md`、`docs/IMPLEMENTATION_PLAN.zh-CN.md`、`docs/architecture.md`
3. `docs/LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md`、**`docs/FEED_DWELL_PLAN.zh-CN.md`**
4. `docs/ACCEPTANCE_MATRIX.zh-CN.md`、`docs/roadmap.md`、`docs/CHATGPT_STUDIO_REUSE.zh-CN.md`
5. `docs/adr/0002-local-library-media.md`、`docs/adr/0003-native-pull-delivery.md`、**`docs/adr/0004-feed-dwell.md`**
6. GitHub Issue #3（真实 X 浏览兼容）、Issue #4（产品功能/实现）；最新未合并 PR、Actions/CI、当前数据库版本与实际源码。
7. `docs/ROUND_E_EVIDENCE.zh-CN.md`（本轮上传/跨source升级与CI82实际证据）、`docs/ROUND_D_EVIDENCE.zh-CN.md`（本轮v5/系统下载/加密资源/批量/删库和当前CI）、`docs/ROUND_C_EVIDENCE.zh-CN.md`（历史v4证据）及 `docs/IMPLEMENTATION_EVIDENCE.zh-CN.md`（早期A/B）；不要用旧CI代替新提交。

## 接续代码基线（2026-11-07，仍需核对最新提交）

D轮已验证源码 `0d193e4689d649970d9705818d33e8706b470d8f` / tested tree `c8551cd4a09e4466cd84e7973039aba06a5f87b2`：[CI74](https://github.com/ForceMind/Xs/actions/runs/42386764024) 六项任务全部成功。1155项JVM、构建/双Lint及API26/27/30/36主仪器608/608通过，0失败/错误/跳过；独立force-stop、同job同源debug3→4升级、guest reboot与真实低空间各4/4，共16/16通过，不并入608主XML。

低空间断言以普通应用原UID和实际可用空间为准，验证新任务拒绝、原文件/hash/离线搜索保留及压力文件清理；仅CI可丢弃旧userdebug模拟器的分配器在严格环境/当次quota/UID门后临时root，并已核实finally删除、unroot及shell UID恢复。10项host故障/安全门回归另行通过。完整case、身份/包摘要与CI66～73失败历史见本轮证据；这不是物理断电、用户手机提权或无限后台保证。


保留A/早期B/C已有即时打开、无点击停留、独立收藏、图片与标准MP4分享、本人行为与可撤销精简夹具。当前XS-06/07/09/14/15等范围已有实际源码；CI74已通过API26/27/30/36的608主用例与force-stop/debug升级/reboot探针；四API低空间driver亦实际通过。先查看 [本轮证据](ROUND_D_EVIDENCE.zh-CN.md) 与最新CI，测试编码/编译不等于仪器执行。

CI74已实际通过核心/SQLite/DOM压力与Android构建检查；已验证的云端合成范围包括真实原生SAF导出/恢复、Browser真实SAF PNG文件选择、只读URI/68字节FileReader读取与手势子窗口、独立UID文件授权/拒绝越权/删源后延迟读取、320dp/1.8字号/中英深浅配置、500个小资源和约481MiB/30文件的实际原生备份恢复，以及512MiB容器在32MiB JVM逐字节往返。不同规模、平台与driver分别记账，不合并成真实X/手机或聊天App验收。

历史源码4ee242e的 [CI42357220689](https://github.com/ForceMind/Xs/actions/runs/42357220689) 已通过933条JVM、SQLite/DOM、构建/Lint及API30/36共146项；这些仅属历史v4提交，不覆盖当前修改。API27在历史CI68架构配置启动0 case，后已用x86在CI74实际通过；真机、真实X与目标接收应用、真实耗电/多日soak仍缺证据。CI66与67真实失败保留，不能改写成环境阻断或PASS。

- SQLite v5保留v1～v4数据/旧命名空间，新增snapshot_versions/current pointer、来源/收录时间/质量、历史正文全文搜索和只读版本浏览。COMPLETE仅表示原生证据支持的接受可见DOM正文，不保证服务器全文；更新但较短COMPLETE可成为current，PARTIAL不能覆盖COMPLETE，全部旧版本保留（版本面板每页50条、前后翻页及正文关键词查询，点选才读单版本正文，不无限累积UI行）。显式合并按SnapshotPolicy保留版本，源/default不覆盖，不能恢复成“最长文本永远正确”。
- History批量收藏/分类/标签追加替换/取消与Assets分页搜索/类型状态/多选分类删除分享/导出已有实现。导出单项原文件、多项ZIP，每批≤200MiB；完成文件才能导出/分享。测试实际操作UI后检查SQLite/bytes，不能只跑helper或放宽assert。
- 下载任务JobScheduler：API34+用户显式请求先UIDT，拒绝后普通持久任务，API26～33普通任务；候选URL由AndroidKeystore AES-GCM密封并绑定task/library/post/kind。每轮最多3次总网络尝试（首次+2次指定网络错误自动重试），每次从byte0重下。onStopJob保留PAUSED并尊重系统，恢复只沿用OS已有任务，missing job不盲目重排绕过stop。FAILED/PAUSED可显式重试保存来源，取消/完成清候选；不支持Range续传，不承诺后台及时完成。
- 标准MP4仍限≤100MiB/10分钟H.264和可选AAC；容器/样本/长度及首尾有限解码不等于全程或目标聊天App播放验收。拒绝blob/HLS/DASH/分片/DRM，不通过导出会话或绕过认证补资源。
- 口令完整资源备份独立于原明文元数据选项。64KiB分块AES-256-GCM、PBKDF2-HMAC-SHA256/600k、最终认证块/EOF，明文负载≤512MiB/500完成资源；内层元数据≤16MiB/20,000、图≤20MiB/视频≤100MiB。口令8～1024字符不持久保存，遗失不可恢复。全部认证、元数据/FK/每资源哈希及native媒体校验后预览；新UUID恢复，media-first/archive-last与崩溃markers，源/default保留。任务、候选URL、会话、密钥、lease不备份；SQLite/FTS/WAL未加密。
- 明文元数据仍≤16MiB/20,000、schema3/4→5；显式合并2～4来源、≤8MiB/20,000，仅合并预览快照，不搬媒体。预览后编辑需重新预览，来源清理/epoch失效拒绝；即时撤销仅限刚建且未改、无媒体/任务结果，不影响源/default和已有任务。
- 完整删库先持久tombstone再写入barrier；资料库Archive公开写入口在实际取得写事务后重查deleted，媒体批量helper只经同一shared writer调用；旧票据/未来普通提交不得复活。仅删default时轮换UUID；清所选scope的全部快照/事件/收藏/分类/本人记录与媒体，另库/网页会话/既有24h接收lease保留。失败保持pending，启动或用户重试只可继续purge，不重新开放空间。
- 停留更新1秒合并、达到10分钟cap后停止反复写，暂停停止JS timer。合成stress不等于真实多日soak/耗电。即时打开、停留、本人行为和精简真实适配仍关闭；native fixture Admission不可由页面/导入provenance字符串伪造。
- 事件明细默认永久，可选7/30/90/365天，只清理过期明细；累计打开次数/最近打开停留事实、帖子/快照/收藏/本人行为/文件保留，“从未打开”不因过期而变真。主动清历史才重置事实。普通shared writer内实际SQLite锁后复验policy，不失效媒体票据；管理页/资料库入口或手动惰性维护，无后台定时保证。
- 每库媒体quota默认不限，完成文件+活动预留预算，未知图/视频预留20/100MiB。下调后总量超限停止并FAILED全部未完成任务，保留密封来源供显式retry，完成文件不删。双语管理页预览/确认和统计已源码；已知累计打开下界逐帖MAX(累计计数,保留OPEN明细数)再求和、不双计；与保留事件分开，曝光下界可重叠不是阅读时长。320dp/1.8字号/中英深浅配置UI回归已在CI74通过，不称真机。
- 独立UID receiver真实Intent/ClipData read grant、拒绝未授权/未选/写/DB及删库后Activity存活延迟hash读取，Browser真实SAF PNG文件选择、只读URI/68字节FileReader读取/触摸window.open已编码；不等于聊天App送达/真实X。外部ADB force-stop消失PID/不同PID恢复、同jobdebug3→4覆盖升级与guest reboot已在CI74各四API通过；不把in-process重启或app-owned lease恢复冒充接收grant跨reboot，也不代表物理断电/production key/跨schema。
- 手动签名候选workflow只接受确切main祖先SHA、预配secret与证书pin，产内部候选与来源摘要，不发布GitHub Release或商店。生产key/cert未配置，稳定升级/回退与正式发布门槛未过；根GPL-3.0不变，不能抹历史授权或跳过rights/notice审查。

继续已有PR而不覆盖未合并进展；保留本轮E/CI82已验证的624主用例/20独立driver，新增改动另跑相关回归，不套D/CI75覆盖任意后续修改。保留CI74低空间脚本/普通UID原生断言/清理恢复的实际证据；不要将CI74已通过的主用例、reboot/升级/大资源恢复重复列为未实现。再推进设备/现场适配。真实Browser尚未挂载自动打开/停留、本人动作和网页精简的live适配器；开关不能把fixture能力变为现场验收。真实X登录/DOM/媒体CDN、微信/QQ/飞书/Telegram接收、厂商后台与多日电量、物理断电、生产签名升级/真实个人资料迁移仍缺独立证据。如选择切换自定义许可，须另行确定权利范围与条款；当前GPL及历史授权/第三方义务继续有效。所有现场启用受独立证据门槛；不能将W0变成停止离线研发的理由。

CI调试证书可能每个job不同，不能保证覆盖旧PoC升级。有资料的旧Xs不要为换装直接卸载；即使已有完整备份源码，也应先在隔离环境实际验证导出/恢复，再操作真实个人资料。

## 产品必须达到的真实用户体验

**打开 Xs，正常使用 X 网页。** 用户一次性开启“打开历史”后，主动点击进入有效帖子详情，哪怕很快返回也保存帖子 ID/链接、打开时间与可见内容，稍后补正文不增加事件数。刷新/旋转/旧回调/预取不能虚增。

**不点开也能记录。** 用户另外开启“时间线停留”：档位 1.2s、3s（推荐默认）、5s；只有在实际前台、未遮挡、约定可阅读区域连续满足基线 60% 时才形成 `FEED_VISIBLE`，长帖视口适配、同一连续区间去重、实际时长保留、中断不拼接。资料库按停留时长/日期/关键词/来源及“从未点开”筛选，不自动将曝光转为 OPEN_POST、FAVORITE 或已读证明。**基础停留闭环提前到早期 B，不要留到最后。**

**资料由 Xs 自己拥有。** X 网页账号的登录/退出/切换不删除或隐式切走本机历史/收藏/媒体；本人原创/回复/引用/转帖仅在真实确认成功或经归属确认的档案导入后记账，动作时间不伪造。

**Xs 自己的收藏/分类。** 显示「收藏到 Xs」按钮，本地分类、标签、备注和可见正文快照，与 X 服务器书签/点赞分离；取消收藏、删历史、清缓存、删媒体、登出 X、清整库互不连带。

**媒体与资源。** 用户主动选择图片/图组/允许的可下载完整视频，生成真实可预览文件、来源记录、进度/错误/取消/重试、资源库搜索分类和删除导出。不能把播放列表/blob/碎片称为已完成视频，不能抓取任意网络响应或绕过访问/内容权限。

**系统分享。** 通过 Android ACTION_SEND/ACTION_SEND_MULTIPLE，向微信/QQ/飞书/Telegram 等已安装 App 分享选定的真实图片/视频/链接和文字；FileProvider 或同等受限 URI 只授权选定完成文件的短期只读权限，用户自己选择会话并发送，**打开分享菜单不代表发送成功**。

**界面精简。** 以可撤销规则隐藏可识别的推广/会员营销/冗余推荐，不影响内容主体、登录安全/验证、付费权益或原有基本交互。

## 研发方式：立即执行，不只给计划

- 先查最新 HEAD、CI、Issue/PR 和代码；确认前一阶段证据与阻塞，不从零开始。遇到旧文档冲突按 docs/README.md 指定优先级。
- 按 **XS-01～XS-15 工作包**推进：A 浏览底座 + 本机资料归属 + 即点即记；B 早期 XS-14 无需点开的停留记录，同期 XS-07～10 收藏/图片/资源/分享；C 转帖/视频/可撤销精简；D 性能、配额、迁移、签名与真实兼容性发布。
- XS-02 浏览容器改善与 XS-03/04 数据/写入控制可并行；先在**合成/脱敏离线 X 页面**验证 SPA、点击/正文延迟/停留/去重，再按明确的 W0 真机登录和站点行为证据决定是否启用真实采集。真实登录不通过时不得用伪装 User-Agent、复制 Cookie 或绕过验证补齐。
- **实际编码**：修改可运行源码、编写迁移、网页脚本与 Android 测试，执行现有 Java/SQLite 检查、Android Gradle/Lint、API 26/27/30/36 模拟器与独立进程/升级驱动回归；记录失败并修复。不能因为自动测试通过就宣称真机或 X 登录已验收。
- 每完成一个可验收阶段就提交代码到 GitHub（适当使用 PR、合并前核验 CI），更新 AGENTS/中英文 README/CHANGELOG/Issue #4 的真实状态和测试证据。不要每写一个文件都停下等待确认；直到本次可执行目标达到或遇到**客观阻塞**再汇总。
- 真机验收尚未进行时，继续推进不依赖真机的源码、模拟器测试、兼容性夹具和资源分享合同；明确列出需要我在 Android 真机完成的具体步骤与版本记录。**不得假称已经在用户设备验证或异步后台持续工作。**

## 验收与最终汇报

必须展示确切 GitHub commit/PR、CI URL 与结果、实现文件和对应 **H、D、L、F、M、X、V、C、Q、S、G 验收编号**，区分 PASS/FAIL/BLOCKED/NOT RUN，以及真实设备/账号/接收应用未验证的范围。

**最重要的验收：**打开帖子立刻形成一次真实 OPEN_POST；未打开却连续可见 3.5 秒的帖子（默认 3 秒档）进入 FEED_VISIBLE，快速划过的帖子不进入；之后断网仍能按“停留 ≥3 秒、从未打开”与正文关键词找回并分类。库不随切换 X 账号消失。收藏/下载/分享另外按各阶段证据交付。

开始读取 GitHub 当前状态，并直接执行第一个尚未完成且可测试的工作包。
