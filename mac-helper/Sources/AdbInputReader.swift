import Foundation

// 一个有界读取线程复用已经配对的 ADB，不在局域网开放新的监听端口。
final class AdbInputReader {
    private let lock = NSLock()
    private var stopped = false
    private var process: Process?
    private var lastData = ProcessInfo.processInfo.systemUptime
    private var streaming = false
    private var watchdog: DispatchSourceTimer?
    private let onFrame: (InputFrame) -> Void
    private let onState: (String) -> Void
    private let package: String

    init(package: String = "com.netease.uuremote", onFrame: @escaping (InputFrame) -> Void, onState: @escaping (String) -> Void) {
        precondition(["com.netease.uuremote", "com.limelight"].contains(package))
        self.package = package
        self.onFrame = onFrame
        self.onState = onState
    }

    private var running: Bool { lock.withLock { !stopped } }

    func start() {
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            self.lock.withLock {
                if self.streaming && ProcessInfo.processInfo.systemUptime - self.lastData > 4 {
                    self.process?.terminate()
                }
            }
        }
        watchdog = timer
        timer.resume()
        Thread.detachNewThread { [self] in loop() }
    }

    func stop() {
        lock.withLock {
            stopped = true
            if process?.isRunning == true { process?.terminate() }
        }
        watchdog?.cancel()
        watchdog = nil
    }

    private func state(_ value: String) {
        DispatchQueue.main.async { [self] in if running { onState(value) } }
    }

    private func launch(_ config: ConnectionConfiguration, _ arguments: [String]) -> (Process, Pipe)? {
        let task = Process(), pipe = Pipe()
        task.executableURL = URL(fileURLWithPath: config.adb)
        task.arguments = arguments
        task.standardOutput = pipe
        task.standardError = FileHandle.nullDevice
        task.standardInput = FileHandle.nullDevice
        return lock.withLock {
            guard !stopped else { return nil }
            do { try task.run(); process = task; return (task, pipe) }
            catch { return nil }
        }
    }

    private func command(_ config: ConnectionConfiguration, _ arguments: [String]) -> String {
        guard let (task, pipe) = launch(config, arguments) else { return "" }
        let timeout = DispatchWorkItem { if task.isRunning { task.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 5, execute: timeout)
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        task.waitUntilExit()
        timeout.cancel()
        try? pipe.fileHandleForReading.close()
        lock.withLock { if process === task { process = nil } }
        guard data.count <= 65536, task.terminationStatus == 0 else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func identify(_ config: ConnectionConfiguration, _ candidate: String) -> Bool {
        command(config, ["-s", candidate, "shell", "getprop", "ro.serialno"])
            .trimmingCharacters(in: .whitespacesAndNewlines) == config.deviceSerial
    }

    private func remember(_ config: ConnectionConfiguration, _ endpoint: String) {
        guard endpoint.contains(":"), ConnectionConfiguration.validEndpoint(endpoint),
              config.endpoint != endpoint else { return }
        var updated = config
        updated.endpoint = endpoint
        if let data = try? JSONEncoder().encode(updated) {
            try? data.write(to: supportDirectory.appendingPathComponent("connection.json"), options: .atomic)
        }
    }

    private func discover(_ config: ConnectionConfiguration) -> String? {
        let devices = command(config, ["devices"]).split(separator: "\n").compactMap { line -> String? in
            let fields = line.split(whereSeparator: { $0.isWhitespace })
            return fields.count >= 2 && fields[1] == "device" ? String(fields[0]) : nil
        }
        for candidate in devices.prefix(16) where running {
            if identify(config, candidate) { remember(config, candidate); return candidate }
        }
        if let endpoint = config.endpoint, running {
            _ = command(config, ["connect", endpoint])
            if identify(config, endpoint) { return endpoint }
        }
        guard config.discovery, running else { return nil }
        let services = command(config, ["mdns", "services"]).split(separator: "\n")
        for service in services.prefix(32) where running {
            let fields = service.split(whereSeparator: { $0.isWhitespace }).map(String.init)
            guard fields.count == 3, fields[1].hasPrefix("_adb-tls-connect._tcp"),
                  fields[0].hasPrefix("adb-" + config.deviceSerial + "-"),
                  ConnectionConfiguration.validEndpoint(fields[2]) else { continue }
            _ = command(config, ["connect", fields[2]])
            if identify(config, fields[2]) { remember(config, fields[2]); return fields[2] }
        }
        return nil
    }

    private func read(_ config: ConnectionConfiguration, device: String) {
        let base = "/data/user/0/\(package)/files/pad_uu_input"
        // 锁只覆盖本通道，防止断线后两个 cat 同时分食 FIFO；文件始终为零字节。
        // Android mksh 的 exec 重定向默认带 CLOEXEC，显式传递 9 才能让锁跨进程保留。
        let remote = "su -c 'umask 077; exec 9>\(base).lock; flock -n 9 9>&9 || exit 75; exec cat \(base).pipe 9>&9'"
        guard let (task, pipe) = launch(config, ["-s", device, "exec-out", remote]) else { return }
        lock.withLock { streaming = true; lastData = ProcessInfo.processInfo.systemUptime }
        state("waiting")
        var buffer = Data()
        while running && task.isRunning {
            let data = pipe.fileHandleForReading.availableData
            if data.isEmpty { break }
            lock.withLock { lastData = ProcessInfo.processInfo.systemUptime }
            buffer.append(data)
            var malformed = false
            while let end = buffer.firstIndex(of: 10) {
                let line = Data(buffer[..<end])
                buffer.removeSubrange(...end)
                guard let frame = InputFrame.decode(line) else { malformed = true; break }
                // 同步交付形成背压，不积累跨线程的无界滚动队列。
                DispatchQueue.main.sync { if running { onFrame(frame) } }
            }
            if malformed || buffer.count > 4096 { break }
        }
        if task.isRunning { task.terminate() }
        task.waitUntilExit()
        try? pipe.fileHandleForReading.close()
        lock.withLock { streaming = false; if process === task { process = nil } }
        state("disconnected")
    }

    private func loop() {
        while running {
            let configURL = supportDirectory.appendingPathComponent("connection.json")
            if let data = try? Data(contentsOf: configURL), data.count <= 4096,
               let config = try? JSONDecoder().decode(ConnectionConfiguration.self, from: data), config.valid {
                if let device = discover(config), running { read(config, device: device) }
                else { state("device-unavailable") }
            } else { state("configuration-required") }
            // 后台重试不阻塞主线程；停止最多等待这一小段退避时间。
            for _ in 0..<20 where running { Thread.sleep(forTimeInterval: 0.1) }
        }
    }
}
