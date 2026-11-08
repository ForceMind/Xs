# Xs｜新 Work 对话持续研发交接指令

你现在接管既有 Xs，继续实际产品、Android、网页观察、本地数据、媒体、测试与 GitHub 研发。
不要新建工程、重写旧方案或只交付计划；先核对最新代码，再连续推进可执行工作包。
本次新增目标是 XS-16「最近时间线」：意外刷新后可找回此前本机已捕获的帖子。
**当前 F0 只固化方案与交接；XS-16 运行时尚未实现，F1～F5 不得记 PASS。**

## 仓库、分支与已验收基线

- 仓库：https://github.com/ForceMind/Xs ，保持公开、独立，不修改或重打包官方 X APK。
- 上次核验 main 为 `f88003c8ec1ee193b75de7267db6ae7654ee77c4`，它不是完整研发现状。
- 当时唯一开放实现 [PR #5](https://github.com/ForceMind/Xs/pull/5)，分支 `feat/local-library-capture-media`，已有29个提交增量。
- 已验收 head：`edf7906eee3c034d26631531feca822d15437ccc`；tree：`7e5e8e6560fd766a51bb2d4c621d53ecab97fe64`。
- [CI83](https://github.com/ForceMind/Xs/actions/runs/42420707113) 实际通过1287项JVM断言、23项host检查、构建/双Lint/SQLite/DOM/容量。
- API26/27/30/36共624/624主执行，0失败/错误/跳过；20/20独立driver与44个外部native phase另计，不加进624。
- 包摘要、公开证书、实际模拟器配置与最终源码见[最终台账](https://github.com/ForceMind/Xs/pull/5#issuecomment-6714025860)。这些结果不覆盖XS-16。
- **立即通过GitHub连接重查最新main、全部开放PR、PR分支HEAD、Issue/评论、review与CI；按实际结果更新上述快照。**
- 从PR最新分支接续，不要只clone旧main而丢掉增量；保留未提交修改，不擅自reset或覆盖他人工作。
- 本轮F0及之后提交的SHA须读取实际PR HEAD，不预猜，也不为把“最新SHA”写回文档而循环提交。

## 首先阅读与核对源码

1. `AGENTS.md`、双语README、`docs/README.md`。
2. `docs/TIMELINE_BUFFER_PLAN.zh-CN.md`、`docs/adr/0005-timeline-buffer.md`及T01～T14验收。
3. `docs/IMPLEMENTATION_PLAN.zh-CN.md`、`docs/architecture.md`、`docs/ACCEPTANCE_MATRIX.zh-CN.md`。
4. `docs/product.md`、`docs/FEED_DWELL_PLAN.zh-CN.md`、`docs/LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md`、`docs/CHATGPT_STUDIO_REUSE.zh-CN.md`、ADR0002～0004。
5. `docs/ROUND_E_EVIDENCE.zh-CN.md`、D轮证据、`docs/CHANGELOG.md`与`docs/roadmap.md`；历史失败和适用源码保留。
6. [Issue #6](https://github.com/ForceMind/Xs/issues/6)是XS-16主跟踪；[#4](https://github.com/ForceMind/Xs/issues/4)保留原产品/实现；[#3](https://github.com/ForceMind/Xs/issues/3)是W0真实站点门禁。
7. 阅读实际SQLite迁移/FTS、CaptureSession、native pull脚本、共享writer/epoch/tombstone、资料库UI和媒体/备份入口。
8. 复核未解决PR review与实际源码；首轮三项P2见下方，不能仅信历史评论的行号或“已通过CI”。
若新方案路径发生变化，按docs索引查找并核实，不能绕过最新规范直接套旧实现。

## 首轮代码任务：复现并修复既有review

- 先重查全部review thread与当前源码，逐条复现、修复并新增回归；CI83绿和评论outdated都不等于问题已解决。
- [规范帖子URL/正文补全P2](https://github.com/ForceMind/Xs/pull/5#discussion_r4500601373)：当前observer.allowed/postUrl仅匹配handle/status；核验并支持合法`/i/web/status/<id>`，保护规范ID与异步正文补全/去重。
- [来源判定P2](https://github.com/ForceMind/Xs/pull/5#discussion_r4504042361)：当前用URL字符串含`/search`否则默认ForYou，explore与query可被误分。
- 来源改用parsed route与经验证feed context；未验证DOM不能默认ForYou/Following，未知保留未知或拒绝不支持上下文；补真实路径、query含`/search`、explore及合法/未知context测试。
- [SAF重建P2](https://github.com/ForceMind/Xs/pull/5#discussion_r4501080305)：虽标outdated，当前备份请求仅在Activity实例字段；复现旋转/重建后请求丢失，保留受控请求身份并回归完成/取消/旧结果拒绝。
- 备份口令不得写Bundle、磁盘或日志；不以持久化秘密解决旋转。其它旧P1也须查当前实现与测试，不靠outdated自动视为resolved。
- 本轮F0未改上述runtime，三项均不得报已修。完成相关源码/实际回归并关联review后，继续XS-16 F1～F5；不另开重复总Issue。

## 必须保留的现有语义

- 主动打开即记OPEN_POST，无停留阈值或额外保存；刷新、旋转、重绘和旧回调不虚增。
- FEED_VISIBLE独立默认关闭，1.2/3（推荐默认）/5秒连续有效曝光；保存真实时长下界，不是已读证明。
- Xs收藏/分类/备注/标签和资源属于本机library，X退出/换号不删库或隐式切库。
- 仅确认本人行为成功或经归属确认导入才记own action，浏览别人的转帖不是本人转帖。
- 保留SQLite Archive v5、快照版本/current/历史FTS、媒体库、文件分享租约、加密备份及原测试投资。
- 浏览器MIME仅是provider声明；原生SAF/FileReader/合成MP4播放不证明真实X服务器上传或聊天送达。

## XS-16范围与硬边界

- 新增独立「最近时间线」开关，默认OFF；与OPEN、FEED、FAVORITE三者分别授权、分别记账。
- 默认48小时，支持24/48/72小时/7天与自定义1～168小时；默认应用全局临时预算100MiB，可调25～500MiB。
- 预算包含临时DB、FTS/索引、WAL及相关落盘开销，不能仅计算正文字符或每个library各给100MiB。
- 超限先淘汰最旧，必要时拒追加/暂停；数量、字段、批次、队列有界，显示实际覆盖与丢弃原因，不保证总能保满48小时。
- 第一版仅接受允许前台时间线中**实际呈现且被观察到的DOM卡片**；无需停够1.2/3/5秒。
- 接口返回但未呈现、瞬间未采样到、虚拟列表未捕获的内容不承诺保存；将来另层数据源须独立批准和验收。
- 仅metadata：规范帖子ID/链接、可见正文/作者、已知发布时间、原生收录时间、来源、批次与观察顺序；未知不造值。
- 正文按内容去重；同批次同帖幂等、首次位置与TTL不重置，新批次引用独立到期。原生刷新/换流分批，DOM推测只标疑似刷新。
- 临时buffer与长期Archive是两层资料。capture provenance、timeline source、batch reason是三项独立证据；未知不虚构Following，疑似刷新不冒充接口批次。
- 新临时DB从schema1起；F1不改Archive v5，不借现有只清事件明细的retention假装释放临时正文空间。
- 后续长期promotion若确需新字段/schema，明确升版本、保留迁移并补backup/import/merge/旧APK升级回归。
- 不为维持v5而伪造DETAIL/FEED/COMPLETE，也不无版本偷偷加表；新能力不得破坏旧库或下载文件。
- 过期内容查询立即排除；物理回收另靠运行中/前台维护及可被系统延迟的后台清理。
- 锁屏、force-stop或系统停止后不能保证准点物理擦除；TTL逻辑到期与真实磁盘释放分别验收。
- 长期保存/收藏用稳定promotion ID；先提交Archive再确认临时项，崩溃重试幂等，不假称两库跨WAL原子事务。
- 临时预览不增加OPEN，缓冲不增加FEED/FAVORITE/已读；真正点开走既有打开规则，不承诺恢复X阅读位置或推荐状态。
- 新DB/任务须受library归属、writer epoch、实际写前复验、删除屏障与持久tombstone约束。
- 清缓冲不删长期收藏/媒体；清理后旧回调不能复活。临时内容不自动进入现有元数据或完整备份。
- 不下载图片/视频，不保存任意原始接口JSON、请求头、Cookie/Token、游标、私信、草稿或隐藏状态。
- 不抓取任意网络流量、不伪装浏览器、不转移会话、不绕过登录、访问控制、付费或DRM。

## 从F1连续实施，按真实证据推进

1. **F1：策略与存储。** 实作有界时钟/TTL/配额/去重模型、独立SQLite/FTS与query-time TTL、实际全局空间计量及保留性兼容。
2. **F2：观察闭环。** 先合成DOM/native pull，独立buffer admission与原生批次/顺序/代次；合法不可变入队不因快返回丢失，迟到新观察仍拒；无停留门槛，不降低FEED标准。
3. **F3：用户找回。** 原生最近时间线、搜索/批次顺序/部分正文/覆盖范围/设置、批量和一键长期保存/收藏，验幂等及来源。
4. **F4：可靠清理。** 惰性/可延迟后台GC、真实磁盘回收、低空间/崩溃/重启/整库删除与并发屏障，保留长期资料/资源/活动分享。
5. **F5：实际交付。** Java/SQLite/DOM、Android构建/双Lint、API26/27/30/36仪器和原20个driver回归；失败实证修复后提交代码/证据。
每步应有可运行源码与相关验证，不能只新增接口名、测试声明或计划；完成一段自主继续下一段。
若已有部分实现，先review实际代码和证据，再执行下一未完成项，不重复创建F1或覆盖已确认方案。

## 核心验收与记录

- 0.5～1秒卡片可仅进buffer；3.5秒卡可另有合格FEED，未点开仍opened_count=0、不自动收藏。
- 意外刷新后断网能按关键词/批次找到前批已捕获内容；未展示/未采样内容不得谎称全量留存。
- TTL后搜索/FTS不可见，维护后临时正文与引用实际回收；已长期收藏、Archive快照和下载文件保留。
- 持续流入超quota、跨library、虚拟DOM重用、后台/遮挡、旧回调/关闭开关、崩溃与清库并发均有测试。
- 以T01～T14逐项挂源码与结果；新增主用例、外部driver、重复native phase分开，不借CI83证明XS-16。
- 记录source/merge/tree、APK/hash/公开证书、API/ABI/WebView、步骤、实际结果及剩余限制；不上传隐私凭证。

## 工具、授权与持续工作

使用GitHub连接和当前实际终端/计算环境。临时工作区、JDK/SDK或Gradle被清空时先实查，再clone正确PR分支并恢复依赖或用CI。
已有终端可执行就实际用；不能仅因旧会话runtime消失便说“没有工具”，也不能把无本地模拟器说成已真机验证。
参考现有workflow：JDK17、Gradle8.9、SDK35；API27用x86，26/30/36用x86_64，以最新CI配置为准。
至少运行`scripts/check-project.sh`、`scripts/test-core.sh`、相关Android构建/Lint和instrumentation；命令/失败/修复与环境要留证。
每阶段更新双README/AGENTS/开发记录及Issue6，必要时同步Issue4；单次代码/测试/文档提交后跑CI，再做最终证据checkpoint。
不要每个文件或小改动都问“继续”；持续编码、测试和修复，直到本轮可执行目标完成或遇到具体客观阻塞。
用户授权的是既有工程持续研发；不擅自操作其电脑/服务器、上传私密数据、合并main或发布正式版本。
当前GPL与历史授权/第三方义务有效；自定义许可切换可选，只有选择切换时另行确认权利范围与条款。

## 真实现场门槛与最终汇报

Issue3的真实X登录/2FA/DOM、刷新/虚拟列表、媒体CDN、聊天App接收、物理设备/耗电和生产签名仍NOT RUN。
live自动打开/停留/本人动作/精简及新增buffer不因夹具通过自动启用；须限定站点适配与现场证据后再审查接入。
已有原生手动收藏、浏览与离线资料库继续使用；别把live关闭误写为全部底座未实现，也别把旧夹具改称现场通过。
最后统一汇报实际功能/提交PR/CI、T01～T14结果、未验证内容和具体手机步骤，明确F0文档不等于F1运行时完成。
现在核对PR最新HEAD与Issue6，先复现并修复上述三项P2及相关回归，再从XS-16 F1的首个未完成源码切片持续执行。
