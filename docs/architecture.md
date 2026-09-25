# 输入链路

平板端两个 Vector 作用域职责分开：系统模块在小米快捷键拦截之前选择路由，UU 模块处理远控消息和触控板动作。Mac 端只有一个 `Pad Mac Helper.app` 和一个 launchd 项。

## Fn 与普通键

UU 前台的物理布局为：原 Ctrl → 按住式 Fn；原语音 → Control；四叶草 → Option；Alt → Command。Fn 本身不发往 Mac。

本次首先定位到部署问题：增量安装把 APK 放在开机早期尚未就绪的 incremental-fs，Vector 因无法打开 APK 而漏加载系统键盘模块。完整安装后已核对 f2fs、注册路径和 system_server 加载记录。

1.6 补齐了标准 Linux F1–F12 扫描码入口，与原有多媒体扫描码统一路由；这不是本次增量安装故障的根因。按下、重复、松开用同一条路由，新增标准键路径在 UU 外保留原处理。最终行为以实体测试为准，不由查表或安装成功推断。

1.7 将两个连接接口的 Fn 状态合并。实测按住 Fn 操作顶排时，系统先插入 Ctrl Up，约 1 毫秒后才发送顶排 Down，顶排 Up 后再恢复 Ctrl Down。状态机仅桥接 8 毫秒内的这段释放帧；失焦、设备消失立即取消，孤立或重复的 Up 不延长窗口，不恢复点按式 Fn。

普通长按放行系统已有的 repeatCount；不合成定时连发，不提前发送 KeyUp。修饰键、静音切换、睡眠等不重复执行。

## 独立输入通道

0.11.0 / Helper 0.3.0 起，滚动、Fn 功能和手势动作不再伪装成键盘组合。Mac 不安装键盘拦截器，Control＋Option＋Command＋空格等用户组合完整留给 UU 和原应用。普通打字、鼠标移动、点击、拖拽仍由 UU 传输。

```text
实体键盘/触控板
  → 系统按键路由 / UU 作用域插件
  → UU 私有 FIFO（0600，零字节文件）
  → 已配对的 ADB TLS 连接
  → 一个 Pad Mac Helper
      ├─ 像素滚动 + 开始/结束/惯性阶段
      └─ 固定功能动作
```

Mac 主动建立读取连接，平板不需要知道 Mac 的 IP。优先寻找已连接且 `ro.serialno` 匹配的设备，再尝试配置地址，最后尝试该设备的 `_adb-tls-connect._tcp` 自动发现。发现成功会记住新地址。设备身份与网络地址分开保存，不依赖写死的网段或主机地址；跨网段不保证 mDNS 可见，也不保证网络允许直连。

`connection.json` 独立于输入协议，支持主机名、IPv4 和 IPv6 地址格式。未来可以配置 Tailscale 地址或 MagicDNS 名称。**当前固定 23333 模块只转发 Wi-Fi 接口的 IPv4 地址**；接入 VPN 时还需验证 Android VPN 入站路由与原生 TLS 监听，并有针对性地配置转发，不能只改成 VPN 地址就声称已支持。本版不安装 VPN、不扩大当前监听范围。

输入协议为一行一个 JSON，版本 `v=1`。只接受固定动作、像素滚动、修饰状态、心跳和重置；没有任意命令或文字输入。普通键盘流以后若迁移，应新增独立消息类型和按下/松开所有权管理，不复用控制动作。

- 每行最多 4096 字节，FIFO 非阻塞写入；背压或断开时丢弃，不堆积历史动作。
- 单读者文件锁防止两个重连进程分食 FIFO。Android mksh 必须显式传递锁文件描述符，实机已验证。
- 1 秒心跳不落盘；Mac 4 秒无数据即结束读取并重试。连接身份重新核对，只有握手后的新鲜消息可执行，超过 350 毫秒的积压消息丢弃。
- 退出 UU 远控或断开通道时终止滚动，取消待执行动作。普通键盘继续由 UU 处理。独立通道不可用时，手势与 Fn 不回退为隐蔽的键盘组合。

音量和静音使用 CoreAudio；亮度调用已有 BetterDisplay，虚拟屏对应软件亮度。上一曲等使用媒体事件；Launchpad 通过系统应用入口打开。慢速动作在工作队列执行，队列有界且过期取消。正常操作不自动测试睡眠。

## 双指与三指

- 纵向保留 0.25 倍率、强度 4 的速度相关惯性，直接生成像素滚动事件，标明接触阶段和惯性阶段。事件使用独立来源，修饰位来自真实键盘状态，不继承 UU 缓存的 Option/Command。
- Mos 保持原配置。带滚动阶段和计数标记的事件由 Mos 识别为触控滚动，避免再次平滑和加速。
- 横向位移达到 2 毫米且方向明确后，发送一次 80 毫秒的原生横向滚动序列，含 began、changed、ended；没有键盘组合，没有横向惯性。
- 浏览器、Launchpad、通知是否接受这些原生滚动事件需分别实测。Chromium 会区分 NSTouch 和无 NSTouch 的 Magic Mouse 路径，不能只发方向键或一个没有阶段的滚轮事件就称为原生触控。
- 三指直接滑动导航；停留约 300 毫秒后移动拖拽；收拢调用统一 App 打开 Launchpad。
- 三指上滑打开 Mission Control。左右切换 Spaces 和下滑显示应用窗口暂用 Mac 端的系统快捷入口，事件完整配对且跳过真实修饰键被按住的情况。

本方案不是一个虚拟多点 HID 设备。像素滚动与阶段是原生事件，但部分系统导航仍是等效动作，并不具备真实触控板全部跟手动画。现成 Karabiner DriverKit 项目主要提供键盘/鼠标；完整多点设备仍需另行研究驱动、签名与系统支持，不为此降低系统安全设置。

## 数据与诊断

平板配置位于 UU 私有目录的 `pad_uu_touchpad.json`。`trace=false` 为正常状态。系统 Fn 诊断由临时属性 `debug.pad.uu.trace` 控制，每次系统模块加载最多 80 条，默认关闭，重启不保留。

Mac 只覆盖 `~/Library/Application Support/Pad UU/helper-status.json` 和 `last-action.json`，以及必要的静音恢复值。连接配置单独保存，不追加日志。固定动作计数和滚动计数不记录文字，写盘合并并避开输入回调。源码、测试和脚本进入 Git；设备地址、签名密钥、备份和临时采样留在被忽略的 `.local`。

## 参考

- [Android 原生无线 ADB](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md)：TLS、配对和 mDNS。
- [Apple CGScrollPhase](https://developer.apple.com/documentation/coregraphics/cgscrollphase)：原生滚动阶段。
- [Chromium 历史导航实现](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/chrome/browser/renderer_host/chrome_render_widget_host_view_mac_history_swiper.mm)：触摸事件与 Magic Mouse 滚动路径。
- [Mos 4.2.1 ScrollEvent](https://github.com/Caldis/Mos/blob/4.2.1/Mos/ScrollCore/ScrollEvent.swift)：滚动阶段和计数标记的分类。
- [Karabiner DriverKit VirtualHIDDevice](https://github.com/pqrs-org/Karabiner-DriverKit-VirtualHIDDevice)：虚拟键盘/鼠标与 DriverKit 部署要求。
- [Tailscale MagicDNS](https://tailscale.com/docs/features/magicdns)：未来连接地址的域名解析。
