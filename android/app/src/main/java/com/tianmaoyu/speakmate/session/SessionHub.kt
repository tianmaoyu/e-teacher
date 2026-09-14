package com.tianmaoyu.speakmate.session

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.tianmaoyu.speakmate.audio.AudioEngine
import com.tianmaoyu.speakmate.core.Const
import com.tianmaoyu.speakmate.core.Prefs
import com.tianmaoyu.speakmate.core.formatRemaining
import com.tianmaoyu.speakmate.data.GatewayApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 会话中枢：唯一持有实时资源的地方。
 *
 * 前台服务负责生命周期，UI 只读 StateFlow —— 锁屏、切后台都不影响会话。
 */
object SessionHub {

    private const val TAG = "SpeakMateHub"

    /** 转写片段间隔超过这个值，就认为换了一轮对话，切成新字幕并提交翻译。 */
    private const val TURN_GAP_MS = 1200L

    /** 说完之后静默这么久就把当前字幕定稿。 */
    private const val IDLE_FLUSH_MS = 1400L

    /** 字幕最多保留多少条，避免长时间会话把内存吃满。 */
    private const val MAX_CAPTIONS = 300

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(SettingsState())
    val settings: StateFlow<SettingsState> = _settings.asStateFlow()

    private var appContext: Context? = null
    private var prefs: Prefs? = null

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 心跳保活：nginx 与中间设备默认会掐掉空闲长连接
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private val api: GatewayApi by lazy { GatewayApi(okHttp) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var client: LiveSessionClient? = null
    private var engine: AudioEngine? = null
    private var tickerJob: Job? = null
    private var translateJob: Job? = null
    private var closeWatchdog: Job? = null

    private val translateQueue = Channel<Long>(Channel.UNLIMITED)

    @Volatile
    private var sessionLive = false

    @Volatile
    private var micLevelHolder = 0f

    @Volatile
    private var playbackLevelHolder = 0f

    private var nextCaptionId = 1L

    private class TurnTracker {
        var captionId = 0L
        var lastEndMs = 0L
        var lastDeltaAtMs = 0L
    }

    private val learnerTurn = TurnTracker()
    private val coachTurn = TurnTracker()

    private var translationNoticeShown = false

    // ==================================================================
    // 初始化
    // ==================================================================

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        appContext = app
        val p = Prefs(app)
        prefs = p
        _settings.update {
            it.copy(
                gatewayBase = p.gatewayBase,
                voice = p.voice,
                level = p.level,
                autoTranslate = p.autoTranslate,
                activated = p.isActivated,
                accountId = p.accountId,
            )
        }
        startTranslateWorker()
    }

    // ==================================================================
    // 账号
    // ==================================================================

