import AppKit

private let padQueue = DispatchQueue(label: "local.pad.sunshine.input", qos: .userInteractive)
private let controlQueue = DispatchQueue(label: "local.pad.sunshine.controls", qos: .userInitiated)
private let statusQueue = DispatchQueue(label: "local.pad.sunshine.status", qos: .utility)
private var contexts = [UInt64: PadContext]()
private var nextContext: UInt64 = 1
private var owner: UInt64?
private var captureDisplay: CGDirectDisplayID = 0
private var captureBounds = CGRect.zero
private var captureEpoch: UInt64 = 1
private var statusAt: Double = 0

private final class PadContext {
    let id: UInt64
    var gate = PadGate()
    let pointer = NativePointer()
    let scroll = NativeScroll(horizontalScale: 3)
    let gesture = NativeGesture()
    let magnify = NativeMagnify()
    var received = 0, rejected = 0, pendingActions = 0
    var actionGeneration: UInt64 = 0
    var actions = [String: Int]()
    init(_ id: UInt64) { self.id = id }

    func cancel() {
        pointer.reset(); scroll.reset(); gesture.reset(); magnify.reset()
        actionGeneration &+= 1
    }
    func stop() {
        cancel(); gate.stop()
        if owner == id { owner = nil }
    }
    func configure() { pointer.configure(display: captureDisplay, bounds: captureBounds) }
}

private func refreshDisplay(force: Bool = false) {
    let bounds = captureDisplay == 0 || CGDisplayIsActive(captureDisplay) == 0 ? CGRect.zero : CGDisplayBounds(captureDisplay)
    if force || bounds != captureBounds {
        captureBounds = bounds
        captureEpoch &+= 1
        for context in contexts.values { context.cancel(); context.configure() }
    }
}

private func isReady(_ context: PadContext) -> Bool {
    NativeGesture.supported && context.gate.active && owner == context.id && AXIsProcessTrusted()
        && captureBounds.width > 0 && captureBounds.height > 0
}

private func recordStatus(force: Bool = false) {
    let now = ProcessInfo.processInfo.systemUptime
    guard force || now >= statusAt else { return }
    statusAt = now + 1
    let context = owner.flatMap { contexts[$0] }
    let status: [String: Any] = ["version": 1, "transport": "moonlight-control", "helper_required": false,
        "pid": ProcessInfo.processInfo.processIdentifier, "permission": AXIsProcessTrusted(),
        "active": context?.gate.active ?? false, "local_cursor": context?.pointer.localCursorActive ?? false,
        "pointer_buttons": context?.pointer.heldButtons ?? 0, "display": captureDisplay, "epoch": captureEpoch,
        "received": contexts.values.reduce(0) { $0 + $1.received },
        "rejected": contexts.values.reduce(0) { $0 + $1.rejected }, "actions": context?.actions ?? [:]]
    guard let data = try? JSONSerialization.data(withJSONObject: status, options: [.sortedKeys]) else { return }
    statusQueue.async {
        try? FileManager.default.createDirectory(at: supportDirectory, withIntermediateDirectories: true)
        try? data.write(to: supportDirectory.appendingPathComponent("sunshine-input-status.json"), options: .atomic)
    }
}

@_cdecl("pad_native_supported")
public func padNativeSupported() -> Bool { NativeGesture.supported }

@_cdecl("pad_native_create")
public func padNativeCreate() -> UInt64 {
    padQueue.sync {
        let id = nextContext; nextContext &+= 1
        contexts[id] = PadContext(id)
        return id
    }
}

@_cdecl("pad_native_destroy")
public func padNativeDestroy(_ id: UInt64) {
    padQueue.sync { contexts.removeValue(forKey: id)?.stop(); recordStatus(force: true) }
}

@_cdecl("pad_native_reset")
public func padNativeReset(_ id: UInt64) {
    padQueue.sync { contexts[id]?.stop(); recordStatus(force: true) }
}

@_cdecl("pad_native_capture_display")
public func padNativeCaptureDisplay(_ display: UInt32) {
    padQueue.sync {
        let changed = display != captureDisplay
        captureDisplay = display
        refreshDisplay(force: changed)
    }
}

