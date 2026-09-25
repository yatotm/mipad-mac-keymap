import CoreGraphics

/** 控制组合先登记，修饰键释放后的动作键 KeyUp 才提交，避免把修饰状态带入系统界面。 */
struct CommandState {
    private var pending: [CGKeyCode: (action: String, at: Double)] = [:]

    mutating func press(key: CGKeyCode, action: String, at time: Double) -> Bool {
        pending = pending.filter { time - $0.value.at <= 2 }
        guard pending[key] == nil else { return false }
        pending[key] = (action, time)
        return true
    }

    mutating func release(key: CGKeyCode, flags: CGEventFlags, at time: Double) -> (consumed: Bool, action: String?) {
        guard let value = pending.removeValue(forKey: key) else { return (false, nil) }
        guard time >= value.at, time - value.at <= 2,
              flags.intersection(CommandProtocol.modifiers).isEmpty else { return (true, nil) }
        return (true, value.action)
    }

    mutating func reset() { pending.removeAll() }
}
