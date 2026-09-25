import AppKit
import ApplicationServices
import CoreAudio

// 仅响应明确的功能动作，执行后退出；不安装事件监听器，也不读取输入文字。
enum ControlError: Error, CustomStringConvertible {
    case failed(String)
    var description: String { switch self { case let .failed(message): return message } }
}

let supportDirectory = FileManager.default.homeDirectoryForCurrentUser
    .appendingPathComponent("Library/Application Support/Pad UU")

func address(_ selector: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope,
             _ element: AudioObjectPropertyElement = kAudioObjectPropertyElementMain) -> AudioObjectPropertyAddress {
    AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: element)
}

func readValue<T>(_ device: AudioObjectID, _ property: AudioObjectPropertyAddress, _ initial: T) throws -> T {
    var property = property, value = initial
    var size = UInt32(MemoryLayout<T>.size)
    let status = withUnsafeMutablePointer(to: &value) {
        AudioObjectGetPropertyData(device, &property, 0, nil, &size, $0)
    }
    guard status == noErr else { throw ControlError.failed("读取音频属性失败：\(status)") }
    return value
}

func writable(_ device: AudioObjectID, _ property: AudioObjectPropertyAddress) -> Bool {
    var property = property, result = DarwinBoolean(false)
    return AudioObjectHasProperty(device, &property)
        && AudioObjectIsPropertySettable(device, &property, &result) == noErr && result.boolValue
}

func writeValue<T>(_ device: AudioObjectID, _ property: AudioObjectPropertyAddress, _ value: T) throws {
    var property = property, value = value
    let status = withUnsafePointer(to: &value) {
        AudioObjectSetPropertyData(device, &property, 0, nil, UInt32(MemoryLayout<T>.size), $0)
    }
    guard status == noErr else { throw ControlError.failed("设置音频属性失败：\(status)") }
}

func audioDevice(input: Bool) throws -> AudioObjectID {
    let selector = input ? kAudioHardwarePropertyDefaultInputDevice : kAudioHardwarePropertyDefaultOutputDevice
    let device = try readValue(AudioObjectID(kAudioObjectSystemObject), address(selector, kAudioObjectPropertyScopeGlobal), UInt32(0))
    guard device != 0 else { throw ControlError.failed("没有可用的音频设备") }
    return device
}

func channels(_ device: AudioObjectID, _ scope: AudioObjectPropertyScope) throws -> [AudioObjectPropertyElement] {
    let found = [UInt32(0), 1, 2].filter { writable(device, address(kAudioDevicePropertyVolumeScalar, scope, $0)) }
    if found.contains(0) { return [0] }
    guard !found.isEmpty else { throw ControlError.failed("当前音频设备不提供软件音量控制") }
    return found
}

func volume(_ delta: Float) throws -> [Float] {
    let device = try audioDevice(input: false), scope = kAudioDevicePropertyScopeOutput
    var result: [Float] = []
    for channel in try channels(device, scope) {
        let property = address(kAudioDevicePropertyVolumeScalar, scope, channel)
        let before = try readValue(device, property, Float(0))
        let value = min(1, max(0, before + delta))
        try writeValue(device, property, value)
        result.append(try readValue(device, property, Float(0)))
    }
    let mute = address(kAudioDevicePropertyMute, scope)
    if delta > 0 && writable(device, mute) { try writeValue(device, mute, UInt32(0)) }
    return result
}

func toggleMute(input: Bool) throws -> Bool {
    let device = try audioDevice(input: input)
    let scope = input ? kAudioDevicePropertyScopeInput : kAudioDevicePropertyScopeOutput
    let mute = address(kAudioDevicePropertyMute, scope)
    if writable(device, mute) {
        let before = try readValue(device, mute, UInt32(0))
        try writeValue(device, mute, UInt32(before == 0 ? 1 : 0))
        return before == 0
    }
    // 部分内置麦克风没有 mute 属性；使用输入增益，并保留恢复值。
    let elements = try channels(device, scope)
    let values = try elements.map { try readValue(device, address(kAudioDevicePropertyVolumeScalar, scope, $0), Float(0)) }
    let file = supportDirectory.appendingPathComponent(input ? "mic-volume-state.json" : "output-volume-state.json")
    let shouldMute = values.contains { $0 > 0 }
    var restore = [Float](repeating: 0.5, count: elements.count)
    if shouldMute {
        let saved: [String: Any] = ["device": device, "volumes": values, "uptime": ProcessInfo.processInfo.systemUptime]
        try JSONSerialization.data(withJSONObject: saved).write(to: file, options: .atomic)
    } else if let data = try? Data(contentsOf: file),
              let saved = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (saved["device"] as? NSNumber)?.uint32Value == device,
              (saved["uptime"] as? Double ?? .infinity) <= ProcessInfo.processInfo.systemUptime,
              let old = saved["volumes"] as? [NSNumber], old.count == elements.count {
        restore = old.map { $0.floatValue }
    }
    for (index, channel) in elements.enumerated() {
        try writeValue(device, address(kAudioDevicePropertyVolumeScalar, scope, channel), shouldMute ? Float(0) : restore[index])
    }
    return shouldMute
}

