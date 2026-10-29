# Xs · 更纯粹、更好用的 X

[简体中文](README.md) | [English](README.en.md)

**Xs 是独立研发、本地优先的 X 使用增强工程。** 2026-10-29 起采用 **Web-first** 路线：以官方 X 网页为浏览主体，以原生本地资料库提供历史、搜索、分类和本人内容管理。不再将修改官方 X Android APK 作为主路线，也不建设新的社交网络、账号服务或 API 代理。

> **当前是路线调整，不是已完成的网页客户端。** 仓库已有 Java/SQLite 本地核心、Android 归档界面、离线导入器与测试。WebView 宿主、网页观察器、真实登录和网页端到端验证尚未实现/验收；没有可直接交付的完整 Xs 客户端。

## 正式计划

- [Web-first 完整产品与研发计划](docs/WEB_FIRST_PLAN.zh-CN.md)
- [路线决策 ADR-0001](docs/adr/0001-web-first.md)
- [产品边界](docs/product.md) · [技术架构](docs/architecture.md) · [研发路线](docs/roadmap.md)

先验证真实登录和核心浏览，再实现安全桥接与本地记录。Google 等登录方式单独验证；外部浏览器会话不会自动转移至 WebView，不复制 Cookie 或规避登录限制。验证失败时评估真正浏览器中的扩展，而不是无限更换嵌入内核。

## 能力与实际状态

| 能力 | 状态 |
| --- | --- |
| 打开历史、时间线、搜索、分类 | 本地数据与原生页面存在；网页事件接入待完成 |
| 信息流记录 | 本地策略存在；至少 60% 可见且连续 1.2 秒，独立开启；网页可见性接入待完成 |
| 我的内容导入 | 已有官方 `tweets.js` / `tweet.js` 单文件/分段导入；归属由用户确认 |
| 多账号/资料隔离 | 既有核心按账号分隔；网页身份绑定和浏览会话隔离待设计与验收 |
| 界面精简 | 网页过滤待实现；不得伪造付费权益或隐藏安全提示 |
| Web-first 宿主与登录 | 待 W0 实测，不以浏览器支持说明代替登录证据 |
| 可用客户端 APK | 尚无；现有 dev-harness 是模拟数据开发宿主 |

## 原则

记录默认关闭；主动打开与刷到分别记账，不把预取或导入当阅读。帖子发布时间、操作时间和本地观察时间不同，未知时间不编造。新增归档不上传；正常 X 网页仍联网并由 WebView 管理登录会话，Xs 不自行复制、导出或记录凭证及私信。

网页不能读取完整资料库、执行 SQL、操作任意文件或开启记录。已有 SQLite 不应被宣传成已加密。平台条款、账号风险、备份排除和公开发行条件是单独验收事项。

## 现有工程

```text
xs-runtime/           Android Library，本地资料库与存储
dev-harness/          离线模拟数据测试宿主（并非 X）
src/main/java/        Android 无关的 URL、查询、Schema 与策略
src/android/java/     Android SQLite、桥接与归档 UI
tests/                JVM、SQLite 和其他回归测试
scripts/              工程验证脚本
docs/                 产品、架构、计划与决策
compat/               旧 APK 元数据校验资产；不是已实现的 Web 安全闸门
```

计划中的 `app-android/` 与 `web-enhancer/` 尚待研发创建。

## 验证已有核心

需要 JDK 17+ 与 Python 3：

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
# Android SDK / JDK 17 / Gradle 8.9 环境：
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug
gradle --no-daemon :dev-harness:connectedDebugAndroidTest
```

现有导入器仅支持用户选择的单个 posts-JS 文件/分段，不超过 16 MiB；不执行 JS、不读取 ZIP 内其他内容。重复导入不伪造阅读历史。未来多分段/ZIP 能力以实现与测试为准。

旧 APK 集成文档和空 allowlist 保留作历史，不继续作为 Web-first 的开发前置。此次文档迁移没有新增运行时代码，也不代表重新通过构建或真机测试。

## 许可与声明

[GNU GPL v3.0](LICENSE)。Xs 与 X Corp. 无关联，不包含官方 X 安装包或凭证。使用与分发须评估适用法律、第三方授权及服务条款；本地保存不等于平台许可，不保证零账号风险或商店审核通过。
