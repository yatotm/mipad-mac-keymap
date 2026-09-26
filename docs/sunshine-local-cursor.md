# Sunshine 本地光标捕获补丁

客户端在平板绘制箭头后，官方 macOS 捕获端仍会把 Mac 箭头编码进视频。用户已确认蓝色本地箭头跟手，同时有黑色远端箭头滞后；只调用 `CGDisplayHideCursor` 没有消除视频中的重复箭头。

本机版本基于官方 `v2026.914.233613`，源码提交固定为 `63d35f702ee9e362e43263742981836ec0710384`。补丁保存在 [local-cursor.patch](../sunshine/local-cursor.patch)，没有修改视频分辨率、配对或键盘协议。

捕获回调每 250 毫秒检查一次现有 Helper 状态文件。只有 `local_cursor=true`、Moonlight 通道连接、Helper 进程存活且状态未超过 3.5 秒，才设置 `AVCaptureScreenInput.capturesCursor=false`。退出、崩溃或状态异常时恢复视频光标。检查使用独立的非阻塞锁，不与捕获会话停止共用锁；不新增服务、端口、输入监听器或日志文件。

扩展默认关闭。项目启动脚本仅在 App 带 `pad-local-cursor.json` 构建标记时设置 `PAD_LOCAL_CURSOR=1`。辅助功能和录屏仍通过 macOS 正常授权；新签名首次安装可能需要重新登记权限。

## 重建

需要 Xcode 命令行工具、CMake、Ninja、miniupnpc、opus、OpenSSL 与 Node。使用上游固定的 Boost 1.89 源码和 FFmpeg 预编译依赖，不需要安装 Homebrew 的其它 Boost 版本。

```sh
brew install cmake ninja miniupnpc opus
# 源码与依赖目录置于被 Git 忽略的本地目录。
git clone --depth 1 --branch v2026.914.233613 https://github.com/LizardByte/Sunshine.git .local/research/Sunshine-pad
cd .local/research/Sunshine-pad
git submodule update --init --depth 1 third-party/Simple-Web-Server third-party/TPCircularBuffer third-party/build-deps third-party/libdisplaydevice third-party/libvirtualhid third-party/lizardbyte-common third-party/moonlight-common-c
git -C third-party/moonlight-common-c submodule update --init --recursive --depth 1
```

从本项目根目录运行：

```sh
python3 tools/build_sunshine.py --source .local/research/Sunshine-pad --official-app .local/rollback/sunshine-official/Sunshine.app
python3 tools/install_sunshine.py
```

首次构建时 `--official-app` 可省略，使用当前安装的官方包提供同版本网页资源。脚本仅构建捕获服务，关闭额外托盘界面，沿用统一 Helper 菜单；产物自带所需动态库，不依赖编译工具常驻。目标系统为 macOS 15。签名沿用本机开发证书，不减弱系统安全设置。

状态判定的正常、断线、过期、未来时间、错误格式、过大文件和 PID 边界检查随构建执行。用户在实际串流中确认鼠标正常；随后新签名的辅助功能权限重新登记，键盘也恢复正常。状态测试不替代这两项实体验收。

## 回退

```sh
python3 tools/install_sunshine.py --restore-official
```

官方包保存在 `.local/rollback/sunshine-official/Sunshine.app`，配对和配置始终保留在 `~/.config/sunshine`。回退后可能需要按 macOS 提示恢复官方签名对应的权限。
