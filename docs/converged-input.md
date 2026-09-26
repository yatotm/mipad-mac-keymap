# 内置输入链路（2026-09-26）

## 运行组成

| 位置 | 当前实现 | 用途 |
|---|---|---|
| Android | MiPad Moonlight 12.2-pad.1，`local.pad.moonlight.client` | 直接处理触控板原始事件，在本地绘制光标，经串流发送增强输入 |
| Android 系统 | Vector 键盘 1.10 | 仅在指定远控窗口前台重映射键盘，受保护的本机广播传递 Fn 功能 |
| Mac | Sunshine 2026.914.233613，PadCursorRevision 2 / PadInputProtocol 1 | 视频捕获、普通键盘与增强原生输入由同一个进程处理 |

没有独立的增强输入端口、主机发现服务或 Mac Helper。系统键盘模块仍然必要：普通 Android 应用无法可靠阻止 HyperOS 先消费系统快捷键。ADB 仅用于安装和开发，断开它不应影响输入。原官方客户端的数据和已验收旧包保留，可回退。

## 传输与状态

- 沿用 Moonlight 已配对的控制连接和控制流加密。`PAD1` 消息放入现有有界输入队列，采用可靠鼠标通道；没有经公共互联网暴露 ADB。
- Sunshine 通过功能位 `0x10000` 协商扩展。每条输入最多 1024 字节；状态反馈最多 512 字节、最多每秒 4 次。不存在任意 shell/文件操作入口。
- 同一时刻只允许一个增强输入会话。输入序号单调递增，显示代次随实际捕获显示器变化；旧屏坐标丢弃。显示边界来自捕获端，而非客户端猜测内屏分辨率。
- 客户端失焦/暂停发送重置并释放已转发的普通按键；服务端会话停止或 4 秒心跳超时释放拖拽、结束手势、恢复光标。客户端可在服务器超时后重新握手。
- 位置消息在客户端按绘制帧合并；按钮前先发送最终位置。手势保持开始/变化/结束阶段，不转换成快捷键。Fn 的固定系统功能沿用原有 Mac 控制实现。
- 光标先在 Android 本地显示，视频捕获不包含重复的 Mac 箭头。当前仍为固定箭头，未实现 I 形等光标图案同步。
- 每端只覆盖一个小型状态文件，不记录输入文字或持续生成轨迹日志。

## 源码保存

全部适配源码在本仓库；上游基础代码以固定提交加补丁重建。没有把修改只留在 `.local` 克隆里。

| 上游 | 固定提交 | 本仓库改动 |
|---|---|---|
| moonlight-stream/moonlight-android | `b48494cb96bff23d8886c4775cc4f39a1075495d` | `moonlight/patches/android-integration.patch`、`moonlight/client` |
| moonlight-stream/moonlight-common-c | `874ac9548f1bd6f095ef2b435c42cdde460e7821` | `moonlight/patches/common-input.patch`、`protocol/pad_input.h` |
| LizardByte/Sunshine | `63d35f702ee9e362e43263742981836ec0710384` | `sunshine/converged.patch`、`sunshine/input`、`mac-input` |

构建同步器验证提交、幂等应用补丁、复制白名单源码。SDK、NDK、Gradle 缓存和签名材料不进入 Git。上游许可与来源见 NOTICE。

## 构建

Mac 工具链：Xcode Command Line Tools、Python 3、Homebrew OpenJDK、CMake、Ninja、miniupnpc、opus、OpenSSL。Sunshine 使用其固定的 Boost 1.89/FFmpeg 依赖，不安装最新版 Boost 代替。Swift 后端静态链接进 Sunshine。

Android 客户端固定 SDK 37.0 / Build Tools 37.0.0 / NDK 29.0.14206865；系统模块仍使用最小 SDK 35 构建。Google 的 package.xml 元数据与下载摘要一同保存，避免 SDK 37.0 的旧式 source.properties 探测失败。构建脚本不自动接受许可证或写入许可证接受标记。

```sh
git clone https://github.com/moonlight-stream/moonlight-android.git .local/moonlight-android
git -C .local/moonlight-android checkout b48494cb96bff23d8886c4775cc4f39a1075495d
git -C .local/moonlight-android submodule update --init --recursive
python3 tools/fetch_client_android.py
python3 tools/build_moonlight.py --source .local/moonlight-android

python3 tools/fetch_android.py
python3 tools/build_android.py system-keyboard
```

沿用 `.local/signing/pad-uu-local.p12` 和 `.local/signing/store-password`；不要换签名覆盖已有安装。APK 位于 `build/moonlight-client/`。系统模块部署参照旧开发文档，必须完整安装并重启核对 Vector 加载。

```sh
git clone https://github.com/LizardByte/Sunshine.git .local/Sunshine
git -C .local/Sunshine checkout 63d35f702ee9e362e43263742981836ec0710384
git -C .local/Sunshine submodule update --init --recursive third-party/moonlight-common-c third-party/libdisplaydevice third-party/libvirtualhid third-party/lizardbyte-common third-party/Simple-Web-Server third-party/TPCircularBuffer
git -C .local/Sunshine submodule update --init third-party/build-deps
python3 tools/build_sunshine.py --source .local/Sunshine
python3 tools/install_sunshine.py
```

新客户端实际进入 Desktop，状态确认 ready 后，可运行 `python3 tools/retire_legacy_helper.py` 停用旧 Helper；脚本先验证新通道、备份，再移除旧常驻程序。本机已经执行。

Sunshine 使用 `.local/mac-signing-identity` 中的原签名，网页资源从同版本已安装 App 复制。不要递归初始化 `third-party/build-deps` 下不参与 macOS 构建的巨型 FFmpeg 源码；构建会下载它所需的固定预编译依赖。输出为 `build/sunshine-stage/Sunshine.app`，安装保留 Sunshine 配对、配置和原路径。

## 回退

本机收敛前整套备份位于 `.local/rollback/pre-convergence/`，包含旧 Sunshine、Helper、两个 launchd 配置和已安装 Android APK。它不是新版构建产物。

1. `python3 tools/install_sunshine.py --restore-previous` 恢复旧 Sunshine。
2. 从备份恢复 `Pad Mac Helper.app` 到 `~/Applications/`，恢复 `local.pad.uu.ScrollBridge.plist` 到 `~/Library/LaunchAgents/`，用 `launchctl bootstrap gui/$(id -u) <plist绝对路径>` 启动。
3. 打开保留的官方 Moonlight。系统 1.10 仍兼容官方客户端，通常不必降级。官方客户端的旧 Vector 模块与数据保留；如需恢复系统 1.9，使用备份 APK 完整降级安装并重启。

回退不删除新客户端或任何用户数据，不重新配对旧客户端。辅助功能/录屏授权由 macOS 管理，若新签名被系统要求再次认证，只能按系统流程处理，不能回写 TCC 数据库。

## 未宣称完成的事项

公网连接本轮不研究打洞、不更改路由，也不以局域网测试冒充公网验收。既有公网串流能承载同一扩展协议，但实际外网延迟仍由用户复测。触点键盘的底层暂停、动态光标图案、跨 macOS 大版本的私有手势接口兼容不在本轮解决范围；当前原生系统手势后端限定 macOS 15。
