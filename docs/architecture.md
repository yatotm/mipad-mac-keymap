# 输入链路

本文描述已验收的 UU 基线路径。Moonlight 的连续手势实现、本地光标与待验收项目另见 [Moonlight 输入适配](moonlight-input.md)。

平板端两个 Vector 作用域职责分开：系统模块在小米快捷键拦截之前选择路由，UU 模块处理远控消息和触控板动作。Mac 端只有一个 `Pad Mac Helper.app` 和一个 launchd 项。

## Fn 与普通键

UU 前台的物理布局为：原 Ctrl → 按住式 Fn；原语音 → Control；四叶草 → Option；Alt → Command。Fn 本身不发往 Mac。

本次首先定位到部署问题：增量安装把 APK 放在开机早期尚未就绪的 incremental-fs，Vector 因无法打开 APK 而漏加载系统键盘模块。完整安装后已核对 f2fs、注册路径和 system_server 加载记录。

1.6 补齐了标准 Linux F1–F12 扫描码入口，与原有多媒体扫描码统一路由；这不是本次增量安装故障的根因。按下、重复、松开用同一条路由，新增标准键路径在 UU 外保留原处理。最终行为以实体测试为准，不由查表或安装成功推断。

1.7 将两个连接接口的 Fn 状态合并。实测按住 Fn 操作顶排时，系统先插入 Ctrl Up，约 1 毫秒后才发送顶排 Down，顶排 Up 后再恢复 Ctrl Down。状态机仅桥接 8 毫秒内的这段释放帧；失焦、设备消失立即取消，孤立或重复的 Up 不延长窗口，不恢复点按式 Fn。

普通长按放行系统已有的 repeatCount；不合成定时连发，不提前发送 KeyUp。修饰键、静音切换、睡眠等不重复执行。

## 当前路径（系统 1.8 / UU 0.11.2）

0.11.0 的消息传输和状态检查通过，但实际滚动、导航和 Fn 大量失效。现已撤回没有通过验收的输入投递，其他功能保留恢复路径，并单独修正 Fn：

| 功能 | 当前路径 |
| --- | --- |
| 普通键盘、鼠标、三指停留拖拽 | UU 原输入消息 |
| 双指上下滚动 | 原 nf.c.w/x 滚动消息，经 UU 发给 Mac；保留 0.25 倍率、惯性强度 4 |
| 双指左右 | 经 UU 发送完整的 Command＋方向键按下/松开，不补 Control 或 Option |
| 三指左右切屏、下滑应用窗口 | 恢复经 UU 发送的完整 Control＋方向键序列 |
| 三指上滑 | ADB 固定动作，Mac 打开 Mission Control |
| 三指捏合 | ADB 固定动作，Mac 打开 Launchpad |
| Fn 顶排功能 | 系统识别/还原真实用途，受保护的本机广播交给 UU，再由 ADB 固定动作调用 Mac 系统接口 |

横向与三指方向仍是兼容操作，不代表完整的原生触控板。浏览器前进后退、Launchpad 翻页需要用户回归；通知清除不因恢复快捷操作而自动获得支持。以后继续原生输入实验时，须先证明事件到达远控目标窗口/屏幕，不能只检查发送计数。

Mac 没有键盘拦截器，Control＋Option＋Command＋空格留给用户原功能。滚轮监听仍为 UU 连续滚动补充 Mos 可识别的标记，Mos 配置不变。

## 蓝牙 Fn 的实测根因与修正

实测按住原 Ctrl 时：

- 音量减从普通 Consumer `0xEA` 变成键盘修饰位图 `0xEA`，即左 Shift、左 Meta、右 Shift、右 Alt、右 Meta。
- 亮度减变成修饰位图 `0x70`，即右 Control、右 Shift、右 Alt。
- 松开功能键后恢复为 `0x01`（原左 Ctrl）。单按音量减则仍正常上报 Consumer `0xEA` / Linux 扫描码 114，并映射成 F11。

因此，这两个 Fn 组合在普通按键通道中根本不是音量/亮度键。只改 UU 接收入口无法修复。1.8 在指定蓝牙键盘、UU 前台、单独按下原 Ctrl 的范围内还原八种标准 Consumer 用途码；假修饰键的按下和松开在进入安卓/UU 前被消费。其它顶排使用已有位置表识别。

同一报告中的空位图保留所有权，直到不同时间戳的新报告开始，避免先释放左 Ctrl 时过早结束 Fn。报告合并后才提交动作，并检查最后变化方向。音量减释放时的中间位图可能等于 `0xE9`，不能误触发音量加。失焦、取消和设备消失终止功能重复；仅亮度/音量允许按住重复，切换型动作只执行一次。

系统通过限定 UU 包名的本机广播发送固定编号。接收器要求 `INJECT_EVENTS` 发送权限，并检查发送者 UID 1000、协议版本、500 毫秒时效和远控页面前台状态。正常 Fn 和错误报告还原后的 Fn 共用这条控制入口，不再依赖安卓将媒体键投递到自定义输入视图。

