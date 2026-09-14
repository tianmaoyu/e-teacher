package com.tianmaoyu.speakmate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.tianmaoyu.speakmate.session.Caption
import com.tianmaoyu.speakmate.session.Speaker
import com.tianmaoyu.speakmate.ui.Palette

/**
 * 双语对话字幕。
 * 英文是主行，中文对照在下方；还没翻译出来时用一行淡色提示占位。
 */
@Composable
fun CaptionList(
    captions: List<Caption>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(captions.size, captions.lastOrNull()?.english?.length) {
        if (captions.isNotEmpty()) {
            listState.animateScrollToItem(captions.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(captions, key = { it.id }) { caption ->
            CaptionBubble(caption)
        }
    }
}

@Composable
private fun CaptionBubble(caption: Caption) {
    val isCoach = caption.speaker == Speaker.Coach
    val bubbleColor = if (isCoach) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onBubble = if (isCoach) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val label = if (isCoach) "Emma" else "你"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isCoach) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = onBubble.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = caption.english,
                style = MaterialTheme.typography.bodyLarge,
                color = onBubble,
            )
            val zh = caption.chinese
            when {
                !zh.isNullOrBlank() -> {
                    Text(
                        text = zh,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBubble.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                else -> {
                    Text(
                        text = "正在翻译…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = onBubble.copy(alpha = 0.38f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 空态提示。 */
@Composable
fun CaptionPlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Teal.copy(alpha = 0.7f),
        )
    }
}
