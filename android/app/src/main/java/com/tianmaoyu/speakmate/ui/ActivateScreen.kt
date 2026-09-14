package com.tianmaoyu.speakmate.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tianmaoyu.speakmate.session.SettingsState
import kotlinx.coroutines.launch

/**
 * 激活页。整个商业模式的第一步：用户拿到一张卡密，在这里核销。
 * 不需要注册账号 —— 核销成功即建号，令牌存在本机加密存储里。
 */
@Composable
fun ActivateScreen(
    settings: SettingsState,
    onRedeem: suspend (String) -> Result<String>,
    onOpenSettings: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            MicGlyph(color = MaterialTheme.colorScheme.primary, size = 44.dp)
        }

        Spacer(Modifier.height(22.dp))
        Text(
            text = "SpeakMate",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "和 Emma 实时对话，练出真正的口语",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(34.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FeatureChip("全双工对话", Modifier.weight(1f))
            FeatureChip("中英字幕", Modifier.weight(1f))
            FeatureChip("按秒计费", Modifier.weight(1f))
        }

        Spacer(Modifier.height(30.dp))

        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(16)
                message = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("兑换码") },
            placeholder = { Text("SM-XXXX-XXXX") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            shape = RoundedCornerShape(14.dp),
        )

        if (message != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = message!!,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(18.dp))

        Button(
            onClick = {
                if (code.isBlank()) return@Button
                busy = true
                message = null
                scope.launch {
                    val result = onRedeem(code)
                    busy = false
                    result.fold(
                        onSuccess = { successMessage ->
                            isError = false
                            message = successMessage
                        },
                        onFailure = {
                            isError = true
                            message = it.message ?: "激活失败，请稍后重试"
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !busy && code.length >= 8,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("激活中…")
            } else {
                Text("激活并开始", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(14.dp))

        TextButton(onClick = onOpenSettings) {
            Text("服务器设置 · ${settings.gatewayBase}")
        }
    }
}

@Composable
private fun FeatureChip(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
