# XS-16｜CI93 真实文件系统低空间与完整回归

T07此前未单独实测的“文件系统不足1MiB时缓冲admission”已在四个可丢弃CI模拟器上实际通过。本轮仍不启用真实X适配，也不是手机、物理断电或正式发布验收。

## 固定源码与执行

- 代码/测试提交：`9e74ac85d96dcfc9f87a47c1e193d3fe85bd8f5d`；实际merge：`3b36ff71d83f5a8c79659524550d707df314770f`；tree：`bce4a29f646bc5b3c2479b414d41c8bc257857d8`。
- [CI93 / run42519140643](https://github.com/ForceMind/Xs/actions/runs/42519140643)六作业全部SUCCESS。本会话未请求rerun；以下只记该run的实际产物。
- **800/800主XML，0失败/错误/跳过**，每API为app135+harness65。新增SQLite FULL两项在四API共8/8；实际文件系统压力是单独driver子场景，不追加为主XML。
- **20/20独立driver、44/44外部native命令阶段**，每API5driver/11phase。固定旧main APK schema2→6、真实force-stop、同job当前源码debug3→4升级、guest reboot、实际低空间全部通过。fixture准备和重复probe不另计。
- 1406 JVM、25临时SQLite/FTS4、34项driver host（low-space12+instrument6+legacy16）与6项allowlist、10k/100k保留性迁移/查询容量、DOM/20k压力、AAR/app/harness/test APK构建和双Lint通过。
- [机器台账](evidence/xs16-ci93-results.json)包含全部XML的计数/身份集合摘要/失败检查、新压力用例、各driver原始状态、native状态行、设备/WebView、ZIP与APK摘要。

| API / ABI | 主XML | 独立driver | 外部native phase |
| --- | --- | --- | --- |
| 26 / x86_64 | 200 PASS | 5 PASS | 11 PASS |
| 27 / x86 | 200 PASS | 5 PASS | 11 PASS |
| 30 / x86_64 | 200 PASS | 5 PASS | 11 PASS |
| 36 / x86_64 | 200 PASS | 5 PASS | 11 PASS |

## 实际余量、清理和恢复

下列全部为普通目标app UID测量的真实文件系统字节；不是SQLite页面上限、mock磁盘、root进程读数或主机df估算。

| API | app UID | 捕获前余量 | 拒收后余量 | 自有压力文件写入字节 | 删除后余量 |
| --- | --- | --- | --- | --- | --- |
| 26 | 10082 | 786432 | 774144 | 27918336 | 28696576 |
| 27 | 10084 | 786432 | 774144 | 28553216 | 29331456 |
| 30 | 10172 | 786432 | 774144 | 28426240 | 29208576 |
| 36 | 10221 | 786432 | 753664 | 22990848 | 23748608 |

四API均在余量小于1,048,576字节时拒收并记录DEVICE_SPACE，原临时卡片数保持，拒绝正文未进入FTS。删除压力文件后新观察成功，暂停提示清除；媒体ticket仍有效。原长期收藏、已晋升正文与单一账本、已登记完成媒体的文件hash，以及OPEN/FEED数量均由原生断言核对。

边界如下：

- 严格复用GITHUB_ACTIONS、emulator serial、qemu、初始shell UID2000门；原生还要求显式fixture capability、ranchu/goldfish硬件和app UID≥10000。普通主套件不执行文件系统填充。
- host仍只把一次性测试机压到约28MiB；已运行的原生调用再写自己的随机临时文件，最多40MiB，逐块fsync并核验实际长度和余量。低余量窗口中不启动新进程。finally只删除该文件；host独立finally删除外层压力文件。
- API26/27仍只有当前shell配额错误且旧userdebug/API/debuggable门全部满足时，才使用既有临时root分配器；原生断言始终在普通app UID。四API最终shell UID2000、压力文件移除均已验证。没有root用户手机或新增生产权限。
- 该测试验证预留不足时admission拒收与释放后恢复，不是下载中途ENOSPC、物理闪存掉电或厂商后台保证。SQLite页FULL/二次回滚/计数失败由另外的原生两项测试覆盖，详细实现见[空间失败记录](XS16_STORAGE_PRESSURE_EVIDENCE.zh-CN.md)。

## 修改后的行为

设备空间不足时，在配额淘汰/FTS写入前停止新捕获，保留原临时卡片，稍后释放空间/维护后恢复；应用级预算超限仍按原规则回收/淘汰。DEVICE_SPACE与SPACE_BUDGET分别显示，不改变默认OFF、48h、应用100MiB、1–168h/25–500MiB或已有OPEN/合格FEED/收藏规则。

FULL只返回拒收和固定原因，不保存异常文本或候选内容作自动重试。诊断也写不进SQLite时仅保留有界进程内计数，进程退出可能丢失该下界；成功捕获清暂停提示，显式全临时reset清计数并保持OFF。SQLite会自行回滚的FULL保留原始异常，仍释放Android session；不把其它事务失败当成功。

## 原失败保持原结果

[CI91台账](evidence/xs16-ci91-results.json)仍是800/800主项、18/19已尝试driver、41/41已执行nativephase；API27 guest reboot后ADB等待180s超时且控制端口拒绝连接，恢复未验证，低空间driver未运行。其根因仍UNKNOWN；CI93是包含新运行时/夹具的完整新源码执行，不把CI91改为PASS，也不宣称其环境故障根因已修。

CI88/89/90/92的真实成功、失败和取消范围分别保留在台账与[前轮记录](XS16_STORAGE_PRESSURE_EVIDENCE.zh-CN.md)。CI89旧包seed后的PID失败不因后续通过而改写；首次软件页上限夹具4失败、修正后软件环境NOT RUN也独立保留，不声称有效baseline RED。

8份ZIP全部实际下载并与GitHub公开digest核对；20份native APK副本另用本地aapt/apksigner核验包名/实际版本/证书和摘要。build job浏览器APK单独核验，不混入20份；记录角色与下载副本的口径分开。不同job debug证书不构成正式更新承诺。

## 独立现场门槛

T14 / Issue #3 的真实X/W0、账号/虚拟列表/CDN、手机多日/耗电/厂商后台、聊天应用接收、物理掉电、生产签名与真实个人库迁移仍NOT RUN；live适配保持OFF、PR保持draft，无main合并或正式发布。T08提交前guard回滚仍是模型；实际OS恢复验证的是Archive已提交、buffer未ack的间隙。Android可推迟维护，SQLite/FTS/WAL未加密且不承诺安全擦除、完整48小时覆盖或恢复X推荐状态。
