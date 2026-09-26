# Moonlight Android / Sunshine 源码适配审查

审查日期：2026-09-26。结论：适合作为长期定制底座，原版不能直接实现完整 Mac 触控板体验。最关键的未实现部分是 macOS 原生多点触控后端，而非是否有一条网络通道。

本次实际浅克隆了两个仓库，并按其 gitlink 取得相关依赖。没有编译、安装、改动现用 UU/输入插件、申请新权限或实测串流。因此，下列“已有/缺失”指固定版本的代码能力，“可实现/难度”是工程判断，不是设备验收结果。

## 审查版本

| 组件 | 固定提交 |
| --- | --- |
| Moonlight Android v12.2 | `b48494cb96bff23d8886c4775cc4f39a1075495d` |
| Sunshine master | `e1e6700bbb47c21512b073ab91156db6e30b8a52` |
| Sunshine 最新正式版交叉核对 | `v2026.914.233613` / `63d35f702ee9e362e43263742981836ec0710384` |
| Sunshine 的 libvirtualhid | `53e1a949fc0784af716b782ddfa6c647cafd1f05` |
| Sunshine 的 libdisplaydevice | `6e9722f89103320c948dc1199066c9e17a69e88a` |
| Moonlight Android 的 moonlight-common-c | `874ac9548f1bd6f095ef2b435c42cdde460e7821` |
| Sunshine 的 moonlight-common-c | `62e066388f1a1b133e0bee947b9a374311a3354b` |

Sunshine 正式版与所查 master 使用相同的上述三个输入/显示依赖提交；macOS `input.cpp` 同样委托给 libvirtualhid。不能只依据函数声明或 README 中“支持触控”的总述判断平台能力。

源码保存在项目忽略目录 `.local/research/moonlight-sunshine/`，Sunshine 对无关的文档站模板使用稀疏检出，保留全部本次研究涉及的源码。没有拉取完整历史或所有子模块，没有生成构建缓存。提交快照另存于该目录的 `review-manifest.json`。

## 需求判断

| 需求 | 固定版本原版代码 | 定制判断 |
| --- | --- | --- |
| 普通键盘、左右修饰键、长按 | 有键码映射、按下/松开、服务端重复和断连释放机制 | 适合继续完善；仍需实际组合键回归 |
| 小米 Fn 报告兼容 | 不包含本设备 Consumer→修饰位图修正 | 复用已验证的系统层解码，移植作用域/接收入口 |
| Fn 亮度、音量、媒体 | Mac 后端多个媒体虚拟键映射为不支持；不是现成完整功能层 | 可补固定功能动作或相应系统键实现 |
| 双指纵横滚动 | 有高精度滚轮量值；协议没有滚动阶段字段，Mac 滚轮路径也未补阶段/惯性生命周期 | 可定制，但不自动等于 Mac 触控板 |
| 三/四指跟手切桌面、半程停住 | Mac trackpad 后端明确未实现 | 核心研发缺口，先验证 Mac 本机原型 |
| 原生像素画面、码率/帧率可控 | 有原生分辨率选项、按显示器像素捕获和编码配置 | 适合做清晰度对照，但须验证实际编码/解码尺寸 |
| 虚拟屏、主屏和关闭内屏 | 可捕获现有显示器并调整已有模式；Mac 主屏/拓扑写入未实现 | 继续利用现有显示工具并与会话联动 |

## 输入链路的关键证据

### 1. 外接触控板默认走鼠标路径

Moonlight Android 的 `Game.handleMotionEvent()` 将 `SOURCE_TOUCHPAD` 放入鼠标分支；优先发送相对鼠标位移，滚动读取 `AXIS_VSCROLL`、`AXIS_HSCROLL` 后转换为高精度滚轮量值。这里没有把整组手指的位置与接触生命周期传成 Mac 系统触控手势。

