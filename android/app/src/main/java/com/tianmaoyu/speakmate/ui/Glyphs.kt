package com.tianmaoyu.speakmate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全部图标都用 Canvas 手绘。
 * 这样可以完全不依赖 material-icons 扩展库（它的包路径在近几年变动过几次），
 * 也让视觉风格和呼吸圆环保持一致。
 */

@Composable
fun MicGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    muted: Boolean = false,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val capW = w * 0.34f
        val capTop = h * 0.10f
        val capBottom = h * 0.60f
        val stroke = w * 0.085f

        drawRoundRect(
            color = color,
            topLeft = Offset(cx - capW / 2f, capTop),
            size = Size(capW, capBottom - capTop),
            cornerRadius = CornerRadius(capW / 2f, capW / 2f),
        )
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - w * 0.27f, h * 0.28f),
            size = Size(w * 0.54f, h * 0.54f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(cx, h * 0.80f),
            end = Offset(cx, h * 0.94f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        if (muted) {
            drawLine(
                color = color,
                start = Offset(w * 0.12f, h * 0.88f),
                end = Offset(w * 0.88f, h * 0.12f),
                strokeWidth = stroke * 1.25f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
fun StopGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val box = w * 0.42f
        drawRoundRect(
            color = color,
            topLeft = Offset((w - box) / 2f, (h - box) / 2f),
            size = Size(box, box),
            cornerRadius = CornerRadius(box * 0.22f, box * 0.22f),
        )
    }
}

@Composable
fun PlayGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.30f, h * 0.20f)
            lineTo(w * 0.82f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.80f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun SlidersGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.075f
        val rows = listOf(0.28f to 0.34f, 0.50f to 0.66f, 0.72f to 0.46f)
        rows.forEach { (y, knobX) ->
            drawLine(
                color = color,
                start = Offset(w * 0.16f, h * y),
                end = Offset(w * 0.84f, h * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = color,
                radius = stroke * 1.9f,
                center = Offset(w * knobX, h * y),
            )
        }
    }
}

@Composable
fun RefreshGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.085f
        drawArc(
            color = color,
            startAngle = 40f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(w * 0.18f, h * 0.18f),
            size = Size(w * 0.64f, h * 0.64f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val head = Path().apply {
            moveTo(w * 0.72f, h * 0.06f)
            lineTo(w * 0.96f, h * 0.26f)
            lineTo(w * 0.68f, h * 0.32f)
            close()
        }
        drawPath(head, color)
    }
}

@Composable
fun CheckGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawLine(
            color = color,
            start = Offset(w * 0.20f, h * 0.54f),
            end = Offset(w * 0.42f, h * 0.76f),
            strokeWidth = w * 0.11f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.42f, h * 0.76f),
            end = Offset(w * 0.82f, h * 0.26f),
            strokeWidth = w * 0.11f,
            cap = StrokeCap.Round,
        )
    }
}
