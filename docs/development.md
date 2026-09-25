# 构建与迭代

当前工具链为 macOS、Python 3、JDK、Xcode Command Line Tools、Android SDK 35。脚本默认使用 Homebrew OpenJDK，也可通过 `JAVA_HOME` 指定。

## Android

1. 执行 `python3 tools/fetch_android.py`。SDK 从 Google 下载并按固定摘要校验，只解出必要文件，下载档案随即删除。Xposed API 镜像也按固定 SHA-256 校验。
2. 将已有安装使用的签名密钥放在 `.local/signing/pad-uu-local.p12`。密码保存在 `.local/signing/store-password`，或通过 `PAD_KEYSTORE_PASSWORD` 提供。不要用新密钥覆盖既有安装。
3. 执行：

```sh
python3 tools/build_android.py system-keyboard
python3 tools/build_android.py uu-input
```

脚本先检查状态逻辑，再编译、签名和校验 APK，输出放在 `build/`。不依赖旧 diagnostics 目录。

部署使用以下脚本，强制完整安装并检查实际文件系统和 Vector 注册路径：

```sh
python3 tools/deploy_android.py --serial '<已授权设备地址>'
```

**不能用增量安装部署系统作用域模块。** 本机已实测：incremental-fs 中的 APK 在 Vector 开机加载 system_server 模块时尚不可读，日志报 APK 不存在；开机后包版本却正常，容易产生“已更新但未生效”的假象。改用 `--no-incremental --no-streaming` 完整安装到 f2fs 后，已在 system_server 中确认模块加载。构建脚本也禁用 v4 增量签名，避免普通 `adb install` 再自动选中增量安装。

系统模块覆盖安装后重启平板；仅更新 UU 模块时重启 UU。可给脚本加 `--reboot`，在全部安装检查通过后重启。已有 Vector 启用状态和作用域保持，不恢复整个 Vector 数据库。

运行配置示例：

```json
{"enabled":true,"reverse":false,"scroll_scale":0.25,"swipe_mm":8,"inertia_strength":4,"trace":false}
```

配置路径：`/data/user/0/com.netease.uuremote/files/pad_uu_touchpad.json`。旧 `horizontal_scale` 和 `mac_controls_path` 已废弃。

## Mac

```sh
python3 tools/build_mac.py
python3 tools/install_mac.py
```

构建只生成 `build/Pad Mac Helper.app`，先运行无输入副作用的检查，再签名。安装脚本将它部署到当前用户的 `Applications`，沿用唯一的 `local.pad.uu.ScrollBridge` launchd 项；旧组件备份只放在 `.local/rollback/mac`。

**先完成编译和签名，再登记辅助功能权限。** 临时签名的代码摘要改变后，即使系统设置开关显示开启，也需要核对该版本实际获得的权限。安装后的 `helper-status.json` 应显示 `permission`、`keyboard_tap`、`scroll_tap` 均为 true。

新组件运行正常后，删除原 `~/Applications/Pad UU Scroll.app`、`~/Library/Application Support/Pad UU/Controls.app` 及后者的旧权限条目。Mos 保留。不要启动第二个同职责常驻进程。

## 验证边界

- 查表和状态检查证明分支、配对和取消逻辑，不证明实体键盘传输。
- Android 普通注入会变成虚拟设备，不能把注入成功当作实体测试通过。
- 优先在独立浏览器测试页检查导航和普通键，避免在用户实际文本中测试删除或快捷键。
- Fn 验收先用音量减/加与亮度减/加；跳过睡眠。同时核对 Mac `last-action.json`，区分请求没到和系统接口失败。
- 完成后关闭临时 trace，停止采样，清理 `.cache` 和编译中间文件。保留本地签名、源码、必要回退包和简短结论。

没有任务需要修改 Codex 或终端快捷键。不要通过工具读取或操作用户的 WezTerm 窗口。
