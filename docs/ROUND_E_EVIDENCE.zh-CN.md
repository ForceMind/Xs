# Xs 网页媒体选择与旧版 APK 升级实施记录

日期：2026-11-07。继续既有 PR #5，没有重建工程。

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

以上是源码场景定义，当前仪器执行仍待新 CI，不提前记为通过。

## 跨源码 APK 与真实旧库

固定旧源 main `f88003c8ec1ee193b75de7267db6ae7654ee77c4`：包名 `dev.forcemind.xs`、versionCode1、数据库 `xs-local-history.db`/schema2。当前包正常 code1→3 覆盖安装，由当前 helper 执行2→3→4→5；不得在验证前卸载、清数据或使用降级参数。

LegacyUpgradeProbe 仅以 Android/Java/JUnit 类型和目标 classloader 反射访问旧 API，避免把当前 Xs 类打入旧 target 或用新 helper 假装旧版本。旧 helper 真实创建打开/LOCAL_SAVE、正文作者与时间、分类、FTS、默认 UUID 和私有文件 canary；记录旧 schema、原 rowid/dataDir/UID。更新后核对新进程、同 UID/路径/UUID、保留的数据与新旧全文搜索，并如实标示旧快照质量。

旧 main 没有独立 favorites 表或媒体库，因此这条 lane 的对应升级项为 N/A；私有 PNG canary 不是旧版下载资源。该测试不声称生产密钥、跨签名或用户手机升级已通过。

驱动限制在明确的 GitHub CI、emulator serial 与 qemu 环境；旧 source/tree、两版 APK/测试 APK 的包名、版本、实际 hash 和公开证书必须核对。它在主仪器之前运行，只在完整 verify 后清理本次合成数据；原四个系统 driver 继续保留。外部 seed/verify 调用独立计数，不加入主 XML。

## 已执行与待执行

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
