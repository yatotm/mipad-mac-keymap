import Foundation

// 会话本身由 Sunshine 配对通道隔离；额外序号阻止重放旧帧，显示代次阻止点击旧屏幕。
struct PadGate {
    enum Result { case rejected, hello, sync, reset, event }
    private(set) var sequence: UInt64 = 0
    private(set) var active = false
    private var lastSeen: Double = 0

    mutating func accept(_ frame: InputFrame, epoch: UInt64, ready: Bool, now: Double) -> Result {
        guard let seq = frame.seq, seq > sequence else { return .rejected }
        if frame.t == "hello" {
            sequence = seq; active = true; lastSeen = now
            return .hello
        }
        guard active else { return .rejected }
        sequence = seq
        lastSeen = now
        if frame.t == "reset" { active = false; return .reset }
        if frame.t == "sync" || frame.t == "meta" { return .sync }
        guard ready, frame.epoch == epoch else { return .rejected }
        return .event
    }

    mutating func expire(now: Double) -> Bool {
        guard active, now - lastSeen > 4 else { return false }
        active = false
        return true
    }

    mutating func stop() { active = false }
}
