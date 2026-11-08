# XS-16｜临时库空间失败与恢复增量

本轮接续 PR #5 的全部父提交，没有从旧 main 重建。三项交接 P2 与 F1–F5 的 CI87 验收作为历史基线保留；本轮新增 SQLite FULL 拒收诊断与实际原生回归，不将旧 green 当成新源码验证。

本记录的CI91结果属于固定源码1672756。后续真实文件系统低空间入口及DEVICE_SPACE分类已通过[CI93独立验收](XS16_FILESYSTEM_PRESSURE_EVIDENCE.zh-CN.md)；下列CI91保留当时结果，不由后轮PASS改写。

## 行为与边界

- 缓冲写入遇到 `SQLiteFullException` 时返回拒收。写入事务结束后只记录固定 `SQLITE_FULL` 和丢弃计数，不保存异常消息、候选正文或自动重试材料。
- 若计数本身也无法持久化，使用饱和上限为 2,147,483,647 的进程内计数与固定原因；这部分在进程退出后可能丢失，不能承诺完整持久故障账。后续成功捕获清除暂停提示，显式全临时 reset 同时清除计数；仍保持 OFF。
- FULL 可能已经自动回滚 SQLite 事务。仍执行 Android `endTransaction` 清理 session，但已有原始 FULL 时不让二次回滚异常覆盖它；其它结束事务异常继续失败。host 引擎实验仅是独立诊断，不冒充 Android 执行。
- 不撤销共享 writer 或其它媒体 ticket；不修改 Archive schema6、晋升账本、媒体文件或备份格式。独立默认 OFF、48h、应用100MiB和原先可调范围均保留；live 开关保持关闭。

## 原生测试范围

`TimelineStoragePressureTest` 使用独立 `xs-timeline-pressure-*.db`，保留生产 WAL 配置，在 shared writer 的显式事务内把 `max_page_count` 限到当前页数。它触发 SQLite 页分配 FULL，不会填满设备文件系统。

1. 新大正文必须被拒收；临时 appearance、FTS 和 seen 收据回滚；原临时记录、长期收藏、生成文件字节、无新增 OPEN/FEED、其它 writer ticket 均保留。提高 SQLite 页上限后重新观察可成功，诊断不重复计数，reopen 保留持久计数。
2. 用单独 trigger 让拒收计数写入也失败，验证进程内下界与固定原因；移除故障后可成功，显式 reset 清零且保留长期资料和生成文件。

生成文件 canary 不是媒体数据库迁移证明；完整媒体/进程/升级/重启/低空间 driver 另记。**CI91当时未单独验收实际文件系统不足1MiB的buffer admission；后续已由CI93四API实测，见上链。** T14真实X、手机、长滚动/耗电、聊天应用与生产签名仍独立 NOT RUN。

## 新源码执行结果

源码 `1672756ff2fb67c30c92df9bc7a5885f19b84c46`；实际 merge `eea88356636b6c7bd389e9f1594f001b3c73cee3`，tree `52532383a3e86118f5516e18875669beeb0891a8`。

[CI91](https://github.com/ForceMind/Xs/actions/runs/42516366007)的主XML **800/800，0失败/错误/跳过**；新增压力两项在四API共 **8/8**。1406 JVM、25临时SQLite、32项driver host与6项allowlist、10k/100k容量、Android构建和双Lint通过。

| API / ABI | 主XML | 独立driver | 外部native phase |
| --- | --- | --- | --- |
| 26 / x86_64 | 200 PASS | 5/5 PASS | 11/11 PASS |
| 27 / x86 | 200 PASS | 3 PASS，1 FAIL，1 NOT RUN | 8/8已执行PASS，另3阶段未执行 |
| 30 / x86_64 | 200 PASS | 5/5 PASS | 11/11 PASS |
| 36 / x86_64 | 200 PASS | 5/5 PASS | 11/11 PASS |

**本次workflow未全绿。** API27 guest reboot后ADB等待180秒超时，device absent且控制端口5554拒绝连接；恢复未验证，根因UNKNOWN，后续低空间driver未执行。19个已尝试driver中18 PASS/1 FAIL，41个实际native命令阶段均PASS；不将它们并入800主XML或补齐未执行阶段。新旧源码软件对照仍未有效执行，不宣称baseline RED。

[完整执行与摘要台账](evidence/xs16-ci91-results.json)保存每份XML摘要/身份集合hash、新压力项结果、原始driver状态、设备/WebView、8份实际下载ZIP摘要及20份native APK副本的独立验签/包名/版本。另单列build job浏览器APK核验；不同job debug签名不作为正式升级承诺。四API实际旧schema2→6升级均通过，说明新增seed/foreground栅栏在本轮实际执行；不据此推断CI89 PID消失的根因。

## 实际失败与未执行记录

| 运行 | 实际结果 | 处理 |
| --- | --- | --- |
| 首次软件 API30 夹具 | 旧/新冻结 APK 各2项，共4个断言失败；页面上限没有限制实际 writer，写入仍成功 | 不是有效产品 RED。修正为 writer 显式事务内设置/读取，原拒收/回滚/重试断言全部保留。见[原始记录](evidence/xs16-pressure-first-probe.json)。 |
| 修正夹具的软件 lane | 一次 power 命令超时；另一次 system_server 退出，安装提示 package 服务缺失；corrected 原生阶段0 | 独立 NOT RUN，不以 CI 成功覆盖。系统终止的根因未知。见[环境记录](evidence/xs16-pressure-software-environment.json)。 |
| CI88 | core、Android build/双Lint通过；四API在开始前取消 | 由夹具修复提交取代，不算原生PASS。 |
| CI89/API36 | 实际旧schema2 seed通过，但介绍页启动后PID为空，旧包driver FAIL；主XML0 | [原始driver/包/设备/ZIP台账](evidence/xs16-ci89-api36-blocked.json)。没有日志证明PID消失根因。其它三API取消。 |
| CI90 | 代码补修前仍排队，后取消，主XML0 | 不计成功或新原生执行。 |

旧包驱动现在先显式停止 seed instrumentation 的进程并证明缺席，再启动独立介绍页并要求不同于 seed 的真实 PID，随后仍要求原 force-stop 后缺席、正常安装升级、同 UID/路径/数据/FTS 和原生清理。失败保持 FAIL，并保存有界只读 logcat/activity 诊断。16项该驱动host测试核验栅栏、缺失/复用PID和失败保留，不以模型代替实际旧APK升级。

## 引擎诊断来源

[host SQLite3.53.1实验](evidence/xs16-pressure-host-engine.json)实际复现 reader 页上限不限制 writer，以及 writer FULL/code13 后已无活动事务。Android30官方 [SQLiteSession源码](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-main-release/android/database/sqlite/SQLiteSession.java)的 `endTransactionUnchecked` 仍执行回滚并释放连接；代码分析支持保留原始 FULL 的修复，但 Android 行为以本轮实际原生结果为准。