func run(_ executable: URL, _ arguments: [String]) throws -> String {
    let task = Process(), output = Pipe(), errors = Pipe()
    task.executableURL = executable; task.arguments = arguments
    task.standardOutput = output; task.standardError = errors
    try task.run(); task.waitUntilExit()
    guard task.terminationStatus == 0 else { throw ControlError.failed("控制命令失败：\(task.terminationStatus)") }
    return String(data: output.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
}

func brightness(_ delta: Double) throws -> Double {
    var displays = [CGDirectDisplayID](repeating: 0, count: 4), count: UInt32 = 0
    let point = CGEvent(source: nil)?.location ?? .zero
    CGGetDisplaysWithPoint(point, 4, &displays, &count)
    let display = count > 0 ? displays[0] : CGMainDisplayID()
    let tool = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Applications/BetterDisplay.app/Contents/MacOS/BetterDisplay")
    let current = try run(tool, ["get", "-displayID=\(display)", "-brightness"])
    guard let before = Double(current.trimmingCharacters(in: .whitespacesAndNewlines)) else {
        throw ControlError.failed("无法读取 BetterDisplay 亮度")
    }
    let value = min(1, max(0.05, before + delta))
    _ = try run(tool, ["set", "-displayID=\(display)", "-brightness=\(value)"])
    return value
}

func mediaKey(_ key: Int) throws {
    guard AXIsProcessTrusted() else { throw ControlError.failed("媒体播放控制需要辅助功能权限") }
    for down in [true, false] {
        let state = down ? 0xA : 0xB
        guard let event = NSEvent.otherEvent(with: .systemDefined, location: .zero,
                modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(state << 8)),
                timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: 0, context: nil,
                subtype: 8, data1: (key << 16) | (state << 8), data2: -1)?.cgEvent else {
            throw ControlError.failed("无法创建媒体控制事件")
        }
        event.post(tap: .cghidEventTap)
    }
}

func openApplication(_ path: String) throws {
    guard NSWorkspace.shared.open(URL(fileURLWithPath: path)) else { throw ControlError.failed("无法打开系统应用") }
}

let action = CommandLine.arguments.dropFirst().first ?? "check"
var result: [String: Any] = ["action": action, "timestamp": Date().timeIntervalSince1970,
                           "media_permission": AXIsProcessTrusted()]
do {
    try FileManager.default.createDirectory(at: supportDirectory, withIntermediateDirectories: true,
                                            attributes: [.posixPermissions: 0o700])
    switch action {
    case "volume-up": result["volume"] = try volume(1.0 / 16)
    case "volume-down": result["volume"] = try volume(-1.0 / 16)
    case "mute": result["muted"] = try toggleMute(input: false)
    case "mic-mute": result["muted"] = try toggleMute(input: true)
    case "brightness-up": result["brightness"] = try brightness(1.0 / 16)
    case "brightness-down": result["brightness"] = try brightness(-1.0 / 16)
    case "previous": try mediaKey(18)
    case "play-pause": try mediaKey(16)
    case "next": try mediaKey(17)
    case "screenshot": try openApplication("/System/Applications/Utilities/Screenshot.app")
    case "assistant": try openApplication("/System/Applications/Siri.app")
    case "sleep": _ = try run(URL(fileURLWithPath: "/usr/bin/pmset"), ["sleepnow"])
    case "check":
        let device = try audioDevice(input: false)
        result["output_device"] = device
        result["volume_channels"] = try channels(device, kAudioDevicePropertyScopeOutput)
        result["output_volume"] = try channels(device, kAudioDevicePropertyScopeOutput).map {
            try readValue(device, address(kAudioDevicePropertyVolumeScalar, kAudioDevicePropertyScopeOutput, $0), Float(0))
        }
    case "verify":
        // 用原值往返验证实际系统接口，不改变音量、静音状态或亮度。
        result["volume"] = try volume(0)
        result["brightness"] = try brightness(0)
    default: throw ControlError.failed("未指定受支持的动作")
    }
    result["ok"] = true
} catch {
    result["ok"] = false; result["error"] = String(describing: error)
}
// 状态仅覆盖一个小文件，不生成持续追加的日志。
if let data = try? JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]) {
    try? data.write(to: supportDirectory.appendingPathComponent("last-action.json"), options: .atomic)
    if action == "check" || action == "verify" { print(String(data: data, encoding: .utf8)!) }
}
exit(result["ok"] as? Bool == true ? 0 : 1)
