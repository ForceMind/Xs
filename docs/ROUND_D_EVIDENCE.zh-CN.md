# Xs 可靠性与完整资料治理实施记录（2026-11-07）

从既有 `a323b7cca2c760defd4b9b58a58e8f76431cdb96` / PR #5 接续，没有重建项目。接手核验 main 为 `f88003c8ec1ee193b75de7267db6ae7654ee77c4`，PR #5 是唯一开放实现PR，Issue #3/#4仍开放；此前CI65成功。此前 [C轮源码和证据](ROUND_C_EVIDENCE.zh-CN.md) 保留。本页区分源码、模拟测试、桌面容量、签名夹具和真实设备，不能相互冒充。


## 当前云端验收结论

源码 **`0d193e4689d649970d9705818d33e8706b470d8f`** / tested tree **`c8551cd4a09e4466cd84e7973039aba06a5f87b2`** 的 [CI74 42386764024](https://github.com/ForceMind/Xs/actions/runs/42386764024) 六个job全部成功。四API的实际XML为**608/608通过，0失败、0错误、0跳过**；16项独立系统driver **16/16通过**，重复原生probe调用不加入608计数。[完整源码、环境、XML逐项、包与公开签名/digest证据](evidence/round-d-ci74-results.json)。后续仅文档/证据提交的最新SHA及CI结果在[PR #5](https://github.com/ForceMind/Xs/pull/5)和[Issue #4](https://github.com/ForceMind/Xs/issues/4)核对，不把旧源码结果自动套给任意新改动。

| 实际环境 | 主XML | 外部系统验证 | 低空间普通应用实测 |
| --- | --- | --- | --- |
| API26 / x86_64 | 52 harness＋100 client PASS | force-stop / debug3→4 / guest reboot / low-space 全PASS | UID10082，28565504字节可用 |
| API27 / x86 | 52 harness＋100 client PASS | 同上四项全PASS | UID10084，29069312字节可用 |
| API30 / x86_64 | 52 harness＋100 client PASS | 同上四项全PASS | UID10172，29106176字节可用 |
| API36 / x86_64 | 52 harness＋100 client PASS | 同上四项全PASS | UID10221，29224960字节可用 |

同一次CI还通过1155项JVM断言、10项host安全/故障测试、SQLite/DOM/20k压力、10k/100k容量迁移、Android构建和双Lint。四版原生治理UI（包括系统SAF、320dp/1.8字号/中英深浅配置）、独立UID文件授权、500tiny和约481MiB/30实际资源的完整备份恢复均有实际测试；512MiB容器另由32MiB JVM逐字节验证，不混同规模/平台。Browser真实上传路径的证明仅为SAF PNG选择、只读URI和68字节FileReader；视频播放与跨UID分享属于单独的标准MP4夹具，不声称浏览器视频上传已验收。

API26/27仅在本次shell分配真实quota、CI/emulator/userdebug/debuggable及UID检查全部满足后，临时root构造唯一压力文件；实际应用始终原普通UID，最终文件不存在且unroot回shell2000都有原文证据。30/36全程普通shell分配。该fixture不是对用户手机提权，也不证明物理flash故障或mid-write ENOSPC。

**当前无需真机的本轮实现与云端可靠性闭环已完成；整体产品/正式发布仍未完成现场验收。** 真正的Browser尚未挂载自动打开/停留、本人动作或精简live适配，开关本身不能激活真实X能力；原生收藏当前保存链接并保留已有快照。后续先完成Issue #3真实登录/2FA/会话/核心浏览，再按真实站点证据接入和限定启用；实际CDN/聊天App接收、厂商后台/多日耗电/物理断电与生产签名升级独立验证。具体四场景手机步骤见下表。以下记录按发生顺序保留全部历史失败与NOT RUN，不覆盖本节当前结果。

## 实际新增源码

| 工作包 | 本轮交付 | 明确边界 |
| --- | --- | --- |
| XS-06 | SQLite v5保留v1～v4，snapshot_versions/current pointer/历史FTS；native采集时间、正文hash、来源和完整度；较新完整可见快照允许短编辑，部分/重复/迟到不错误替换；50条分页和版本关键词检索 | COMPLETE仅表示当前可见DOM正文完整捕获，不证明服务器全文、真实编辑时间或阅读完成；迁移旧时间/hash保持未知，live仍关闭 |
| XS-08/12/15 | Media DB v3保留v1/v2；Keystore AES-GCM封存明确选择的待下载URL；JobScheduler持久任务，34+可用用户启动传输，旧版本普通系统任务；每轮最多3次总网络尝试，run token隔离临时文件/回调，取消/停机/删除/显式重试 | 不复制Cookie，不输出URL/密钥；重试从byte0，不伪称Range续传；Android可因网络/配额/健康/用户停止而延期或暂停，不绕过force-stop，不承诺无限后台 |
| XS-09/07 | 收藏批量标签/分类/取消；资源SQL搜索/状态分类过滤、多选分类删除、实际文件分享与多文件ZIP经SAF导出；版本分页只读不改current | 操作按当前明确选择执行，导出/分享使用受限副本，不删除接收方仍可能读取的租约；实际聊天App仍需用户验证 |
| XS-03/04/15 | 持久删除tombstone、默认UUID轮换、pending purge启动重试；资料库DB在实际事务锁后复验deleted；旧票/新票/旧Admission/导入均不能复活已删scope | 删除持久化失败恢复旧缓存且明确失败；purge失败保留停用/pending，未宣称已经擦除；其他scope与浏览会话保留 |
| XS-15完整备份 | 分块AES-256-GCM、PBKDF2-HMAC-SHA256 600000轮，随机salt/nonce与独立最终空块认证；非ZIP条目协议，全部认证/manifest/hash/type验证后media-first、archive-last发布到新UUID；持久marker处理跨DB中断恢复 | ≤512MiB明文/500个已完成资源；metadata≤16MiB/20000条，单图/视频沿原上限。超限明确拒绝而非截断。包含正文版本和已完成实际文件，排除任务、URL、会话、凭证、密钥及租约；不是SQLite/WAL在设备上的透明加密 |
| XS-14/15 | 达阈值后1秒合并更新、600秒cap停止同值写；关开独立consent代次与owner session防旧事件复活；page pause/overlay/freeze停止JS；geometry跟踪与正文读取分离 | 保留1.2/3/5秒连续下界、不拼接中断、不把曝光变打开。20k虚拟列表与模拟时钟压力不等于24小时手机或电量验证 |
| XS-15构建 | release缺签名输入直接失败；人工candidate workflow限定main祖先、完整commit、正版本号、受配置环境/固定证书pin，禁用签名job共享Gradle缓存；只上传APK和公开来源记录，无自动发布/私钥上传；源码和NOTICE摘要清单 | 正式密钥/环境保护尚未配置，未生成用户正式密钥，未运行生产candidate工作流，不替换GPL或完成自定义重新许可 |

合并现在保留全部来源正文版本并重映射pointer，按相同SnapshotPolicy选择当前正文；来源与默认不覆盖。撤销在普通队列与实际SQLite写锁内完成全部拒绝检查后，才持久化删除承诺。被拒绝的修改/媒体结果不会进入pending误删；已接受的purge失败则保持停用并可重试，不能以拒绝名义在下次启动误删后续内容。

## 首次本地验证与发现修正（历史）

- 核心：1141项JVM断言通过（包含204 capture、220 writer、31 snapshot、112加密与32合并检查），SQLite v5保留迁移/两类FTS与scope、DOM回归和20k observer压力通过。增加完整正文证据后压力预算从96 article styles细化为96 geometry + 4个viewport正文style；没有放开候选遍历上限。
- 完整AAR、harness/client及两套AndroidTest APK、双模块Lint本地通过。见[本地完整检查/包摘要](evidence/round-d-local.json)，客户端Lint 0错误/11警告、运行库0错误/16警告（包含有意后台commit、已受版本控制的内联常量提示）；Android实际仪器测试仍 PENDING，不以编译通过代替执行。
- 删除审查补了commit(false)仍改SharedPreferences内存的回滚、旧callback取票拒绝、事务锁后deleted检查、undo旧mandatory clear交错回归和跨controller事件owner校验。
- 最低版本审查移除图片/视频选择脚本的optional chaining，保持旧WebView语法兼容；API26新增CI，结果待实际运行，不提前声明兼容。

## 容量与模拟压力

[桌面真实SQLite容量原始数据](evidence/round-d-capacity.json)：10k和100k从v1逐级到v5，保留原post rowid/正文/分类/事件，FK/integrity与current/version FTS映射通过；100k生成115294 history和101090 snapshot versions，数据库约136MB。修复了旧历史回填仅account索引导致反复扫描，以及相关FTS反复执行；结果记录原/新EXPLAIN、8类查询中位耗时，没有以机器耗时阈值伪造手机性能。

20k虚拟帖子、1000次路由/遮挡/冻结循环已实际执行；候选geometry≤96，style=100（含4个可见正文）、active runs最多8，payload约6770字节，geometry周期读取正文0次。另行通过`--long-stress`运行了24小时**模拟时钟**压力（CI常规为11分钟，不混称同一次运行），不是24小时真实等待：24卡、200ms采样，写入含24个cap共14400次；多日真机soak、低内存/电量仍NOT RUN。

## 签名验证

[合成签名实际证据](evidence/round-d-signing.json)：使用临时自生成测试key实际构建versionCode 3和4，两个APK均非debuggable且证书pin一致；真实apksigner/aapt校验通过，错误pin拒绝；缺输入的assembleRelease --dry-run明确失败。临时私钥已销毁且未上传。这证明签名工程路径，不证明在用户设备上覆盖旧PoC，也不代表正式签名已经配置。该证据记录的是提交前工作树，source clean=false；不能把其中旧HEAD冒充新源码CI身份。

## 首次本地实现后的待跑状态（历史）

API26/30/36的真实WebView、SQLite、Job/Keystore、完整资源、SAF和新资料治理UI夹具即将由本轮CI执行；预计每API52项harness+62项client，但最终仅按XML的实际case/失败/跳过数记账。各job保留自己的APK/public cert与source/tree，不借用另一构建job的包。

真实X登录/2FA/会话、站点DOM/短编辑/停留/本人动作/精简、真实CDN、微信/QQ/飞书/Telegram会话接收、多日真机/存储满/强制停止/重启仍需物理设备。自动live capture/action/cleanup仍关闭。候选签名需要权利方配置固定私钥和环境保护；如选择切换自定义许可，须另行确定条款及权利范围；当前GPL、历史授权和第三方义务继续有效，保留GPL不以另拟自定义文本为前提。真实设备和正式签名的缺口不能以合成PASS替代，也不发布未经验收正式版本。

手机步骤：使用空白/隔离测试实例，记录APK/public signer/系统/WebView；正常登录与切号；选择有权保存的图片/direct MP4并测试后台/切网/重启/取消；SAF导出加密完整备份再恢复到新库，核对正文版本与实际文件；分别手选聊天App会话确认接收；验证整库删不影响其他scope/活动分享租约。调试证书可能逐job变化，不为安装新包直接卸载已有资料的Xs；先完成迁出/固定签名流程。

## 首轮三版本实际失败记录

[CI66 42369802915](https://github.com/ForceMind/Xs/actions/runs/42369802915) 的core/构建通过；API26/30/36均实际启动并运行，每套52项harness＋62项app，共342项，304通过/38失败/0错误/0跳过。head `50c91ecfa5eff5df306a6adfe9c63c17750ea3cb`，实际merge `8be3b3c254c956d89a448d622488724e3de90058`，tree `768cf90d7d9fab28bff9123578fd69dc723d1fd3`。原始case与栈、每job四APK/public cert、ZIP摘要见 [失败轮完整诊断](evidence/round-d-ci66-failures.json)。不能将其改记为环境阻断或PASS。

确认根因：完整资源描述符含sha256但通用字段名拒数字，导致共同导出失败；SAF测试管理入口的MANAGE_DOCUMENTS/跨UID权限与错误shell fallback使4个Governance用例尚未进入UI；两consent关闭时测试错误期待observer仍安装；旧WebView去clamp时样式未完全恢复；API26 retriever尾帧EOS空返回及真实Safe Browsing开关未生效。修复保持完整视频/尾PTS、COMPLETENESS/版本数、真实SAF和能力边界的业务断言，没有跳过设备或缩短验证路径。

三套LibraryUiTest9项与AssetLibraryUiTest4项均PASS（合计39），包括206版本分页、batch与删库/lease/失败commit检查；这只证明对应源码的局部场景，不覆盖失败功能。Source-epoch导出和SQLite abort断言也已加强，防止前置codec错误被误当作预期失败。

本轮继续补上独立清缓存/清网页登录会话：仅清WebView数据，清会话销毁旧DOM、清Cookie/WebStorage/HTTPauth/formdata，保留native库/资源/lease；持久epoch/pending与上传身份阻止旧callback和恢复状态跨清理。API26旧provider（Chrome69.0.3497.100）确实不支持启用所需Safe Browsing，显示实际不足并拒真实浏览导航，资料库/离线夹具仍可用；不伪造provider支持。正常更新后的API26 provider须另验，不能泛称整个OS版本不支持。

恢复marker补完整checkpoint确认才清理，忙/不完整保留；非WAL仅EXTRA同步可清，已发布result不被清理异常改报失败。新增真实SQLite busy/解除后重试回归，不宣称物理断电已经验证。

修复后的本地统一复测：[1152项JVM、SQLite/DOM/20k压力和完整Android构建/双Lint证据](evidence/round-d-fixes-local.json) 均PASS（165 tasks，27s）。新增3项隐私用例含真实Activity关闭后携旧Intent重启，持久显式导航屏障阻止自动restore/HOME/deep link；只有用户新的原生导航通过安全检查后解除。marker父目录和资源目录链接均增加fsync，但不宣称物理断电验证。模拟器执行仍须下一轮CI实际结果。


## CI67与继续补齐的D源码

[CI67 42372593770](https://github.com/ForceMind/Xs/actions/runs/42372593770)实际执行API26/30/36共360项：336通过、24失败、0错误/跳过；core1152和构建通过。head `aaf4f1be709d12d3e05c66669fb60eacbcd75761`，tested merge `67b594c27eda42de9782b692320bab51457dec81`，tree `cf99e2281e5e23e528d8b2bffd2845d1ff103549`。见[完整失败诊断](evidence/round-d-ci67-failures.json)。API26的视频首尾验证/完整播放和能力门、三版本NativeText/cleanup修复已实际PASS。剩余为API30设置`busy_timeout`的PRAGMA实际返回行，不能由`execPerConnectionSQL`执行；另有版本合并的current与历史检索预期、DocumentsUI真实文件行触摸、Android标准FTS4语法下显式AND被当作搜索词，导致隐私测试前置收藏搜索为空，以及ActivityScenario Intent bookkeeping。保留失败历史，不冒充PASS或环境阻断。

接续源码另外补上默认永久/7/30/90/365天的可选事件明细保留，保留累计打开事实，防止将淘汰明细误判成从未打开；默认不限的每库媒体配额、完成/活动预算和并发预留，下调不删除完成文件；管理页双语确认与分清保留明细/累计事实的统计。保留清理使用普通同库串行队列和实际SQLite锁内时间复验，不为清理明细撤销进行中的媒体票据。历史事件懒清理，不承诺后台准时清理，其他实体不自动过期。

新增独立UID接收fixture，通过真实Android Intent READ/ClipData grant测试选中媒体、拒绝未授权/未选中/写入/数据库路径，以及整库删除后接收Activity存活期间首次延迟读的实际hash；不是聊天App接收验证。新增Browser真实SAF/触摸window.open夹具、API27，以及由外部ADB驱动的存活PID→force-stop→确认进程消失→不同PID验证和同job调试证书版本3→4安装升级路径；尚待新CI执行，不把默认套件内的in-process recovery case计为OS杀进程证据。

完整加密容器的最大536870912字节已在32MiB JVM真实流式加解密并逐字节核对，1152断言与DOM/SQLite压力本地PASS。它证明容器内存边界，不等于Android近512MiB实际文件恢复、真实断电或手机磁盘压力。

多词FTS的实际根因已在[SDK SQLite标准语法复现](evidence/round-d-fts-compatibility.json)：旧`native* AND resource*`为0，新隐式交集`native* resource*`为1；两个Java builder统一修复，保留token白名单。新增原生Android current/history/multiword/Tab/字面OR回归及浏览器动作前完整种子校验。桌面SQLite的增强语法通过不能代替Android原生测试。

本轮追加源码的[本地统一检查](evidence/round-d-governance-local.json)实际通过1155项JVM、v5迁移/搜索、DOM/20k压力、SDK标准FTS复现、完整构建和双Lint；[五个实际debug夹具APK/public cert](evidence/round-d-governance-local-apks.json)单独留证。196 tasks最终17秒（修改触摸源前完整31秒）；源码base为aaf4f1b的未提交工作树，仪器声明52＋98仅是待跑数量，不提前记600项通过。生产key/许可/真实设备边界不变。


## CI68真实结果与下一修复

[CI68 42374634739](https://github.com/ForceMind/Xs/actions/runs/42374634739) core1155/构建通过；API26/30/36各150项共450执行，415通过/35失败/0错误/跳过。API27因官方无`google_apis;x86_64`镜像未启动，实际0项，不计为应用失败或PASS。[完整原始诊断](evidence/round-d-ci68-failures.json)含merge `27769f02f1f661454c4ea1559a4c1eb2b0a4a6c5`、tree `595dcb18016bf4bdf2f0e520dce1236ac5fc35a5`和各job五APK/环境/ZIP摘要。官方[sdkmanager目录证据](evidence/round-d-api27-catalog.json)/[7条包片段](evidence/round-d-api27-packages.txt)确认27有google_apis;x86 rev11，下一矩阵27改x86，另三版保留x86_64，并记录实际ABI和/data容量。

三套FullBackup10项（含500真实tiny PNG）/MediaQuota7项/Retention9项/原生多词FTS均实际PASS；旧容器/视频/NativeText/精简回归也通过。失败集中于测试跨APK匿名ResultReceiver具体类不可解包、DocumentsUI返回true的祖先AX点击未激活文件、输入合成器呈现/过期BACK事件/原生文本匹配、API30管理窗口焦点/销毁泄漏，以及规范URL预期。修复使用framework ResultReceiver Binder、受限固定filename实际触摸、visual/frame同步与isTrusted命中、当前uptime键盘事件及弹窗销毁，不伪造文件选择结果/URI grant，也不延长业务时限。此前隐私两例已PASS，第三已抵达最后canonical断言，实际URI行为正确。

继续修正legacy累计统计：每帖取MAX(原计数,当前OPEN证据数)再求和，标已知下界，避免清理前0/曾开1、清理后数值跳变；不相加双计。追加第10个保留回归。下次新增近上限Android真实30 PNG（≥480MiB）流式完整恢复，不能与已通过的JVM512MiB或Android500tiny相互冒充。

外部OS强停/升级驱动在CI68因主仪器失败**NOT RUN**。下一轮另外实际执行同一guest serial的reboot前后kernel boot_id变化与native数据/Keystore/lease保留，及仅GITHUB_ACTIONS且证实emulator/qemu的真实/data低空间测试：唯一shell自建文件压至约28MiB可用，native实测低于40MiB并拒绝新图任务、保留既有PNG/hash/离线搜索，finally删除该唯一文件、核不存在并记录释放后容量，清理失败不能记PASS。8GiB AVD给大资源恢复余量；这些不是mid-stream ENOSPC/物理断电/厂商后台或用户手机证据。

[下一轮提交前本地复测](evidence/round-d-pre-ci69-local.json)实际1155/SQLite/DOM/SDK FTS/完整Android与双Lint PASS（196 tasks28秒）；52＋100每API只为新源码声明，608项及外部drivers结果仍待下一CI，不提前记通过。


## CI69：完整四版本已实际执行，剩测试手势修复

[CI69 42376642706](https://github.com/ForceMind/Xs/actions/runs/42376642706) head `a5695701ea09baa736811403af6d687b87418920`，tested merge `d36da90b73870aa038545e36a033295d8fb49a5d`，tree `006bac2fbf0e6e2c56c145091fd8bcf3acd181d7`。4API各152项，**608实际执行、589通过、19失败、0错误/跳过**。API27 x86镜像启动和ABI实证已具备；原架构缺包不再阻断。core1155/完整构建与Lint实际通过。[完整结果/环境/20主APK签名和hash/ZIP摘要](evidence/round-d-ci69-failures.json)。

四API的CrossUid5、StorageUI3、Retention10、FullBackup11（含500tiny和30个大PNG）、原生FTS与视频都PASS；API36客户端100项全部PASS。近限资源实际source bytes：API26/27为504333120，API30/36为504333510；ciphertext分别504477301/504477691字节。每套30个新UUID实际文件逐项hash/类别/时间/来源/源与default保留通过。它是约481MiB的真实原生资源恢复，与512MiB JVM容器和500tiny数量边界分别记账。

19项失败为Gov每API3个SAF目录选择、API27额外1个原生合并按钮受键盘/滚动影响，以及26/27/30各2个Browser实际SAF文件选择未返回。没有把失败改为产品PASS；根因诊断集中于DocumentsUI对TOOL_TYPE_FINGER的判断、drawer呈现与陈旧/未可见节点。下一修复只改测试触摸/滚动/诊断：完整PointerProperties/PointerCoords、真实finger且当前时钟、fresh节点/窗口相交/稳定bounds和帧同步，仍强制真实目录、关闭drawer与真实导出/恢复/URI数据断言，时限不增加。

[两测试APK编译/工程检查](evidence/round-d-pre70-local.json) PASS（132 tasks10秒）；runtime源码未变化，上一轮1155/Lint保留源身份。CI wrapper在Gradle失败前收集合成截图/AX并保留原退出码，[4个模拟主机故障场景](evidence/round-d-wrapper-control-flow.json)实际验证成功（不是Android结果）。外部四driver在CI69仍因主仪器失败NOT RUN；下一轮首次实际执行后才可报告OS强停/同jobdebug升级/guest reboot/真实低空间通过。


## 手机现场验收的具体操作（全部NOT RUN）

先在空白/隔离测试环境核对APK SHA256、公开签名证书、Android/API与实际WebView包/版本。现有资料机不要为更换调试证书直接卸载；先完成可恢复备份与固定签名路径。当前真实站点自动采集/本人动作/精简仍关闭：先完成正常X登录、2FA、核心浏览/发帖/回复/上传、旋转重启/过期/退出/切号的W0证据，再在经过站点选择器确认的限定测试构建中验以下自动场景。不能只打开开关就声称真实采集已获验证。

| 场景 | 现场步骤 | 必须观察到的结果 |
| --- | --- | --- |
| 打开历史 H01～H05/L03 | 开启一次打开记录；主动进入一条有权测试的详情，少于1.2秒即返回；同导航刷新/旋转/正文补载；断网后按关键词/作者找回，查看打开时间 | 即时一条OPEN_POST，不需另存、不等停留；同导航不重复；先链接后补正文，离开再主动进入才新增打开；时间/正文质量如实，离线可查 |
| 不打开的停留 D01～D09 | 单独开启停留，选3秒；帖子A在无遮挡前台可阅读区连续3.5秒且不点开，B仅1秒；返回后断网搜‘停留≥3秒且从未打开’，再主动点开A | A有FEED_VISIBLE/有效时长下界，B没有；原查询仅A，opened_count=0且未自动收藏；后来打开A有独立OPEN且曝光保留；后台/锁屏/遮罩/中断不能拼时长 |
| 独立收藏 F01～F04/L02 | 在原帖明确点击收藏到Xs，分类/标签/备注；正常退出并切另一X账号；断网进同本机库再查；取消收藏 | 收藏与分类保留，不向X服务器调用书签/点赞；取消收藏后历史、已下载文件仍在；不同本机scope不串写 |
| 图片/视频与接收 M01～M05/X01～X05/V01～V03 | 选择允许保存的单图/图组/直达完整标准MP4，完成后断网预览并核大小/时长/音轨；经系统分享分别手选微信/QQ/飞书/Telegram的会话并由用户确认发送；另测取消、延迟读取和删源库 | 实际文件完整可读/可播，接收端确实收到对应字节/媒体；选择目标或返回不能记送达；现仅直达MP4窄范围，blob/HLS/DASH/DRM/超限应明确不支持，不能冒充成品 |

补测手机后台切网/用户停止/重启后任务状态、加密完整备份到SAF再恢复新库、旧正式版本覆盖升级/失败回退、多日长滚动/电量/存储压力。模拟器的guest reboot、同源debug3→4和合成接收方不替代这些设备/正式签名/真实聊天App结果。现场只回报脱敏结果、版本和必要公开摘要；不把密码、验证码、Cookie/Token、私信或私密截图贴到公开Issue。


## CI70：152项完整通过的两个系统与外部启动准备

[CI70 42378804866](https://github.com/ForceMind/Xs/actions/runs/42378804866) head `ce9cae7c1102d1efb2156e1f55907dbbd3d423b3`，merge `cc219a339b2466959dc5141eb502604dbba9d265`，tree `002bcab34404e57d8b99bf1821eed4ddd2ef8760`。**608主用例实际执行、605通过/3失败/0错误/跳过**；30/36各152全PASS，26仍有1个Gov选文件未返回和1个Browser权限负例，27有同1个权限负例。详细[完整原始报告](evidence/round-d-ci70-failures.json)保留4API/20APK/环境/ZIP摘要。

权限负例原用BOUNDARY，但另一case此前已由用户真实选择过该URI，旧的合法临时授权不能当作“本次授权扩散”。下一fixture增加任何case都从不选择的第三文档：选择前selected/never均DENIED，选择后selected明确授权、68字节/写拒绝和never仍DENIED全部保留，不手动grant/revoke、不改生产权限。26偶发Gov仍需实际截图定位，没有给它增加盲重试/双击或放宽20秒时限。

30/36主仪器通过后首次外部seed报`Unable to find instrumentation info`，实际原生探针调用0；UTP结束会卸载测试APK，后续组件并不存在。因此下一workflow在四driver前仅一次显式安装本job main3/test3（`-r -t`，不降级、不用别job的包），记录hash、真实`pm list instrumentation`组件与installedVersion3；升级后不再装回3。矩阵也明确安装35.0.0工具供升级验证。随后OS探针仍须实际首次跑通才可PASS。

UTP同时删除target externalFilesDir，导致先前失败截图在Gradle返回时已丢失；下一Gov仅在证实qemu=1后，把白名单生成的PNG/脱敏AX经shell复制到chmod700的固定临时目录，wrapper在AVD alive时拉取。它只改诊断持久性，不掩盖原测试失败。源不包含真实用户手机/文件/网页数据。

[本地新两测试APK和工程检查](evidence/round-d-pre71-local.json)PASS（132 tasks9秒），Python脚本语法通过。生产runtime源码未变化，core1155/双Lint沿用已核验源范围，下一CI仍完整执行608与四独立driver。


## CI71：四版本主测试及跨进程验证全部通过，旧系统压力构造尚未通过

[CI71 42381897122](https://github.com/ForceMind/Xs/actions/runs/42381897122) head `7511d5212f4caa20828526a901922bd2fe99b2ca`，tested merge `cebcf3267977be00f84ec2ccd0e5dc5136fcaef7`，tree `869385002569cd7163309326619be166ccda6313`。core 1155、构建和双Lint通过；四API各52项harness＋100项client，**608/608主测试通过，0失败/错误/跳过**。外部真实force-stop、同job同源调试签名3→4安装升级、guest reboot/boot-ID变化，各四系统全通过。主XML、20个主APK及8个升级APK的SHA256/公开证书、环境/ABI/driver原文与独立校验见[完整结果](evidence/round-d-ci71-results.json)。重复外部探针调用不并入608个主用例。

低空间原生拒绝/既有文件hash/离线搜索在API30、36通过，普通app实测分别29204480、29102080字节可用。API26、27在构造压力文件时失败，未进入`low-space`原生业务断言，不记PASS或产品逻辑FAIL：shell `df`约7954148KiB可用，fallback `dd`写到7488761856字节遇到`Quota exceeded`，26的fallocate也quota、27的旧工具为Invalid argument。两套finally均已删除且证实唯一自建文件不存在，释放后容量有记录；不能为通过忽略EDQUOT或缩小压力要求。

后续最小修复`026245fa8cfa791f85d766d34a90b2822fe5f514`只调整探针：seed直接由普通应用`File.getUsableSpace()`报告可用量与UID，host据同一真实API预算，低空间阶段再次要求普通UID相同与真实可用0～40MiB；shell df保留为诊断。没有修改生产下载门槛。[本地测试APK编译/脚本语法和工程检查](evidence/round-d-pre72-local.json)通过（91 tasks10秒），此处不提前记CI72为PASS。


## CI72：普通应用可用量已核实，旧模拟器shell配额单独处理

[CI72 42383813896](https://github.com/ForceMind/Xs/actions/runs/42383813896) head `026245fa8cfa791f85d766d34a90b2822fe5f514`，merge `9f3478cf23d15195afec13b95141ed69acbd956a`，tree `532e0f582df363c16dd8967f96929ad2a2fa80d7`。四API主测试仍**608/608通过、0失败/错误/跳过**，core1155与完整构建通过；force-stop、同源debug3→4、guest reboot各4/4，低空间30/36通过，26/27压力构造仍失败（14/16外部driver PASS，整个run不能记成功）。[完整原始结果/包证据](evidence/round-d-ci72-results.json)。

API26普通app UID10082，native baseline8170389504字节；27 UID10084，baseline8147226624字节，均与对应shell df一致。两套dd又在7488761856字节准确触发quota，并已清理唯一生成文件；因此‘df与getUsableSpace口径不同’已排除为本次原因。30/36最终普通UID分别10172/10221，可用29118464/29261824字节，真实新任务拒绝与既有PNG/hash/FTS保持通过。

下一修复仅针对已证实的旧模拟器shell quota：先通过GITHUB_ACTIONS、emulator serial、qemu门，在普通分配真实报Quota exceeded且API26/27、userdebug、ro.debuggable=1、shellUID2000时，才允许临时adb root重建同一个唯一压力文件；实际rootUID0必须确认，应用验证仍必须原普通UID且实际0～40MiB。finally无论成功失败都清该文件/核不存在/记录释放容量，再unroot并核shellUID2000；任何清理或权限恢复失败均不能PASS。不对用户手机执行，不降低应用空间门槛，不把脚本构造失败伪装成产品通过。最终结果仍须后续CI实际执行。


实现依据仅限官方调试模拟器：[ADB root/unroot说明](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/root.md)及[Chromium模拟器镜像属性表](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/docs/android_emulator.md)说明userdebug与生产user镜像的权限差别；包名本身不作为执行证明，脚本仍强制读取实际属性/UID并验证恢复。没有remount、修改系统镜像或对真实设备提权。


新增[10项host故障/安全门回归](evidence/round-d-pre73-host.json)全部通过，执行真实driver控制流而仅替换subprocess，不连接ADB：错误环境/初始root不得分配，当前quota才授权，旧日志+当前reset错误不得root，错误/缺失原生压力证据必须失败，root部分生效后报错仍恢复，删除/不存在检查/unroot失败永不PASS。命令异常绑定本次operation/output，避免旧日志误授权。Python语法、shell语法、工程检查通过；这些10项不并入608项Android主XML，真实26/27压力仍待下一CI。生产Java未改，没有重复冒称本次重新构建Android。


## CI73：旧系统低空间首次真正通过，精确修正剩余系统UI夹具

[CI73 42385436203](https://github.com/ForceMind/Xs/actions/runs/42385436203) head `7e86d795bac11020bd08af2b9cdef39eeb8e742c`，tested merge `2ed09f5d9641f040af67faad8927c46f105694e2`，tree `901613812c4e63504cef5f1b3debdf499888d852`。core1155和10项host测试、构建通过；**主608实际执行，607通过/1失败/0错误/跳过**。API26/27/30各152全PASS且四个外部driver均通过，12/12 attempted通过；API36客户端100/100、harness51/52，主测试失败后四个driver均NOT RUN，不与前轮拼接成16/16。[完整原始报告](evidence/round-d-ci73-results.json)保留4ZIP、20主APK和6份实际升级APK、未产出的36升级两份、环境/失败stack/PNG与AX摘要。

API26/27严格先shell2000、当次dd quota、实际userdebug/debuggable1，才root并核UID0；原生分别仍UID10082/10084、与seed一致，可用28332032/28794880字节，真实空间拒绝/保留文件与FTS通过。唯一生成压力文件最终不存在，unroot后shellUID2000均通过。API30普通shell无需root，可用29065216字节。此前26/27构造失败不是忽略后硬记PASS，这次已真正执行应用业务断言。

剩1失败`GovernanceUiTest.assetsMultiSelectSafZipContainsEverySelectedOriginalFile`发生于API36真实DocumentsUI选provider，未到导出完整性断言。已实际查看持久PNG和AX：完整展开的drawer仍高亮Downloads，fixture标题可见，直接roots_list item可点击。tap标题的Rect与失败AX一致，无坐标漂移证据；同DocumentsUI进程恰在DOWN/UP间有两次ProvidersAccess刷新，‘列表重绑取消触摸’是日志支持的推论，未当成已证产品根因。

下一修复仅精确激活该标题所属roots_list的**直接可点击item**，fresh后再次确认其子树仍含fixture标题、可见/启用/正确窗口与边界，单次真实ACTION_CLICK；随后仍要求正确toolbar和drawer关闭。文档文件Recycler继续FINGER触摸，原SAF返回/实际字节/ZIP逐文件hash断言均保留，没有延时增加/盲重试/手造URI结果。官方[AbsListView item delegate](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/widget/AbsListView.java)将item ACTION_CLICK交给performItemClick，[DocumentsUI RootsFragment](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/07e538caffec348d204096b387562b99437ba780/src/com/android/documentsui/sidebar/RootsFragment.java)的真实item listener打开所选root并关闭drawer，与泛祖先/整个ListView点击不同。

[本地harness测试APK编译](evidence/round-d-pre74-local.json)通过（58 tasks9秒），生产runtime未改，实际四API结果待CI74；不将编译记为系统UI通过。


## CI74闭环与文档收尾

[CI74](https://github.com/ForceMind/Xs/actions/runs/42386764024) 对`0d193e4689d649970d9705818d33e8706b470d8f`实际tested merge `f23a211e083fe922456f903f5357d160c87f1c29` / tree `c8551cd4a09e4466cd84e7973039aba06a5f87b2`的六job全PASS；608主用例与16独立driver的全部原始结果已在[完整JSON](evidence/round-d-ci74-results.json)留档。精确item操作后的Governance四场景在四API共16例均通过，原生100例每版全通过，不再有该UI失败。26/27实际root构造/普通app测压/清理降权，以及30/36普通shell分配，各自独立验证，不互相借用结果。

本次统一更新中英文README、AGENTS、架构/计划/路线图/验收矩阵/CHANGELOG/交接文件，保留CI66～73失败史。当前已完成云端可执行子集，Issue #3现场总体验收和Issue #4完整产品跟踪保持开放。最终文档/证据提交的CI将单独在PR/Issue记录精确HEAD、tree、run与artifact，避免为了记录同一提交的测试结果而无限递归制造新提交；文档提交本身不等于重新运行测试。