@_cdecl("pad_native_cursor_active")
public func padNativeCursorActive() -> Bool {
    padQueue.sync { owner.flatMap { contexts[$0] }?.pointer.localCursorActive ?? false }
}

// 返回 2 表示客户端结束输入，Sunshine 同时释放普通键盘状态。
@_cdecl("pad_native_receive")
public func padNativeReceive(_ id: UInt64, _ bytes: UnsafePointer<UInt8>?, _ length: Int32) -> Int32 {
    guard let bytes, length > 0, length <= 1024,
          let frame = InputFrame.decode(Data(bytes: bytes, count: Int(length))) else { return 0 }
    return padQueue.sync {
        guard let context = contexts[id] else { return 0 }
        refreshDisplay()
        if frame.t == "hello", let owner, owner != id { context.rejected += 1; return 0 }
        let result = context.gate.accept(frame, epoch: captureEpoch, ready: isReady(context),
                                        now: ProcessInfo.processInfo.systemUptime)
        if result == .rejected { context.rejected += 1; recordStatus(); return 0 }
        context.received += 1
        switch result {
        case .hello:
            context.cancel(); context.configure(); owner = id
        case .reset:
            context.stop(); recordStatus(force: true); return 2
        case .event:
            switch frame.t {
            case "position", "button": context.pointer.receive(frame)
            case "scroll": context.scroll.receive(frame)
            case "gesture": context.gesture.receive(frame)
            case "magnify": context.magnify.receive(frame)
            case "action":
                if let action = frame.a, context.pendingActions < 8 {
                    context.pendingActions += 1
                    let generation = context.actionGeneration
                    let deadline = ProcessInfo.processInfo.systemUptime + 0.35
                    controlQueue.async { [weak context] in
                        guard let context else { return }
                        let shouldRun = padQueue.sync {
                            context.pendingActions -= 1
                            return context.gate.active && owner == context.id && generation == context.actionGeneration
                                && ProcessInfo.processInfo.systemUptime <= deadline
                        }
                        guard shouldRun else { return }
                        let result = executeControl(action)
                        padQueue.async {
                            if result["ok"] as? Bool == true { context.actions[action, default: 0] += 1 }
                            recordStatus(force: true)
                        }
                    }
                }
            default: break
            }
        default: break
        }
        recordStatus()
        return 1
    }
}

// 控制线程定期调用；心跳失联后立即清理，不能等 TCP/视频超时才释放拖拽。
@_cdecl("pad_native_tick")
public func padNativeTick(_ id: UInt64) -> Bool {
    padQueue.sync {
        guard let context = contexts[id] else { return false }
        refreshDisplay()
        if context.gate.expire(now: ProcessInfo.processInfo.systemUptime) {
            context.stop(); recordStatus(force: true); return true
        }
        recordStatus()
        return false
    }
}

@_cdecl("pad_native_metadata")
public func padNativeMetadata(_ id: UInt64, _ output: UnsafeMutablePointer<UInt8>?, _ capacity: Int32) -> Int32 {
    guard let output, capacity > 0 else { return 0 }
    return padQueue.sync {
        guard let context = contexts[id] else { return 0 }
        refreshDisplay()
        let global = CGEvent(source: nil)?.location ?? CGPoint(x: captureBounds.midX, y: captureBounds.midY)
        let x = min(max(0, global.x - captureBounds.minX), max(0, captureBounds.width - 1))
        let y = min(max(0, global.y - captureBounds.minY), max(0, captureBounds.height - 1))
        let state: [String: Any] = ["v": 1, "ready": isReady(context), "permission": AXIsProcessTrusted(),
            "busy": owner != nil && owner != id, "active": context.gate.active, "epoch": captureEpoch,
            "w": Int(captureBounds.width), "h": Int(captureBounds.height), "x": Int(x), "y": Int(y),
            "seq": context.gate.sequence, "received": context.received, "buttons": context.pointer.heldButtons]
        guard let data = try? JSONSerialization.data(withJSONObject: state, options: [.sortedKeys]),
              data.count <= capacity else { return 0 }
        data.copyBytes(to: output, count: data.count)
        return Int32(data.count)
    }
}
