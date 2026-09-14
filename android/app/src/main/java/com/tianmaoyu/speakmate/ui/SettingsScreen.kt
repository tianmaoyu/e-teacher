package com.tianmaoyu.speakmate.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tianmaoyu.speakmate.core.Const
import com.tianmaoyu.speakmate.core.formatDuration
import com.tianmaoyu.speakmate.core.formatMoney
import com.tianmaoyu.speakmate.session.SettingsState

@Composable
fun SettingsScreen(
    settings: SettingsState,
    onBack: () -> Unit,
    onSaveGateway: (String) -> Unit,
    onSelectVoice: (String) -> Unit,
    onSelectLevel: (String) -> Unit,
    onToggleTranslate: (Boolean) -> Unit,
    onRefreshAccount: () -> Unit,
    onSignOut: () -> Unit,
) {
    var gateway by remember(settings.gatewayBase) { mutableStateOf(settings.gatewayBase) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Spacer(Modifier.weight(1f))
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.padding(horizontal = 22.dp))
        }

        Spacer(Modifier.height(18.dp))

        SectionTitle("账户")
        InfoRow("账号", settings.accountId.ifEmpty { "未激活" })
        InfoRow("状态", statusLabel(settings.status))
        InfoRow("剩余可聊", formatDuration(settings.remainingSeconds))
        InfoRow("钱包余额", formatMoney(settings.balanceMicroUsd, settings.usdCny))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRefreshAccount,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text("刷新") }
            Button(
                onClick = onSignOut,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("退出登录") }
        }

        Spacer(Modifier.height(26.dp))

        SectionTitle("服务器")
        OutlinedTextField(
            value = gateway,
            onValueChange = { gateway = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("网关地址") },
            placeholder = { Text("https://api.yourdomain.com") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "填运营方提供的网关域名。App 不保存任何 OpenAI 凭证，只认你的接入令牌。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onSaveGateway(gateway) },
            enabled = gateway.isNotBlank() && gateway != settings.gatewayBase,
            shape = RoundedCornerShape(12.dp),
        ) { Text("保存地址") }

        Spacer(Modifier.height(26.dp))

        SectionTitle("Emma 的声音")
        ChipGrid(
            options = Const.VOICES,
            selected = settings.voice,
            perRow = 3,
            onSelect = onSelectVoice,
        )

        Spacer(Modifier.height(26.dp))

        SectionTitle("难度")
        ChipGrid(
            options = Const.LEVELS.keys.toList(),
            selected = settings.level,
            perRow = 4,
            onSelect = onSelectLevel,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = Const.LEVELS[settings.level] ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(26.dp))

        SectionTitle("字幕")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "中文对照",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "关闭可以省一点文本用量",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.autoTranslate, onCheckedChange = onToggleTranslate)
        }

        Spacer(Modifier.height(40.dp))
        Text(
            text = "SpeakMate 1.0 · 语音由 gpt-live-1 全双工模型驱动",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun ChipGrid(
    options: List<String>,
    selected: String,
    perRow: Int,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    Chip(
                        label = option,
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(perRow - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun statusLabel(status: String): String = when (status) {
    "active" -> "正常"
    "suspended" -> "已停用"
    "deleted" -> "已注销"
    else -> status
}
