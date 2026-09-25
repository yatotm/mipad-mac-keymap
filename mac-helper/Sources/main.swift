import AppKit

if CommandLine.arguments.contains("--self-test") {
    precondition(CommandProtocol.actions.count == 15)
    precondition(CommandProtocol.action(key: 103, flags: CommandProtocol.modifiers, fromUU: true) == "volume-down")
    precondition(CommandProtocol.action(key: 103, flags: [], fromUU: true) == nil)
    precondition(CommandProtocol.action(key: 103, flags: CommandProtocol.modifiers, fromUU: false) == nil)
    precondition(CommandProtocol.action(key: 0, flags: CommandProtocol.modifiers, fromUU: true) == nil)
    precondition(CommandProtocol.action(key: 123, flags: CommandProtocol.modifiers, fromUU: true) == "navigate-back")
    var phase = ScrollPhaseState()
    precondition(phase.next() == .began)
    precondition(phase.next() == .changed)
    precondition(phase.finish() && !phase.finish())
    var commands = CommandState()
    precondition(commands.press(key: 49, action: "launchpad", at: 1))
    precondition(!commands.press(key: 49, action: "launchpad", at: 1.01))
    let held = commands.release(key: 49, flags: CommandProtocol.modifiers, at: 1.02)
    precondition(held.consumed && held.action == nil)
    precondition(commands.press(key: 49, action: "launchpad", at: 2))
    precondition(commands.release(key: 49, flags: [], at: 2.02).action == "launchpad")
    precondition(!commands.release(key: 49, flags: [], at: 2.03).consumed)
    precondition(commands.press(key: 103, action: "volume-down", at: 3))
    precondition(commands.release(key: 103, flags: [], at: 6).action == nil)
    precondition(commands.press(key: 123, action: "navigate-back", at: 7))
    commands.reset()
    precondition(!commands.release(key: 123, flags: [], at: 7.1).consumed)
    print("Mac 控制协议、来源过滤和滚动阶段检查通过；未监听或发送输入事件。")
} else {
    let app = NSApplication.shared
    let bridge = Bridge()
    app.setActivationPolicy(.accessory)
    app.delegate = bridge
    app.run()
}
