import AppKit

final class NativeScroll {
    private let source = CGEventSource(stateID: .combinedSessionState)
    private var contact = false
    private var momentum = false
    private var flags = CGEventFlags()
    private var start = MotionPhaseStart()
    private let horizontalScale: Int32

    init(horizontalScale: Int32 = 1) { self.horizontalScale = horizontalScale }

    static func event(source: CGEventSource?, x: Int32, y: Int32, phase: String,
                      flags: CGEventFlags, point: CGPoint? = nil) -> CGEvent? {
        guard let event = CGEvent(scrollWheelEvent2Source: source, units: .pixel,
                                  wheelCount: 2, wheel1: y, wheel2: x, wheel3: 0) else { return nil }
        event.flags = flags
        event.timestamp = DispatchTime.now().uptimeNanoseconds
        if let point { event.location = point }
        event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
        event.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        event.setIntegerValueField(.scrollWheelEventScrollCount, value: 1)
        // 位移按手指方向发送；通知中心等视图还会读取自然滚动方向标记。
        event.setIntegerValueField(CGEventField(rawValue: 137)!, value: 1)
        let scroll: Int64 = ["began": 1, "changed": 2, "ended": 4, "cancelled": 8][phase] ?? 0
        let inertia: Int64 = ["momentum-began": 1, "momentum-changed": 2, "momentum-ended": 3][phase] ?? 0
        event.setIntegerValueField(.scrollWheelEventScrollPhase, value: scroll)
        event.setIntegerValueField(.scrollWheelEventMomentumPhase, value: inertia)
        return event
    }

    private func post(x: Int32 = 0, y: Int32 = 0, phase: String) {
        guard let phase = start.phase(phase, moved: x != 0 || y != 0) else { return }
        let x = x * horizontalScale
        guard let wheel = Self.event(source: source, x: x, y: y, phase: phase, flags: flags) else { return }
        wheel.post(tap: .cgSessionEventTap)
        if let gesture = Self.gestureEvent(source: source, x: x, y: y, phase: phase, flags: flags) {
            gesture.location = wheel.location
            gesture.timestamp = wheel.timestamp
            gesture.post(tap: .cgSessionEventTap)
        }
    }

    // 滚轮像素和触控手势分别交付；后者保留横向页面追踪的接触生命周期。
    static func gestureEvent(source: CGEventSource?, x: Int32, y: Int32, phase: String,
                             flags: CGEventFlags) -> CGEvent? {
        guard NativeGesture.supported,
              let code: Int64 = ["began": 1, "changed": 2, "ended": 4, "cancelled": 8][phase],
              let event = CGEvent(source: source) else { return nil }
        event.setIntegerValueField(CGEventField(rawValue: 55)!, value: 29)
        event.setIntegerValueField(CGEventField(rawValue: 110)!, value: 6)
        // 页面导航与内容滚动使用不同单位；只校准横向手势，不放大滚轮像素。
        event.setDoubleValueField(CGEventField(rawValue: 118)!, value: Double(x) * 10)
        event.setDoubleValueField(CGEventField(rawValue: 119)!, value: Double(y))
        event.setIntegerValueField(CGEventField(rawValue: 132)!, value: code)
        event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
        event.flags = flags
        event.timestamp = DispatchTime.now().uptimeNanoseconds
        return event
    }

    func receive(_ frame: InputFrame) {
        guard let x = frame.x, let y = frame.y, var phase = frame.phase else { return }
        flags = androidModifiers(frame.mods ?? 0)
        switch phase {
        case "began": reset(); contact = true
        case "changed":
            if !contact { reset(); phase = "began"; contact = true }
        case "ended", "cancelled":
            guard contact else { return }; contact = false
        case "momentum-began":
            if contact { post(phase: "ended"); contact = false }
            momentum = true
        case "momentum-changed":
            if contact { post(phase: "ended"); contact = false }
            if !momentum { phase = "momentum-began"; momentum = true }
        case "momentum-ended":
            guard momentum else { return }; momentum = false
        default: return
        }
        post(x: x, y: y, phase: phase)
    }

    func reset() {
        if contact { post(phase: "cancelled") }
        if momentum { post(phase: "momentum-ended") }
        contact = false; momentum = false
    }
}