来源：[Game.java 鼠标/触控板分支](https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/Game.java#L1805-L1901)。

代码中另有多指 `sendTouchEventForPointer()`，但其用途是直接触屏输入。在同一版本的触屏分支中，调用入口被注释，并有取消触点/三指呼出键盘的 TODO。这不等于外接触控板已支持原生多指透传。

来源：[Game.java 触屏入口状态](https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/Game.java#L2020-L2042)。

### 2. 协议有触点消息，但没有现成的 Mac 触控板通路

`SS_TOUCH_PACKET` 包含触点 ID、事件类型、坐标、压力和接触面积。`LiSendTouchEvent()` 的坐标按视频区域归一化，并受主机 `LI_FF_PEN_TOUCH_EVENTS` 能力协商控制。这是触屏语义；手柄触控板消息又是另一类输入。不能把它们直接当成 Mac 的间接多点触控板。

纵横滚轮包只包含滚动量，没有 begin/change/end/momentum 等阶段。若要保留触控生命周期，需设计兼容的扩展，而非只修改一个灵敏度参数。

来源：[Input.h 消息结构](https://github.com/moonlight-stream/moonlight-common-c/blob/874ac9548f1bd6f095ef2b435c42cdde460e7821/src/Input.h#L111-L138)、[Limelight.h 触点语义](https://github.com/moonlight-stream/moonlight-common-c/blob/874ac9548f1bd6f095ef2b435c42cdde460e7821/src/Limelight.h#L615-L660)。

### 3. Mac 后端仍是 CoreGraphics 键鼠注入

Sunshine 查询 libvirtualhid 的平台能力。该库的 Mac 后端只开启 keyboard/mouse；`create_trackpad()`、`create_touchscreen()`、`create_pen_tablet()` 都直接返回未实现。

`submit_scroll()` 创建像素滚轮，设置 continuous 标记后投递；这里没有滚动阶段、惯性阶段，更没有驱动 Spaces 连续进度的实现。单看 `libvirtualhid` 的名字会误判：其 Mac 文档明确说明当前不是虚拟 HID 设备实现。

来源：[macos_backend.cpp 平台能力及空实现](https://github.com/LizardByte/libvirtualhid/blob/53e1a949fc0784af716b782ddfa6c647cafd1f05/src/platform/macos/macos_backend.cpp#L777-L830)、[滚轮投递](https://github.com/LizardByte/libvirtualhid/blob/53e1a949fc0784af716b782ddfa6c647cafd1f05/src/platform/macos/macos_backend.cpp#L747-L764)、[Mac 平台说明](https://github.com/LizardByte/libvirtualhid/blob/53e1a949fc0784af716b782ddfa6c647cafd1f05/docs/platform-support.md#L430-L463)。

因此，“在 Windows/Linux 上有触摸/虚拟 HID”不能推导为“在 Mac 上能原生四指跟手切屏”。

### 4. 普通键盘机制值得复用，但 Fn 仍要补做

客户端将左右 Meta 转为 0x5B/0x5C，Mac 后端再转为左右 Command；普通 Alt 对应 Option。旧入门文档中笼统的 Command 限制与这组代码不一致，应按实际版本和客户端捕获情况判断。

Moonlight 屏蔽 Android 重复 KeyDown，由 Sunshine 的 `repeat_key()` 处理长按。Sunshine 还在断连时释放已按住的键。这些机制比继续通过隐藏快捷键承载控制消息更便于维护，但不能由源码直接宣称没有卡键或重复问题。

来源：[客户端键码](https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/binding/input/KeyboardTranslator.java#L163-L239)、[Mac 键码表](https://github.com/LizardByte/libvirtualhid/blob/53e1a949fc0784af716b782ddfa6c647cafd1f05/src/platform/macos/macos_backend.cpp#L122-L187)、[服务端重复](https://github.com/LizardByte/Sunshine/blob/e1e6700bbb47c21512b073ab91156db6e30b8a52/src/input.cpp#L1131-L1207)、[断连释放](https://github.com/LizardByte/Sunshine/blob/e1e6700bbb47c21512b073ab91156db6e30b8a52/src/input.cpp#L2161-L2169)。

Mac 键码表中音量、静音和媒体虚拟键有多项为 `-1`。小米 Fn 的异常又发生在应用收到按键之前，因此安装原版不会自动继承本项目 1.8 的修正。现有系统模块还限定在 UU 作用域，迁移时必须明确添加 Moonlight 的作用域与功能消息接收。

另外，Moonlight 的新标准键盘捕获仅在 Android API 36.1 及以上启用，不能直接解决当前 Android 15 的系统快捷键拦截。Root 构建也不是自动补齐触控的开关：捕获选择器仍优先使用 Android O+ 原生鼠标捕获。

来源：[键盘捕获版本门槛](https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/Game.java#L675-L700)、[捕获提供者选择](https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/binding/input/capture/InputCaptureManager.java#L11-L34)。

## 画面与显示器

Moonlight 会添加设备原生分辨率选项，并独立配置流分辨率、帧率、码率与编解码能力。Sunshine 的 Mac 捕获初始化读取 `CGDisplayModeGetPixelWidth/Height`，所以值得以现有 2880×1800 HiDPI 虚拟屏作为对照基准。最终仍要确认捕获尺寸、编码尺寸、解码尺寸、显示缩放四处是否一致。

来源：[原生分辨率选项](https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/preferences/StreamSettings.java#L146-L179)、[Mac 像素尺寸捕获](https://github.com/LizardByte/Sunshine/blob/e1e6700bbb47c21512b073ab91156db6e30b8a52/src/platform/macos/av_video.m#L10-L23)。

不能承诺文字无损：所查 VideoToolbox 分支的 SDR/HDR 4:4:4 像素格式为 `AV_PIX_FMT_NONE`，Android 客户端协商也未声明 4:4:4 格式。分辨率和缩放仍是本设备首要检查项，不能把既有模糊问题简单改称色度采样问题。

来源：[VideoToolbox 格式](https://github.com/LizardByte/Sunshine/blob/e1e6700bbb47c21512b073ab91156db6e30b8a52/src/video.cpp#L1375-L1385)、[Android 协商格式](https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/Game.java#L412-L424)。

显示管理并非“Mac 完全不能自动改分辨率”：libdisplaydevice 能在 VerifyOnly 模式调整已有活动屏幕的可用分辨率/刷新率。但设置主屏和拓扑方法返回 false；不能因此承诺内置关闭内屏、创建虚拟屏或自动切主屏。现有显示工具仍有用途，也可结合 Sunshine 会话准备/恢复命令。

来源：[Mac 显示管理支持范围](https://github.com/LizardByte/libdisplaydevice/blob/6e9722f89103320c948dc1199066c9e17a69e88a/src/macos/include/display_device/macos/settings_manager.h#L17-L23)、[主屏写入](https://github.com/LizardByte/libdisplaydevice/blob/6e9722f89103320c948dc1199066c9e17a69e88a/src/macos/mac_display_device_primary.cpp#L15-L18)、[拓扑写入](https://github.com/LizardByte/libdisplaydevice/blob/6e9722f89103320c948dc1199066c9e17a69e88a/src/macos/mac_display_device_topology.cpp#L81-L84)。

Mac 鼠标绝对坐标后端的文档当前限定主显示器。副屏捕获与指针定位必须联合验证，不能只看到虚拟屏画面就算成功。

## 建议的开发顺序

1. **原版建立视频基准。** 使用现有虚拟屏，对比静止细字、移动画面、实际流尺寸、延迟及普通键鼠；先不移除已经工作的 UU。
2. **先验证 Mac 原生手势后端。** 在本机独立实验中证明切屏能停在半程、反向、取消，且目标屏幕正确。`create_trackpad()` 的空实现正是关键缺口；需要研究合适的系统手势注入或虚拟多点设备后端，不能承诺只补几行 CGEvent 就够。
3. **再做客户端/协议集成。** 保留触点身份、位置、按下/移动/抬起、会话/帧顺序和取消语义。不要在 Android 端先压成快捷键，也不要把“手指停住没新位移”当成结束。
4. **迁移键盘和 Fn。** 复用小米报告解码；让明确功能动作走可协商的输入扩展或支持的系统键。接入 Sunshine 的 Mac 后端后，有机会收敛掉额外 ADB 和独立 Helper，但不是当前原版已有的能力。
5. **逐项验收再切换。** 包括按住/释放、多修饰组合、断线释放、Fn 后正常输入、三指导航/拖拽区分、四指实际上报、正确显示器目标。每项失败都留在实验分支，不替换稳定路径。

判断：这条路线的优势是三段源码都可控，能做真正的客户端与主机配合；现阶段不应把它描述成已经解决 Mac 原生触控板问题的成品。
