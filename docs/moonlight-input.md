# Moonlight 输入适配第一版

这条路径与已验收的 UU 路径并存。分辨率和 BetterDisplay 配置不在本次改动范围内。

## 当前交付状态

| 组件 | 状态 |
| --- | --- |
| 官方 Moonlight 12.2 | 已安装，保留原第三方 Moonlight；已与本机 Sunshine 完成配对 |
| 官方 Sunshine 2026.914.233613 | 已安装，已建立当前用户的启动项；独立启动后确认缺少录屏权限，不能称为串流验收完成 |
| 系统键盘 1.9 | 已安装、完整重启，并核对 system_server 实际加载；原布局扩展到 Moonlight Game 页面 |
| Moonlight 输入模块 0.1.0 | 已安装、启用并限定官方包名，已核对实际加载；未连接 Mac 输入通道时保留 Moonlight 默认触控处理 |
| Pad Mac Helper 0.4.0 | 已构建并使用本机现有开发证书签名；新权限登记受系统认证界面限制，尚未切换为常驻版本 |
| 当前常驻 Helper | 保留已获授权的 0.3.0，保证原 UU 路径可回退 |

**状态机测试和安装成功不等于实体设备验收。** 连续系统动画、Chrome 横滑返回、通知滑走、双指缩放和两种键盘连接方式仍需在新 Helper 获得授权后逐项实测。目前不能称为“完美适配”。

## 输入如何流动

```mermaid
flowchart LR
    K[小米键盘] --> S[Android 系统布局与 Fn 修正]
    S --> M[Moonlight]
    T[小米触控板] --> A[Moonlight 作用域适配模块]
    A -->|普通指针与拖拽| M
    M -->|原生串流输入协议| U[Sunshine]
    S -->|受保护的 Fn 消息| A
    A -->|已配对的 ADB TLS| H[唯一的 Pad Mac Helper]
    H -->|带阶段的滚动与手势| OS[macOS]
    U --> OS
```

Moonlight 与 Sunshine 的官方二进制未修改。源码核对后，现阶段采用小范围 Vector 适配，避免承担整个视频客户端和服务端的分叉维护。触控扩展协议与传输分开，后续可换成串流协议扩展；当前扩展通道仍是 ADB TLS，不应描述成已全部走 Moonlight 协议。

官方 release 实测已内联 `NvConnection` 的部分包装方法，因此键盘状态记录和拖拽直接使用上游 keep 规则保留的 `MoonBridge` JNI 入口。模块加载时核对所需方法、字段，并严格限制版本为 12.2；不能只根据未优化源码假定发行 APK 仍保留每个 Java 方法。

同一个 Helper 内保留 UU 和 Moonlight 两个有界读取器；只有一个输入辅助 App 和一个输入辅助启动项。Sunshine 是串流服务，不是第二个滚动辅助程序。

## 行为与边界

- 原 Ctrl 为按住式 Fn，语音键为 Control，四叶草为 Option，Alt 为 Command。Fn 不发送到 Mac，不模拟 Globe。所有 Fn 功能目标均为 Mac；执行接口沿用已验收的固定动作。
- 普通键盘按下和松开由 Moonlight 传输，长按重复由 Sunshine 的服务端实现。没有新增定时打字器，也没有修改 Codex、终端或应用快捷键。
- 双指平移发送像素滚动及开始、改变、结束、取消、惯性阶段。方向不再用 Command/Option 加箭头模拟；抬手速度决定惯性，重新落指停止惯性。初始换算为每毫米 6 像素，惯性采用之前选择的强度 4，最终手感仍待实体测试。
- 双指改变间距发送独立的 magnify 事件，与横向滚动互斥。
- 三指直接滑动发送连续 Dock 手势，横向对应 Spaces，纵向对应 Mission Control / App Exposé；停留约 0.3 秒后移动则通过 Moonlight 左键拖拽。三指改变间距发送系统捏合手势。
- 四指使用相同导航逻辑，前提是 Android 实际上报四个触点。没有因为硬件“支持多点”就假定一定能收到四指。
- 输入距离按触控板自身的轴分辨率换算成毫米，不依赖平板屏幕 DPI。设备未提供分辨率时才使用明确的尺寸回退估算。
- Mac 原生手势使用私有 CoreGraphics 字段，仅在本机 macOS 15 上开放；它是系统手势注入，不是完整虚拟 Apple 多点 HID 设备。升级系统后需要重新核对。

## 防止残留输入

