# MiPad Mac Keymap

针对小米 Pad 6 Max（yudi / HyperOS OS3.0.6.0.VMHCNXM / Android 15）和 UU 远程 4.40.0 的输入适配。

当前恢复版本：系统键盘 1.7、UU 输入 0.11.1、Mac Helper 0.3.0。**0.11.0 的独立滚动和导航没有通过用户验收，已从日常输入路径撤回。** 0.11.1 已安装加载，实际恢复效果仍需用户确认。

上下滚动、双指左右、三指下滑/左右切屏恢复经 UU 发送；Fn、捏合打开 Launchpad、三指上滑保留独立 ADB 控制。普通键盘、鼠标与拖拽仍由 UU 处理。Mac 端只保留一个 App，不占用 Control＋Option＋Command＋空格作为控制协议。

- `android/system-keyboard`：Vector 系统作用域的按键路由。
- `android/uu-input`：仅 UU 作用域的键盘与触控板适配。
- `magisk/keyboard-layout`：指定键盘的缺失键位入口。
- `magisk/wifi-adb`：固定端口 23333 转发至 Android 原生 TLS 无线调试，保留配对认证。
- `mac-helper`：Mac 端滚动适配和功能动作。
- `tools`：构建与部署脚本。
- `.local`、`.cache`、`build`：本地密钥、缓存和构建产物，不提交 Git。

项目不修改 UU 原 APK，不修改 Codex 或终端快捷键，不记录输入文字。

构建和部署见 [开发说明](docs/development.md)，协议与作用范围见 [输入链路](docs/architecture.md)，验证状态见 [验证记录](docs/verification.md)。
