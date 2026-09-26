# 当前安装组成与控制台（2026-09-27）

## Sunshine 管理

在 Mac 本机浏览器访问 **https://localhost:47990**。管理接口已核对在线，未登录请求返回 401。仅允许本机进入管理页面，公网串流连接方式保持原样。

登录信息保存在本机 `.local/sunshine-web.json`，不提交 Git。浏览器首次访问可能显示本机自签名证书提示，需要用户处理；未修改浏览器的全局安全设置。

Sunshine 构建参数关闭托盘图标，因此没有菜单栏图标。它由当前用户的 `local.pad.sunshine` LaunchAgent 在登录后启动，并在异常退出时重新启动。配置文件是 `~/.config/sunshine/sunshine.conf`；网页提供主机配置、应用列表和配对管理。保存后如需重启，按网页提示操作；会断开当前串流。

## 保留的组件

| 位置 | 组件 | 状态与用途 |
|---|---|---|
| Mac | 定制 Sunshine | 唯一新增的远控输入后台；辅助功能、录屏、系统音频三项授权有效 |
| Mac | BetterDisplay / Mos / UU | 原有显示、鼠标和备用远控工具保留，没有更改配置 |
| Android | MiPad Moonlight 12.2-pad.1 | 已验收的客户端，没有重编译或替换 |
| Vector/LSP | 小米 Pad · Mac 键盘直通 1.11 | 唯一启用的定制输入模块，作用域为系统；更新后须重启加载 |
| Magisk | Vector | 提供当前 LSP 框架；其共用数据完整保留 |
| Magisk | 小米 Pad · Mac 键盘入口 1.3 | 原“UU 键盘入口”更名，布局文件未改；语音键与缺失功能键的底层入口仍需要它 |
| Magisk | 原厂显示密度修复 | 保留已验收的 360 DPI 修复 |
| Magisk | 加密 Wi-Fi ADB 23333 | 按原要求保留开发维护入口；日常远控不依赖它 |
| 其他停用插件 | HyperCeiler、MIUI 工具箱、解除安装来源限制、Shamiko | 按用户选择保留原状态，没有更新或启用 |

UU 只保留语音键→Control、四叶草→Option、Alt→Command。左下角 Fn 不再向旧 Helper 发请求；自制顶排和触控板适配已退出 UU。Moonlight 的功能层、手势、本地光标、惯性与现有参数保持原实现。

## 已清理

- LSP 应用：`local.pad.uu.touchpad`、`local.pad.moonlight`、`com.jozein.xedgepro`。卸载后 Vector 的模块及作用域记录已自动移除，没有直接修改运行中的配置数据库。
- Magisk：已停用的 `jefferderp.keyboardremaps`、`zygisk_lsposed`。旧 LSPosed 的卸载脚本会删除当前 Vector 共用的 `/data/adb/lspd`，因此备份后仅移除旧模块目录，没有执行该脚本。
- Mac：旧 Helper、Controls 与监听工具均无现存常驻项目。旧 Helper 的辅助功能授权，以及已卸载 DeskPad 的录屏授权，已通过系统 `tccutil` 定向撤销。未改写 TCC 数据库，未重置 Sunshine 或其它应用的权限。
- Mac 后台项目核对只发现本任务当前的 Sunshine LaunchAgent，没有旧 Helper/Controls 项。后台列表可能按签名开发者分组；Sunshine 所属项目需要保留。
- UU 目录内的旧输入管道、锁和触控配置已备份移除，UU 其它数据保留。旧 Helper 的 ADB 连接配置移入回退备份；当前目录仅保留 Sunshine 覆盖写入的小型状态与动作结果，不持续积累输入日志。Sunshine 日志当前为 0 字节。

由于旧 App 已卸载，`tccutil` 起初无法解析它们的身份。处理方式为临时注册原始签名 App 的身份、定向撤销、随即注销；没有启动或重新安装旧 App。DeskPad 使用之前记录的相同官方版本和 SHA-256，临时下载随即删除。

## 备份与检查

本机 `.local/rollback/input-cleanup-20260927/` 保存被卸载 APK、应用数据、两个 Magisk 模块和系统键盘 1.10，另有旧 Helper 连接配置。备份不提交 Git，不覆盖当前 Vector 的全库配置。

系统键盘 1.11 通过既有 Fn/修饰键检查，以及新增的“Moonlight 功能层保持、UU 精简、应用切换”检查。APK 完整安装与 Vector 注册检查通过。重启前运行的仍是旧系统进程中的模块，不能把已安装版本当作已经加载。

用户选择稍后自行重启，当前远控会话保持运行；没有声称 1.11 已在 system_server 中加载。
