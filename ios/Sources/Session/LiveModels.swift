import Foundation

enum Phase {
    case idle
    case connecting
    case listening
    case speaking
    case muted
    case closing
    case ended
}

enum Speaker {
    case learner
    case coach
}

struct Caption: Identifiable, Equatable {
    let id: Int64
    let speaker: Speaker
    var english: String
    var chinese: String?
}

struct SessionResult: Equatable {
    let voiceSeconds: Int
    let costMicroUsd: Int64
    let remainingSeconds: Int
    let closeReason: String
    let unmetered: Bool
}

struct UiState {
    var phase: Phase = .idle
    var captions: [Caption] = []
    var elapsedSeconds: Int = 0
    var remainingSeconds: Int = 0
    var sessionCostMicroUsd: Int64 = 0
    var micLevel: Float = 0
    var playbackLevel: Float = 0
    var muted: Bool = false
    var error: String?
    var notice: String?
    var result: SessionResult?

    var inSession: Bool {
        switch phase {
        case .connecting, .listening, .speaking, .muted, .closing: return true
        default: return false
        }
    }

    var lowBalance: Bool { inSession && (1...59).contains(remainingSeconds) }

    var phaseText: String {
        switch phase {
        case .idle: return "准备就绪"
        case .connecting: return "正在接通…"
        case .listening: return "在听你说"
        case .speaking: return "老师在说"
        case .muted: return "已静音"
        case .closing: return "正在结算…"
        case .ended: return "会话结束"
        }
    }
}

struct SettingsState {
    var gatewayBase: String = Const.DEFAULT_GATEWAY
    var voice: String = "marin"
    var level: String = Const.DEFAULT_LEVEL
    var autoTranslate: Bool = true
    var activated: Bool = false
    var accountId: String = ""
    var status: String = "active"
    var balanceMicroUsd: Int64 = 0
    var remainingSeconds: Int = 0
    /// 服务端下发的折算汇率，调价不需要发版
    var usdCny: Double = 7.2
}
