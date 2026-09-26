import AppKit

final class NativeScroll {
    private let source = CGEventSource(stateID: .combinedSessionState)
    private var contact = false
    private var momentum = false
    private var flags = CGEventFlags()

    static func event(source: CGEventSource?, x: Int32, y: Int32, phase: String,
                      flags: CGEventFlags, point: CGPoint? = nil) -> CGEvent? {
        guard let event = CGEvent(scrollWheelEvent2Source: source, units: .pixel,
                                  wheelCount: 2, wheel1: y, wheel2: x, wheel3: 0) else { return nil }
        event.flags = flags
        if let point { event.location = point }
        event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
        event.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        event.setIntegerValueField(.scrollWheelEventScrollCount, value: 1)
        let scroll: Int64 = ["began": 1, "changed": 2, "ended": 4, "cancelled": 8][phase] ?? 0
        let inertia: Int64 = ["momentum-began": 1, "momentum-changed": 2, "momentum-ended": 3][phase] ?? 0
        event.setIntegerValueField(.scrollWheelEventScrollPhase, value: scroll)
        event.setIntegerValueField(.scrollWheelEventMomentumPhase, value: inertia)
        return event
    }

    private func post(x: Int32 = 0, y: Int32 = 0, phase: String) {
        Self.event(source: source, x: x, y: y, phase: phase, flags: flags)?.post(tap: .cgSessionEventTap)
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
