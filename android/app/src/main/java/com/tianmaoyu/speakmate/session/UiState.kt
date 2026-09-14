package com.tianmaoyu.speakmate.session

import com.tianmaoyu.speakmate.core.Const

enum class Phase {
    Idle,
    Connecting,
    Listening,
    Speaking,
    Muted,
    Closing,
    Ended,
}

enum class Speaker { Learner, Coach }

data class Caption(
    val id: Long,
    val speaker: Speaker,
    val english: String,
    val chinese: String? = null,
)

data class SessionResult(
    val voiceSeconds: Int,
    val costMicroUsd: Long,
    val remainingSeconds: Int,
    val closeReason: String,
    val unmetered: Boolean,
)

data class UiState(
    val phase: Phase = Phase.Idle,
    val captions: List<Caption> = emptyList(),
    val elapsedSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val sessionCostMicroUsd: Long = 0,
    val micLevel: Float = 0f,
    val playbackLevel: Float = 0f,
    val muted: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val result: SessionResult? = null,
) {
    val inSession: Boolean
        get() = phase == Phase.Connecting ||
            phase == Phase.Listening ||
            phase == Phase.Speaking ||
            phase == Phase.Muted ||
            phase == Phase.Closing

    val lowBalance: Boolean get() = inSession && remainingSeconds in 1..59
}

data class SettingsState(
    val gatewayBase: String = Const.DEFAULT_GATEWAY,
    val voice: String = "marin",
    val level: String = Const.DEFAULT_LEVEL,
    val autoTranslate: Boolean = true,
    val activated: Boolean = false,
    val accountId: String = "",
    val status: String = "active",
    val balanceMicroUsd: Long = 0,
    val remainingSeconds: Int = 0,
    /** 服务端下发的折算汇率，调价不需要发版 */
    val usdCny: Double = 7.2,
)
