# XS-16 本轮源码与执行证据

本轮接续 PR #5 `78a29cb05f908e5f44b6111183a202655da009a9` / tree `0aa07c42ac901a877a4112b92255f4e2697b2370`，完整保留 30 个未合并提交。main 重新核对为 `f88003c8ec1ee193b75de7267db6ae7654ee77c4`。本页不以旧 CI83 验证 XS-16，不发布正式版，也不启用 live 适配。

## 已复现并修复的审阅项

| 项目 | 失败实证 | 修复与回归 |
| --- | --- | --- |
| 规范URL正文观察 r4500601373 | 旧DOM脚本在 `/i/web/status/126` 返回0卡，新增断言0≠1失败 | 严格支持canonical route/链接，敏感路径限制保留；DOM与延迟正文原生WebView回归 |
| 来源误分类 r4504042361 | 提取旧表达式实际运行，home查询含`/search`误分search、explore误分ForYou，断言失败 | 解析路径；home仅采信原生验证context，explore/unknown不伪造FEED；JVM/原生WebView回归 |
| 备份选择器重建 r4501080305 | 当前基线仅Activity实例requestTicket，旧实例重建后请求不在新实例；outdated不作为已修证据 | 进程内无Activity引用的retained request、唯一requestCode、writer复验/一次消费；口令仅char[]且不写Bundle；plain/encrypted SAF重建与撤销/失效原生回归 |

旧P1仍检查现有独立consent revoke epoch和incremental observer。新增路径保留这些规则。

## 新源码范围

- `TimelineBufferPolicy/Schema` 与 `XsTimelineBuffer`：独立schema1/FTS、默认OFF/48h/全应用100MiB、1–168h/25–500MiB边界；同批去重/首序与TTL、跨批引用、查询到期、逻辑时钟高水位/回拨暂停、实际DB/WAL/SHM/整理开销、最旧淘汰/容量暂停、独立clear generation与durable pending-clear、持久tombstone。
- `TimelineCaptureSession`/packaged observer/controller：正实际视口交集的独立admission、原生刷新/换流/本地会话批次、根替换只标suspected；已有60%/连续1.2/3/5秒FEED门槛不变；旧回调/关闭后重开不能复活。只在离线夹具挂载。
- `XsRecentTimelineActivity`：原生正文/作者/ID/CJK离线搜索、批次筛选、50条分页、可见部分正文预览、覆盖/容量/丢弃原因、独立设置/清缓冲、单条/批量长期保存/收藏。预览不制造OPEN，过期前台查询与入口复验。
- `XsTimelinePromotion`/Archive v6：显式v5→v6 additive ledger/source保留性迁移；Archive先提交、buffer后ack，原始捕获时间/来源/质量与明确保存时间分开；不制造OPEN/FEED或伪造COMPLETE；backup/merge/旧schema3/4/5文件兼容。临时正文、凭证、媒体、网页状态不进入备份。
- 前台/启动与JobScheduler可延迟维护、整库删除覆盖两层存储；清缓冲不废媒体ticket、不删长期资料/下载/分享副本。

## 本地实际执行

环境：云端Linux，JDK17.0.21、Gradle8.9、Android SDK35，Node24；无`/dev/kvm`，本地未运行模拟器。源码与运行结果分开。

| 命令/范围 | 当前结果 |
| --- | --- |
| `bash scripts/check-project.sh` | PASS，6项host检查 |
| `bash scripts/test-core.sh` | df91eab冻结源码本地及CI85均PASS：1406 JVM、25临时SQLite、29driver host、6allowlist、DOM/20k虚拟卡与1000生命周期、10k/100k容量 |
| 临时SQLite实际FTS/物理回收 | host最终25断言PASS；实际文件5402624→151552字节（前轮5386240→135168）（非Android/安全擦除证明） |
| 原生AAR/app/harness/androidTest构建与双Lint | df91eab最终冻结源码本地完整构建/测试APK/双Lint PASS；同源CI85 core/build也PASS |
| API26/27/30/36主仪器与20独立driver | CI85 API36实际191项、34失败；其余当时运行/排队，新提交后状态独立记账 |

## 保留的CI84失败

