package com.tianmaoyu.speakmate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tianmaoyu.speakmate.core.formatDuration
import com.tianmaoyu.speakmate.core.formatMoney
import com.tianmaoyu.speakmate.session.Phase
import com.tianmaoyu.speakmate.session.SettingsState
import com.tianmaoyu.speakmate.session.UiState
import com.tianmaoyu.speakmate.ui.components.BreathingOrb
import com.tianmaoyu.speakmate.ui.components.CaptionList
import com.tianmaoyu.speakmate.ui.components.CaptionPlaceholder

@Composable
fun TalkScreen(
    state: UiState,
    settings: SettingsState,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {

        StatusBar(state = state, settings = settings, onOpenSettings = onOpenSettings)

        AnimatedVisibility(visible = state.error != null) {
            Banner(
                text = state.error.orEmpty(),
                background = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                foreground = MaterialTheme.colorScheme.error,
            )
        }
        AnimatedVisibility(visible = state.notice != null) {
            Banner(
                text = state.notice.orEmpty(),
                background = Palette.Amber.copy(alpha = 0.15f),
                foreground = Palette.Amber,
            )
        }

        val speaking = state.phase == Phase.Speaking
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(210.dp), contentAlignment = Alignment.Center) {
                BreathingOrb(
                    active = state.inSession,
                    speaking = speaking,
                    level = if (speaking) state.playbackLevel else state.micLevel,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = phaseLabel(state),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.inSession) Palette.TealDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp)) {
            if (state.captions.isEmpty()) {
                CaptionPlaceholder(
                    text = if (state.inSession) "开始说英语吧，Emma 会接住你" else "点下面的按钮开始一节口语课",
                    modifier = Modifier.fillMaxSize().padding(top = 40.dp),
                )
            } else {
                CaptionList(captions = state.captions, modifier = Modifier.fillMaxSize())
            }
        }

        Controls(
            state = state,
            onStart = onStart,
            onEnd = onEnd,
            onToggleMute = onToggleMute,
        )
    }

    if (state.phase == Phase.Ended && state.result != null) {
        ResultSheet(
            state = state,
            settings = settings,
            onDismiss = onDismissResult,
            onRestart = {
                onDismissResult()
                onStart()
            },
        )
    }
}

@Composable
private fun phaseLabel(state: UiState): String = when (state.phase) {
    Phase.Idle -> "准备就绪"
    Phase.Connecting -> "正在接通…"
    Phase.Listening -> "Emma 在听你说"
    Phase.Speaking -> "Emma 在说"
    Phase.Muted -> "麦克风已静音"
    Phase.Closing -> "正在结算…"
    Phase.Ended -> "本节结束"
}

@Composable
private fun StatusBar(
    state: UiState,
    settings: SettingsState,
    onOpenSettings: () -> Unit,
) {
    val remaining = if (state.inSession) state.remainingSeconds else settings.remainingSeconds
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (state.inSession) formatDuration(state.elapsedSeconds) else "SpeakMate",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "剩余 ${formatDuration(remaining)}" +
                    if (state.inSession && state.sessionCostMicroUsd > 0) {
                        " · 本节 ${formatMoney(state.sessionCostMicroUsd, settings.usdCny)}"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.lowBalance) Palette.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            SlidersGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
        }
    }
}

@Composable
private fun Banner(text: String, background: Color, foreground: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = foreground,
        )
    }
}

@Composable
private fun Controls(
    state: UiState,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!state.inSession) {
            Button(
                onClick = onStart,
                enabled = state.phase != Phase.Connecting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                PlayGlyph(color = MaterialTheme.colorScheme.onPrimary, size = 20.dp)
                Spacer(Modifier.size(10.dp))
                Text("开始对话", style = MaterialTheme.typography.labelLarge)
            }
            return@Row
        }

        RoundAction(
            background = if (state.muted) Palette.Rose else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (state.muted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            enabled = state.phase != Phase.Closing,
            onClick = onToggleMute,
        ) {
            MicGlyph(
                color = if (state.muted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 24.dp,
                muted = state.muted,
            )
        }

        Spacer(Modifier.size(26.dp))

        RoundAction(
            background = Palette.Rose,
            contentColor = Color.White,
            enabled = true,
            onClick = onEnd,
        ) {
            StopGlyph(color = Color.White, size = 24.dp)
        }
    }
}

@Composable
private fun RoundAction(
    background: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .border(1.dp, contentColor.copy(alpha = 0.18f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ResultSheet(
    state: UiState,
    settings: SettingsState,
    onDismiss: () -> Unit,
    onRestart: () -> Unit,
) {
    val result = state.result ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = {})
                .padding(horizontal = 26.dp, vertical = 28.dp),
        ) {
            Text(
                text = "本节结算",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("时长", formatDuration(result.voiceSeconds), Modifier.weight(1f))
                StatCell("花费", formatMoney(result.costMicroUsd, settings.usdCny), Modifier.weight(1f))
                StatCell("剩余", formatDuration(result.remainingSeconds), Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))

            val note = when {
                result.unmetered -> "本次未取得服务端最终用量，暂按估算结算，请以账单为准。"
                result.closeReason == "insufficient_balance" -> "余额已用完。充值后可以继续。"
                result.closeReason == "max_session_seconds" -> "已达单次会话时长上限，休息一下再来。"
                result.closeReason == "content" -> "会话因内容安全策略被结束。"
                else -> "本节共 ${state.captions.count { it.speaker == com.tianmaoyu.speakmate.session.Speaker.Coach }} 轮 Emma 回复。"
            }
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text("关闭") }
                Button(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("再来一节") }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
