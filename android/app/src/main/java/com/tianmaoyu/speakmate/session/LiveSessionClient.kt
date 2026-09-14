package com.tianmaoyu.speakmate.session

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * 到 SpeakMate 网关的 WebSocket 连接。
 *
 * 注意 OkHttp 只接受 http/https 形式的 URL，ws:// 与 wss:// 必须转换后再构造
 * Request，否则会抛 "unexpected scheme"。
 */
class LiveSessionClient(
    private val client: OkHttpClient,
    private val onOpen: () -> Unit,
    private val onEvent: (JSONObject) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onClosed: (Int, String) -> Unit,
) {

    private var socket: WebSocket? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    fun connect(wsUrl: String, token: String, deviceId: String) {
        val request = Request.Builder()
            .url(normalizeScheme(wsUrl))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("X-Device-Id", deviceId)
            .addHeader("User-Agent", "SpeakMate-Android")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                onEvent(obj)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                onFailure(t)
            }
        })
    }

    fun send(obj: JSONObject): Boolean = socket?.send(obj.toString()) ?: false

    fun close() {
        val s = socket
        socket = null
        isConnected = false
        runCatching { s?.close(1000, "client closing") }
    }

    companion object {
        fun normalizeScheme(url: String): String = when {
            url.startsWith("wss://") -> "https://" + url.removePrefix("wss://")
            url.startsWith("ws://") -> "http://" + url.removePrefix("ws://")
            else -> url
        }

        fun toWebSocketUrl(base: String, path: String): String {
            val trimmed = base.trim().trimEnd('/')
            val schemeFixed = when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
                else -> "wss://$trimmed"
            }
            return schemeFixed + path
        }
    }
}
