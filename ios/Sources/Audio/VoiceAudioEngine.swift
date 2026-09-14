import AVFoundation
import Foundation

/// 音频引擎：麦克风采集 + 流式播放（对齐 Android 侧 AudioEngine 的行为）。
///
/// 几个必须做对的地方：
///   1. 会话用 `.playAndRecord` + `.voiceChat` 模式，并开启 Voice Processing，
///      否则外放时模型会听到自己的声音并不断自我打断 —— 全双工语音最常见的翻车点。
///   2. 采集到的硬件格式（通常是 48kHz Float32）统一转换成 24kHz 单声道 PCM16，
///      上行帧固定 100ms（4800 字节），且字节数一定是偶数。
///   3. 播放队列要设上限，慢网络下宁可丢旧帧也不能让延迟无限累积。
final class VoiceAudioEngine {

    // MARK: - 对外

    var onFrame: ((Data) -> Void)?
    var onMicLevel: ((Float) -> Void)?
    var onPlaybackLevel: ((Float) -> Void)?

    /// 静音时只停上行，不打断正在播放的模型语音（由上层决定是否清空播放）。
    var muted = false

    let sampleRate: Int = Const.AUDIO_RATE

    // MARK: - 内部

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let audioSession = AVAudioSession.sharedInstance()

    /// 与网关约定的线上格式：24kHz 单声道 Int16 交错。
    private let wireFormat: AVAudioFormat
    /// 采集转换目标：24kHz 单声道 Float32（非交错），转换器处理起来最省事。
    private let captureFormat: AVAudioFormat
    private var captureConverter: AVAudioConverter?

    private let frameBytes: Int
    private var pendingUpload = Data()

    private let lock = NSLock()
    private var queuedBytes = 0
    private var lastPlayedBackAt: CFTimeInterval = 0
    private var running = false

    private var maxQueueBytes: Int {
        Const.AUDIO_RATE * 2 * Const.MAX_PLAYBACK_QUEUE_MS / 1000
    }

