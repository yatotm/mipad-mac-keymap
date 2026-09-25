import AppKit
import ApplicationServices

// 只取鼠标下的进程编号，不读取窗口、网页或通知正文。
private func pointerApplication() -> NSRunningApplication? {
    let point = CGEvent(source: nil)?.location ?? .zero
    var element: AXUIElement?
    guard AXUIElementCopyElementAtPosition(AXUIElementCreateSystemWide(), Float(point.x), Float(point.y),
                                          &element) == .success, let element else { return nil }
    var pid: pid_t = 0
    guard AXUIElementGetPid(element, &pid) == .success else { return nil }
    return NSRunningApplication(processIdentifier: pid)
}

private func pressCommandArrow(back: Bool) throws {
    for down in [true, false] {
        guard let event = CGEvent(keyboardEventSource: nil, virtualKey: back ? 123 : 124, keyDown: down) else {
            throw ControlError.failed("无法创建导航事件")
        }
        event.flags = .maskCommand
        event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
        event.post(tap: .cghidEventTap)
    }
}

private func fixedHorizontalStep(back: Bool) {
    let point = CGEvent(source: nil)?.location ?? .zero
    // 非浏览器保留一次短促的横向滑动，明确结束，不生成惯性或等待超时。
    for (delay, delta, phase) in [(0.0, back ? 480 : -480, CGScrollPhase.began),
                                   (0.035, 0, CGScrollPhase.ended)] {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            guard let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel,
                                       wheelCount: 2, wheel1: 0, wheel2: Int32(delta), wheel3: 0) else { return }
            event.location = point
            event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
            event.setIntegerValueField(.scrollWheelEventScrollCount, value: 1)
            event.setIntegerValueField(.scrollWheelEventScrollPhase, value: Int64(phase.rawValue))
            event.post(tap: .cgAnnotatedSessionEventTap)
        }
    }
}

func navigate(back: Bool) throws -> String {
    guard AXIsProcessTrusted() else { throw ControlError.failed("导航需要辅助功能权限") }
    let pointed = pointerApplication()?.bundleIdentifier ?? ""
    let front = NSWorkspace.shared.frontmostApplication?.bundleIdentifier ?? ""
    let browsers: Set<String> = ["com.google.Chrome", "com.google.Chrome.beta", "com.google.Chrome.dev",
                                "com.apple.Safari", "com.microsoft.edgemac", "com.brave.Browser", "org.mozilla.firefox"]
    if pointed == "com.apple.notificationcenterui" {
        fixedHorizontalStep(back: back)
        return "notification-step"
    }
    if pointed == "com.apple.dock" || front == "com.apple.dock" || front == "com.apple.launchpad.launcher"
            || browsers.contains(front) {
        // Chrome 的历史导航和 Launchpad 的整页切换都使用 Command+方向键。
        try pressCommandArrow(back: back)
        return "command-arrow"
    }
    fixedHorizontalStep(back: back)
    return "horizontal-step"
}
