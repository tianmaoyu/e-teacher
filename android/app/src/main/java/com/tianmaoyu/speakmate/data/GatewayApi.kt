package com.tianmaoyu.speakmate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** 网关返回的业务错误，message 已经是可直接展示给用户的中文。 */
class GatewayException(
    val code: String,
    override val message: String,
    val httpStatus: Int,
) : Exception(message)

/**
 * 网关 REST 客户端：兑换码、账户查询、字幕翻译。
 * 这里不持有任何 OpenAI 凭证 —— App 只认运营方签发的 access_token。
 */
class GatewayApi(private val client: OkHttpClient) {

    suspend fun redeem(
        base: String,
        code: String,
        deviceId: String,
        deviceName: String,
    ): JSONObject {
        val body = JSONObject()
            .put("code", code)
            .put("deviceId", deviceId)
            .put("deviceName", deviceName)
        return postJson(url(base, "/v1/redeem"), body, token = null)
    }

    suspend fun me(base: String, token: String): JSONObject =
        getJson(url(base, "/v1/me"), token)

    suspend fun translate(base: String, token: String, texts: List<String>): List<String> {
        val arr = JSONArray()
        texts.forEach { arr.put(it) }
        val body = JSONObject().put("texts", arr).put("target", "zh-CN")
        val json = postJson(url(base, "/v1/translate"), body, token)
        val out = json.optJSONArray("translations") ?: return emptyList()
        return (0 until out.length()).map { out.optString(it, "") }
    }

    // ------------------------------------------------------------------

    private fun url(base: String, path: String): String = base.trim().trimEnd('/') + path

    private suspend fun postJson(endpoint: String, body: JSONObject, token: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(endpoint)
                .post(body.toString().toRequestBody(JSON_MEDIA))
            if (!token.isNullOrEmpty()) builder.addHeader("Authorization", "Bearer $token")
            client.newCall(builder.build()).execute().use { parse(it) }
        }

    private suspend fun getJson(endpoint: String, token: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(endpoint).get()
            if (!token.isNullOrEmpty()) builder.addHeader("Authorization", "Bearer $token")
            client.newCall(builder.build()).execute().use { parse(it) }
        }

    private fun parse(response: okhttp3.Response): JSONObject {
        val text = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (!response.isSuccessful) {
            throw GatewayException(
                code = json.optString("error", "http_${response.code}"),
                message = json.optString("message").ifEmpty { friendly(response.code) },
                httpStatus = response.code,
            )
        }
        return json
    }

    private fun friendly(code: Int): String = when (code) {
        401 -> "接入令牌无效，请重新兑换"
        402 -> "余额不足"
        403 -> "账号已被停用"
        404 -> "接口不存在，请检查服务器地址"
        429 -> "操作过于频繁，请稍后再试"
        502, 503 -> "服务暂时不可用，请稍后重试"
        else -> "请求失败（HTTP $code）"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
