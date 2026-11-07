# Xs 后续离线闭环实施记录（2026-11-07）

接续现有 PR #5 的 `7777e1f9ee6d901a189eda73b40e056e1362adfd`，不是重建工程。main 核验为 `f88003c8ec1ee193b75de7267db6ae7654ee77c4`；Issue #3 的真实站点门禁保留。此前的 [A / early-B 记录](IMPLEMENTATION_EVIDENCE.zh-CN.md) 与失败历史仍有效，本页只记录后续改动。

## 源码与验收边界

| 工作包 | 实际代码 | 本轮证明与限制 |
| --- | --- | --- |
| XS-11 本人内容索引 | `OwnActionSession`、SQLite v4 `own_actions`/`own_action_events`、导入器、搜索与 History UI | 原生合成会话确认原创/回复/引用/转帖，失败/取消/错归属拒绝，记录关联帖子、真实或未知时间及撤销事件。旧数据保留并迁移 UNKNOWN，观察时间未知不伪造；无结构的 RT 文本不当作确认转帖。真实 X 行为适配器关闭。 |
| XS-12 视频基础 | `PostVideoSelection`、`Mp4Structure`、`VideoFileValidator`、媒体 DB v2、下载/预览/导出/分享 | 用户明确选择当前帖子 direct MP4，严格 HTTPS video.twimg.com 路径及逐次重定向；最多100MiB、10分钟、H.264及可选AAC。完整容器、sample范围与全sample读取；首尾图帧与AAC有限解码。拒绝 blob/播放列表/fragment/DRM/外部引用；不承诺所有格式及逐帧审计。合成1秒H.264/AAC另外有实际MediaPlayer播完测试。真实X/CDN仍待验收。 |
| XS-13 可撤销精简 | `xs-cleanup.js`、`NativePageCleanupController`、3项独立默认关闭偏好 | 合成WebView广告/会员营销/推荐限定结构；导航/后台/遮挡/销毁恢复；保留原style与站点变更。保护登录/验证/私信/编辑器/已有付费页面，隐藏卡不产生停留。Browser未接真实过滤，LIVE_SITE_ENABLED=false。 |
| XS-03 / L04 显式合并 | `LibraryMergePlan`、`XsLibraryConsolidation`、`XsLibrariesActivity` | 用户选择2–4库，预览确认后仅创建新独立空间，源与默认库保留；总8MiB/20000记录。同ID取较长正文、首个已知作者/发布时间、打开次数相加，所有history独立保留；分类ID按来源重映射，标签去重，多备注带来源顺序；旧行为同identity不同source、超长备注明确拒绝。媒体文件不复制；结果对话框可明确撤销刚创建且未修改/无媒体的新空间，后续变更则拒绝，源和默认库保留。提交预览时快照，后续普通编辑需重新预览；源清除使预览失效。 |
| XS-15 元数据备份 | `LibraryBackupCodec`、`XsLibraryBackup`、`XsBackupActivity` | 版本化JSONL，16MiB/20000行记录/64KiB每行，严格UTF-8/类型/列/表/引用/校验和；schema3可投影到4。SAF用户选择文件，预览后事务恢复新UUID空间，FTS/FK验证，失败回滚；不覆盖、不自动合并、不改默认。明文、无真实性保证，不包含媒体/任务/会话/凭证/密钥；不是完整加密设备备份。 |
| 并发与容量 | `SerialWriteCoordinator`、`XsLibraryWrites` | 32个队列是同时保留容量，idle可回收；ticket绑定实例与代次，旧票不能复活。忙队列不回收，满载明确拒绝；新恢复库先预留容量、失败释放。清除与获取队列使用同一映射锁；drain回调异常仍完成全部任务清理；IO不占提交锁。打开计数上限intMAX避免备份恢复后的溢出。 |

导入或备份中的actor/source是记录来源，不是当前X账号认证。分享只输出用户选定的完整原文件与只读content URI，不做联系人选择或自动发送。接收应用选择、会话及最终发送均由用户完成；chooser返回不是送达凭据。用户资源和WebView缓存分离，视频沿用24小时/200MiB分享副本租约。

## 验证

