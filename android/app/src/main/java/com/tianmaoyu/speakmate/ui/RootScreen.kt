package com.tianmaoyu.speakmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tianmaoyu.speakmate.session.SessionHub
import kotlinx.coroutines.launch

/**
 * 顶层路由：未激活 → 激活页；已激活 → 通话页；右上角进设置。
 */
@Composable
fun RootScreen() {
    val state by SessionHub.state.collectAsStateWithLifecycle()
    val settings by SessionHub.settings.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        SessionHub.refreshAccount()
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when {
            showSettings -> SettingsScreen(
                settings = settings,
                onBack = { showSettings = false },
                onSaveGateway = { SessionHub.setGatewayBase(it) },
                onSelectVoice = { SessionHub.setVoice(it) },
                onSelectLevel = { SessionHub.setLevel(it) },
                onToggleTranslate = { SessionHub.setAutoTranslate(it) },
                onRefreshAccount = { scope.launch { SessionHub.refreshAccount() } },
                onSignOut = {
                    SessionHub.signOut()
                    showSettings = false
                },
            )

            !settings.activated -> ActivateScreen(
                settings = settings,
                onRedeem = { code -> SessionHub.redeem(code) },
                onOpenSettings = { showSettings = true },
            )

            else -> TalkScreen(
                state = state,
                settings = settings,
                onStart = { SessionHub.startSession() },
                onEnd = { SessionHub.endSession() },
                onToggleMute = { SessionHub.toggleMute() },
                onOpenSettings = { showSettings = true },
                onDismissResult = { SessionHub.dismissResult() },
            )
        }
    }
}
