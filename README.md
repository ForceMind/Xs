# Xs · 更纯粹、更好用的 X

[简体中文](README.md) | [English](README.en.md)

**Xs 是独立研发、本地优先的 X 使用增强工程。** 用官方 X 网页正常浏览和社交，用应用自己的本机资料库记录打开历史、收藏分类、管理本人内容、下载图片/视频，并把选中的资源分享给其他应用。不修改官方 X APK，不建设新的社交后台或账号代理。

> **当前是内部开发 PoC，不是正式客户端。** 已实现 v3 保留性迁移、应用资料库、独立收藏/标签/备注、离线停留查询、图片任务/资源页和受限文件分享；本地观察器及立即打开/连续停留在合成 WebView 中验证。真实 X 自动采集仍关闭；登录、实际站点解析和接收应用仍需单独验收。

本轮实现、命令、证据与未完成项见 [研发执行记录](docs/IMPLEMENTATION_EVIDENCE.zh-CN.md)。

## 从这里继续研发

- **[文档总入口](docs/README.md)**：当前方案、文档优先级、实际状态和跟踪入口。
- **[完整实施计划 v2.1](docs/IMPLEMENTATION_PLAN.zh-CN.md)**：XS-01～XS-15 工作包、依赖、A～D 交付批次与下一步。
- **[Work 持续研发交接指令](docs/WORK_HANDOFF_PROMPT.zh-CN.md)**：在新工作会话中接管实际代码、测试、GitHub 提交与验收。
- **[产品要求](docs/product.md)** · [本机资料库、下载与分享规范](docs/LOCAL_LIBRARY_MEDIA_PLAN.zh-CN.md)。
- **[ChatGPT Studio 借鉴清单](docs/CHATGPT_STUDIO_REUSE.zh-CN.md)**：浏览底座、页面保留、原生快照获取、源码来源与不应照搬的部分。
- **[架构](docs/architecture.md)** · [路线图](docs/roadmap.md) · [验收矩阵](docs/ACCEPTANCE_MATRIX.zh-CN.md) · [变更记录](docs/CHANGELOG.md)。
- [ADR-0002：应用资料库与媒体](docs/adr/0002-local-library-media.md) · [ADR-0003：原生主动获取与并行实施](docs/adr/0003-native-pull-delivery.md) · [许可决策](docs/LICENSING.md)。

