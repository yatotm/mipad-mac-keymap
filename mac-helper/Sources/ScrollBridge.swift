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
        item.button?.title = "UU↕"
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
        if AXIsProcessTrusted() { enable() } else { waitForPermission() }
    }

    private func showWindow() {
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 260),
                         styleMask: [.titled, .closable], backing: .buffered, defer: false)
        w.title = "UU 滚动适配"
        let title = NSTextField(labelWithString: "UU 滚动适配")
        title.font = .boldSystemFont(ofSize: 22)
        title.frame = NSRect(x: 24, y: 205, width: 470, height: 32)
        let info = NSTextField(wrappingLabelWithString:
            "只修正 UU 传来的滚动，本地鼠标继续使用 Mos 原有设置。\n需要在系统的辅助功能权限列表中允许此程序。")
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
        summary?.title = tap != nil ? "已处理 \(count) 次 UU 滚动" : "未启用：需辅助功能权限"
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
        stateLabel?.stringValue = "已启用：仅处理 UU 滚动"
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
        stateLabel?.stringValue = "已暂停"
    }
    @objc private func quit() { pause(); NSApp.terminate(nil) }
    func applicationWillTerminate(_ notification: Notification) { pause() }
}

if CommandLine.arguments.contains("--self-test") {
    var phase = ScrollPhaseState()
    precondition(phase.next() == .began)
    precondition(phase.next() == .changed)
    precondition(phase.finish())
    precondition(!phase.finish())
    precondition(phase.next() == .began)
    let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel,
                        wheelCount: 2, wheel1: 7, wheel2: -3, wheel3: 0)!
    let x = event.getDoubleValueField(.scrollWheelEventPointDeltaAxis2)
    let y = event.getDoubleValueField(.scrollWheelEventPointDeltaAxis1)
    event.setIntegerValueField(.scrollWheelEventScrollCount, value: 1)
    event.setIntegerValueField(.scrollWheelEventScrollPhase, value: Int64(CGScrollPhase.began.rawValue))
    precondition(event.getDoubleValueField(.scrollWheelEventPointDeltaAxis2) == x)
    precondition(event.getDoubleValueField(.scrollWheelEventPointDeltaAxis1) == y)
    print("滚动阶段状态与位移保留检查通过；未安装事件拦截器，未发送输入事件。")
} else {
    let app = NSApplication.shared
    let bridge = Bridge()
    app.setActivationPolicy(.accessory)
    app.delegate = bridge
    app.run()
}
