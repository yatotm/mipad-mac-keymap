import AppKit

if CommandLine.arguments.contains("--self-test") {
    func frame(_ body: String) -> InputFrame { InputFrame.decode(Data(body.utf8))! }
    let sync = frame(#"{"v":1,"t":"sync","at":1000,"mods":0}"#)
    let action = frame(#"{"v":1,"t":"action","at":1020,"a":"launchpad"}"#)
    precondition(InputFrame.decode(Data(#"{"v":1,"t":"action","at":1,"a":"run-shell"}"#.utf8)) == nil)
    precondition(InputFrame.decode(Data(#"{"v":2,"t":"reset","at":1}"#.utf8)) == nil)
    precondition(InputFrame.decode(Data(#"{"v":1,"t":"scroll","at":1,"x":9000,"y":0,"phase":"changed","mods":0}"#.utf8)) == nil)
    precondition(InputFrame.decode(Data(#"{"v":1,"t":"scroll","at":1,"x":0,"y":0,"phase":"unknown","mods":0}"#.utf8)) == nil)
    var clock = InputClock()
    precondition(!clock.accepts(action, now: 20))
    precondition(clock.accepts(sync, now: 20))
    precondition(clock.accepts(action, now: 20.03))
    let delayed = frame(#"{"v":1,"t":"action","at":1050,"a":"volume-down"}"#)
    precondition(!clock.accepts(delayed, now: 20.7))
    precondition(!clock.accepts(sync, now: 21))
    precondition(clock.accepts(frame(#"{"v":1,"t":"sync","at":2000,"mods":0}"#), now: 21.01))
    precondition(!clock.accepts(frame(#"{"v":1,"t":"action","at":2010,"a":"launchpad"}"#), now: 21.9))
    precondition(androidModifiers(0) == [])
    precondition(androidModifiers(0x11003) == [.maskShift, .maskAlternate, .maskControl, .maskCommand])
    precondition(androidModifiers(0x8) == [])
    precondition(ConnectionConfiguration.validEndpoint("pad.example.ts.net:23333"))
    precondition(ConnectionConfiguration.validEndpoint("[fd00::1]:23333"))
    precondition(!ConnectionConfiguration.validEndpoint("host;id"))
    precondition(!ConnectionConfiguration.validEndpoint("--listen"))
    for (name, scrollPhase, momentumPhase) in [("began", 1, 0), ("changed", 2, 0), ("ended", 4, 0),
             ("cancelled", 8, 0), ("momentum-began", 0, 1), ("momentum-changed", 0, 2), ("momentum-ended", 0, 3)] {
        let event = NativeScroll.event(source: CGEventSource(stateID: .privateState), x: 3, y: -4,
                                      phase: name, flags: [])!
        precondition(event.flags.isEmpty)
        precondition(event.getIntegerValueField(.scrollWheelEventScrollPhase) == scrollPhase)
        precondition(event.getIntegerValueField(.scrollWheelEventMomentumPhase) == momentumPhase)
        precondition(event.getIntegerValueField(.scrollWheelEventPointDeltaAxis1) == -4)
        precondition(event.getIntegerValueField(.scrollWheelEventPointDeltaAxis2) == 3)
        precondition(event.getIntegerValueField(.scrollWheelEventScrollCount) == 1)
    }
    var phase = ScrollPhaseState()
    precondition(phase.next() == .began && phase.next() == .changed)
    precondition(phase.finish() && !phase.finish())
    print("输入帧白名单、断线时钟、修饰位与原生滚动阶段检查通过；没有发送输入事件。")
} else {
    let app = NSApplication.shared
    let bridge = Bridge()
    app.setActivationPolicy(.accessory)
    app.delegate = bridge
    app.run()
}
