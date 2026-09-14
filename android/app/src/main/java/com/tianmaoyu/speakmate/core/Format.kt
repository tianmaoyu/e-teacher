package com.tianmaoyu.speakmate.core

/** 秒 → mm:ss 或 h:mm:ss */
fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val m = safe / 60
    val s = safe % 60
    return if (m >= 60) {
        "%d:%02d:%02d".format(m / 60, m % 60, s)
    } else {
        "%d:%02d".format(m, s)
    }
}

/** 剩余时长的人话版本，用于激活成功的提示。 */
fun formatRemaining(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return when {
        safe >= 3600 -> "%d 小时 %d 分".format(safe / 3600, (safe % 3600) / 60)
        safe >= 60 -> "%d 分 %d 秒".format(safe / 60, safe % 60)
        else -> "$safe 秒"
    }
}

/**
 * 微美元 → 人民币展示。
 * 汇率由服务端下发（/v1/me 的 usdCny），这样调价不需要发版。
 */
fun formatMoney(microUsd: Long, usdCny: Double): String {
    val usd = microUsd / 1_000_000.0
    return "¥%.2f".format(usd * usdCny)
}
