import AppKit

// 字段定义来自 WebKit CoreGraphicsTestSPI.h；许可和验证边界见 NOTICE.md。
// 只支持已核对的 macOS 15。未知系统不发送私有事件，避免静默误操作。
final class NativeGesture {
    private let source = CGEventSource(stateID: .combinedSessionState)
    private var axis: Int?
    private var progress: Double = 0

    static var supported: Bool { ProcessInfo.processInfo.operatingSystemVersion.majorVersion == 15 }

    static func event(source: CGEventSource?, axis: Int, progress: Double,
                      velocity: Double, phase: String) -> CGEvent? {
        guard let event = CGEvent(source: source), (1...3).contains(axis),
              progress.isFinite, velocity.isFinite,
              let code: Int64 = ["began": 1, "changed": 2, "ended": 4, "cancelled": 8][phase]
        else { return nil }
        func integer(_ field: UInt32, _ value: Int64) {
            event.setIntegerValueField(CGEventField(rawValue: field)!, value: value)
        }
        func real(_ field: UInt32, _ value: Double) {
            event.setDoubleValueField(CGEventField(rawValue: field)!, value: value)
        }
        integer(55, 30)
        integer(110, 23)
        integer(123, Int64(axis))
        integer(132, code)
        real(124, progress)
        // 用有符号 Int32 保存 Float32 位模式，再传入 CoreGraphics 的 Int64 参数。
        integer(135, Int64(Int32(bitPattern: Float(progress).bitPattern)))
        if code == 4 || code == 8 {
            real(129, velocity); real(130, velocity)
        }
        event.flags = .maskNonCoalesced
        event.timestamp = DispatchTime.now().uptimeNanoseconds
        event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
        return event
    }

    private func post(_ axis: Int, _ progress: Double, _ velocity: Double, _ phase: String) {
        guard Self.supported else { return }
        Self.event(source: source, axis: axis, progress: progress, velocity: velocity, phase: phase)?
            .post(tap: .cgSessionEventTap)
    }

    func receive(_ frame: InputFrame) {
        guard let incoming = frame.axis, let value = frame.progress,
              let velocity = frame.velocity, let phase = frame.phase else { return }
        if phase == "began" {
            reset()
            axis = incoming
        } else if axis != incoming { return }
        progress = value
        post(incoming, value, velocity, phase)
        if phase == "ended" || phase == "cancelled" {
            axis = nil
        }
    }

    func reset() {
        if let axis { post(axis, progress, 0, "cancelled") }
        axis = nil
        progress = 0
    }
}