本轮最终源提交、CI merge提交、job-local APK SHA/公开签名证书与设备/WebView版本在CI完成后追加于此，不借用上一轮82例的结果。合成素材来自色块与440Hz人工音调，见 [素材生成/散列](../app-android/src/androidTest/assets/VIDEO_FIXTURES.md)。没有采集用户账号或真实媒体。

| 检查 | 当前结果 |
| --- | --- |
| 独立工程/双语文档检查 | PASS |
| JVM、真实SQLite v4迁移/查询、observer/cleanup DOM | PASS；933项JVM断言，并有SQLite/DOM回归 |
| AAR、harness/client APK、两套AndroidTest APK、Lint | PASS，162 tasks；Lint 0错误/11既有警告，见[本地构建记录](evidence/round-c-local.json) |
| API30 / API36 仪器测试 | PENDING，新提交后运行GitHub KVM模拟器；本地主机无KVM，本轮不重复软件模拟器尝试 |
| 真机、真实X登录/DOM/CDN/目标聊天App | NOT RUN；未启用真实自动采集、本人动作适配或过滤，无正式发布 |

## 真机操作清单

1. 从指定CI下载内部PoC并核对APK SHA，记录机型/Android/System WebView版本；按Issue #3直接正常登录X，验证2FA、跳转、发帖/回复/转帖、媒体、旋转与会话恢复，不导入Cookie/Token。
2. 对有权保存的公开图片/direct MP4明确选择下载，核对实际音画、时长、大小、来源、本地离线预览/导出、失败重试/取消/删除、清缓存仍保留。没有合规direct资源时应明确失败，不将blob/列表改名。
3. 微信/QQ/飞书/Telegram分别验证单图、多图、单视频、取消、延迟读取和源删除后读取；记录应用版本/MIME/大小，用户自己选择会话并发送，核对接收文件。混合图/视频组当前不支持。
4. 离线导出含Unicode备注/分类/历史/本人行为的明文元数据，经系统文件选择器预览再恢复；新空间能全文查找且默认/源库不变。损坏文件、取消、并发清除均不得半恢复。
5. 选择两个测试空间预览合并，核对较长正文、两次打开事件、独立同名分类、来源备注和行为轨迹；取消保留来源；结果对话框撤销未修改的新空间，后续编辑/新增媒体后必须拒绝撤销。源清除后旧预览必须失败；后续普通编辑需重新预览。
6. 完成W0与真实站点观察安全审查后，才在另行待验收构建接入采集/动作/过滤适配。再实际完成快速打开返回与3.5秒/1秒停留、离线“>3秒且从未打开”、本人成功/失败/撤销、精简恢复及付费权益保护。当前PoC不满足这些真实站点验收，不能用合成报告替代。

仍需设备/站点适配、可靠系统后台任务、广格式视频、长期滚动/电量/容量、完整资源备份/加密恢复、稳定签名与发布门禁。上述基础闭环不代表整个C/D阶段完成。

## 首轮双版本执行通过与后续兼容修正

[CI 42356157321](https://github.com/ForceMind/Xs/actions/runs/42356157321) 的 core、Android构建、API30、API36四任务均成功。源码head `70fa6cea1a3d93ee86596362623109eab260f523`，实际PR测试merge `a30a047c25eaedb61d4d5abb7aeb8feff3fe4bff`、tree `584635bf7a2538bf2282f6c17dcc61d95169951d`。两套模拟器各36项资料库与37项客户端用例，合计146次，0失败/错误/跳过；933项JVM及SQLite/DOM也通过。完整case、OS/WebView、artifact ZIP校验与job-local四APK摘要/公开证书见 [首轮实际执行摘要](evidence/round-c-ci63.json)。

后续SDK审查发现 `MediaExtractor.getSampleSize` 仅API28起可用，而工程minSdk26。本轮追加兼容读取修正，并将 `:xs-runtime:lintDebug` 纳入CI；先前仅app Lint未覆盖运行库NewApi检查。该追加源码需要新的CI，不将首轮通过冒充追加修正已验证。API26/27真设备与模拟器仍NOT RUN，不宣称全版本兼容。

兼容修正本地全量5项assemble及双模块Lint通过（165 tasks，两个模块各0 Error/0 NewApi/11 Warning），见 [兼容修正构建记录](evidence/round-c-compat-local.json)。新CI尚待执行；原有933 JVM及146次设备回归不会被扩展为API26实测。