    init() {
        // 这两个格式是固定常量，构造失败说明运行环境异常，直接崩比静默降级好排查。
        wireFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(Const.AUDIO_RATE),
            channels: 1,
            interleaved: true,
        )!
        captureFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(Const.AUDIO_RATE),
            channels: 1,
            interleaved: false,
        )!
        frameBytes = Const.AUDIO_RATE * 2 * Const.FRAME_MS / 1000
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: wireFormat)
    }

    // MARK: - 生命周期

    func start() throws {
        guard !running else { return }

        try audioSession.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.defaultToSpeaker, .allowBluetooth],
        )
        try audioSession.setActive(true, options: [])

        let input = engine.inputNode
        // Voice Processing 提供系统级回声消除（等价 Android 的 AcousticEchoCanceler）
        if #available(iOS 13.0, *) {
            try? input.setVoiceProcessingEnabled(true)
        }

        let hardwareFormat = input.inputFormat(forBus: 0)
        guard hardwareFormat.sampleRate > 0,
              let converter = AVAudioConverter(from: hardwareFormat, to: captureFormat)
        else {
            throw NSError(
                domain: "SpeakMate",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "麦克风不可用，请确认已授权且未被其他应用占用"],
            )
        }
        captureConverter = converter
        pendingUpload.removeAll(keepingCapacity: true)

        input.installTap(onBus: 0, bufferSize: 4800, format: hardwareFormat) { [weak self] buffer, _ in
            self?.consumeInput(buffer)
        }

        engine.prepare()
        try engine.start()
        player.play()
        running = true
    }

    func stop() {
        guard running else { return }
        running = false
        engine.inputNode.removeTap(onBus: 0)
        captureConverter = nil
        pendingUpload.removeAll(keepingCapacity: true)
        player.stop()
        engine.stop()
        lock.lock()
        queuedBytes = 0
        lastPlayedBackAt = 0
        lock.unlock()
        try? audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
    }

    // MARK: - 上行

    private func consumeInput(_ buffer: AVAudioPCMBuffer) {
        guard let converter = captureConverter, buffer.frameLength > 0 else { return }

        let ratio = captureFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 128
        guard let output = AVAudioPCMBuffer(pcmFormat: captureFormat, frameCapacity: capacity) else { return }

        var supplied = false
        var conversionError: NSError?
        let status = converter.convert(to: output, error: &conversionError) { _, outStatus in
            if supplied {
                outStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, output.frameLength > 0, let channel = output.floatChannelData?[0] else { return }

        let frameCount = Int(output.frameLength)
        onMicLevel?(Self.rmsFloat(channel, frameCount))
        guard !muted else { return }

        var samples = [Int16](repeating: 0, count: frameCount)
        for i in 0..<frameCount {
            let clamped = max(-1.0, min(1.0, channel[i]))
            samples[i] = Int16(clamped * 32767.0)
        }
        var bytes = Data(capacity: frameCount * 2)
        samples.withUnsafeBufferPointer { bytes.append($0) }
        enqueueUpload(bytes)
    }

    /// 攒够 100ms 再发，保证每次上行的字节数是偶数、帧长稳定。
    private func enqueueUpload(_ data: Data) {
        pendingUpload.append(data)
        while pendingUpload.count >= frameBytes {
            let frame = pendingUpload.prefix(frameBytes)
            pendingUpload.removeFirst(frameBytes)
            onFrame?(Data(frame))
        }
    }

    // MARK: - 下行

    /// 模型语音入队播放。超过队列上限时丢弃已排队内容，保证低延迟。
    func enqueue(pcm: Data) {
        guard !pcm.isEmpty else { return }
        let byteCount = pcm.count
        let frames = AVAudioFrameCount(byteCount / 2)
        guard frames > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: wireFormat, frameCapacity: frames)
        else { return }

        buffer.frameLength = frames
        if let destination = buffer.int16ChannelData?[0] {
            pcm.withUnsafeBytes { raw in
                if let base = raw.baseAddress {
                    memcpy(destination, base, byteCount)
                }
            }
        }

        var overflow = false
        lock.lock()
        queuedBytes += byteCount
        if queuedBytes > maxQueueBytes {
            // 积压过多：清掉已排队的音频，从当前这一帧重新开始
            queuedBytes = byteCount
            overflow = true
        }
        lock.unlock()
        if overflow {
            player.stop()
            player.play()
        }

        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            guard let self else { return }
            self.lock.lock()
            self.queuedBytes = max(0, self.queuedBytes - byteCount)
            self.lastPlayedBackAt = CACurrentMediaTime()
            self.lock.unlock()
        }
        onPlaybackLevel?(Self.rmsInt16(pcm))
    }

    /// 模型是否仍在说话 —— 由播放队列推导，官方没有「说完」事件。
    func isSpeaking() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if queuedBytes > 0 { return true }
        return lastPlayedBackAt > 0 && CACurrentMediaTime() - lastPlayedBackAt < 0.25
    }

    func bufferedBytes() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return queuedBytes
    }

    /// 用户按下静音时清空未播完的音频，避免"静音了还在响"。
    func flushPlayback() {
        lock.lock()
        queuedBytes = 0
        lock.unlock()
        player.stop()
        player.play()
    }

    // MARK: - 电平

    private static func rmsFloat(_ samples: UnsafeMutablePointer<Float>, _ count: Int) -> Float {
        guard count > 0 else { return 0 }
        var sum = 0.0
        for i in 0..<count {
            let v = Double(samples[i])
            sum += v * v
        }
        let rms = (sum / Double(count)).squareRoot()
        return min(1.0, Float(rms / 0.35))
    }

    private static func rmsInt16(_ data: Data) -> Float {
        let count = data.count / 2
        guard count > 0 else { return 0 }
        var sum = 0.0
        data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            let samples = base.assumingMemoryBound(to: Int16.self)
            for i in 0..<count {
                let v = Double(samples[i])
                sum += v * v
            }
        }
        let rms = (sum / Double(count)).squareRoot()
        // 语音场景下 RMS 3000 已经算很响，与 Android 侧口径一致
        return min(1.0, Float(rms / 3000.0))
    }
}