[CI84](https://github.com/ForceMind/Xs/actions/runs/42455063484) attempt1未通过：API36真实SAF多选定位点击侧栏断言失败；API27主156项通过后guest reboot等待ADB180秒超时，模拟器端口消失，后续low-space未执行。修复定位为有界重取精确标题对应可点击项，保留真实SAF选择与字节集合校验。重启驱动增加只读transport/console与部分超时输出诊断，不自动重启/重跑/延长等待以掩盖失败；新增6项host回归通过。下一次CI仍须实际通过。

## T01–T14执行账本

| 验收 | 新增源码/测试 | 本轮状态 |
| --- | --- | --- |
| T01 | policy39、SQLite默认/边界、RecentTimelineUiTest独立设置 | JVM PASS；原生待CI |
| T02/T03 | independent admission40、DOM正交集、NativeCaptureIntegrationTest快卡/刷新前批、UI搜索 | JVM/DOM PASS；原生待CI |
| T04/T05 | 同批去重/首序、版本复用、独立TTL、时钟/重启/延长不复活 | policy/SQLite host PASS；原生待CI |
| T06/T07 | 实际bytes/FTS回收、全局配额/降限/锁与低空间 | host回收PASS；原生待CI |
| T08 | promotion数据12、v6迁移/backup/merge、native archive-before-ack失败/重试 | JVM/SQL PASS；原生待CI；helper reopen不冒充OS杀进程 |
| T09/T10 | buffer-only generation、pending clear/tombstone、media ticket/文件与其他scope保留 | 源码/测试已添加；原生待CI |
| T11 | 临时新库/损坏保留Archive、旧schema2→6、actual force-stop/guest reboot | 待新CI外部driver；旧结果不覆盖 |
| T12 | 原生分页/CJK/批次/partial，320dp/1.8字号中英深浅实际生产layout | 源码/测试已添加；原生待CI |
| T13 | 有界字段/文章/队列/会话、20k虚拟DOM/洪泛/隐藏遮挡 | JVM/DOM PASS；原生待CI；不等于真机耗电 |
| T14 | 真实X/W0、自动刷新/虚拟列表/账号切换/真机电量 | NOT RUN；live保持关闭 |

未验证：真实X登录/DOM/CDN、微信/QQ/飞书/Telegram接收、物理设备/电量/物理断电/生产签名。SQLite/FTS/WAL未加密；TTL查询不可见与实际磁盘回收分开，锁屏/force-stop后不承诺准时物理擦除，也不承诺恢复X推荐状态或完整捕获未呈现项目。

## 最终源码审计修复

新双线程latch回归复现session→store与store→validator锁反转：临时恢复synchronized校验稳定5秒超时失败，当前volatile不可变revocation stamp无锁校验通过40条timeline断言。实际write仍检查同一不可复活撤销身份。另修复首次LINK_ONLY保存后的显式再保存正文补全：保留首保存时刻、单条LOCAL_SAVE/ledger，按实际正文捕获时间补snapshot，不生成OPEN/FEED；新增第9项native promotion回归。

## CI85 实际失败与下一源码修复

提交`df91eab7b32eb58333c1bcaa3f729e74550325ab`，tested merge `803d414f3b6abe789b7ce8f4205acf7bd4b3f995`，tree `b07678d5ae7a19074f5050343302d74e085a9198`。core/build实际PASS；[API36](https://github.com/ForceMind/Xs/actions/runs/42460035826/job/128668155027)主XML实际191（127客户端+64宿主），34失败、0error/skip。固定旧APK schema2→6 driver PASS，后4driver NOT RUN；不可把157个通过项或旧CI83冒充全套通过。

根因堆栈为`SQLiteDatabase.execSQL → XsTimelineBuffer.onConfigure:65`：`PRAGMA busy_timeout=1000`返回一行，Android要求query/rawQuery。后续源码改为rawQuery并消费结果；SQLite实查busy_timeout/wal_autocheckpoint/journal_size_limit均返回行，auto_vacuum=FULL setter无结果。构建/host SQLite通过不能验证Android执行API的这个边界。实际下载ZIP SHA256 `2f5bcc81bc6ef923778286536aee6eb7d54715c0603099ee383b98d22a71576a`，3份APK实际aapt/apksigner核验及全191case/设备/WebView/driver/provenance见[原始解析账本](evidence/xs16-ci85-api36-failure.json)。

本轮API36中规范URL迟到正文、真实来源路由与native context、3项明文/加密SAF重建/失效及真实双PNG选择均实际PASS，分别只证明这些子项。失败状态完整保留。其余旧源码任务若在新源码推送后被workflow并发规则取消，记CANCELED/NOT RUN，不写PASS，也不手动重跑掩盖旧失败。

后续源码同时关闭复核发现的边界：

- 已打开预览本身按最近TTL重验/到期关闭并清空视图；外部清理、暂停/销毁与旧弹窗回调有独立epoch，周期刷新保留仍有效多选。新增真实Activity暂停/恢复与预览到期/外部清理回归。
- 设备误跳未来再调回时，旧“重试”保留未来高水位会永久拒收新卡。改为**明确确认后重置所有空间的临时缓冲和时钟**：同事务不可逆删除全部临时数据/反重放凭据后才允许降低时间域，失效全部buffer票据且收录保持OFF；小时/预算、Archive、媒体和其他writer票据保留，需另行开启。新增2项原生回归。正常TTL/关闭/修改设置仍不静默清空。
- 每次原生JS读取前绑定buffer ticket，回调不重新签发票据。跨清理/重置旧读取仅拒buffer、不影响OPEN/FEED，下一新读取使用新batch；新增实际WebView跨清理回归。
- 外部ProcessRecoveryProbe故意提交Archive后不ack，再让实际force-stop/guest/debug升级驱动跨进程重试；只在新CI执行后可记实际commit→ack中断证据。增加取消收藏后同出现记录再次收藏的回归（原生产逻辑已支持）。

以上后续源码和新测试不得用CI85覆盖；等待新提交的四API/20driver执行。真实X/T14/live/正式发布边界不变。
