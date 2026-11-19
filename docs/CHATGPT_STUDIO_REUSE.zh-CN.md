# ChatGPT Studio → Xs 借鉴与迁移清单

日期：2026-11-06。状态：已审阅实现的迁移设计；**未因本文迁入源码**。

参考基线为本次讨论中已审阅的 `ForceMind/ChatGPT@d0be3a853929eb8bc0595075767e8d14ace8c71f`，不是声称该提交永远最新。实现复用前重新核对来源提交和修改范围。用户报告手机网页壳可用，这支持借鉴现成底座，但不替代 X 登录/媒体的站点验收。

## 1. 主线与实验线分开

[Android 构建文件](https://github.com/ForceMind/ChatGPT/blob/d0be3a853929eb8bc0595075767e8d14ace8c71f/android/app/build.gradle)使用 Java 17、AndroidX WebKit 与系统 WebView；[Android README](https://github.com/ForceMind/ChatGPT/blob/d0be3a853929eb8bc0595075767e8d14ace8c71f/android/README.md)将其他浏览内核列为独立实验。桌面的 Electron 方案不是手机运行时。

Xs 保留现有 Android/Java 工程。参考模块若需要更高 Java/API/WebKit 版本，先列兼容差异再升级，不直接搬来无法编译的源码，也不为迁移同时重写全部 UI/ORM。

## 2. 逐组件映射

以下路径均相对于上述 ChatGPT 仓库基线。

| 来源组件 | 已审阅的作用 | Xs 借鉴/改造 | 不应直接照搬 |
| --- | --- | --- | --- |
| android/app/src/main/java/com/forcemind/chatgpt/profiles/MainActivity.java | WebView 创建、Profile 绑定、受控子窗口、页面回调与宿主交互 | 拆出 BrowserHost/WebViewFactory、认证窗口与原生动作 | 整个大型 Activity、聊天任务流、所有认证域名 |
| 同目录 BrowserMemory.java | 进程内页面持有、窗口重绑定、最近关闭池与代次 | 进入资料库/返回/旋转时优先复用浏览页，限制页面数量 | 无上限保留或默认后台长驻；页面保留不等于后台采集 |
| 同目录 NavigationPolicy.java | 内容站点、认证站点、外链分类和恢复地址处理 | X-specific 精确来源/窗口策略；认证页不赋予采集权限 | 把 ChatGPT/Google 等列表原样当 X 已验证清单 |
| 同目录 PageRecoveryManager.java | 原生 evaluateJavascript 调用、会话/页面/存储代次校验与失效回调 | NativePageController：主动取回受限帖子事件和快照，写入前二次检查 | 聊天草稿恢复、自动发送流程或把普通窗口 token 当身份认证 |
| android/app/src/main/assets/page-recovery.js | 顶层/来源检查、MutationObserver 标记变化、受限快照 | XPageObserver：导航、帖子文本、质量标记，可撤销样式 | ChatGPT 选择器、输入框/草稿/对话内容采集；不能换域名就宣称适配完成 |
| 同 Java 目录 PageRecoveryStore.java | 按代次拒绝旧写入、原文件保留和原子文件写入 | 迁移/清理屏障、备份恢复思路；资料主库仍用 Xs SQLite | 把文件恢复存储完整替换成资料库或称已完成数据库加密 |
| 同目录 DevicePayloadKeys.java | Android KeyStore 设备密钥与加密载荷入口 | 作为独立加密设计的参考 | 复制一个工具类便宣称 FTS/WAL/备份已经加密 |
| 同目录 AssetDownloads.java、AssetLedger.java 等 | 资产/下载候选实现 | XS-08/09 前继续逐文件审计生命周期、鉴权、分享边界 | 尚未审阅实现不能列为“可直接搬入且已验证” |

源码目录：[固定提交](https://github.com/ForceMind/ChatGPT/tree/d0be3a853929eb8bc0595075767e8d14ace8c71f/android/app/src/main/java/com/forcemind/chatgpt/profiles)。本表是来源索引，不是已迁移清单。

## 3. 采用原生主动获取作为首版观察实现

优先以随 APK 交付的本地脚本观察允许的帖子上下文，在原生已验证的时机调用 evaluateJavascript 取回有限 JSON。原生拥有 library、browser context、document/navigation ID、时钟与授权；页面无任意 SQL、文件、导出或删除接口。

网页返回值仍不可信：同源脚本/DOM 可影响结果，随机键或 token 不是认证。收到回调、入队和实际落库均核对当前上下文；限制字节数、条数、解析深度和速率。当前 ObservationGate/Parser 是基础，不是完整适配器。

点击打开不能照抄周期快照：快速打开后返回也要捕获一个导航事件；正文未到先记 LINK_ONLY，再补快照。页面观察队列须有上限；退出文档前/返回等边界要测试，不靠每几秒读取一次 URL 漏掉短访问。刷新/旋转/DOM 重绘不新增 OPEN_POST。

信息流与打开历史分别授权和计时。第一版不因需要有限观察结果而先建设通用双向桥；后续确有性能/能力需求时再评估来源受限消息接口，并保持同样的权限和失效边界。

## 4. Profile 和资料库不绑定所有权

浏览 Profile 决定网页 Cookie/网站存储上下文；应用 library_id 决定本机资料所有者。可以借鉴多 Profile 能力检查，但第一交付可先单会话。用户切换 X 账号只使旧页面任务失效，不删除或隐式更换应用收藏和资源。

本人转帖行为的账号来源单独记录，无法确认就不冒充本人成功。保存公开帖子无需可信 X 账号 ID；不得向旧 bindAuthenticatedAccount 传本地 UUID 冒充认证。

## 5. 复用和许可检查单

- [ ] 记录拟迁移文件、准确提交、作者/第三方来源和适配 diff。
- [ ] 对所有者有权授权的自有代码确认 Xs 所用许可；第三方组件继续保留原声明。
- [ ] ChatGPT Studio 的自定义许可与 Xs 当前根 LICENSE 分开处理；可采用自定义方向不等于自动完成所有授权手续。
- [ ] 移除不需要的工作流、聊天输入、生成任务保活和服务域名耦合。
- [ ] 运行生命周期、窗口、账号/库边界、回调失效、源版本兼容测试。
- [ ] 更新 Xs 双语 README 与来源台账；不冒用另一个 App 的包名、数据目录或签名。

不导入另一个 App 的 Cookie、恢复密钥或用户数据。Xs 应建立自己的稳定测试/发布身份。详见 [许可决策](LICENSING.md)；本文不修改任何仓库的有效许可证。

## 本轮实际复核与采用范围

重新读取上述固定提交的 LICENSE、BrowserMemory.java、NavigationPolicy.java、PageRecoveryManager.java。Xs 本轮独立实现单页 MutableContextWrapper/配置重建保留、受控临时窗口路由、原生主动取回及写入失效；没有复制参考项目自定义非商业许可文件的源码，也没有移入其聊天/自动发送/密钥/工作流。保留 Xs 根GPL文本；多Profile仍待能力检测/验证。实际范围和测试见 [实施记录](IMPLEMENTATION_EVIDENCE.zh-CN.md)。
