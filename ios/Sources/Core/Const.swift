import Foundation

/// 与 Android 端保持一致的常量。改动这里之前先改 docs/PROTOCOL.md。
enum Const {

    /// 构建期通过 SPEAKMATE_GATEWAY 注入；未注入时回落到默认值。
    /// 用户也可以在设置页里覆盖。
    static let DEFAULT_GATEWAY: String = {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "SpeakMateGateway") as? String) ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        // 未注入时 Xcode 会把 "$(SPEAKMATE_GATEWAY)" 原样写进 plist
        if trimmed.isEmpty || trimmed.hasPrefix("$(") { return FALLBACK_GATEWAY }
        return trimmed
    }()

    static let FALLBACK_GATEWAY = "https://api.speakmate.example"

    static let PROTOCOL_VERSION = 1

    /// GPT-Live-1 支持的音色（网关侧还有一层白名单校验）。
    static let VOICES: [String] = [
        "marin", "quartz", "ripple", "vesper", "willow", "stone", "gleam",
        "meridian", "bossa", "tempo", "beacon", "delta", "cinder",
    ]

    /// 难度档位 → 会话指令片段。
    static let LEVELS: [(key: String, hint: String)] = [
        ("A2", "Use short, simple sentences and a slow pace. Stick to everyday vocabulary."),
        ("B1", "Use everyday vocabulary at a natural pace. Add a few idioms now and then."),
        ("B2", "Speak naturally and use richer vocabulary. Gently push for longer answers."),
        ("C1", "Speak at full native pace with idiomatic, nuanced language and abstract topics."),
    ]

    static let DEFAULT_LEVEL = "B1"

    static func instructions(for level: String) -> String {
        let hint = LEVELS.first { $0.key == level }?.hint
            ?? LEVELS.first { $0.key == DEFAULT_LEVEL }!.hint
        return "You are Emma, a warm and patient English speaking partner for a Chinese learner. "
            + "Speak natural, conversational English with clear articulation. "
            + "Keep your turns short — one or two sentences — and ask at most one question at a time. "
            + "Never correct the learner's grammar out loud, never translate, and never switch to Chinese. "
            + "If the learner hesitates, wait quietly or give a gentle encouraging cue. "
            + "If the learner goes silent for a long while, ask a simple follow-up question to restart. "
            + hint
    }

    /// 单次上行音频帧长：100ms。
    static let FRAME_MS = 100

    /// 播放侧预缓冲，抗网络抖动。
    static let PLAYBACK_PREBUFFER_MS = 120

    /// 播放队列上限，超出则丢最旧的，避免延迟无限累积。
    static let MAX_PLAYBACK_QUEUE_MS = 3000

    /// 协商后的音频采样率与网关约定一致。
    static let AUDIO_RATE = 24000
}
