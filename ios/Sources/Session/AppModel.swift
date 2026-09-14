import Foundation
import UIKit

/// 会话中枢：唯一持有实时资源的地方（对齐 Android 侧 SessionHub）。
///
/// UI 只读 `state` / `settings` 两个 @Published，不直接碰音频与网络。
@MainActor
final class AppModel: ObservableObject {

    @Published private(set) var state = UiState()
    @Published private(set) var settings = SettingsState()

    // MARK: - 常量

    /// 转写片段间隔超过这个值，就认为换了一轮对话，切成新字幕并提交翻译。
    private let turnGapMs: Int64 = 1200
    /// 说完之后静默这么久就把当前字幕定稿。
    private let idleFlushMs: Int64 = 1400
    /// 字幕最多保留多少条，避免长时间会话把内存吃满。
    private let maxCaptions = 300

    // MARK: - 内部

    private let prefs = Prefs()

    private var engine: VoiceAudioEngine?
    private var socket: LiveSocket?
    private var ticker: Timer?
    private var closeWatchdog: Task<Void, Never>?

    private var sessionLive = false
    private var translationNoticeShown = false
    private var nextCaptionId: Int64 = 1

    private var micLevelHolder: Float = 0
    private var playbackLevelHolder: Float = 0

    private struct TurnTracker {
        var captionId: Int64 = 0
        var lastEndMs: Int64 = 0
        var lastUpdateAt: CFTimeInterval = 0
    }

    private var learnerTurn = TurnTracker()
    private var coachTurn = TurnTracker()

    private var translateWorker: Task<Void, Never>?
    private var translateContinuation: AsyncStream<Int64>.Continuation?

    private lazy var translateStream: AsyncStream<Int64> = AsyncStream(bufferingPolicy: .unbounded) { continuation in
        self.translateContinuation = continuation
    }

    // MARK: - 初始化

    init() {
        settings = SettingsState(
            gatewayBase: prefs.gatewayBase,
            voice: prefs.voice,
            level: prefs.level,
            autoTranslate: prefs.autoTranslate,
            activated: prefs.isActivated,
            accountId: prefs.accountId,
        )
        startTranslateWorker()
        Task { await refreshAccount() }
    }

    // MARK: - 账号

