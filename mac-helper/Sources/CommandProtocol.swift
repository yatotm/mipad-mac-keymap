import CoreGraphics

enum CommandProtocol {
    static let modifiers: CGEventFlags = [.maskControl, .maskAlternate, .maskCommand]
    static let actions: [CGKeyCode: String] = [
        122: "brightness-down", 120: "brightness-up", 99: "mic-mute", 118: "screenshot",
        96: "assistant", 97: "sleep", 98: "previous", 100: "play-pause", 101: "next",
        109: "mute", 103: "volume-down", 111: "volume-up",
        123: "navigate-back", 124: "navigate-forward", 49: "launchpad"
    ]

    static func action(key: CGKeyCode, flags: CGEventFlags, fromUU: Bool) -> String? {
        guard fromUU, flags.contains(modifiers) else { return nil }
        return actions[key]
    }
}
