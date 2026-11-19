# Xs 网页媒体选择与旧版 APK 升级实施记录

日期：2026-11-07。继续既有 PR #5，没有重建工程。

## 当前结果：本轮源码与四版云端验收已通过

验证源码 `2c667517e5a812232a8b41e7a3f99166855963e6` / tested tree `e52dfdd3f5f7b51751154bf1cf2601554786ef33` 的[CI82 42419306946](https://github.com/ForceMind/Xs/actions/runs/42419306946)六个job全部成功。本轮没有rerun，不借前轮结果；当前可执行的XS-02媒体请求/浏览器交互和XS-03/15旧包升级切片已闭环，整体产品与正式发布仍有真实站点/手机门槛。

| 检查 | 本轮实际结果 |
| --- | --- |
| JVM / host driver / 构建 | 1287项JVM断言、23项驱动host检查、SQLite/FTS/DOM/容量检查、Android构建及app/runtime双Lint通过 |
| API26 x86_64 | harness52＋client104＝156/156；五项独立driver全部通过 |
| API27 x86 | harness52＋client104＝156/156；五项独立driver全部通过 |
| API30 x86_64 | harness52＋client104＝156/156；五项独立driver全部通过 |
| API36 x86_64 | harness52＋client104＝156/156；五项独立driver全部通过 |
| 分开记账 | 624/624主执行，0失败/错误/跳过；20/20独立driver、44/44外部native phase，重复native probe不并入主XML |

Browser24/24均通过：真实SAF文件选择、完整PNG/双图集合字节、5783字节MP4的FileReader/hash与真实手势播放至ended、真实pending选择器期间的敏感SPA→HOME及旧结果拒绝。SPA的刺激是本地合成函数执行真实pushState，不能当visibility事件必达或真实X上传成功。MIME是provider声明，不认证内容。

四项独立legacy lane均实际构建固定旧main f880 code1/schema2、调用其旧原生API写数据，再正常覆盖安装本源code3/schema5并验证同UID/路径/默认UUID、不同PID、原行/分类/FTS/时间/私有canary保留及完整verify后清理。旧独立收藏/媒体库不存在，N/A；canary不是下载资源。这是同job debug签名合成升级，不是生产签名或用户个人库升级。

本轮API26元数据选择真实List动作两次、fullbackup一次，原文档字节/预览取消/确认恢复均通过；合并IME关闭marker一次及完整case通过。API27/36两种IME分支未触发；本轮四版SAF-save IME分支均未触发。CI79的save分支、CI81的merge分支已有独立历史实测，不拼成CI82未发生的分支覆盖。Browser可选List分支没有自定义成功marker，若原日志不能独立证明具体别名使用，仅记六例结果，不从PASS推断每个别名都被执行。

实际tested merge为 `36022c99ce37b631135ab7c2a393400ca09811d8`；40份APK角色副本＝28个独立字节摘要＋12份重复副本，其中20份主provenance＋8份debug＋12份legacy。20份下载APK已独立核对实际bytes/aapt/apksigner；四API ZIP及8份当前UTP设备pb已校验。源码/每case、各独立driver raw命令与进程/UID/路径/版本/DB/公开证书、实际设备/WebView及ZIP/APK摘要见[CI82完整原证据](evidence/round-e-ci82-results.json)。以下按发生顺序保留提交前声明、CI76～81全部失败和未运行项；不把历史失败改记通过。最终仅同步文档/证据的HEAD/CI与产物账记录到PR/Issue，避免为记录自己的测试结果递归创建下一提交。

**真实X登录/DOM/服务器上传/CDN、微信/QQ/飞书/Telegram接收、实体手机/长期耗电/物理断电和生产签名仍NOT RUN。** live打开/停留、本人动作和精简适配仍未挂载/关闭；先按Issue #3完成W0，再在站点证据支持的限定构建验证。具体四场景与手机步骤见[D轮手机验收表](ROUND_D_EVIDENCE.zh-CN.md#手机现场验收的具体操作全部not-run)。未合并main、未发布正式版。


## 接续基线与本轮范围

接续源码 `ee119c24aa5e4ba77b0872e4abdf2bd950d74851` / tree `9998e21331936e7dbefbc61f295430352185f977`。重新核对 main 仍为 `f88003c8ec1ee193b75de7267db6ae7654ee77c4`，只有 PR #5 开放；Issue #1/#3/#4 保持开放。历史 [CI75](https://github.com/ForceMind/Xs/actions/runs/42388170475) 的 608 次主执行、16 项系统 driver 和 1155 项 JVM 断言通过，完整记录见 [D 轮证据](ROUND_D_EVIDENCE.zh-CN.md)。这些结果不自动覆盖本轮修改。

本轮补 XS-02 的媒体选择请求约束和真实浏览器交互，以及 XS-03/15 从旧 main 到新客户端的跨源码 APK 升级验证。真实 X 登录/站点适配/媒体 CDN/聊天应用/物理设备和生产签名门槛不变，自动打开/停留、本人动作和精简的 live 适配继续关闭。

## 实际源码改动

| 问题 | 实现 | 验证边界 |
| --- | --- | --- |
| 文件选择器忽略网页 accept，固定允许全部图片/视频；返回仅检查 MIME 前缀 | 不可变 BrowserUploadPolicy 保存当前请求的 MIME、OPEN/OPEN_MULTIPLE 与单选1项/多选最多8项；picker 与返回结果复用同一策略；拒绝空、重复、数量与 MIME 不符的结果 | MIME 来自 provider 声明，不是文件内容真实性证明；网页或服务器的上传权限/格式限制仍需实际站点验证 |
| 保存模式、目录请求或超大 accept 输入没有统一边界 | 只支持 OPEN/OPEN_MULTIPLE；空或通配请求仍仅允许图片/视频，明确类型和常见扩展名映射；有限 token/字符预算，完全不支持的请求取消 | 不把下载的20/100MiB限制套给上传，不新增权限，不原生全量读取、转码或自动提交媒体 |
| 同 DOM 的 history 路由变化不一定触发 onPageStarted | doUpdateVisitedHistory 按每次回调 URL 取消旧上传，覆盖敏感页再回原 URL 的边界；保存请求 owner/epoch | 原生/系统 picker 的正常暂停不等于失效；真实 WebView 回跳案例须以本轮仪器结果为准 |
| 旧回调可能同步触发新请求 | 结果和取消先清空旧 pending 状态，再交回 WebView；旧 dialog/callback 不覆盖新请求 | 不向页面返回本机资料库、会话或其他文件 |

此切片不修改数据库 schema；保留现有 v1～v4→v5 和 media v1/v2→v3 迁移实现。

## 新增真实交互夹具

- 测试 APK 的 DocumentsProvider 提供固定只读 MP4、两张新的 PNG，以及永不选择的权限负例；不复用已有用例的 URI 来伪造“此前没有授权”。MP4 复用仓库已有 5783 字节合成视频，来源/生成命令及摘要见 [视频夹具说明](../app-android/src/androidTest/assets/VIDEO_FIXTURES.md)。
- 视频走真实触摸 input → 原生确认 → 系统 SAF → FileReader 全字节比对 → 用户手势播放至结束，不联网发帖。双图走系统实际多选，核对两个文件名、MIME 和各自字节，不假设系统返回顺序。
- 系统 picker 存活期间实际 pushState 至敏感页再回 HOME；最终 URL 相同也不能将旧结果交给页面。原有正常选择、取消、导航失效和未选文件拒绝断言保留。
- 目录选择只激活精确 roots_list 的直接 item；具体文件选择继续使用真实 FINGER 手势，不手造 Activity 结果或 URI grant。

提交CI76时，以上只是源码场景定义，原生执行当时待新CI；最终实际结果见本文顶部和各轮报告。

## 跨源码 APK 与真实旧库

固定旧源 main `f88003c8ec1ee193b75de7267db6ae7654ee77c4`：包名 `dev.forcemind.xs`、versionCode1、数据库 `xs-local-history.db`/schema2。当前包正常 code1→3 覆盖安装，由当前 helper 执行2→3→4→5；不得在验证前卸载、清数据或使用降级参数。

LegacyUpgradeProbe 仅以 Android/Java/JUnit 类型和目标 classloader 反射访问旧 API，避免把当前 Xs 类打入旧 target 或用新 helper 假装旧版本。旧 helper 真实创建打开/LOCAL_SAVE、正文作者与时间、分类、FTS、默认 UUID 和私有文件 canary；记录旧 schema、原 rowid/dataDir/UID。更新后核对新进程、同 UID/路径/UUID、保留的数据与新旧全文搜索，并如实标示旧快照质量。

旧 main 没有独立 favorites 表或媒体库，因此这条 lane 的对应升级项为 N/A；私有 PNG canary 不是旧版下载资源。该测试不声称生产密钥、跨签名或用户手机升级已通过。

驱动限制在明确的 GitHub CI、emulator serial 与 qemu 环境；旧 source/tree、两版 APK/测试 APK 的包名、版本、实际 hash 和公开证书必须核对。它在主仪器之前运行，只在完整 verify 后清理本次合成数据；原四个系统 driver 继续保留。外部 seed/verify 调用独立计数，不加入主 XML。

## CI76之前的本地检查与当时待执行声明

本地全量核心检查实际通过 **1287 项 JVM 断言**（原1155＋新增132），SQLite迁移/标准FTS/DOM/20k压力、512MiB容器在32MiB JVM逐字节往返、原10项低空间 host 故障测试均通过。尚未以编译或本地单元结果代替新的 Android/系统 driver 结果。

新增10项legacy驱动host安全/故障测试也已通过；它们执行实际驱动控制流、替换外部进程，不连接ADB/Gradle或改变设备。完整Android构建196 tasks/26秒和双Lint通过（运行库0错误/20警告，客户端0错误/12警告）；最后测试helper修改后再执行app测试APK及Lint，121 tasks/17秒通过。固定旧main独立worktree实际构建code1 APK通过，49 tasks/10秒；与本地当前code3 APK的公开证书一致。以上工作树含未提交改动，不把旧HEAD当成已提交的新源码身份。

原始日志摘要与确切范围见[本地检查](evidence/round-e-local.json)、[本地两版APK](evidence/round-e-local-apks.json)。[实际DEX包隔离](evidence/round-e-dex-audit.json)确认当前测试APK确含LegacyUpgradeProbe，而当前主APK529个Xs产品class定义没有被重复打入测试APK；这只排除用当前产品类冒充旧target的一种路径，真正旧schema2的实际执行仍必须由CI断言确认。两张新增选择用PNG也已验证格式/字节/摘要。

预计声明为每 API52项harness＋104项client，四版624次主执行；新增跨源码升级加原四项 driver，共20项。这只是待跑声明，最终仅按实际 XML 和独立报告记账。新源码的真实 X、Browser服务器上传、目标聊天应用、实体手机和正式签名验收均 NOT RUN。

官方依据：[WebChromeClient.FileChooserParams](https://developer.android.com/reference/android/webkit/WebChromeClient.FileChooserParams) 与 [onShowFileChooser](https://developer.android.com/reference/android/webkit/WebChromeClient) 要求宿主应用处理筛选，并自行审查不可信返回 URI。这里未复制官方实现代码，未扩展为通用文件桥接。


## CI76：旧 APK 原生 seed 实际通过，主套件被宿主输出解析阻断

[CI76 42409507801](https://github.com/ForceMind/Xs/actions/runs/42409507801) 测试head `72d4db537d4df635da39add3abd06f1896b36a33` / tree `a6e225510610ee69ccb2bbd945c1a998c1a3bc5e`。core1287/原10项低空间host＋10项legacy host/Android构建通过；四个模拟器job均在pre-main legacy seed的**宿主状态解析**阶段失败，主XML实际0次、新Browser6例各API均NOT RUN，不计为主套件零失败通过。

四个旧target的真实native seed均返回`OK (1 test)`：实际schema2、旧helper/原生API/FTS/分类/私有canary写入成功，普通UID26/27/30/36分别10077/10079/10167/10216。尚未启动旧介绍页/force-stop/安装新APK/verify/cleanup；旧库升级到5仍未运行。

具体framing：AndroidJUnitRunner会将第一项状态紧接在`dev.forcemind.xs.android.LegacyUpgradeProbe:`进度前缀后，没有换行。API26首项为pid，另外三版首项为db_path；原parser只匹配严格行首`INSTRUMENTATION_STATUS`，因此漏掉一个字段。这是宿主parser错误，不是旧APK链接、SQL或浏览器测试失败。

下一最小修正只接受两种明确格式：行首状态，或精确测试类名＋冒号/水平空白再状态；字段仍须唯一一次，schema/UID/PID/path/scope语义校验完全保留。新增[13项host方法回归](evidence/round-e-pre77-host.json)通过，包含真实首字段、CRLF、错误prefix和混合重复字段拒绝；四份实际CI76 seed stdout的宿主回放也已成功解析。以同格式替换schema测试verify解析分支只是host合同，不冒充真的新APK迁移。生产/Android源码未变化，没有重复构建来冒充新仪器结果；下一CI仍需实际执行624/20声明。


[CI76完整原始汇总](evidence/round-e-ci76-results.json)保留四份ZIP摘要、实际旧seed输出和宿主失败阶段、24份实际APK角色记录及16个不同字节摘要（重复副本另计）、缺失harness/debug产物和NOT RUN范围；不借用CI75报告充当本轮执行。


## CI77：四版真实旧库升级通过；九项浏览器选择步骤失败

[CI77 42410468459](https://github.com/ForceMind/Xs/actions/runs/42410468459) 的head为 `5773aa0d8b6f081f5dd9bd48c844b6cac99d92a2`，实际merge `b86a21e2e3ca5307c42009341776d1ac7a56fd12` / tree `e64cb4084399b44852f188434d5c3f5985fae223`。core1287、23项host检查和构建通过；主XML实际执行624次，615通过、9失败、0错误/跳过，不能记为全量通过。

四个API的跨源码旧main code1/schema2→当前code3/schema5 driver均完整通过：真实旧API seed、旧介绍页启动与force-stop、正常覆盖安装、同UID/路径/默认UUID、不同进程、原始行/分类/FTS/时间/私有文件保留及完整verify后自有夹具清理。四版MP4真实SAF、5783字节FileReader/hash、真实手势播放至ended均通过。

API26主156次全过，并完成原4项系统driver；API27主155/156，仅双图确认按钮查找失败；API30/36各152/156，四个PNG/双图/SPA用例停在文件名查找。实际外部driver8/8通过（跨源码4＋API26原4），其余12项因主套件失败未运行，不能借用API26结果。完整输出、APK角色摘要、ZIP/命令证据及未运行范围见[CI77原始汇总](evidence/round-e-ci77-results.json)。

已核实API27日志中的真实长按进入action mode；Android8.1官方DocumentsUI的确认项为`action_menu_open`，框架AX类可以是TextView，因此测试helper补精确ID。Android11官方GridPhotoHolder图片网格不提供文件名TextView；新helper仅在已确认的合成目录真实触摸“List view”，必须看到相反“Grid view”动作后继续精确文件名和真实FINGER选择。保留20秒/总5次真实滚动上限，支持回到目录开头，不放宽完整字节/权限/失效断言。

CI77虽然运行了诊断pull，但实际没有图片/AX文件：UiAutomation执行命令不经shell，先前路径单引号变成了路径字面字符。修复为固定白名单ASCII路径，并分别保存AX、有限logcat和可用时的截图；安全窗口不解除。未声称看过不存在的截图。若下一轮全部通过未触发failure hook，诊断导出应记“未触发”，不能记实际导出通过。

官方源码依据（未复制实现）：[Android8.1 action_mode_menu](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-8.1.0_r1/res/menu/action_mode_menu.xml)、[Android11 GridPhotoHolder](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-11.0.0_r1/src/com/android/documentsui/dirlist/GridPhotoHolder.java)、[Android11 sub_menu](https://android.googlesource.com/platform/packages/apps/DocumentsUI/+/refs/tags/android-11.0.0_r1/res/menu/sub_menu.xml)。这些是结合实际失败日志对UI分支的定位，不冒充CI77已导出的现场AX。

CI78前仅改浏览器测试helper；生产源码/Provider/legacy driver不变。app测试APK及appLint实际编译通过，121 tasks/17秒；下一轮仍需实际执行624主用例和20项driver。


## CI78：全部浏览器用例通过，API26导出保存受IME遮挡

[CI78 42412703904](https://github.com/ForceMind/Xs/actions/runs/42412703904) 的head `c04d9738cbc3583f67f4d1872d7480248d2f11d6` / source tree `176fdec125339be9d1f068d8daea78752b1e262c`。四API主XML实际624次，623通过/1失败/0错误/跳过。24次Browser用例全部通过：真实PNG、MP4读取/手势播放、双PNG多选、SPA敏感路由回返拒绝及原有边界均有各系统独立结果。API27/30/36主套件全156通过且各5项driver通过；API26的client104全过，harness52中1项Gov导出失败，legacy通过，其余四driver未运行。完整计数/源身份/原始摘要以[CI78汇总](evidence/round-e-ci78-results.json)为准。

失败为`GovernanceUiTest.assetsMultiSelectSafZipContainsEverySelectedOriginalFile`在`saveInFixtureRoot`等待可见“Save”。这次**真正导出了并查看了PNG/AX**：目录和Xs-selected-media.zip正确，SAVE截图位于(237,345)-(310,381)，但AX保存表单/editor/button1被标为visible=false；TYPE_INPUT_METHOD窗口bounds(0,311)-(320,640)覆盖该区域。不是ZIP字节、provider或根目录失败。Gov诊断导出已实际验证；Browser没有失败，不能将其未触发的failure hook记为实际捕获通过。

下一最小修正仅在已确认fixture目录/关闭侧栏、固定三个导出文件名之一、exact container_save内android:id/button1 Save且实际IME窗口存在、Save不可见时，执行一次真实系统Back收键盘。随后必须确认IME消失、原目录/文件名保留、原Save可见，再走原系统ACTION_CLICK和SAF返回，全部ZIP原文件字节断言不变。未无条件Back、未点不可见节点、未加超时、未降低验收。

该单文件helper通过测试APK和dev-harness Lint实际构建（88 tasks/30秒；0错误/16警告），无生产/Browser/Provider/legacy修改。下一CI79需重跑真实四API，不将本地编译当原生通过。


## CI79：IME修复实际执行；API30 SPA合成触发条件失败

[CI79 42413903426](https://github.com/ForceMind/Xs/actions/runs/42413903426) 的head `32b43387ef7e2d886ccfe73b43b58d1842e43882` / tree `9a235dce29f26cafe164f02465d371fb44ca71f5`。实际624主执行中623通过，唯API30 SPA用例在选择文件前等待页面路由复合条件失败；0错误/跳过。16次Gov执行全过，Browser23/24；API26/27/36各156主用例及全部5driver通过，API30跨源码legacy通过，其后4driver未运行。实际16/16 attempted driver、36个外部native phase通过；详见[CI79完整报告](evidence/round-e-ci79-results.json)。

API26 Gov同一ZIP用例原始logcat明确出现`Dismiss actual IME over fixed SAF save form: Xs-selected-media.zip`，并实际PASS原ZIP每个选中文件的字节/hash断言。IME新增条件分支这次确实执行，不只是未触发时的用例通过。

API30 Browser已实际PAUSED/STOPPED、真实DocsUI显示正确fixture目录，失败发生在等待`spaVisitLog`/sameDocument/最终URL的复合表达式，尚未点击返回文件。原日志未采集各JS字段和nativeHistoryCallbacks的实际值，故**不能断言其中哪个子条件或visibility行为是根因**。失败时生成33656字节AX与36920字节PNG，但shell副本preserved=false，未真正导出；只有受限AX logcat备用输出可读，不声称查看了PNG。

下一修正限定合成触发契约：真实点击SPA arm并观察onclick/trusted生效，打开真实SAF并确认native上传仍pending后，由标准evaluateJavascript一次调用本地HTML定义的合成函数，执行真实history.pushState敏感路由→HOME。用例目标是实际pending选择器期间的瞬时SPA路由失效，不再依赖与该目标无关的visibilitychange必达时序。仍要求两个实际URL、原document marker、真实WebView native回调顺序、最终URL等于原URL、真实选择后的FileList为空和旧callback清空；不手调Client/Activity结果、伪造URI或写入通过标志。完整导航的visibility用例保留；未扩大超时/自动重跑/削弱业务断言。新增失败诊断仅含白名单合成方法的有限状态/路由类别，不采正文、私密输入或任意网页数据。

CI80前实际编译app测试APK及appLint通过：121 tasks/16秒；[本地构建摘要](evidence/round-e-pre80-build.json)。Gov/生产/Provider/legacy未变，未以编译替代下一轮真实624/20验收。


## CI80：SPA四版全部通过；API27合并表单按钮被IME压出可见区

[CI80 42415353729](https://github.com/ForceMind/Xs/actions/runs/42415353729) 的head `22894747b40d64fcc00cc8d252257ab13ad40778` / tree `854c8661f8227637a5df9f897c3228b78e066fc8`。实际624主执行中623通过/1失败/0错误跳过；Browser24/24和四版真实legacy升级均通过。API26/30/36各156主用例及5driver通过；API27 client104全过，harness52中1项Gov合并预览点击前失败，其后四driver未运行。实际16attempted driver、36个外部native phase全通过；[完整原始报告](evidence/round-e-ci80-results.json)包含38 APK角色副本/27个不同字节摘要、ZIP/公开证书/8份真实UTP设备记录和原始driver证据。

新的SPA刺激已在四版真实SAF pending状态执行一次真实pushState敏感→HOME，两个实际URL、原document marker、有序native回调及真实选文件后的旧结果拒绝断言均通过；这不是visibility事件必达验收。80四版Gov保存用例均未出现SAF IME关闭marker，故不能声称80触发了该条件分支；79的实际分支触发及同case ZIP PASS保留为独立历史证据。

API27实际导出AX（没有PNG，原生Activity保持FLAG_SECURE）显示IME窗口(0,391)-(320,640)，app可见内容高至391；合并页固定名称和A/B已出现，Check & preview Button bounds(24,515)-(296,391)、enabled/clickable但不可见。失败尚未进入合并事务，不能据此声称生产滚动损坏。

下一最小test helper仅在合并目标不可见且真实IME存在时，核对target owner、XsLibrariesActivity、固定标题/名称、恰好A/B两个已选来源，执行一次真实系统Back；必须确认IME消失、同表单/名称/两个来源选择保留，然后仍由原SHOW_ON_SCREEN→visible→native ACTION_CLICK进入预览。预览/取消/创建/撤销/版本/来源/default全部原断言保留，未增加超时或点击不可见控件。Browser/生产/Provider/legacy未变。

CI81前最终helper含固定合成分支marker的测试APK及harnessLint实际构建通过：88 tasks/15秒；[本地摘要](evidence/round-e-pre81-build.json)。下一轮仍须实际624/20结果，条件marker仅在真实执行时计覆盖。


## CI81首次API36：SDK下载错误，原生未运行

[CI81 42416903923](https://github.com/ForceMind/Xs/actions/runs/42416903923) 的head `e3990d5047c8e94f136c033fa7d92a6e5bfa882e` / tree `d0e68c50aa8cc15e304894b35e34887eeda53b44`。API36首次job `128537228135`在sdkmanager下载Android Emulator时报告`Error on ZipFile unknown archive`，模拟器没有启动：该job主XML0、legacy/全部driver和原生新分支均NOT RUN。仅产生3/5预编译APK provenance和1244字节小型artifact，不能当原生逻辑失败或通过。

[原始基础设施失败摘要](evidence/round-e-ci81-attempt1-api36-infrastructure.json)保留job、原日志/小ZIP摘要及明确未运行范围。首次日志/ZIP与后续重跑分目录保存。GitHub在其余job运行时拒绝单job rerun（workflow already running），当时拟等待整run结束后仅重跑该明确SDK下载失败的job；随后API26的重启等待也失败，见下。源代码不变，不重跑已通过job来凑结果。最终总计须按同一source每个API的实际最新执行及各自attempt/job核算，首次0次不得塞入624/20或抹除。


## CI81首次API26：主156通过，重启后ADB未恢复；定向重跑两个失败job

API26首次job `128537228149`实际156/156主用例、legacy/force-stop/debug3→4均通过，合计8个已完成外部native phase；guest reboot只有seed和原活进程停止。`adb reboot`未报错，但`adb wait-for-device`180秒超时，结束时emu kill也报告TCP5554连接拒绝。没有执行重启后的native verify、没有low-space，不能声称恢复数据丢失或通过；原日志不含模拟器消失的根因。

[首次重启等待失败原始摘要](evidence/round-e-ci81-attempt1-api26-reboot-failure.json)保留boot-before、PID、seed/stop和明确NOT RUN边界；与ZIP/原目录/全job日志分开保存，后续不覆盖。全部首次job终结后，API27/30各156主＋5driver通过，API36下载失败0主，API26如上。已只重跑同源的API26/API36两个failed job一次，API27/30/core/build不重复跑，源码和180秒门槛均不变。最后只按每API实际最终job记一份矩阵，保留第一次失败但不重复累加其156主/8phase。

[CI81首次完整汇总](evidence/round-e-ci81-attempt1-results.json)实际计468主PASS、14 attempted driver中13PASS/1重启等待FAIL、30个已完成外部native phase；6driver未运行。最终attempt不能把这些重复加总。API26/36重跑新job分别128540458038/128540458107，其他成功项由GitHub沿用check结果，不是重新执行。


## CI81第二次尝试：SDK恢复；旧版网格文件点击后未返回

[CI81有效执行完整汇总](evidence/round-e-ci81-results.json)以API26/36 attempt2与API27/30 attempt1各自整job为准，不挑选同API不同attempt的最佳case拼通过：有效624主执行、623通过/唯API26 metadata SAF选择未返回失败，0错误/跳过；Browser24/24、SPA4/4、真实legacy4/4通过；16 attempted driver和36个有效外部native phase通过，API26其后四driver未运行。历史attempt1的468主/30phase/两个失败未擦除、未重复加总。

API36 SDK经唯一一次定向重跑恢复并完整156主＋5driver通过；API26此次是新的真实UI失败，不是重启基础设施失败。真实PNG/AX已查看：正确fixture目录的网格文件xs-library-metadata.xsmeta，title bounds(66,302)-(142,324)可见；原日志有一次真实FINGER触摸，之后60秒选择器不返回，未出现选择状态。没有足够日志证明其具体原因是重绘或触摸race，不据此定因。新旧同名artifact均实际上传成功，无409，未为假设改上传流程。

API27本源真实出现新的合并IME marker，并通过同case完整preview/cancel/create/undo/source/default断言。有效四版均未触发SAF-save IME marker，历史79的真实执行仍独立保留。

下一test helper将备份文件选择规范为实际系统List view：确认固定fixture目录，仅精确匹配官方menu_list/menu_grid（8.0）、option_menu_list/option_menu_grid（8.1）、sub_menu_list/sub_menu_grid（新版）和实际标签，通过真实FINGER切换，并以相反Grid动作确认List模式，再走原精确文件名/稳定坐标/FINGER/真实SAF返回及全部内容断言。Browser已有同样模式helper也只补这三个精确官方ID；此前Browser全部通过，不声称存在同一实测失败。原IME guards、生产/Provider/legacy和超时不变，未盲重点击。

CI82前双测试APK和app/harness Lint实际构建通过：171 tasks/16秒；[本地摘要](evidence/round-e-pre82-build.json)。下一轮仍需实际624/20，不将编译或81其他版本通过当新源仪器结果。
