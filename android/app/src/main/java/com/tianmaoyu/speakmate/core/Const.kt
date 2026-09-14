package com.tianmaoyu.speakmate.core

import com.tianmaoyu.speakmate.BuildConfig

object Const {
    /** 构建期注入的默认网关地址；用户可在设置页覆盖。 */
    val DEFAULT_GATEWAY: String = BuildConfig.DEFAULT_GATEWAY

    const val PROTOCOL_VERSION = 1

    /** GPT-Live-1 支持的音色（网关侧还有一层白名单校验）。 */
    val VOICES = listOf(
        "marin", "quartz", "ripple", "vesper", "willow", "stone", "gleam",
        "meridian", "bossa", "tempo", "beacon", "delta", "cinder",
    )

    /** 难度档位 → 会话指令片段。 */
    val LEVELS = linkedMapOf(
        "A2" to "Use short, simple sentences and a slow pace. Stick to everyday vocabulary.",
        "B1" to "Use everyday vocabulary at a natural pace. Add a few idioms now and then.",
        "B2" to "Speak naturally and use richer vocabulary. Gently push for longer answers.",
        "C1" to "Speak at full native pace with idiomatic, nuanced language and abstract topics.",
    )

    const val DEFAULT_LEVEL = "B1"

    fun instructionsFor(level: String): String {
        val levelHint = LEVELS[level] ?: LEVELS.getValue(DEFAULT_LEVEL)
        return buildString {
            append("You are Emma, a warm and patient English speaking partner for a Chinese learner. ")
            append("Speak natural, conversational English with clear articulation. ")
            append("Keep your turns short — one or two sentences — and ask at most one question at a time. ")
            append("Never correct the learner's grammar out loud, never translate, and never switch to Chinese. ")
            append("If the learner hesitates, wait quietly or give a gentle encouraging cue. ")
            append("If the learner goes silent for a long while, ask a simple follow-up question to restart. ")
            append(levelHint)
        }
    }

    /** 单次上行音频帧长：100ms。 */
    const val FRAME_MS = 100

    /** 播放侧预缓冲，抗网络抖动。 */
    const val PLAYBACK_PREBUFFER_MS = 120

    /** 播放队列上限，超出则丢最旧的，避免延迟无限累积。 */
    const val MAX_PLAYBACK_QUEUE_MS = 3000
}
