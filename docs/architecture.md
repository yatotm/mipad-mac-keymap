# 输入链路

平板端两个 Vector 作用域职责分开：系统模块在小米快捷键拦截之前选择路由，UU 模块处理远控消息和触控板动作。Mac 端只有一个 `Pad Mac Helper.app` 和一个 launchd 项。

## Fn 与普通键

UU 前台的物理布局为：原 Ctrl → 按住式 Fn；原语音 → Control；四叶草 → Option；Alt → Command。Fn 本身不发往 Mac。

本次首先定位到部署问题：增量安装把 APK 放在开机早期尚未就绪的 incremental-fs，Vector 因无法打开 APK 而漏加载系统键盘模块。完整安装后已核对 f2fs、注册路径和 system_server 加载记录。

1.6 补齐了标准 Linux F1–F12 扫描码入口，与原有多媒体扫描码统一路由；这不是本次增量安装故障的根因。按下、重复、松开用同一条路由，新增标准键路径在 UU 外保留原处理。最终行为以实体测试为准，不由查表或安装成功推断。

1.7 将两个连接接口的 Fn 状态合并。实测按住 Fn 操作顶排时，系统先插入 Ctrl Up，约 1 毫秒后才发送顶排 Down，顶排 Up 后再恢复 Ctrl Down。状态机仅桥接 8 毫秒内的这段释放帧；失焦、设备消失立即取消，孤立或重复的 Up 不延长窗口，不恢复点按式 Fn。

普通长按放行系统已有的 repeatCount；不合成定时连发，不提前发送 KeyUp。修饰键、静音切换、睡眠等不重复执行。

## 统一 Mac 控制通道

Fn 功能和手势复用 UU 键盘消息。约定控制组合是 **Control＋Option＋Command** 加以下键：

| 约定键 | 动作 |
| --- | --- |
| F1 / F2 | 当前 Mac 显示器亮度减 / 加 |
| F3 | 麦克风静音切换 |
| F4 | 打开系统截屏工具 |
| F5 | 打开 Siri |
| F6 | Mac 睡眠 |
| F7 / F8 / F9 | 上一曲 / 播放暂停 / 下一曲 |
| F10 / F11 / F12 | 输出静音 / 音量减 / 音量加 |
| 左 / 右方向键 | 一次后退 / 前进 |
| 空格 | 打开 Launchpad |

平板只补上尚未按住的修饰键，先发送动作键 Down，随后释放自己补上的修饰键，最后发送动作键 Up。Mac 在 Down 时登记动作，只有 Up 到达且控制修饰位已清空才执行，超过两秒或缺少完整配对则取消；这样不会带着 Option 打开 Launchpad 的图标编辑模式。Mac 只消费来自 `/Applications/UURemote.app/Contents/Helpers/UURemoteServer` 的上述组合，普通键盘事件直接通过，不记录输入文字。旧的按需 `Controls.app` 和 LaunchApp 控制协议不再使用。

统一 App 在 session 阶段识别控制键，在 annotated-session 阶段适配滚动。两个事件入口属于同一个进程、同一套权限。耗时亮度调用在工作队列执行，重复动作的待执行队列有界。

音量和静音使用 CoreAudio；亮度使用已有 BetterDisplay，虚拟屏对应软件亮度。上一曲等使用原生媒体事件。功能只在用户实际触发时执行，不自动测试睡眠。

## 双指与三指

- 纵向保持 0.25 倍率、强度 4 的速度相关惯性。
- 横向方向明确且位移达到 2 毫米时提交一次动作，同次落指继续移动不追加翻页，没有横向惯性；阈值用于过滤落指抖动。
- 浏览器的历史导航使用 Command＋方向键，Launchpad 同样提交整页方向操作。其它区域退回一次短促横向滚动，明确发送结束阶段，不等待静止超时。
- 通知区域保留一次短促横滑路径，最终清除效果仍需实机确认。
- 三指直接滑动导航；停留约 300 毫秒再移动拖拽；收拢调用统一 App 打开 Launchpad。

导航不是对原生多点触控协议的仿真，系统自身的切换动画仍由 macOS 控制。

## 数据与诊断

平板配置位于 UU 私有目录的 `pad_uu_touchpad.json`。`trace=false` 为正常状态。系统 Fn 诊断由临时属性 `debug.pad.uu.trace` 控制，每次系统模块加载最多 80 条，默认关闭，重启不保留。

Mac 只覆盖 `~/Library/Application Support/Pad UU/helper-status.json` 和 `last-action.json`，以及必要的静音恢复值；没有追加日志。状态文件包含固定动作的接收、执行计数，最多十五种，不记录普通按键；写盘异步合并，避开输入回调。源码、测试和脚本进入 Git；设备地址、签名密钥、备份和采样留在被忽略的 `.local`。

## 参考

- [Chrome 官方快捷键说明](https://support.google.com/chrome/answer/157179)：Mac 历史前进、后退的 Command＋方向键。
- [Apple NSWorkspace.OpenConfiguration](https://developer.apple.com/documentation/appkit/nsworkspace/openconfiguration)：启动参数针对新的应用实例，不把重新启动常驻 App 当作命令传输通道。
- [Apple CGScrollPhase](https://developer.apple.com/documentation/coregraphics/cgscrollphase)：滚动结束阶段。
- [Android 原生无线 ADB](https://android.googlesource.com/platform/packages/modules/adb/+/HEAD/docs/dev/adb_wifi.md)：固定 23333 转发仍使用原生 TLS 认证。