    func redeem(code: String) async -> Result<String, Error> {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return .failure(GatewayError(code: "invalid_code", message: "请输入兑换码", httpStatus: 0))
        }
        do {
            let json = try await GatewayAPI.redeem(
                base: prefs.gatewayBase,
                code: trimmed,
                deviceId: prefs.deviceId,
                deviceName: prefs.deviceName,
            )
            let token = (json["accessToken"] as? String) ?? ""
            if !token.isEmpty {
                prefs.accessToken = token
            } else if prefs.accessToken.isEmpty {
                return .failure(GatewayError(
                    code: "token_missing",
                    message: "该兑换码已在本设备使用过。若令牌丢失，请联系客服轮换后重试。",
                    httpStatus: 0,
                ))
            }
            let accountId = (json["accountId"] as? String) ?? ""
            if !accountId.isEmpty { prefs.accountId = accountId }
            let remaining = intValue(json["remainingSeconds"])
            settings.activated = true
            settings.accountId = accountId.isEmpty ? settings.accountId : accountId
            settings.balanceMicroUsd = int64Value(json["balanceMicroUsd"])
            settings.remainingSeconds = remaining
            if let rate = json["usdCny"] as? Double { settings.usdCny = rate }
            return .success("已激活，可聊 \(formatRemaining(remaining))")
        } catch {
            return .failure(error)
        }
    }

    func refreshAccount() async {
        guard !prefs.accessToken.isEmpty else { return }
        do {
            let json = try await GatewayAPI.me(base: prefs.gatewayBase, token: prefs.accessToken)
            settings.activated = true
            settings.accountId = (json["accountId"] as? String) ?? settings.accountId
            settings.status = (json["status"] as? String) ?? "active"
            settings.balanceMicroUsd = int64Value(json["balanceMicroUsd"])
            settings.remainingSeconds = intValue(json["remainingSeconds"])
            if let rate = json["usdCny"] as? Double { settings.usdCny = rate }
        } catch {
            // 刷新失败不打扰用户：离线也应能进设置页改地址
        }
    }

    func signOut() {
        prefs.clearCredentials()
        settings.activated = false
        settings.accountId = ""
        settings.remainingSeconds = 0
        settings.balanceMicroUsd = 0
    }

    // MARK: - 设置

    func updateGateway(_ value: String) {
        prefs.gatewayBase = value
        settings.gatewayBase = prefs.gatewayBase
    }

    func updateVoice(_ value: String) {
        prefs.voice = value
        settings.voice = value
    }

    func updateLevel(_ value: String) {
        prefs.level = value
        settings.level = value
    }

    func updateAutoTranslate(_ value: Bool) {
        prefs.autoTranslate = value
        settings.autoTranslate = value
    }

    func clearMessages() {
        state.error = nil
        state.notice = nil
    }

    /// 关掉结算单，回到可以再次开始的状态。
    func dismissResult() {
        state.result = nil
        state.notice = nil
        state.error = nil
        if state.phase == .ended { state.phase = .idle }
    }

    // MARK: - 会话

    func startSession() {
        guard !state.inSession else { return }
        guard !prefs.accessToken.isEmpty else {
            state.error = "请先输入兑换码激活"
            return
        }

        translationNoticeShown = false
        resetTurns()
        state = UiState(phase: .connecting, remainingSeconds: settings.remainingSeconds)

        let audio = VoiceAudioEngine()
        audio.muted = false
        audio.onFrame = { [weak self] data in
            Task { @MainActor in self?.sendAudioFrame(data) }
        }
        audio.onMicLevel = { [weak self] level in
            Task { @MainActor in self?.micLevelHolder = level }
        }
        audio.onPlaybackLevel = { [weak self] level in
            Task { @MainActor in self?.playbackLevelHolder = level }
        }
        do {
            try audio.start()
        } catch {
            state.phase = .idle
            state.error = "麦克风启动失败：\(error.localizedDescription)"
            return
        }
        engine = audio

        guard let url = LiveSocket.webSocketURL(base: prefs.gatewayBase, path: "/v1/live/sessions") else {
            audio.stop()
            engine = nil
            state.phase = .idle
            state.error = "服务器地址无效，请到设置页检查"
            return
        }

        let live = LiveSocket(
            onOpen: { [weak self] in Task { @MainActor in self?.handleSocketOpen() } },
            onEvent: { [weak self] event in Task { @MainActor in self?.handleGatewayEvent(event) } },
            onFailure: { [weak self] error in Task { @MainActor in self?.handleSocketFailure(error) } },
            onClosed: { [weak self] code, reason in Task { @MainActor in self?.handleSocketClosed(code, reason) } },
        )
        socket = live
        live.connect(url: url, token: prefs.accessToken, deviceId: prefs.deviceId)
        startTicker()
    }

    func endSession() {
        guard state.inSession else { return }
        state.phase = .closing
        socket?.send(["type": "session.close"])
        closeWatchdog?.cancel()
        closeWatchdog = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard let self, !Task.isCancelled else { return }
            if self.state.phase == .closing {
                self.state.error = "未收到最终结算，用量以服务端账单为准"
                self.finish(result: nil)
            }
        }
    }

    func toggleMute() {
        guard let audio = engine else { return }
        let next = !state.muted
        audio.muted = next
        if next { audio.flushPlayback() }
        socket?.send(["type": next ? "session.input_audio.mute" : "session.input_audio.unmute"])
        state.muted = next
        state.phase = next ? .muted : .listening
    }

    // MARK: - WebSocket 事件

    private func handleSocketOpen() {
        // 只提交教学相关的字段：model / delegation / store 由网关强制覆写，
        // 客户端提交也会被丢弃。
        socket?.send([
            "type": "session.start",
            "event_id": "start_\(Int(Date().timeIntervalSince1970 * 1000))",
            "session": [
                "instructions": Const.instructions(for: prefs.level),
                "audio": [
                    "format": ["rate": engine?.sampleRate ?? Const.AUDIO_RATE],
                    "output": ["voice": prefs.voice],
                ],
            ],
        ])
    }

    private func handleGatewayEvent(_ event: [String: Any]) {
        switch (event["type"] as? String) ?? "" {
        case "gateway.ready":
            state.remainingSeconds = intValue(event["remainingSeconds"])
            state.sessionCostMicroUsd = 0

        case "session.started":
            sessionLive = true
            state.phase = .listening

        case "gateway.usage":
            state.elapsedSeconds = intValue(event["elapsedSeconds"])
            state.remainingSeconds = intValue(event["remainingSeconds"])
            state.sessionCostMicroUsd = int64Value(event["costMicroUsd"])

        case "gateway.notice":
            let message = (event["message"] as? String) ?? ""
            state.notice = message.isEmpty ? nil : message

        case "gateway.session.terminated":
            let message = (event["message"] as? String) ?? ""
            state.notice = message.isEmpty ? "会话已结束" : message

        case "gateway.closed":
            let result = SessionResult(
                voiceSeconds: intValue(event["voiceSeconds"]),
                costMicroUsd: int64Value(event["costMicroUsd"]),
                remainingSeconds: intValue(event["remainingSeconds"]),
                closeReason: (event["closeReason"] as? String) ?? "unknown",
                unmetered: (event["unmetered"] as? Bool) ?? false,
            )
            settings.remainingSeconds = result.remainingSeconds
            finish(result: result)

        case "session.output_audio.delta":
            if let base64 = event["delta"] as? String, !base64.isEmpty,
               let pcm = Data(base64Encoded: base64) {
                engine?.enqueue(pcm: pcm)
            }

        case "session.input_transcript.delta":
            handleTranscript(
                speaker: .learner,
                delta: (event["delta"] as? String) ?? "",
                startMs: int64Value(event["start_ms"]),
                endMs: int64Value(event["end_ms"]),
            )

        case "session.output_transcript.delta":
            handleTranscript(
                speaker: .coach,
                delta: (event["delta"] as? String) ?? "",
                startMs: int64Value(event["start_ms"]),
                endMs: int64Value(event["end_ms"]),
            )

        case "error":
            let error = event["error"] as? [String: Any]
            let code = (error?["code"] as? String) ?? ""
            let message = (error?["message"] as? String) ?? ""
            // session_not_ready 属于正常竞态（音频早于 started），不打扰用户
            if code != "session_not_ready" {
                state.error = message.isEmpty ? code : message
            }

        default:
            break
        }
    }

    private func handleSocketFailure(_ error: Error) {
        guard state.inSession else { return }
        state.error = "连接中断：\(error.localizedDescription)"
        finish(result: nil)
    }

    private func handleSocketClosed(_ code: Int, _ reason: String) {
        guard state.inSession else { return }
        finish(result: nil)
    }

    // MARK: - 音频上行

    private func sendAudioFrame(_ pcm: Data) {
        guard sessionLive, engine?.muted != true else { return }
        socket?.send([
            "type": "session.input_audio.append",
            "audio": pcm.base64EncodedString(),
        ])
    }

    // MARK: - 字幕

    private func handleTranscript(speaker: Speaker, delta: String, startMs: Int64, endMs: Int64) {
        guard !delta.isEmpty else { return }
        var tracker = speaker == .learner ? learnerTurn : coachTurn

        // 间隔够大 → 上一轮结束，切新字幕
        if tracker.captionId != 0, tracker.lastEndMs > 0, startMs - tracker.lastEndMs > turnGapMs {
            closeCaption(speaker: speaker)
            tracker = speaker == .learner ? learnerTurn : coachTurn
        }

        if tracker.captionId == 0 {
            let id = nextCaptionId
            nextCaptionId += 1
            tracker.captionId = id
            state.captions.append(Caption(id: id, speaker: speaker, english: delta))
            if state.captions.count > maxCaptions {
                state.captions.removeFirst(state.captions.count - maxCaptions)
            }
        } else {
            let id = tracker.captionId
            if let index = state.captions.firstIndex(where: { $0.id == id }) {
                state.captions[index].english += delta
            }
        }
        tracker.lastEndMs = endMs
        tracker.lastUpdateAt = CACurrentMediaTime()
        if speaker == .learner { learnerTurn = tracker } else { coachTurn = tracker }
    }

    private func closeCaption(speaker: Speaker) {
        var tracker = speaker == .learner ? learnerTurn : coachTurn
        let id = tracker.captionId
        tracker.captionId = 0
        tracker.lastEndMs = 0
        if speaker == .learner { learnerTurn = tracker } else { coachTurn = tracker }
        if id != 0 { translateContinuation?.yield(id) }
    }

    private func resetTurns() {
        learnerTurn = TurnTracker()
        coachTurn = TurnTracker()
    }

    // MARK: - 翻译

    private func startTranslateWorker() {
        guard translateWorker == nil else { return }
        let stream = translateStream
        translateWorker = Task { [weak self] in
            for await id in stream {
                guard let self else { break }
                await self.translateCaption(id)
            }
        }
    }

    private func translateCaption(_ id: Int64) async {
        guard prefs.autoTranslate else { return }
        guard let index = state.captions.firstIndex(where: { $0.id == id }) else { return }
        let caption = state.captions[index]
        guard caption.chinese == nil, !caption.english.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        do {
            let texts = try await GatewayAPI.translate(
                base: prefs.gatewayBase,
                token: prefs.accessToken,
                texts: [caption.english],
            )
            guard let chinese = texts.first, !chinese.isEmpty else { return }
            if let current = state.captions.firstIndex(where: { $0.id == id }) {
                state.captions[current].chinese = chinese
            }
        } catch {
            if !translationNoticeShown {
                translationNoticeShown = true
                state.notice = "中文对照暂时不可用（\(error.localizedDescription)）"
            }
        }
    }

    // MARK: - 心跳

    private func startTicker() {
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func tick() {
        let now = CACurrentMediaTime()
        if sessionLive {
            if learnerTurn.captionId != 0,
               now - learnerTurn.lastUpdateAt > Double(idleFlushMs) / 1000 {
                closeCaption(speaker: .learner)
            }
            if coachTurn.captionId != 0,
               now - coachTurn.lastUpdateAt > Double(idleFlushMs) / 1000 {
                closeCaption(speaker: .coach)
            }
        }
        refreshPhase()
        state.micLevel = micLevelHolder
        state.playbackLevel = playbackLevelHolder
    }

    /// 「模型在说话」一律由播放队列推导 —— 官方没有播放完成事件。
    private func refreshPhase() {
        guard sessionLive, let audio = engine else { return }
        let next: Phase
        if state.muted {
            next = .muted
        } else if audio.isSpeaking() {
            next = .speaking
        } else {
            next = .listening
        }
        if state.phase != next { state.phase = next }
    }

    // MARK: - 收尾

    private func finish(result: SessionResult?) {
        sessionLive = false
        closeWatchdog?.cancel()
        closeWatchdog = nil
        ticker?.invalidate()
        ticker = nil

        engine?.stop()
        engine = nil
        socket?.close()
        socket = nil

        state.phase = .ended
        if let result { state.result = result }
        state.micLevel = 0
        state.playbackLevel = 0
        state.muted = false
        micLevelHolder = 0
        playbackLevelHolder = 0

        Task { await refreshAccount() }
    }

    // MARK: - 工具

    private func intValue(_ any: Any?) -> Int {
        if let n = any as? Int { return n }
        if let n = any as? NSNumber { return n.intValue }
        if let d = any as? Double { return Int(d) }
        return 0
    }

    private func int64Value(_ any: Any?) -> Int64 {
        if let n = any as? Int64 { return n }
        if let n = any as? Int { return Int64(n) }
        if let n = any as? NSNumber { return n.int64Value }
        if let d = any as? Double { return Int64(d) }
        return 0
    }
}
