# 第三方来源

`magisk/keyboard-layout/system/usr/keylayout` 中的两份布局以 Android Open Source Project 的 Generic.kl 为基础，保留了原版权头，适用 Apache License 2.0，许可文本见 `LICENSES/Apache-2.0.txt`。

Android SDK 和 Xposed API 仅作为下载的构建依赖保存在 `.cache`，不提交仓库，也不将 Xposed API 实现打包进模块。

本文件不替项目的其它代码另行指定许可证。

`mac-input/Sources/NativeGesture.swift` 、`NativeMagnify.swift` 和 `NativeScroll.swift` 使用的私有 CoreGraphics 字段编号参考 [Apple WebKit 的 CoreGraphicsTestSPI.h](https://github.com/WebKit/WebKit/blob/main/Tools/TestRunnerShared/spi/CoreGraphicsTestSPI.h)，相关许可保留在 `LICENSES/WebKit-BSD-2-Clause.txt`。Dock 手势格式同时核对了 [FasterSwiper 的回放实现](https://github.com/mgbowen/FasterSwiper/blob/68f5c9b80a7d4876463d05bbf48d1a004651e143/src/tools/playback-gesture.cc)。本项目的状态机与 Swift 实现自行编写，没有合入 Mac Mouse Fix 的实现。滚动事件与手势事件的配对、自然滚动字段语义另参考了 [Mac Mouse Fix 的协议研究注释](https://github.com/noah-nuebling/mac-mouse-fix/blob/master/Helper/Core/Touch/GestureScrollSimulator.m)，未采用其滚动算法。

Moonlight Android 的固定基线为 `b48494cb96bff23d8886c4775cc4f39a1075495d`，moonlight-common-c 为 `874ac9548f1bd6f095ef2b435c42cdde460e7821`。两者的接入补丁保存在 `moonlight/patches`，适配源码在 `moonlight/client`。遵循上游 GPL-3.0 许可，许可文本见 `LICENSES/Moonlight-GPL-3.0.txt`。

Sunshine 的固定基线为 `63d35f702ee9e362e43263742981836ec0710384`，完整接入补丁保存在 `sunshine/converged.patch`，新增源码在 `sunshine/input` 和 `mac-input`，遵循上游 GPL-3.0-only，许可见 `LICENSES/Sunshine-GPL-3.0.txt`。旧的独立光标补丁仍保留用于历史版本。构建说明见 `docs/converged-input.md`；不将第三方二进制提交仓库。