此修正属于 Android 系统输入层适配，没有修改键盘固件。普通多修饰键组合、Fn 与额外修饰键混用，以及每个顶排位置的支持边界以实际验证记录为准；不能把两种连接模式或全部 F1–F12 都推断为逐项验收完成。

## 保留的独立控制通道

Mac 主动经已配对的 ADB TLS 读取 UU 私有 FIFO（0600），平板不需要知道 Mac 的地址。通道目前承载固定功能动作和状态；原生像素滚动/部分 Mac 键盘导航代码留作诊断，从 0.11.1 起不在日常手势路径调用它们。

设备身份与地址分开配置：优先已连接且序列号匹配的设备，再试上次地址和 `_adb-tls-connect._tcp` 自动发现。跨网段不保证 mDNS 可见或网络可达，支持手动配置地址和域名。

协议为一行一个 JSON，最多 4096 字节。FIFO 非阻塞写入，单读者锁防止并发读取分食消息；心跳不落盘，过期输入不补执行。实际发现 Helper 被强制终止后 ADB 子进程可能残留；安装流程现在清理本插件的精确读取命令，不重启全局 ADB server。不能将这项修补说成强制杀进程后所有情况都已自动恢复。

CoreAudio 负责音量/静音；已有 BetterDisplay 负责亮度；系统应用入口负责 Launchpad 和 Mission Control。Fn 的目标仍为 Mac。1.8 的系统解码与直达消息已通过用户 Fn 测试。ScreenActivity 的旧媒体键入口仅保留兼容作用。

## 后续网络和原生输入

`connection.json` 与输入处理分开，支持域名/IPv4/IPv6 地址格式，未来可用 Tailscale 地址或 MagicDNS。**固定 23333 模块目前仅转发 Wi-Fi IPv4 地址**，VPN 入站路由和端口转发还需适配。本版不改变 VPN 或监听范围。

完整虚拟多点 HID 设备尚未实现。Moonlight 分支已实现按位移更新、带开始和结束阶段的系统手势通道，用户已确认主要手势正常；延迟、通知方向和横向灵敏度的后续调整单独验收。现成 Karabiner DriverKit 项目主要提供键盘/鼠标。实验与已验证路径分开，按单项真实行为验收后再切换。

## 数据与诊断

平板配置位于 UU 私有目录的 `pad_uu_touchpad.json`。`trace=false` 为正常状态。恢复期最多记录八次功能键按下；系统发送最多记录 16 次，UU 接收最多记录 12 次，不记录文字，也不追加自建日志文件。系统 Fn 诊断由临时属性 `debug.pad.uu.trace` 控制，每次系统模块加载最多 80 条，默认关闭，重启不保留。

Mac 只覆盖 `~/Library/Application Support/Pad UU/helper-status.json` 和 `last-action.json`，以及必要的静音恢复值。连接配置单独保存，不追加日志。固定动作计数和滚动计数不记录文字，写盘合并并避开输入回调。源码、测试和脚本进入 Git；设备地址、签名密钥、备份和临时采样留在被忽略的 `.local`。

## 参考

- [Android 原生无线 ADB](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md)：TLS、配对和 mDNS。
- [Apple CGScrollPhase](https://developer.apple.com/documentation/coregraphics/cgscrollphase)：原生滚动阶段。
- [Chromium 历史导航实现](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/chrome/browser/renderer_host/chrome_render_widget_host_view_mac_history_swiper.mm)：触摸事件与 Magic Mouse 滚动路径。
- [Mos 4.2.1 ScrollEvent](https://github.com/Caldis/Mos/blob/4.2.1/Mos/ScrollCore/ScrollEvent.swift)：滚动阶段和计数标记的分类。
- [Karabiner DriverKit VirtualHIDDevice](https://github.com/pqrs-org/Karabiner-DriverKit-VirtualHIDDevice)：虚拟键盘/鼠标与 DriverKit 部署要求。
- [Tailscale MagicDNS](https://tailscale.com/docs/features/magicdns)：未来连接地址的域名解析。

- [Linux 官方 HID Consumer 映射](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-input.c)：`0x70`/`0x6F` 亮度、`0xEA`/`0xE9` 音量及媒体用途码。
- [Android BroadcastReceiver.getSentFromUid](https://developer.android.com/reference/android/content/BroadcastReceiver#getSentFromUid()) 和 [BroadcastOptions.setShareIdentityEnabled](https://developer.android.com/reference/android/app/BroadcastOptions#setShareIdentityEnabled(boolean))：向接收方提供真实发送者身份。

## Moonlight 指针与显示器

Moonlight 模块直接读取指定小米触控板的原始坐标并在平板绘制光标，普通指针和拖拽通过同一 ADB 输入通道交给 Helper。位置帧带视频参考尺寸；Mac 用当前主显示器的逻辑边界扣除黑边并定位，显示配置变化时重新发布尺寸。普通键盘仍通过 Moonlight/Sunshine。此路由没有新增后台 App，也不改变 UU 当前的指针路径。
