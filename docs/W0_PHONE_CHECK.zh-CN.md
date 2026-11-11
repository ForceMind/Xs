# Xs W0｜三星 S25 首轮现场验证

先验证 Xs 容器能否用系统 WebView 正常登录 X、浏览和恢复会话。首轮使用独立测试账号和无隐私测试素材。填写结果前，所有现场用例均为 **NOT RUN**。完整范围见 [W0 矩阵](../compat/web/W0-MATRIX.md)，XS-16 现场门槛见 [验收矩阵](ACCEPTANCE_MATRIX.zh-CN.md)。

## 核对安装包

从 [CI93](https://github.com/ForceMind/Xs/actions/runs/42519140643) 的 `xs-w0-browser-poc` 产物取 `outputs/apk/debug/app-android-debug.apk`。固定测试源码 `9e74ac85d96dcfc9f87a47c1e193d3fe85bd8f5d`，实际构建提交 `3b36ff71d83f5a8c79659524550d707df314770f`。文件可重命名，SHA-256 须保持相同。

| 核对项 | 值 |
| --- | --- |
| ZIP SHA-256 | `c1b7d0d1a136479cd4200dd085ff53ec37862b855c9b03deaef4fa793ef252f3` |
| APK SHA-256 | `bd6a4a97fa88960079032b0461138ed38d94d5ba306dc0c5c06392148f3bbeab` |
| CI 调试签名证书 SHA-256 | `c634bd7ce16573960a6a5a5b48178097221c0d9b3db9186290eeafaa42ffa13e` |
| 应用包名 | `dev.forcemind.xs` |

在手机上打开 APK 安装。已有 Xs 且系统提示签名或版本冲突时，记录冲突并使用备用设备或同签名的测试构建，保留原安装和本地资料。

打开 Xs 菜单的构建与兼容性页面，记录系统和 WebView 版本。也可在自己的电脑上用 Python 3.9+ 和 Android Platform Tools 运行以下只读命令。`SERIAL` 从 `adb devices` 取得，仅在本机使用。若系统拒绝读取已安装 APK，记为摘要未核对，并保留手动记录。

```bash
python3 scripts/collect-w0-device.py --serial SERIAL \
  --apk-sha256 bd6a4a97fa88960079032b0461138ed38d94d5ba306dc0c5c06392148f3bbeab
```

命令比对已安装 APK，输出机型、Android/API、WebView 版本与摘要。模拟器标志取自 Android 属性，是否真机由现场操作者确认。输出只有环境元数据，实际登录和浏览结果仍需逐项记录。

## 手机操作顺序

| 步骤 | 观察点 | 记录 |
| --- | --- | --- |
| 1. 打开 Xs，阅读提示并点「打开 X」 | 登录界面是否出现，是否提示不支持该浏览器 | NOT RUN |
| 2. 手动输入账号，完成账号要求的 2FA | 登录是否成功，能否进入已登录首页 | NOT RUN |
| 3. 浏览首页、详情、搜索及个人页，返回并刷新 | 页面是否可操作、返回位置和刷新结果 | NOT RUN |
| 4. 后台再回来，退出后重新打开 | 会话是否恢复，是否要求再次登录 | NOT RUN |
| 5. 用测试账号退出、重新登录或切换账号 | 页面账号是否正确，原本机资料是否仍可访问 | NOT RUN |
| 6. 测试正常媒体播放和系统文件选择 | 使用非私密素材，分别记录播放、选择、取消 | NOT RUN |

每项填写 PASS、FAIL、UNSUPPORTED 或 NOT RUN，附简短的可重复步骤。登录页、认证完成和进入首页分别记结果。仅反馈错误类型和步骤，不附账号、验证码、Cookie、完整登录 URL、私信或未脱敏截图。

此 CI 调试包用于 W0 容器验收，真实 X 自动记录保持关闭。真站点时间线观察、刷新找回、长滚动耗电和各聊天应用实际收件，继续在 T14 独立记录。生产签名与发布另行验收。
