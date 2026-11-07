# Xs 本轮实施与验收记录

本轮从 `main@f88003c8ec1ee193b75de7267db6ae7654ee77c4` 接续。核对时无未合并 PR；基线 CI [42260071115](https://github.com/ForceMind/Xs/actions/runs/42260071115) 成功。该基线没有自动网页历史和媒体运行时。本文件跟踪本轮实际源码，不将合成测试当作真实 X 验收。

## 实际交付范围

| 工作包 | 本轮实现 | 尚未完成 |
| --- | --- | --- |
| XS-01/02 | 复核固定 Studio 来源；单 WebView 在旋转时保留实例/JS 状态；短期窗口运输仅路由链接，明确用户确认，无站点原生接口 | 多 Profile 能力、真实认证窗口/登录/Passkey/2FA 仍未验证 |
| XS-03/06 | Schema v3，v1/v2 保留性迁移；旧 scope 原样映射且可选；默认应用库；FTS、作者/来源/标签/备注检索 | 显式旧库合并/回滚 UI、完整版本化快照、备份恢复未实现 |
| XS-04/05 | 有界原生写队列、代次、擦除屏障、严格 JSON；本地打包观察器，原生主动取回；确认进入详情即写链接，后补可见正文；导航 ID 去重 | BrowserActivity 未接入真实 X 自动观察，站点导航回调/SPA 点击归因仍需实测后接入 |
| XS-07 | 原生「收藏到 Xs」，独立关系/备注/标签/分类；取消不删历史；清历史保留收藏和资源 | 浏览器当前收藏仅链接；本地已有快照可保留正文；批量收藏整理尚未完备 |
| XS-08/09/10 | 用户主动选当前帖图片；窄范围 HTTPS pbs 媒体校验、最多 16 任务、20MiB/图、3 次重定向；临时文件、实际类型/长度/解码校验后完成；资源页/分类/预览/导出/多图分享 | 实际 X/CDN 下载未验收；可靠系统后台调度、自动续传未实现；中断需到来源重选，从头下载 |
| XS-14 基础 | 默认独立关闭，1.2/3/5 秒偏好；合成时间线连续可见区间、长卡可读区域、遮挡/后台边界、曝光去重；单段时长及「从未打开」本地筛选 | 真实 X、长滚动/CPU/电量、不同 WebView/布局仍未验收 |
| XS-11/12/13/15 | 保留既有归属确认的单分段档案导入，不新造社交动作 | 实时本人行为及撤销、完整视频、可撤销精简、备份恢复、稳定签名与正式交付仍待开发/验证 |

时长是有界采样得到的可见连续区间下界，不是眼球跟踪或阅读完成证明；采样空档不计，单段上限 10 分钟并标记下界。旧 FEED_VISIBLE 没有实测时长时保持未知。阈值只影响后续曝光；不同连续区间不相加凑阈值。

## 代码和测试入口

- `CaptureSession` / `NativePageCaptureController` / `xs-observer.js`：native 确认打开、有限可见快照及连续曝光；没有 JavaScriptInterface、任意 SQL/文件/Intent 能力。
- `SerialWriteCoordinator` / `XsLibraryWrites`：按库有界队列，入队失效与擦除序列，IO 不占用提交锁；旧回调不能跨清理屏障复活资料。
- `ArchiveSchema` / `XsArchiveDatabase` / `ArchiveSearch`：兼容 scope 物理键，不重写旧命名空间；独立收藏和 measured history。
- `XsHistoryActivity`：离线正文、打开时间、停留/未打开筛选、旧空间、分类、收藏备注标签、清历史。
- `MediaDownloadPolicy` / `XsMediaRepository` / `XsAssetsActivity`：图片任务与独立的用户保存文件；视频、blob、manifest 不伪装成成品。
- `PostImageSelection`：仅 native 点击触发，匹配当前详情的时间戳/图片锚点；引用和其他帖子图片不被选入。布局无法识别时返回空列表。
- `XsAssetProvider` / `XsShareCoordinator`：只对已选完成文件创建临时只读副本和 content URI；ClipData、真实 MIME、单/多图；不读聊天凭证、不自动发送。

## 验证状态

本地工具链：JDK 17.0.21、Gradle 8.9、Android SDK 35 / Build Tools 35.0.0。`3068676` 的全部 AAR/APK/AndroidTest 构建和 Lint 通过（162 tasks，33秒）；443 项 JVM 断言及真实 SQLite、DOM 回归通过。源 SHA 与各包 SHA256 见 [构建记录](evidence/build-3068676.json)。云主机不提供 KVM，API30两次成功启动但应用测试均未开始，见 [环境诊断](evidence/api30-environment.json)。CI仍需实际报告，不能用构建通过代替仪器测试。

| 验证 | 本地状态 | 证据/范围 |
| --- | --- | --- |
| `bash scripts/check-project.sh` | PASS | 双语/独立源码及历史 allowlist 回归；allowlist 不代表实时 X 接入 |
| `bash scripts/test-core.sh` | PASS，443 项 JVM 断言及 SQLite/DOM 回归 | JVM + 严格 JSON/连续区间/队列/媒体策略；真实 SQLite v1/v2 迁移、FTS、scope、事件去重/时长/收藏查询 |
| AAR / harness APK / browser APK / Lint | PASS，最终代码复验通过 | `assembleDebug` / `lintDebug`，不代表设备登录或媒体兼容性 |
| AndroidTest APK 编译 | PASS，新增回归编译通过 | 实际 WebView/SQLite/Provider/UI 合成测试，非伪造 PASS |
| API 30 / 36 仪器测试 | 本地 BLOCKED（0 应用用例）；CI 排队 | `NativeCaptureIntegrationTest`、`PostImageSelectionTest`、`MediaSharingTest`、`ArchiveIntegrationTest` 和资料库 UI 回归 |
| 真实 X / 真机 / 微信、QQ、飞书、Telegram | NOT RUN | 没有访问用户手机、真实账号或聊天会话 |

测试映射：H01–H05、D01–D08、L01–L03、F01–F04、M01/M02 的策略部分、X01/X02/X04 的本地契约、Q01–Q03、S01 有离线源码和回归。上述编号包含多项实机条件，**只对具体测试的合成子场景声明结果，不将整行产品验收标为通过**。M03–M05 的实际网络中断/磁盘满/下载、X03/X05 的真实接收应用仍未验收。

## 分享与删除边界

分享副本在 `files/xs-share-leases`，24 小时到期拒绝新读取、后续清理；总配额 200MiB。源图片删除或清浏览缓存不立即撤销正在分享的副本。临时授权由 Android 管理；实际接收方是否已发送/送达从不记录。

用户保存的图片在 `files/xs-saved-media`，与 WebView cache 分开。清浏览历史不删收藏/媒体；取消收藏不删历史/媒体。媒体目录与主资料库不宣称已加密。图片任务不是完整视频能力；没有 HLS/DASH、DRM 绕过或会话凭证导出。

## 真机验证步骤

1. 安装 CI 的内部 PoC，记录 APK/SHA、Android/机型、System WebView 包和版本；不开自动采集。直接登录 X，分别测试 2FA、退出/重启/过期、发帖/回复/转帖/媒体/上传、外链、返回、旋转和断网恢复。Google/Apple/Passkey 分别记录结果，不迁移 Cookie。
2. 用公开帖子测试原生「收藏到 Xs」及分类/备注，切换或退出 X 后断网查看；验证资料仍在。当前真网页收藏是链接，不能预期自动正文历史。
3. 对有权保存的公开图片明确点击下载、选择单图/图组，核对本地预览、来源、大小、取消/失败/重试、导出；若当前 DOM 无法识别，记录失败布局，不能扩大抓取范围。
4. 分别在微信/QQ/飞书/Telegram 测单图、多图、取消、延迟读取和源删除后读取；记录接收 App 版本/MIME/大小。用户自己选择会话并发送，核对收件方文件；不以 chooser 返回当通过。
5. 完成 W0 及真实站点观察安全审查后，在独立待验收构建接入观察器，再做快速打开/返回及 3.5 秒对 1 秒停留、未打开/时长断网检索。当前普通 PoC 自动观察仍关闭，不能用本次合成测试代替此步骤。

## 源码借鉴与许可

重新读取 `ForceMind/ChatGPT@d0be3a853929eb8bc0595075767e8d14ace8c71f` 的 LICENSE、BrowserMemory、NavigationPolicy、PageRecoveryManager。采用页面所有权与窗口分离、MutableContextWrapper 重绑、原生取回及代次失效的思路；本轮没有复制这些自定义非商业许可文件的源码，也没有导入聊天草稿/自动发送/账号/密钥或依赖。新增 X-specific 实现保留 Xs 根 GPL-3.0；自定义许可最终决策仍未完成。来源清单见 [复用审阅记录](CHATGPT_STUDIO_REUSE.zh-CN.md)。