触点增加时重置原点，减少时结束已有操作，避免重心跳变；三指捏合不会调用鼠标按下。失焦、暂停、设备断开、输入通道失效都会取消手势并释放本模块持有的拖拽。拖拽连接已经关闭时，依赖 Sunshine 的会话释放逻辑。

键盘部分只在内存记录实际发送到 Moonlight 连接且尚未松开的键码，重复按下不会增加记录。失焦、暂停或设备移除时，按原协议标志补齐 KeyUp 并清除客户端临时修饰状态；不记录字符、输入历史，也不安装 Mac 键盘监听器。

Mac 的接收队列有背压，帧长度最多 4096 字节，超过 350 毫秒的旧输入不补执行。Dock 结束帧的有限重发带代次检查，不取消随后开始的新手势。触控扩展不模拟修饰键按下，因此不会用 Option 或 Command 当控制信号。

## 构建、安装与启用

Android 沿用现有签名和最小 SDK：

```sh
python3 tools/fetch_android.py
python3 tools/build_android.py system-keyboard
python3 tools/build_android.py moonlight-input
python3 tools/deploy_android.py --serial '<设备地址>' --module system-keyboard
python3 tools/deploy_android.py --serial '<设备地址>' --module moonlight-input
```

首次安装新模块要在 Vector 启用 `local.pad.moonlight`，作用域只添加 `com.limelight/0`。不能回写整个 Vector 数据库。系统模块更新后重启平板；Moonlight 模块更新只需重启 Moonlight。安装必须禁用 incremental 和 streaming。

```sh
python3 tools/build_mac.py
python3 tools/install_mac.py
python3 tools/start_sunshine.py
```

本机固定签名身份保存在被忽略的 `.local/mac-signing-identity`，也可通过 `PAD_MAC_SIGN_IDENTITY` 指定。签名材料来自现有钥匙串，不提交仓库；没有身份配置时构建回退为临时签名，此时更新可能重新触发 TCC 授权。

启用新版需要用户在系统设置完成：

1. Sunshine 的“录屏与系统录音”和“辅助功能”权限。
2. 安装后的 `~/Applications/Pad Mac Helper.app` 的“辅助功能”权限。新签名登记必须对应安装后的最终 App。
3. 重启 Sunshine 与 Helper，确认 `helper-status.json` 中权限为 true，并在 Moonlight 进入 Desktop 后检查 `moonlight_connection=connected`。

本次电脑控制工具明确禁止操作 `LocalAuthenticationRemoteService`，无法替用户完成该系统认证。没有改 TCC 数据库、关闭 SIP 或使用旧程序身份绕过授权。授权完成前运行原 Helper；新版已留在 `build/Pad Mac Helper.app`。

## Sunshine 配置与网络

使用标准目录 `~/.config/sunshine`，配对、证书、主机身份和 Web 管理凭据持久保存。只提供 Desktop 入口，没有分辨率修改命令。UPnP 关闭，Web 管理界面限本机访问。

发布版本和下载摘要固定在 `tools/streaming-downloads.json`，已与 GitHub 官方 release 的摘要比对。管理凭据保存在本机忽略目录，不使用 Mac 或平板的登录密码。

当前按实际局域网地址配对，Moonlight 保留自己的主机发现能力。ADB 仍按设备序列号检查身份并支持配置地址或域名。Tailscale 可以作为后续传输承载，但当前未安装或启用 VPN，也未宣称固定端口转发已经适配 VPN 入站。

## 本次验证记录

通过：Android 状态机测试、原蓝牙 Fn 238 项检查、布局/修饰键/按住 Fn 检查、Mac 协议白名单和手势字段检查、编译与签名验证、完整 APK 安装和 Vector 实际加载、Moonlight 到 Sunshine 的加密配对。

未完成：新 Helper 获得系统权限后的端到端投递、真实滚动手感、原生动画半程保持和反向、浏览器历史导航/通知清除、全部实体 Fn 位置及 Bluetooth/Pogo 回归。键盘需回归字母、方向键、Backspace 长按，以及 Option＋↑、Control＋Option＋Command＋空格。之前的 Mac 探针只核对权限/事件字段和系统元数据，不能据此声称动画已实际生效。

切屏测试需要被控屏幕上至少两个桌面；本次没有创建、删除或移动用户的桌面。Sunshine 的 Mac 绝对鼠标定位针对主显示器，因此后续实测还要确认指针和所选虚拟屏幕对应。

完成调试时移除临时测试窗口、截图、安装下载包和编译中间文件，恢复临时亮屏超时。正常模式不记录输入文字；Helper 只覆盖状态文件，Sunshine 使用低日志量配置。
