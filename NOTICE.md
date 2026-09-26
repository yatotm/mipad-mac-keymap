# 第三方来源

`magisk/keyboard-layout/system/usr/keylayout` 中的两份布局以 Android Open Source Project 的 Generic.kl 为基础，保留了原版权头，适用 Apache License 2.0，许可文本见 `LICENSES/Apache-2.0.txt`。

Android SDK 和 Xposed API 仅作为下载的构建依赖保存在 `.cache`，不提交仓库，也不将 Xposed API 实现打包进模块。

本文件不替项目的其它代码另行指定许可证。

`mac-helper/Sources/NativeGesture.swift` 和 `NativeMagnify.swift` 使用的私有 CoreGraphics 字段编号参考 [Apple WebKit 的 CoreGraphicsTestSPI.h](https://github.com/WebKit/WebKit/blob/main/Tools/TestRunnerShared/spi/CoreGraphicsTestSPI.h)，相关许可保留在 `LICENSES/WebKit-BSD-2-Clause.txt`。Dock 手势格式同时核对了 [FasterSwiper 的回放实现](https://github.com/mgbowen/FasterSwiper/blob/68f5c9b80a7d4876463d05bbf48d1a004651e143/src/tools/playback-gesture.cc)。本项目的状态机与 Swift 实现自行编写，没有合入 Mac Mouse Fix 的实现。

Moonlight 和 Sunshine 使用各自官方发布的未修改安装包；本仓库只保存版本、摘要和适配模块源码，不重新分发第三方二进制或改变其许可证。