[Issue #4](https://github.com/ForceMind/Xs/issues/4) 统一跟踪当前范围；[Issue #3](https://github.com/ForceMind/Xs/issues/3) 保留真实浏览验收。旧 [Web-first 计划](docs/WEB_FIRST_PLAN.zh-CN.md) 与 [ADR-0001](docs/adr/0001-web-first.md) 保留历史和未被覆盖的约束；有冲突时按文档总入口执行。

**[时间线停留专项方案](docs/FEED_DWELL_PLAN.zh-CN.md)** · [ADR-0004](docs/adr/0004-feed-dwell.md) · [D01～D09 验收](docs/ACCEPTANCE_MATRIX.zh-CN.md)：只在时间线停留、没有打开帖子，也可以保存到本机。基础停留功能优先到 B 阶段早期，不再全部压到 D 阶段。

## 用户确认的体验

**点开即记，刷到停留也可找回。** 用户开启打开历史后，在 Xs 主动进入有效帖子详情就记录，不设停留门槛、不要求额外保存。未点开的时间线帖子也可以由用户**单独开启停留记录**：可选 **1.2秒/3秒（推荐默认）/5秒** 连续可见阈值，达标后本地留存实际停留时间与可见快照，可筛选“停留超过5秒、从未打开”；不把曝光说成已读。刷新、旋转、正文补载不虚增打开。本人转帖只在确认成功后记录。

**收藏属于 Xs。** 提供「收藏到 Xs」按钮、分类、标签和备注，不替用户调用 X 书签、点赞或转帖。退出/切换 X 账号不删除或隐式切走历史、收藏和资源。浏览 Profile、应用资料库、本人行为执行者是不同概念。

**下载进入资源库。** 用户选择图片、图组或视频下载，完成后可本地预览、查看来源、按时间/类别管理、重试、取消、导出或删除。收藏不默认下载全部媒体；缓存不等于用户下载，清缓存不能删主动保存的文件。

**分享真实文件。** 选链接、正文、单图、多图或视频，通过系统分享菜单选择微信、QQ、飞书、Telegram 等支持对应内容的已安装应用，再选会话并确认发送。联系人捷径由接收应用提供，不读取聊天令牌或自动发送；调起菜单不等于送达。

## 能力与实际状态

| 能力 | 当前状态 |
| --- | --- |
| Android System WebView 宿主 | 已有；真实登录、媒体与长期真机使用待验收 |
| SQLite / FTS、分页查询、分类与原生归档 UI | v3 保留 v1/v2 数据和旧空间映射；默认应用库，旧空间可选；显式合并尚未实现 |
| 手动帖子链接保存、其他 App 分享到 Xs | 已有；只是链接，不等于正文快照或自动历史 |
| tweets.js / tweet.js 导入 | 已有单文件/分段导入，上限 16 MiB；归属需用户确认 |
| 原生主动获取 + 本地打包观察器 | 已实现有限读取、严格 JSON、代次/队列校验；只在离线夹具接入，真实 X 自动采集关闭 |
| 立即打开与停留 | 合成页面实现无门槛打开、补正文、导航去重、1.2/3/5 秒、单次时长及未打开筛选；真实站点未验收 |
| 本人行为记录 | 保留已确认归属的档案导入；实时行为确认与撤销尚未实现 |
| 独立收藏按钮、标签、备注、分类 | 已实现；真实浏览器收藏先存链接，本地已有正文可保存快照；不调用 X 书签 |
| 图片任务、资源库、文件分享 | 已实现窄范围图片选择/下载校验/预览/分类/导出/多图分享；中断后从来源重新选择，实际 X 与接收应用未验收 |
| 视频下载 | 尚未实现；blob/播放列表/分段不作为成品 |
| 广告/会员营销/推荐精简 | 可撤销规则待实现；不解锁权益、不隐藏安全提示 |

## 实施方向

参考已有 ChatGPT Studio 的 Android 浏览生命周期、Profile、受控窗口和原生主动获取模式；保留 Xs 的 SQLite/FTS 与 UI，为 X 单独实现本地打包的帖子观察脚本。优先用原生主动取回受限结果完成历史，不把通用双向桥作为第一版前提。返回值仍不可信，实际写入前必须校验上下文、授权、代次和导航身份。

离线观察器、迁移、收藏与媒体开发可和真机验证并行；未验证的真实站点采集不能默认开启。历史方案中的「等 W0 后才开始所有开发」不作为当前执行方式。

交付顺序：**A 浏览与点开自动历史 → B 早期停留记录（无需点开）＋收藏、图片、资源页及分享 → C 本人转帖、视频与精简 → D 长期停留性能、容量及发布加固。**

## 当前可测试的手动流程

浏览器菜单「保存当前帖子链接」「我的本机资料库」或 Android 分享菜单「保存到 Xs」，经过原生确认创建本机空间和保存链接。重复保存不制造阅读历史。这只是补充路径，不是强迫用户手动收藏每次浏览的主产品。

## 隐私、迁移与删除

打开历史、停留与过滤分别授权；不读取密码、验证码、Cookie/Token、私信、草稿或隐藏状态。X 网页仍正常联网，Xs 不自建资料上传服务。用户主动交给其他 App 的文件随后按接收方规则处理，不能再承诺永不离开设备。

取消收藏、清历史、删资源、清缓存、退出 X 和删整库分开处理。旧 account_id 数据保留映射，不静默合并、不重建空库；并发导入/下载/分享/清理不能使旧内容复活。既有 SQLite 不宣称已加密，网页无整库读取、任意 SQL、导出或删除权限。

## 工程与检查

```text
app-android/          浏览宿主、原生收藏/图片选择/分享、合成集成测试
xs-runtime/           本机归档 Android Library
dev-harness/          离线合成测试宿主，不是 X
src/main/java/        URL、v3查询/Schema、连续停留/串行写入/严格JSON/媒体策略
src/android/java/     SQLite、收藏/资料UI、原生主动取回、媒体与文件分享
tests/                JVM、SQLite 和回归测试
scripts/              工程检查
docs/                 当前方案、执行计划、决策与验收
compat/               设备矩阵及历史 APK 元数据
```

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
# Android SDK、JDK 17、Gradle 8.9 环境：
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:lintDebug
gradle --no-daemon --no-parallel :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

[W0 执行记录](docs/W0-EXECUTION.md) · [设备矩阵](compat/web/W0-MATRIX.md)。文档提交不等于新编译/测试；合成或模拟器结果不替代真实账号、目标应用接收文件和个人数据升级验收。

## 许可与声明

当前根目录有效文本仍为 [GNU GPL v3.0](LICENSE)。所有者已允许自定义许可方向；详见 [LICENSING](docs/LICENSING.md)。本次计划整理不替换许可证、不擅自加入商业限制、不删除第三方声明或回溯改写旧授权。

Xs 与 X Corp. 无关联，不包含官方安装包、登录凭证或订阅权益。仅处理有权访问、允许下载的资源，不绕过 DRM、付费验证或安全限制。源码许可不授予第三方内容/服务权利，不能承诺零账号风险或商店批准。
