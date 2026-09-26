import AppKit
import ApplicationServices

// 横向一次动作提交完整的原生滚动阶段。没有键盘组合，也没有惯性尾巴。
private var horizontalGeneration = 0
private var horizontalEnd: (() -> Void)?

func cancelHorizontalNavigation() {
    horizontalGeneration += 1
    horizontalEnd?()
    horizontalEnd = nil
}

func navigate(back: Bool) throws -> String {
    guard AXIsProcessTrusted() else { throw ControlError.failed("导航需要辅助功能权限") }
    cancelHorizontalNavigation()
    let generation = horizontalGeneration
    let point = CGEvent(source: nil)?.location ?? .zero
    let source = CGEventSource(stateID: .privateState)
    let direction: Int32 = back ? 1 : -1
    horizontalEnd = {
        NativeScroll.event(source: source, x: 0, y: 0, phase: "ended", flags: [], point: point)?
            .post(tap: .cgAnnotatedSessionEventTap)
    }
    for (delay, delta, phase) in [(0.0, Int32(0), "began"), (0.016, 80, "changed"),
                                  (0.032, 160, "changed"), (0.048, 240, "changed"), (0.080, 0, "ended")] {
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            guard horizontalGeneration == generation else { return }
            NativeScroll.event(source: source, x: delta * direction, y: 0, phase: phase, flags: [], point: point)?
                .post(tap: .cgAnnotatedSessionEventTap)
            if phase == "ended" { horizontalEnd = nil }
        }
    }
    return "native-phased-scroll"
}

func systemNavigation(_ action: String) throws {
    if action == "windows" {
        try openApplication("/System/Applications/Mission Control.app")
        return
    }
    // Spaces 和应用窗口概览暂用系统现有快捷入口；不经过 UU，也不占用任何用户快捷键。
    // 若用户确实按着修饰键则取消，避免合成松开覆盖用户的真实按住状态。
    let held = CGEventSource.flagsState(.combinedSessionState)
        .intersection([.maskControl, .maskAlternate, .maskCommand, .maskShift])
    guard held.isEmpty else { throw ControlError.failed("用户仍按着修饰键，已跳过系统导航") }
    let key: CGKeyCode = action == "space-left" ? 123 : action == "space-right" ? 124 : 125
    let source = CGEventSource(stateID: .privateState)
    for (code, down, isModifier, flags) in [(CGKeyCode(59), true, true, CGEventFlags.maskControl),
            (key, true, false, .maskControl), (key, false, false, .maskControl),
            (CGKeyCode(59), false, true, CGEventFlags())] {
        guard let event = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down) else {
            throw ControlError.failed("无法创建系统导航事件")
        }
        if isModifier { event.type = .flagsChanged }
        event.flags = flags
        event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
        event.post(tap: .cghidEventTap)
    }
}
