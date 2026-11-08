# Xs｜实际研发接续指令

继续 ForceMind/Xs 的现有实现，不从旧main重建、不另写一轮计划、不丢 PR #5 未合并进展。

- 仓库：https://github.com/ForceMind/Xs
- PR：https://github.com/ForceMind/Xs/pull/5
- 分支：`feat/local-library-capture-media`
- XS-16工作包：https://github.com/ForceMind/Xs/issues/6
- 本轮起点：`78a29cb05f908e5f44b6111183a202655da009a9`，其前30个提交完整保留。**当前HEAD必须重新从GitHub读取，不能把本轮起点当最新。**
- 当前源码与实际执行账本：[XS16_IMPLEMENTATION_EVIDENCE](XS16_IMPLEMENTATION_EVIDENCE.zh-CN.md)。实际CI最终结果也记录在Issue/PR，避免为了回填自身SHA循环提交。

先核对最新main、开放PR、分支HEAD、review与CI；读取AGENTS、docs/README、本交接、TIMELINE_BUFFER_PLAN、ADR-0005与ACCEPTANCE_MATRIX。从PR分支接续，保留未提交修改。

## 本轮已经修改的源码

1. 三项既有P2已修源码并添加回归：canonical i/web/status观察；parsed route+verified native context来源（query不冒充search、explore不默认ForYou）；SAF选择器重建保留进程内请求/口令、唯一请求码与writer复验。CI绿灯/outdated不作为证明，执行以新报告为准。
2. F1：临时schema1 SQLite/FTS，独立默认OFF、48h/全应用100MiB，1–168h/25–500MiB；字段/批次/记录/队列有界，实际DB/WAL/SHM与整理峰值，query-time TTL/高水位时钟。
3. F2：独立TimelineCaptureSession/native pull正视口admission；原生批次与首序/同批去重、疑似刷新诚实标注；FEED原60%和1.2/3/5s不变。实际观察只在合成fixture挂载。
4. F3：原生最近时间线列表/搜索/CJK/批次/分页/预览/设置/清缓冲及单条/批量长期保存/收藏。
5. F3持久层明确升Archive v6：保留性v5→v6 additive promotion ledger/source，兼容旧schema3/4/5备份、合并及固定旧schema2 APK。Archive先提交再buffer ack，不假称两库原子事务；保存时刻、首次出现与正文捕获时间分开，质量/来源不冒充detail/COMPLETE。
6. F4：过期正文/FTS/孤儿实际回收、配额淘汰、独立clear代次/pending-clear、持久tombstone与整库删除覆盖临时库；启动/可延迟Job维护，buffer清理不废媒体票据、不删长期资料或下载。
7. F5已新增JVM/SQLite/DOM/native UI/SAF/跨库故障回归与进程/guest probe；**是否通过以实际新CI为准，存在源码不算PASS。**

## 继续执行规则

从最新失败或未执行的实际检查推进，持续修源码、运行测试、提交该分支、更新Issue6。不要重复实现已通过部分来代替未完成检查。

```bash
bash scripts/check-project.sh
bash scripts/test-core.sh
python3 tests/test_legacy_upgrade_driver.py
gradle --no-daemon :xs-runtime:assembleDebug :dev-harness:assembleDebug :app-android:assembleDebug :app-android:assembleDebugAndroidTest :dev-harness:assembleDebugAndroidTest :app-android:lintDebug :xs-runtime:lintDebug
gradle --no-daemon --no-parallel :dev-harness:connectedDebugAndroidTest :app-android:connectedDebugAndroidTest
```

四API：26x86_64、27x86、30x86_64、36x86_64。既有20个外部driver（固定旧APK升级、force-stop、debug升级、guest reboot、真实低空间）和新增场景都要实际验，不把内部probe重复计入主XML。记录源SHA/tree/merge、包hash、API/ABI/WebView、实际case与失败。

CI84原失败保留：API36真实SAF侧栏定位失败；API27主仪器通过后guest rebootADB超时/模拟器端口消失。侧栏现在有界重取精确可点击项；重启失败增加诊断而不自动重启/重跑掩盖。最终报告应保留每次失败及修复范围。

## 必须保持的边界

- 缓冲独立同意；不制造OPEN/FEED/FAVORITE/已读；显式保存/收藏独立事务。预览不计打开，真正点原帖按既有导航规则。
- 只保存实际呈现并观察到的允许卡片metadata；不抓rawAPI、隐藏状态、Cookie/Token、游标、DM/草稿；不自动下载媒体。不保证接口全量或漏采瞬时内容。
- 临时内容不自动进入备份；清buffer、历史、收藏、媒体、网页cache/session、整库各自有范围。过期不删长期内容/下载/已有分享副本。
- SQL查询立即排除过期；后台清理由系统延迟，不承诺force-stop/休眠后准点物理擦除或SQLite安全擦除。
- 保持library归属/epoch/writer/buffer generation、实际写前复验、durable tombstone；旧回调/关闭重开/清理重启不能复活。
- 快照LINK_ONLY/PARTIAL/COMPLETE语义保留；原生捕获/时间线来源/批次原因分别记。Following不能由不可信DOM自称。
- 不操作用户电脑/服务器、不合并main、不发布正式版。GPL/历史授权/第三方声明保留。

## 独立现场门禁

Issue3真实X/W0、登录/DOM/自动刷新/虚拟列表/账号切换、媒体CDN、微信/QQ/飞书/Telegram接收、手机耗电/物理断电/生产签名继续独立验收。合成/模拟器PASS不启用live；现有原生手动收藏、资源管理和正常浏览仍可用。

统一汇报实际修改/提交/CI/T01–T14与具体阻塞，不要求用户每个文件说“继续”，也不承诺对话结束后后台研发。
