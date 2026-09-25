# MiPad Mac Keymap

针对小米 Pad 6 Max（yudi / HyperOS OS3.0.6.0.VMHCNXM / Android 15）和 UU 远程 4.40.0 的输入适配。

当前版本：系统键盘 1.8、UU 输入 0.11.2、Mac Helper 0.3.0。用户已确认 Fn 功能生效；其他输入沿用已确认基本恢复的 0.11.1 路径。

蓝牙模式下，按住原 Ctrl 时，部分顶排的 Consumer 用途码被报告成修饰键位图。系统模块现在还原用途并消费假修饰键，通过受保护的本机消息和已配对的 ADB TLS 通道执行 Mac 功能。Fn 不再依赖媒体键穿过安卓系统和 UU 的普通键盘分发。

上下滚动、双指左右、三指下滑/左右切屏经 UU 发送；Fn、捏合打开 Launchpad、三指上滑使用独立 ADB 控制。Mac 只保留一个辅助 App，不占用 Control＋Option＋Command＋空格作为控制协议。

- `android/system-keyboard`：Vector 系统作用域的按键路由。
- `android/uu-input`：仅 UU 作用域的键盘与触控板适配。
- `magisk/keyboard-layout`：指定键盘的缺失键位入口。
- `magisk/wifi-adb`：固定端口 23333 转发至 Android 原生 TLS 无线调试，保留配对认证。
- `mac-helper`：Mac 端滚动适配和功能动作。
- `tools`：构建与部署脚本。
- `.local`、`.cache`、`build`：本地密钥、缓存和构建产物，不提交 Git。

项目不修改 UU 原 APK，不修改 Codex 或终端快捷键，不记录输入文字。

构建和部署见 [开发说明](docs/development.md)，协议与作用范围见 [输入链路](docs/architecture.md)，验证状态见 [验证记录](docs/verification.md)。
