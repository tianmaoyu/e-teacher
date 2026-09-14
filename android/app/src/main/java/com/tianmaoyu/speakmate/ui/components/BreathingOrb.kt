package com.tianmaoyu.speakmate.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.tianmaoyu.speakmate.ui.Palette
import kotlin.math.min

/**
 * 呼吸光球：整块屏幕的"呼吸感"来源。
 *
 * 半径与亮度由三层叠加决定：
 *   1. 常驻呼吸节奏（idle 时慢，避免静止画面显得卡死）
 *   2. 麦克风真实电平 / 播放电平
 *   3. 说话状态（听 = 青绿，说 = 靛蓝）
 */
@Composable
fun BreathingOrb(
    active: Boolean,
    speaking: Boolean,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )

    val smoothed by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 140),
    )

    val coreColor = if (speaking) Palette.Indigo else Palette.Teal
    val ringColor = if (speaking) Palette.Indigo else Palette.Mint

    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val base = min(size.width, size.height) / 2f
        val breath = if (active) pulse else 0.4f
        val energy = smoothed
        val dim = if (active) 1f else 0.45f

        drawCircle(
            color = ringColor.copy(alpha = (0.09f + 0.13f * energy) * dim),
            radius = base * (0.95f + 0.28f * energy),
            center = center,
        )

        for (i in 0..2) {
            val scale = 1f + i * 0.17f
            val radius = base * 0.60f * scale * (1f + 0.05f * breath + 0.15f * energy)
            drawCircle(
                color = ringColor.copy(alpha = (0.40f - i * 0.10f) * dim),
                radius = radius,
                center = center,
                style = Stroke(width = base * 0.022f),
            )
        }

        drawCircle(
            color = coreColor.copy(alpha = 0.94f * dim + 0.06f),
            radius = base * 0.44f * (1f + 0.06f * energy),
            center = center,
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.10f + 0.18f * energy),
            radius = base * 0.28f,
            center = center,
        )
    }
}