    suspend fun redeem(code: String): Result<String> {
        val p = prefs ?: return Result.failure(IllegalStateException("尚未初始化"))
        return try {
            val json = api.redeem(p.gatewayBase, code.trim(), p.deviceId, Build.MODEL)
            val token = json.optString("accessToken")
            if (token.isNotEmpty()) {
                p.accessToken = token
            } else if (p.accessToken.isEmpty()) {
                return Result.failure(
                    IllegalStateException("该兑换码已在本设备使用过。若令牌丢失，请联系客服轮换后重试。"),
                )
            }
            val accountId = json.optString("accountId")
            if (accountId.isNotEmpty()) p.accountId = accountId
            val remaining = json.optInt("remainingSeconds")
            _settings.update {
                it.copy(
                    activated = true,
                    accountId = accountId.ifEmpty { it.accountId },
                    balanceMicroUsd = json.optLong("balanceMicroUsd"),
                    remainingSeconds = remaining,
                    usdCny = json.optDouble("usdCny", it.usdCny),
                )
            }
            Result.success("已激活，可聊 ${formatRemaining(remaining)}")
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun refreshAccount() {
        val p = prefs ?: return
        if (p.accessToken.isEmpty()) return
        try {
            val json = api.me(p.gatewayBase, p.accessToken)
            _settings.update {
                it.copy(
                    activated = true,
                    accountId = json.optString("accountId"),
                    status = json.optString("status", "active"),
                    balanceMicroUsd = json.optLong("balanceMicroUsd"),
                    remainingSeconds = json.optInt("remainingSeconds"),
                    usdCny = json.optDouble("usdCny", it.usdCny),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "刷新账户失败：${t.message}")
        }
    }

    fun signOut() {
        prefs?.clearCredentials()
        _settings.update { it.copy(activated = false, accountId = "", remainingSeconds = 0, balanceMicroUsd = 0) }
    }

    // ==================================================================
    // 设置
    // ==================================================================

    fun setGatewayBase(value: String) {
        val p = prefs ?: return
        p.gatewayBase = value
        _settings.update { it.copy(gatewayBase = p.gatewayBase) }
    }

    fun setVoice(value: String) {
        val p = prefs ?: return
        p.voice = value
        _settings.update { it.copy(voice = value) }
    }

    fun setLevel(value: String) {
        val p = prefs ?: return
        p.level = value
        _settings.update { it.copy(level = value) }
    }

    fun setAutoTranslate(value: Boolean) {
        val p = prefs ?: return
        p.autoTranslate = value
        _settings.update { it.copy(autoTranslate = value) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /** 关掉结算单，回到可以再次开始的状态。 */
    fun dismissResult() {
        _state.update {
            it.copy(
                result = null,
                notice = null,
                error = null,
                phase = if (it.phase == Phase.Ended) Phase.Idle else it.phase,
            )
        }
    }

    // ==================================================================
    // 会话
    // ==================================================================

    fun startSession() {
        val ctx = appContext ?: return
        val p = prefs ?: return
        if (_state.value.inSession) return

        if (p.accessToken.isEmpty()) {
            _state.update { it.copy(error = "请先输入兑换码激活") }
            return
        }

        translationNoticeShown = false
        resetTurns()
        _state.update {
            it.copy(
                phase = Phase.Connecting,
                captions = emptyList(),
                elapsedSeconds = 0,
                sessionCostMicroUsd = 0,
                error = null,
                notice = null,
                result = null,
                muted = false,
                micLevel = 0f,
                playbackLevel = 0f,
            )
        }

        val eng = AudioEngine(
            context = ctx,
            onFrame = ::onMicFrame,
            onMicLevel = { micLevelHolder = it },
            onPlaybackLevel = { playbackLevelHolder = it },
        )
        val rate = eng.start(preferSpeaker = true)
        if (rate <= 0) {
            _state.update {
                it.copy(
                    phase = Phase.Idle,
                    error = "麦克风启动失败。请确认已授权录音权限，且没有被其他应用占用。",
                )
            }
            return
        }
        engine = eng
        Log.i(TAG, "音频引擎就绪，采样率 $rate Hz")

        val socket = LiveSessionClient(
            client = okHttp,
            onOpen = ::onSocketOpen,
            onEvent = ::onGatewayEvent,
            onFailure = ::onSocketFailure,
            onClosed = ::onSocketClosed,
        )
        client = socket
        startForegroundService(ctx)
        socket.connect(
            LiveSessionClient.toWebSocketUrl(p.gatewayBase, "/v1/live/sessions"),
            p.accessToken,
            p.deviceId,
        )
        startTicker()
    }

    fun endSession() {
        if (!_state.value.inSession) return
        _state.update { it.copy(phase = Phase.Closing) }
        client?.send(JSONObject().put("type", "session.close"))
        closeWatchdog?.cancel()
        closeWatchdog = scope.launch {
            delay(6000)
            if (_state.value.phase == Phase.Closing) {
                _state.update { it.copy(error = "未收到最终结算，用量以服务端账单为准") }
                finish(null)
            }
        }
    }

    fun toggleMute() {
        val eng = engine ?: return
        val next = !_state.value.muted
        eng.muted = next
        if (next) eng.flushPlayback()
        client?.send(
            JSONObject().put("type", if (next) "session.input_audio.mute" else "session.input_audio.unmute"),
        )
        _state.update { it.copy(muted = next, phase = if (next) Phase.Muted else Phase.Listening) }
    }

    // ==================================================================
    // WebSocket 回调
    // ==================================================================

    private fun onSocketOpen() {
        val p = prefs ?: return
        val rate = engine?.sampleRate ?: 24000
        // 只提交教学相关的字段：model / delegation / store 由网关强制覆写，
        // 客户端提交也会被丢弃。
        val session = JSONObject()
            .put("instructions", Const.instructionsFor(p.level))
            .put(
                "audio",
                JSONObject()
                    .put("format", JSONObject().put("rate", rate))
                    .put("output", JSONObject().put("voice", p.voice)),
            )
        client?.send(
            JSONObject()
                .put("type", "session.start")
                .put("event_id", "start_${System.currentTimeMillis()}")
                .put("session", session),
        )
    }

    private fun onGatewayEvent(evt: JSONObject) {
        when (evt.optString("type")) {
            "gateway.ready" -> _state.update {
                it.copy(
                    remainingSeconds = evt.optInt("remainingSeconds"),
                    sessionCostMicroUsd = 0,
                )
            }

            "session.started" -> {
                sessionLive = true
                _state.update { it.copy(phase = Phase.Listening) }
            }

            "gateway.usage" -> _state.update {
                it.copy(
                    elapsedSeconds = evt.optInt("elapsedSeconds"),
                    remainingSeconds = evt.optInt("remainingSeconds"),
                    sessionCostMicroUsd = evt.optLong("costMicroUsd"),
                )
            }

            "gateway.notice" -> _state.update {
                it.copy(notice = evt.optString("message").ifEmpty { null })
            }

            "gateway.session.terminated" -> {
                val message = evt.optString("message").ifEmpty { "会话已结束" }
                _state.update { it.copy(notice = message) }
            }

            "gateway.closed" -> {
                val result = SessionResult(
                    voiceSeconds = evt.optInt("voiceSeconds"),
                    costMicroUsd = evt.optLong("costMicroUsd"),
                    remainingSeconds = evt.optInt("remainingSeconds"),
                    closeReason = evt.optString("closeReason", "unknown"),
                    unmetered = evt.optBoolean("unmetered"),
                )
                _settings.update { it.copy(remainingSeconds = result.remainingSeconds) }
                finish(result)
            }

            "session.output_audio.delta" -> {
                val b64 = evt.optString("delta")
                if (b64.isNotEmpty()) {
                    runCatching { Base64.decode(b64, Base64.DEFAULT) }
                        .onSuccess { engine?.enqueue(it) }
                }
            }

            "session.input_transcript.delta" -> handleTranscript(
                Speaker.Learner,
                evt.optString("delta"),
                evt.optLong("start_ms"),
                evt.optLong("end_ms"),
            )

            "session.output_transcript.delta" -> handleTranscript(
                Speaker.Coach,
                evt.optString("delta"),
                evt.optLong("start_ms"),
                evt.optLong("end_ms"),
            )

            "error" -> {
                val err = evt.optJSONObject("error")
                val code = err?.optString("code").orEmpty()
                val message = err?.optString("message").orEmpty()
                Log.w(TAG, "网关拒绝：$code $message")
                // session_not_ready 属于正常竞态（音频早于 started），不打扰用户
                if (code != "session_not_ready") {
                    _state.update { it.copy(error = message.ifEmpty { code }) }
                }
            }
        }
    }

    private fun onSocketFailure(t: Throwable) {
        if (!_state.value.inSession) return
        Log.e(TAG, "连接失败：${t.message}")
        _state.update { it.copy(error = "连接中断：${t.message ?: "网络异常"}") }
        finish(null)
    }

    private fun onSocketClosed(code: Int, reason: String) {
        if (!_state.value.inSession) return
        Log.w(TAG, "连接被关闭 code=$code reason=$reason")
        finish(null)
    }

    // ==================================================================
    // 转写与字幕
    // ==================================================================

    private fun onMicFrame(pcm: ByteArray) {
        if (!sessionLive || engine?.muted == true) return
        val c = client ?: return
        val encoded = Base64.encodeToString(pcm, Base64.NO_WRAP)
        c.send(
            JSONObject()
                .put("type", "session.input_audio.append")
                .put("audio", encoded),
        )
    }

    private fun trackerFor(speaker: Speaker): TurnTracker =
        if (speaker == Speaker.Learner) learnerTurn else coachTurn

    private fun handleTranscript(speaker: Speaker, delta: String, startMs: Long, endMs: Long) {
        if (delta.isEmpty()) return
        val tracker = trackerFor(speaker)

        // 间隔够大 → 上一轮结束，切新字幕
        if (tracker.captionId != 0L && tracker.lastEndMs > 0 && startMs - tracker.lastEndMs > TURN_GAP_MS) {
            closeCaption(speaker)
        }

        if (tracker.captionId == 0L) {
            val id = nextCaptionId++
            tracker.captionId = id
            _state.update { st ->
                val next = st.captions + Caption(id, speaker, delta)
                st.copy(captions = if (next.size > MAX_CAPTIONS) next.takeLast(MAX_CAPTIONS) else next)
            }
        } else {
            val id = tracker.captionId
            _state.update { st ->
                st.copy(
                    captions = st.captions.map {
                        if (it.id == id) it.copy(english = it.english + delta) else it
                    },
                )
            }
        }
        tracker.lastEndMs = endMs
        tracker.lastDeltaAtMs = SystemClock.elapsedRealtime()
    }

    private fun closeCaption(speaker: Speaker) {
        val tracker = trackerFor(speaker)
        val id = tracker.captionId
        tracker.captionId = 0L
        tracker.lastEndMs = 0L
        if (id != 0L) translateQueue.trySend(id)
    }

    private fun resetTurns() {
        learnerTurn.captionId = 0L
        learnerTurn.lastEndMs = 0L
        learnerTurn.lastDeltaAtMs = 0L
        coachTurn.captionId = 0L
        coachTurn.lastEndMs = 0L
        coachTurn.lastDeltaAtMs = 0L
    }

    private fun startTranslateWorker() {
        if (translateJob != null) return
        translateJob = scope.launch(Dispatchers.IO) {
            for (id in translateQueue) {
                val p = prefs ?: continue
                if (!p.autoTranslate) continue
                val caption = _state.value.captions.firstOrNull { it.id == id } ?: continue
                if (caption.chinese != null || caption.english.isBlank()) continue
                try {
                    val result = api.translate(p.gatewayBase, p.accessToken, listOf(caption.english))
                    val zh = result.firstOrNull().orEmpty()
                    if (zh.isNotBlank()) {
                        _state.update { st ->
                            st.copy(
                                captions = st.captions.map {
                                    if (it.id == id) it.copy(chinese = zh) else it
                                },
                            )
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "翻译失败：${t.message}")
                    if (!translationNoticeShown) {
                        translationNoticeShown = true
                        _state.update { it.copy(notice = "中文对照暂时不可用（${t.message}）") }
                    }
                }
            }
        }
    }

    // ==================================================================
    // 心跳
    // ==================================================================

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(100)
                val now = SystemClock.elapsedRealtime()
                if (sessionLive) {
                    if (learnerTurn.captionId != 0L && now - learnerTurn.lastDeltaAtMs > IDLE_FLUSH_MS) {
                        closeCaption(Speaker.Learner)
                    }
                    if (coachTurn.captionId != 0L && now - coachTurn.lastDeltaAtMs > IDLE_FLUSH_MS) {
                        closeCaption(Speaker.Coach)
                    }
                }
                refreshPhase()
                _state.update {
                    it.copy(micLevel = micLevelHolder, playbackLevel = playbackLevelHolder)
                }
            }
        }
    }

    /** 「模型在说话」一律由播放队列推导 —— 官方没有播放完成事件。 */
    private fun refreshPhase() {
        val eng = engine ?: return
        if (!sessionLive) return
        val next = when {
            _state.value.muted -> Phase.Muted
            eng.isSpeaking() -> Phase.Speaking
            else -> Phase.Listening
        }
        if (_state.value.phase != next) {
            _state.update { it.copy(phase = next) }
        }
    }

    // ==================================================================
    // 收尾
    // ==================================================================

    private fun finish(result: SessionResult?) {
        sessionLive = false
        closeWatchdog?.cancel()
        closeWatchdog = null
        tickerJob?.cancel()
        tickerJob = null

        val eng = engine
        engine = null
        runCatching { eng?.stop() }

        val c = client
        client = null
        runCatching { c?.close() }

        appContext?.let { stopForegroundService(it) }

        _state.update {
            it.copy(
                phase = Phase.Ended,
                result = result ?: it.result,
                micLevel = 0f,
                playbackLevel = 0f,
                muted = false,
            )
        }
        scope.launch { refreshAccount() }
    }

    private fun startForegroundService(ctx: Context) {
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LiveSessionService::class.java))
        }.onFailure { Log.w(TAG, "启动前台服务失败：${it.message}") }
    }

    private fun stopForegroundService(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, LiveSessionService::class.java)) }
    }
}
