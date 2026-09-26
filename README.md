# MiPad Mac Keymap

为小米 Pad 6 Max（yudi、HyperOS 3 / Android 15）、配套键盘与 Mac 提供远控输入适配。

内置版已通过用户本轮体验验收。2026-09-27 清理了旧输入插件与 Mac 授权残留，当前组件和控制台入口见 [安装组成](docs/installed-components.md)。

当前链路为 **MiPad Moonlight → 已配对、加密的串流连接 → Sunshine 内置输入后端**。普通键盘使用上游协议；本地光标、双指连续滚动、三指导航/拖拽、捏合与 Fn 使用同一连接中的扩展消息。日常输入不再需要 Wi-Fi ADB，也不需要独立的 Pad Mac Helper。公网连接沿用 Moonlight/Sunshine 原有方式，没有新增远控端口或修改路由设置。

Mac 的显示仍由 BetterDisplay 管理。安卓保留 Vector 系统键盘模块。Moonlight 沿用完整键位与功能层：物理左下角 Ctrl 为按住式 Fn，语音键为 Control，四叶草为 Option，Alt 为 Command。UU 精简为后三个修饰键映射和系统快捷键放行，不再使用自制 Fn/触控板链路或 Mac Helper。系统键盘 1.11 需要重启平板后加载。

- `moonlight/client`：客户端内置的本地光标与输入会话。
- `moonlight/patches`：Moonlight Android 和 moonlight-common-c 的上游接入补丁。
- `sunshine/input`、`sunshine/converged.patch`：Sunshine 会话接入及静态链接的 Swift 后端。
- `mac-input`：共享 Mac 原生事件实现。
- `protocol`：两端共享的协议常量。
- `android/system-keyboard`、`magisk/keyboard-layout`：安卓系统键位入口及前台路由。
- `android/moonlight-input`、`android/uu-input`、`mac-helper`：保留的旧链路源码，用于历史参考与回退；这些旧输入模块和 Helper 已从运行环境卸载。
- `magisk/wifi-adb`：仅用于开发调试的已配对 TLS ADB 入口，日常远控不依赖它。
- `.local`、`.cache`、`build`：本地签名/回退、临时依赖、构建产物，不提交 Git。

二开的全部新增源码、上游补丁、固定提交和构建步骤都保存在本仓库 main，不需要另一个私有 fork。详见 [收敛实现与构建](docs/converged-input.md)、[手动验收](docs/manual-input-checklist.md) 和 [验证记录](docs/verification.md)。历史 UU/Helper 开发步骤见 [旧链路开发说明](docs/development.md)。

本项目不修改 Codex 或终端快捷键，不记录输入文字。已知的触点连接“按键时暂停触控上报”尚未修复；蓝牙模式经用户确认正常，见 [并发记录](docs/keyboard-pointer-concurrency.md)。本地光标保持已验收的较小固定箭头，动态光标形状仍未实现。
