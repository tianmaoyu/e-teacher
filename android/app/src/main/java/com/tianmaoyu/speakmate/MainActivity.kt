package com.tianmaoyu.speakmate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tianmaoyu.speakmate.session.SessionHub
import com.tianmaoyu.speakmate.ui.MicGlyph
import com.tianmaoyu.speakmate.ui.RootScreen
import com.tianmaoyu.speakmate.ui.SpeakMateTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionHub.init(applicationContext)
        setContent {
            SpeakMateTheme {
                MicrophoneGate {
                    RootScreen()
                }
            }
        }
    }
}

/**
 * 录音权限闸门。
 * 没有麦克风权限，这个 App 一点用都没有，所以进门就先要。
 */
@Composable
private fun MicrophoneGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    val permissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var granted by remember { mutableStateOf(hasMicrophone(context)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val micResult = result[Manifest.permission.RECORD_AUDIO]
        granted = micResult == true || hasMicrophone(context)
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(permissions)
    }

    if (granted) {
        content()
    } else {
        PermissionScreen(onRequest = { launcher.launch(permissions) })
    }
}

private fun hasMicrophone(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                MicGlyph(color = MaterialTheme.colorScheme.primary, size = 42.dp)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "需要麦克风权限",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "SpeakMate 靠实时收听你的英语来对话。不录音、不上传，音频只在会话期间流式传输给语音模型。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onRequest,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("授予权限")
            }
        }
    }
}
