import AppKit

// 双指缩放与系统三指捏合分开，不转成 Command 或滚轮快捷键。
final class NativeMagnify {
    private let source = CGEventSource(stateID: .combinedSessionState)
    private var active = false
    private var flags = CGEventFlags()

    private func post(_ delta: Double, _ phase: Int64) {
        guard NativeGesture.supported, let event = CGEvent(source: source) else { return }
        event.flags = flags.union(.maskNonCoalesced)
        event.timestamp = DispatchTime.now().uptimeNanoseconds
        event.setIntegerValueField(CGEventField(rawValue: 55)!, value: 29)
        event.setIntegerValueField(CGEventField(rawValue: 110)!, value: 8)
        event.setIntegerValueField(CGEventField(rawValue: 132)!, value: phase)
        event.setDoubleValueField(CGEventField(rawValue: 113)!, value: delta)
        event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
        event.post(tap: .cgSessionEventTap)
    }

    func receive(_ frame: InputFrame) {
        guard let delta = frame.progress, let phase = frame.phase,
              let code: Int64 = ["began": 1, "changed": 2, "ended": 4, "cancelled": 8][phase] else { return }
        if phase == "began" { reset(); active = true }
        guard active else { return }
        flags = androidModifiers(frame.mods ?? 0)
        post(delta, code)
        if phase == "ended" || phase == "cancelled" { active = false }
    }

    func reset() { if active { post(0, 8) }; active = false; flags = [] }
}
