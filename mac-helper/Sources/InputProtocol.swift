import Foundation
import CoreGraphics

// 输入协议只接受固定动作和像素滚动，不包含任意命令、文件路径或文字输入。
struct InputFrame: Decodable {
    let v: Int
    let t: String
    let at: Int64
    let a: String?
    let x: Int32?
    let y: Int32?
    let phase: String?
    let mods: Int?

    static let actions: Set<String> = ["brightness-down", "brightness-up", "mic-mute", "screenshot",
        "assistant", "sleep", "previous", "play-pause", "next", "mute", "volume-down", "volume-up",
        "launchpad", "navigate-back", "navigate-forward", "space-left", "space-right", "windows", "app-windows"]
    static let phases: Set<String> = ["began", "changed", "ended", "cancelled",
        "momentum-began", "momentum-changed", "momentum-ended"]

    static func decode(_ data: Data) -> InputFrame? {
        guard data.count <= 4096, let frame = try? JSONDecoder().decode(Self.self, from: data),
              frame.v == 1, frame.at >= 0 else { return nil }
        switch frame.t {
        case "hello", "sync", "meta":
            guard let mods = frame.mods, (0...0x7fffffff).contains(mods) else { return nil }
        case "action": guard let action = frame.a, actions.contains(action) else { return nil }
        case "scroll":
            guard let x = frame.x, let y = frame.y, (-2048...2048).contains(x), (-2048...2048).contains(y),
                  let phase = frame.phase, phases.contains(phase), let mods = frame.mods,
                  (0...0x7fffffff).contains(mods) else { return nil }
        case "reset": break
        default: return nil
        }
        return frame
    }
}

struct InputClock {
    private var offset: Double?
    private var last: Int64 = -1

    mutating func accepts(_ frame: InputFrame, now: Double) -> Bool {
        guard frame.at >= last else { return false }
        let remote = Double(frame.at) / 1000
        if frame.t == "hello" || frame.t == "sync" {
            offset = min(offset ?? (now - remote), now - remote)
        }
        guard let offset else { return false }
        let age = now - remote - offset
        guard age >= -0.1 && age < 0.35 else { return false }
        last = frame.at
        return true
    }
}

func androidModifiers(_ value: Int) -> CGEventFlags {
    var flags = CGEventFlags()
    if value & 0x1 != 0 { flags.insert(.maskShift) }
    if value & 0x2 != 0 { flags.insert(.maskAlternate) }
    if value & 0x1000 != 0 { flags.insert(.maskControl) }
    if value & 0x10000 != 0 { flags.insert(.maskCommand) }
    if value & 0x100000 != 0 { flags.insert(.maskAlphaShift) }
    return flags
}

struct ConnectionConfiguration: Codable {
    var adb: String
    var deviceSerial: String
    var endpoint: String?
    var discovery: Bool

    var valid: Bool {
        adb.hasPrefix("/") && !deviceSerial.isEmpty && deviceSerial.count <= 128
            && (endpoint == nil || Self.validEndpoint(endpoint!))
    }

    static func validEndpoint(_ value: String) -> Bool {
        // 域名和 IPv6 均可配置，供后续 VPN 使用；不经本地 shell 展开。
        !value.isEmpty && value.count <= 253 && !value.hasPrefix("-")
            && value.unicodeScalars.allSatisfy { CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._:[]%").contains($0) }
    }
}
