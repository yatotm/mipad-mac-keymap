# MiPad Mac Keymap

针对小米 Pad 6 Max（yudi / HyperOS OS3.0.6.0.VMHCNXM / Android 15）、Mac、UU 远程及 Moonlight 的输入适配。

上次实体 Fn 验收通过的基线为系统键盘 1.8、UU 输入 0.11.2、Mac Helper 0.3.0；其他输入沿用已确认基本恢复的 0.11.1 路径。

`feature/moonlight-input` 当前安装系统键盘 1.9、Moonlight 模块 0.1.0 和 Helper 0.4.2。官方 Moonlight / Sunshine 已授权、配对并实际连接；键盘、Fn 音量、滚动阶段及连续切屏已通过自动回放。Chrome 横滑历史导航尚未验收成功，实体手感和连接模式回归见 [手动测试清单](docs/manual-input-checklist.md)。实现与验证边界见 [Moonlight 输入适配](docs/moonlight-input.md)。

蓝牙模式下，按住原 Ctrl 时，部分顶排的 Consumer 用途码被报告成修饰键位图。系统模块现在还原用途并消费假修饰键，通过受保护的本机消息和已配对的 ADB TLS 通道执行 Mac 功能。Fn 不再依赖媒体键穿过安卓系统和 UU 的普通键盘分发。

UU 路径的上下滚动、双指左右、三指下滑/左右切屏经 UU 发送；Fn、捏合打开 Launchpad、三指上滑使用独立 ADB 控制。Moonlight 路径的滚动和连续系统手势由同一个 Helper 接收。Mac 只保留一个输入辅助 App，不占用 Control＋Option＋Command＋空格作为控制协议。

- `android/system-keyboard`：Vector 系统作用域的按键路由。
- `android/uu-input`：仅 UU 作用域的键盘与触控板适配。
- `android/moonlight-input`：官方 Moonlight 12.2 作用域的连续手势、拖拽与键盘释放保护。
- `magisk/keyboard-layout`：指定键盘的缺失键位入口。
- `magisk/wifi-adb`：固定端口 23333 转发至 Android 原生 TLS 无线调试，保留配对认证。
- `mac-helper`：Mac 端滚动适配和功能动作。
- `tools`：构建与部署脚本。
- `.local`、`.cache`、`build`：本地密钥、缓存和构建产物，不提交 Git。

项目不修改 UU 原 APK，不修改 Codex 或终端快捷键，不记录输入文字。

构建和部署见 [开发说明](docs/development.md)，协议与作用范围见 [输入链路](docs/architecture.md)，验证状态见 [验证记录](docs/verification.md)。

Moonlight/Sunshine 的源码适配评估见 [源码审查报告](docs/moonlight-sunshine-source-review.md)。
