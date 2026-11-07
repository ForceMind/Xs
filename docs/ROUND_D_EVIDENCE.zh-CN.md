# Xs 可靠性与完整资料治理实施记录（2026-11-07）

从既有 `a323b7cca2c760defd4b9b58a58e8f76431cdb96` / PR #5 接续，没有重建项目。接手核验 main 为 `f88003c8ec1ee193b75de7267db6ae7654ee77c4`，PR #5 是唯一开放实现PR，Issue #3/#4仍开放；此前CI65成功。此前 [C轮源码和证据](ROUND_C_EVIDENCE.zh-CN.md) 保留。本页区分源码、模拟测试、桌面容量、签名夹具和真实设备，不能相互冒充。

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

## 本地验证与发现修正

- 核心：1141项JVM断言通过（包含204 capture、220 writer、31 snapshot、112加密与32合并检查），SQLite v5保留迁移/两类FTS与scope、DOM回归和20k observer压力通过。增加完整正文证据后压力预算从96 article styles细化为96 geometry + 4个viewport正文style；没有放开候选遍历上限。
- 完整AAR、harness/client及两套AndroidTest APK、双模块Lint本地通过。见[本地完整检查/包摘要](evidence/round-d-local.json)，客户端Lint 0错误/11警告、运行库0错误/16警告（包含有意后台commit、已受版本控制的内联常量提示）；Android实际仪器测试仍 PENDING，不以编译通过代替执行。
- 删除审查补了commit(false)仍改SharedPreferences内存的回滚、旧callback取票拒绝、事务锁后deleted检查、undo旧mandatory clear交错回归和跨controller事件owner校验。
- 最低版本审查移除图片/视频选择脚本的optional chaining，保持旧WebView语法兼容；API26新增CI，结果待实际运行，不提前声明兼容。

## 容量与模拟压力

[桌面真实SQLite容量原始数据](evidence/round-d-capacity.json)：10k和100k从v1逐级到v5，保留原post rowid/正文/分类/事件，FK/integrity与current/version FTS映射通过；100k生成115294 history和101090 snapshot versions，数据库约136MB。修复了旧历史回填仅account索引导致反复扫描，以及相关FTS反复执行；结果记录原/新EXPLAIN、8类查询中位耗时，没有以机器耗时阈值伪造手机性能。

20k虚拟帖子、1000次路由/遮挡/冻结循环已实际执行；候选geometry≤96，style=100（含4个可见正文）、active runs最多8，payload约6770字节，geometry周期读取正文0次。24小时**模拟时钟**压力实际执行，不是24小时真实等待：24卡、200ms采样，写入含24个cap共14400次；多日真机soak、低内存/电量仍NOT RUN。

## 签名验证

[合成签名实际证据](evidence/round-d-signing.json)：使用临时自生成测试key实际构建versionCode 3和4，两个APK均非debuggable且证书pin一致；真实apksigner/aapt校验通过，错误pin拒绝；缺输入的assembleRelease --dry-run明确失败。临时私钥已销毁且未上传。这证明签名工程路径，不证明在用户设备上覆盖旧PoC，也不代表正式签名已经配置。该证据记录的是提交前工作树，source clean=false；不能把其中旧HEAD冒充新源码CI身份。

## 本轮执行与现场缺口

API26/30/36的真实WebView、SQLite、Job/Keystore、完整资源、SAF和新资料治理UI夹具即将由本轮CI执行；预计每API52项harness+62项client，但最终仅按XML的实际case/失败/跳过数记账。各job保留自己的APK/public cert与source/tree，不借用另一构建job的包。

真实X登录/2FA/会话、站点DOM/短编辑/停留/本人动作/精简、真实CDN、微信/QQ/飞书/Telegram会话接收、多日真机/存储满/强制停止/重启仍需物理设备。自动live capture/action/cleanup仍关闭。候选签名需要权利方配置固定私钥和环境保护；自定义许可还缺最终条款及权利范围确认，根GPL和历史授权不动。以上客观缺口不能以合成PASS替代，也不发布未经验收正式版本。

手机步骤：使用空白/隔离测试实例，记录APK/public signer/系统/WebView；正常登录与切号；选择有权保存的图片/direct MP4并测试后台/切网/重启/取消；SAF导出加密完整备份再恢复到新库，核对正文版本与实际文件；分别手选聊天App会话确认接收；验证整库删不影响其他scope/活动分享租约。调试证书可能逐job变化，不为安装新包直接卸载已有资料的Xs；先完成迁出/固定签名流程。
