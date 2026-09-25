import AppKit
import ApplicationServices

// 只匹配实测的 UU 被控辅助进程；普通鼠标和其他应用不在作用范围内。
let uuExecutable = "/Applications/UURemote.app/Contents/Helpers/UURemoteServer"
let bridgeMarker: Int64 = 0x50414455554d4143
let mosMarker: Int64 = 0x4d4f53534d4f4f54

struct ScrollPhaseState {
    private(set) var active = false
    mutating func next() -> CGScrollPhase {
        let phase: CGScrollPhase = active ? .changed : .began
        active = true
        return phase
    }
    mutating func finish() -> Bool {
        let wasActive = active
        active = false
        return wasActive
    }
}

final class Bridge: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var tap: CFMachPort?
    private var source: CFRunLoopSource?
    private var keyTap: CFMachPort?
    private var keySource: CFRunLoopSource?
    private var commandState = CommandState()
    private var receivedActions: [String: Int] = [:]
    private var finishedActions: [String: Int] = [:]
    private var cancelledCommands = 0
    private let statusWriter = DispatchQueue(label: "local.pad.uu.status", qos: .utility)
    private var statusQueued = false
    private let actions = DispatchQueue(label: "local.pad.uu.controls", qos: .userInitiated)
    private var pendingActions: [String] = []
    private var actionBusy = false
    private var commandCount = 0
    private var status: NSStatusItem?
    private var summary: NSMenuItem?
    private var phaseState = ScrollPhaseState()
    private var lastEvent: CGEvent?
    private var finishWork: DispatchWorkItem?
    private var count = 0
    private var cachedPID: pid_t = -1
    private var cachedMatch = false
    private var cachedAt: TimeInterval = 0
    private var window: NSWindow?
    private var stateLabel: NSTextField?
    private var permissionTimer: Timer?
    private var lastStatusUpdate: TimeInterval = 0

    func applicationDidFinishLaunching(_ notification: Notification) {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.title = "Pad"
        let menu = NSMenu()
        menu.delegate = self
        let line = NSMenuItem(title: "尚未启用", action: nil, keyEquivalent: "")
        menu.addItem(line)
        summary = line
        menu.addItem(.separator())
        for (title, action) in [("启用适配", #selector(enable)), ("暂停适配", #selector(pause)),
                                ("打开辅助功能设置", #selector(openSettings)), ("退出", #selector(quit))] {
            let entry = NSMenuItem(title: title, action: action, keyEquivalent: "")
            entry.target = self
            menu.addItem(entry)
        }
        item.menu = menu
        status = item
        if !CommandLine.arguments.contains("--background") { showWindow() }
        if AXIsProcessTrusted() { enable() } else { waitForPermission(); writeStatus() }
    }

    private func showWindow() {
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 260),
                         styleMask: [.titled, .closable], backing: .buffered, defer: false)
        w.title = "Pad Mac Helper"
        let title = NSTextField(labelWithString: "Pad Mac Helper")
        title.font = .boldSystemFont(ofSize: 22)
        title.frame = NSRect(x: 24, y: 205, width: 470, height: 32)
        let info = NSTextField(wrappingLabelWithString:
            "统一处理 UU 的功能控制、导航和滚动适配。\n只识别约定控制组合，不记录普通键盘输入。")
        info.frame = NSRect(x: 24, y: 128, width: 470, height: 62)
        let label = NSTextField(labelWithString: "等待辅助功能权限")
        label.frame = NSRect(x: 24, y: 92, width: 470, height: 28)
        stateLabel = label
        let settings = NSButton(title: "打开辅助功能设置", target: self, action: #selector(openSettings))
        settings.frame = NSRect(x: 24, y: 30, width: 220, height: 36)
        let start = NSButton(title: "启用适配", target: self, action: #selector(enable))
        start.frame = NSRect(x: 270, y: 30, width: 180, height: 36)
        [title, info, label, settings, start].forEach { w.contentView?.addSubview($0) }
        w.center(); w.makeKeyAndOrderFront(nil)
        window = w
        NSApp.activate(ignoringOtherApps: true)
    }

    private func waitForPermission() {
        guard permissionTimer == nil else { return }
        permissionTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] timer in
            if AXIsProcessTrusted() { timer.invalidate(); self?.permissionTimer = nil; self?.enable() }
        }
    }

    func menuWillOpen(_ menu: NSMenu) {
        summary?.title = tap != nil && keyTap != nil ? "滚动 \(count) 次 · 控制 \(commandCount) 次" : "未启用：需辅助功能权限"
    }

    @objc private func openSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    @objc private func enable() {
        guard AXIsProcessTrusted() else {
            summary?.title = "请先在辅助功能设置中允许此应用"
            stateLabel?.stringValue = "等待辅助功能权限"
            waitForPermission()
            return
        }
        pause()
        let callback: CGEventTapCallBack = { _, type, event, pointer in
            guard let pointer = pointer else { return Unmanaged.passUnretained(event) }
            return Unmanaged<Bridge>.fromOpaque(pointer).takeUnretainedValue().handle(type, event)
        }
        // Mos 位于相同阶段的末尾；这里放在头部，顺序不依赖两者的启动先后。
        guard let port = CGEvent.tapCreate(tap: .cgAnnotatedSessionEventTap,
                place: .headInsertEventTap, options: .defaultTap,
                eventsOfInterest: CGEventMask(1) << CGEventType.scrollWheel.rawValue,
                callback: callback, userInfo: Unmanaged.passUnretained(self).toOpaque()) else {
            summary?.title = "无法建立滚动适配，请检查辅助功能权限"
            return
        }
        tap = port
        source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, port, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: port, enable: true)
        summary?.title = "UU 滚动适配已启用"
        let keyCallback: CGEventTapCallBack = { _, type, event, pointer in
            guard let pointer else { return Unmanaged.passUnretained(event) }
            return Unmanaged<Bridge>.fromOpaque(pointer).takeUnretainedValue().handleKey(type, event)
        }
        let keyMask = (CGEventMask(1) << CGEventType.keyDown.rawValue) | (CGEventMask(1) << CGEventType.keyUp.rawValue)
        guard let keyboard = CGEvent.tapCreate(tap: .cgSessionEventTap,
                place: .headInsertEventTap, options: .defaultTap, eventsOfInterest: keyMask,
                callback: keyCallback, userInfo: Unmanaged.passUnretained(self).toOpaque()) else {
            pause(); stateLabel?.stringValue = "无法建立功能控制入口，请检查权限"; writeStatus()
            return
        }
        keyTap = keyboard
        keySource = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, keyboard, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), keySource, .commonModes)
        CGEvent.tapEnable(tap: keyboard, enable: true)
        stateLabel?.stringValue = "已启用：UU 控制与滚动适配"
        writeStatus()
    }

    private func writeStatus() {
        guard !statusQueued else { return }
        statusQueued = true
        // 合并短时间内的计数更新，磁盘操作不进入输入回调。
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            self.statusQueued = false
            let value: [String: Any] = ["version": "0.2.1", "pid": ProcessInfo.processInfo.processIdentifier,
                "permission": AXIsProcessTrusted(), "keyboard_tap": self.keyTap != nil,
                "scroll_tap": self.tap != nil, "received_actions": self.receivedActions,
                "finished_actions": self.finishedActions, "cancelled_commands": self.cancelledCommands]
            self.statusWriter.async {
                try? FileManager.default.createDirectory(at: supportDirectory, withIntermediateDirectories: true)
                if let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) {
                    try? data.write(to: supportDirectory.appendingPathComponent("helper-status.json"), options: .atomic)
                }
            }
        }
    }

    private func handleKey(_ type: CGEventType, _ event: CGEvent) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let keyTap, AXIsProcessTrusted() { CGEvent.tapEnable(tap: keyTap, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        guard event.getIntegerValueField(.eventSourceUserData) != bridgeMarker,
              isUU(pid_t(event.getIntegerValueField(.eventSourceUnixProcessID))) else {
            return Unmanaged.passUnretained(event)
        }
        let key = CGKeyCode(event.getIntegerValueField(.keyboardEventKeycode))
        let now = ProcessInfo.processInfo.systemUptime
        if type == .keyUp {
            let result = commandState.release(key: key, flags: event.flags, at: now)
            if result.consumed {
                if let action = result.action { enqueue(action) }
                else { cancelledCommands += 1 }
                writeStatus()
                return nil
            }
        }
        guard type == .keyDown,
              let action = CommandProtocol.action(key: key, flags: event.flags, fromUU: true) else {
            return Unmanaged.passUnretained(event)
        }
        if commandState.press(key: key, action: action, at: now) {
            finishScroll()
            receivedActions[action, default: 0] += 1
            writeStatus()
        }
        return nil
    }

    private func enqueue(_ action: String) {
        // 慢速显示器接口执行期间，重复请求最多保留一项，抬手后不会消化长队列。
        guard pendingActions.count < 8, !pendingActions.contains(action) else { return }
        pendingActions.append(action)
        drainActions()
    }

    private func drainActions() {
        guard !actionBusy, !pendingActions.isEmpty else { return }
        actionBusy = true
        let action = pendingActions.removeFirst()
        let work = {
            let result = executeControl(action)
            DispatchQueue.main.async {
                self.commandCount += 1
                self.finishedActions[action, default: 0] += 1
                self.writeStatus()
                self.stateLabel?.stringValue = result["ok"] as? Bool == true
                    ? "已执行：\(action)" : "动作失败：\(result["error"] ?? "未知错误")"
                self.actionBusy = false
                self.drainActions()
            }
        }
        if action.hasPrefix("navigate-") || ["launchpad", "assistant", "screenshot"].contains(action) {
            DispatchQueue.main.async(execute: work)
        } else {
            actions.async(execute: work)
        }
    }

    private func isUU(_ pid: pid_t) -> Bool {
        guard pid > 0 else { return false }
        let now = ProcessInfo.processInfo.systemUptime
        if pid != cachedPID || now - cachedAt > 2 {
            cachedPID = pid
            cachedAt = now
            cachedMatch = NSRunningApplication(processIdentifier: pid)?.executableURL?.path == uuExecutable
        }
        return cachedMatch
    }

    private func handle(_ type: CGEventType, _ event: CGEvent) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap = tap, AXIsProcessTrusted() { CGEvent.tapEnable(tap: tap, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        let marker = event.getIntegerValueField(.eventSourceUserData)
        guard type == .scrollWheel, marker != bridgeMarker, marker != mosMarker,
              isUU(pid_t(event.getIntegerValueField(.eventSourceUnixProcessID))),
              event.getIntegerValueField(.scrollWheelEventIsContinuous) == 1 else {
            return Unmanaged.passUnretained(event)
        }
        count += 1
        let now = ProcessInfo.processInfo.systemUptime
        if now - lastStatusUpdate > 1 {
            lastStatusUpdate = now
            stateLabel?.stringValue = "已启用：已处理 \(count) 次 UU 滚动"
        }
        // 保留原始位移与方向，只给 Mos 可识别的连续触控滚动标记。
        event.setIntegerValueField(.scrollWheelEventScrollCount, value: 1)
        if event.getIntegerValueField(.scrollWheelEventScrollPhase) == 0
                && event.getIntegerValueField(.scrollWheelEventMomentumPhase) == 0 {
            event.setIntegerValueField(.scrollWheelEventScrollPhase, value: Int64(phaseState.next().rawValue))
            lastEvent = event.copy()
            finishWork?.cancel()
            let work = DispatchWorkItem { [weak self] in self?.finishScroll() }
            finishWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: work)
        }
        return Unmanaged.passUnretained(event)
    }

    private func finishScroll() {
        finishWork?.cancel()
        finishWork = nil
        guard phaseState.finish(), let event = lastEvent else { return }
        lastEvent = nil
        for field: CGEventField in [.scrollWheelEventDeltaAxis1, .scrollWheelEventDeltaAxis2,
                .scrollWheelEventPointDeltaAxis1, .scrollWheelEventPointDeltaAxis2,
                .scrollWheelEventFixedPtDeltaAxis1, .scrollWheelEventFixedPtDeltaAxis2] {
            event.setDoubleValueField(field, value: 0)
        }
        event.timestamp = DispatchTime.now().uptimeNanoseconds
        event.setIntegerValueField(.eventSourceUserData, value: bridgeMarker)
        event.setIntegerValueField(.scrollWheelEventScrollPhase, value: Int64(CGScrollPhase.ended.rawValue))
        let target = pid_t(event.getIntegerValueField(.eventTargetUnixProcessID))
        if target > 0 { event.postToPid(target) }
        // 没有原目标时不补发，避免将结束事件投到其他窗口。
    }

    @objc private func pause() {
        permissionTimer?.invalidate(); permissionTimer = nil
        finishScroll()
        if let tap = tap { CGEvent.tapEnable(tap: tap, enable: false); CFMachPortInvalidate(tap) }
        if let source = source { CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes) }
        tap = nil
        source = nil
        if let keyTap { CGEvent.tapEnable(tap: keyTap, enable: false); CFMachPortInvalidate(keyTap) }
        if let keySource { CFRunLoopRemoveSource(CFRunLoopGetMain(), keySource, .commonModes) }
        keyTap = nil; keySource = nil
        commandState.reset(); pendingActions.removeAll()
        stateLabel?.stringValue = "已暂停"
    }
    @objc private func quit() { pause(); NSApp.terminate(nil) }
    func applicationWillTerminate(_ notification: Notification) { pause() }
}
