import Foundation

/// 到 SpeakMate 网关的 WebSocket 连接。
///
/// 只做收发与 JSON 解析，不含业务逻辑；事件处理在 AppModel 里。
final class LiveSocket: NSObject {

    private let onOpen: () -> Void
    private let onEvent: ([String: Any]) -> Void
    private let onFailure: (Error) -> Void
    private let onClosed: (Int, String) -> Void

    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    private var pingTimer: Timer?

    private(set) var isConnected = false

    init(
        onOpen: @escaping () -> Void,
        onEvent: @escaping ([String: Any]) -> Void,
        onFailure: @escaping (Error) -> Void,
        onClosed: @escaping (Int, String) -> Void,
    ) {
        self.onOpen = onOpen
        self.onEvent = onEvent
        self.onFailure = onFailure
        self.onClosed = onClosed
        super.init()
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 30
        cfg.waitsForConnectivity = true
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
    }

    func connect(url: URL, token: String, deviceId: String) {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(deviceId, forHTTPHeaderField: "X-Device-Id")
        request.setValue("SpeakMate-iOS", forHTTPHeaderField: "User-Agent")

        let socketTask = session.webSocketTask(with: request)
        task = socketTask
        socketTask.resume()
        receiveLoop(socketTask)
    }

    @discardableResult
    func send(_ payload: [String: Any]) -> Bool {
        guard let socketTask = task,
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8)
        else { return false }
        socketTask.send(.string(text)) { _ in }
        return true
    }

    func close() {
        stopPing()
        isConnected = false
        let socketTask = task
        task = nil
        socketTask?.cancel(with: .normalClosure, reason: nil)
    }

    // MARK: - 内部

    private func receiveLoop(_ socketTask: URLSessionWebSocketTask) {
        socketTask.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error):
                self.isConnected = false
                self.stopPing()
                self.onFailure(error)
            case .success(let message):
                switch message {
                case .string(let text):
                    self.dispatch(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) { self.dispatch(text) }
                @unknown default:
                    break
                }
                self.receiveLoop(socketTask)
            }
        }
    }

    private func dispatch(_ text: String) {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let payload = object as? [String: Any]
        else { return }
        onEvent(payload)
    }

    /// nginx 与中间设备默认会掐掉空闲长连接，20 秒一次心跳保活。
    private func startPing() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.stopPing()
            self.pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
                self?.task?.sendPing { _ in }
            }
        }
    }

    private func stopPing() {
        DispatchQueue.main.async { [weak self] in
            self?.pingTimer?.invalidate()
            self?.pingTimer = nil
        }
    }

    // MARK: - 地址归一化

    /// 把用户填的 http(s) 地址转成 WebSocket 地址。
    static func webSocketURL(base: String, path: String) -> URL? {
        let trimmed = base.trimmingCharacters(in: .whitespacesAndNewlines)
        let stripped = trimmed.hasSuffix("/") ? String(trimmed.dropLast()) : trimmed
        let schemeFixed: String
        if stripped.hasPrefix("https://") {
            schemeFixed = "wss://" + stripped.dropFirst("https://".count)
        } else if stripped.hasPrefix("http://") {
            schemeFixed = "ws://" + stripped.dropFirst("http://".count)
        } else if stripped.hasPrefix("wss://") || stripped.hasPrefix("ws://") {
            schemeFixed = stripped
        } else {
            schemeFixed = "wss://" + stripped
        }
        return URL(string: schemeFixed + path)
    }
}

extension LiveSocket: URLSessionWebSocketDelegate {

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?,
    ) {
        isConnected = true
        startPing()
        onOpen()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?,
    ) {
        isConnected = false
        stopPing()
        let text = reason.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        onClosed(closeCode.rawValue, text)
    }
}
