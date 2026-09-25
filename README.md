# MiPad Mac Keymap

针对小米 Pad 6 Max（yudi / HyperOS OS3.0.6.0.VMHCNXM / Android 15）和 UU 远程 4.40.0 的输入适配。

当前候选版本：系统键盘 1.7、UU 输入 0.11.0、Mac Helper 0.3.0。系统 Fn 路由已通过用户验证；新的独立输入通道已连接，滚动手感与手势行为仍需实机回归。

滚动、Fn 功能和手势指令使用已配对的 ADB TLS 通道，普通键盘、鼠标与拖拽继续由 UU 处理。Mac 端只保留一个 App，不再占用用户快捷键作为控制协议。蓝牙和 Pogo 是同一把键盘的两种连接方式。

- `android/system-keyboard`：Vector 系统作用域的按键路由。
- `android/uu-input`：仅 UU 作用域的键盘与触控板适配。
- `magisk/keyboard-layout`：指定键盘的缺失键位入口。
- `magisk/wifi-adb`：固定端口 23333 转发至 Android 原生 TLS 无线调试，保留配对认证。
- `mac-helper`：Mac 端滚动适配和功能动作。
- `tools`：构建与部署脚本。
- `.local`、`.cache`、`build`：本地密钥、缓存和构建产物，不提交 Git。

项目不修改 UU 原 APK，不修改 Codex 或终端快捷键，不记录输入文字。

构建和部署见 [开发说明](docs/development.md)，协议与作用范围见 [输入链路](docs/architecture.md)，验证状态见 [验证记录](docs/verification.md)。
