import AppKit

// 客户端负责本地光标；主机只接收有序的位置和按钮，不再同时 warp 与投递鼠标事件。
final class NativePointer {
    private let source = CGEventSource(stateID: .combinedSessionState)
    private var buttons = Set<Int>()
    private var point: CGPoint?
    private var lastPosted: CGPoint?
    private var hidden = false
    private var target = configuredDisplay()
    private(set) var received = 0
    private var flags = CGEventFlags()
    private var lastClick: (button: Int, at: Double, point: CGPoint, count: Int)?

    var localCursorActive: Bool { hidden }
    var heldButtons: Int { buttons.count }

    private static func configuredDisplay() -> CGDirectDisplayID? {
        let file = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config/sunshine/sunshine.conf")
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return nil }
        for line in text.split(separator: "\n") {
            let parts = line.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if parts.count == 2 && parts[0] == "output_name" { return CGDirectDisplayID(parts[1]) }
        }
        return nil
    }

    func configure() { reset(); target = Self.configuredDisplay() }

    static func capabilities() -> String {
        let bounds = CGDisplayBounds(configuredDisplay() ?? CGMainDisplayID())
        return "{\"v\":1,\"w\":\(Int(bounds.width)),\"h\":\(Int(bounds.height))}"
    }

    static func map(x: Double, y: Double, width: Double, height: Double, bounds: CGRect) -> CGPoint {
        let scale = min(width / bounds.width, height / bounds.height)
        let left = (width - bounds.width * scale) / 2
        let top = (height - bounds.height * scale) / 2
        return CGPoint(x: bounds.minX + min(bounds.width - 1, max(0, (x - left) / scale)),
                       y: bounds.minY + min(bounds.height - 1, max(0, (y - top) / scale)))
    }

    private func post(_ type: CGEventType, button: Int = 1, clicks: Int = 0) {
        guard let point, let cgButton = CGMouseButton(rawValue: UInt32(button == 1 ? 0 : button == 3 ? 1 : 2)),
              let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: point, mouseButton: cgButton)
        else { return }
        event.flags = flags
        event.timestamp = DispatchTime.now().uptimeNanoseconds
        let previous = lastPosted ?? CGEvent(source: source)?.location ?? point
        event.setDoubleValueField(.mouseEventDeltaX, value: point.x - previous.x)
        event.setDoubleValueField(.mouseEventDeltaY, value: point.y - previous.y)
        event.setIntegerValueField(.mouseEventClickState, value: Int64(clicks))
        event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
        event.post(tap: .cghidEventTap)
        lastPosted = point
    }

    func receive(_ frame: InputFrame) {
        received += 1
        flags = androidModifiers(frame.mods ?? 0)
        if frame.t == "position", let x = frame.x, let y = frame.y, let width = frame.w, let height = frame.h {
            let bounds = CGDisplayBounds(target ?? CGMainDisplayID())
            guard bounds.width > 0, bounds.height > 0 else { reset(); return }
            point = Self.map(x: Double(x), y: Double(y), width: Double(width), height: Double(height), bounds: bounds)
            if frame.local == true && !hidden {
                hidden = CGDisplayHideCursor(CGMainDisplayID()) == .success
            } else if frame.local == false && hidden {
                CGDisplayShowCursor(CGMainDisplayID()); hidden = false
            }
            let button = buttons.contains(1) ? 1 : buttons.contains(3) ? 3 : buttons.contains(2) ? 2 : 0
            post(button == 1 ? .leftMouseDragged : button == 3 ? .rightMouseDragged : button == 2 ? .otherMouseDragged : .mouseMoved,
                 button: button == 0 ? 1 : button)
        } else if frame.t == "button", let button = frame.button, let down = frame.down, let point {
            guard down != buttons.contains(button) else { return }
            var clicks = lastClick?.count ?? 1
            if down {
                let now = ProcessInfo.processInfo.systemUptime
                if let lastClick, lastClick.button == button, now - lastClick.at < NSEvent.doubleClickInterval,
                   hypot(point.x - lastClick.point.x, point.y - lastClick.point.y) < 4 {
                    clicks = min(3, lastClick.count + 1)
                } else { clicks = 1 }
                lastClick = (button, now, point, clicks)
                buttons.insert(button)
            } else { buttons.remove(button) }
            post(button == 1 ? (down ? .leftMouseDown : .leftMouseUp)
                 : button == 3 ? (down ? .rightMouseDown : .rightMouseUp)
                 : (down ? .otherMouseDown : .otherMouseUp), button: button, clicks: clicks)
        }
    }

    func reset() {
        for button in buttons {
            post(button == 1 ? .leftMouseUp : button == 3 ? .rightMouseUp : .otherMouseUp, button: button)
        }
        buttons.removeAll(); point = nil; lastPosted = nil; flags = []; lastClick = nil
        if hidden { CGDisplayShowCursor(CGMainDisplayID()); hidden = false }
    }
}
