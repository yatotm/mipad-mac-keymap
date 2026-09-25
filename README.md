# MiPad Mac Keymap

针对小米 Pad 6 Max（yudi / HyperOS OS3.0.6.0.VMHCNXM / Android 15）和 UU 远程 4.40.0 的输入适配。

项目正在从本机调试目录迁入。本次迭代修复 Fn 功能层、将双指横向动作改为一次性导航，并把 Mac 组件统一为一个 App。

- `android/system-keyboard`：Vector 系统作用域的按键路由。
- `android/uu-input`：仅 UU 作用域的键盘与触控板适配。
- `magisk/keyboard-layout`：指定键盘的缺失键位入口。
- `magisk/wifi-adb`：固定端口 23333 转发至 Android 原生 TLS 无线调试，保留配对认证。
- `mac-helper`：Mac 端滚动适配和功能动作。
- `tools`：构建与部署脚本。
- `.local`、`.cache`、`build`：本地密钥、缓存和构建产物，不提交 Git。

项目不修改 UU 原 APK，不修改 Codex 或终端快捷键，不记录输入文字。
