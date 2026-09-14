package com.tianmaoyu.speakmate.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * 本地配置。接入令牌用 Android Keystore 托管的 AES256-GCM 加密后落盘。
 *
 * 若个别机型 Keystore 异常导致 EncryptedSharedPreferences 初始化失败，
 * 会降级到普通 SharedPreferences —— 宁可可用，也不要用户开不了应用。
 * 同时在日志里留痕，便于排查。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = createSecure(context)

    private fun createSecure(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "加密存储不可用，降级为普通存储：${t.message}")
            context.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
        }
    }

    var accessToken: String
        get() = sp.getString(KEY_TOKEN, "").orEmpty()
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    var accountId: String
        get() = sp.getString(KEY_ACCOUNT, "").orEmpty()
        set(value) = sp.edit().putString(KEY_ACCOUNT, value).apply()

    var gatewayBase: String
        get() = sp.getString(KEY_GATEWAY, Const.DEFAULT_GATEWAY).orEmpty().ifEmpty { Const.DEFAULT_GATEWAY }
        set(value) = sp.edit().putString(KEY_GATEWAY, value.trim().trimEnd('/')).apply()

    var voice: String
        get() = sp.getString(KEY_VOICE, "marin").orEmpty().ifEmpty { "marin" }
        set(value) = sp.edit().putString(KEY_VOICE, value).apply()

    var level: String
        get() = sp.getString(KEY_LEVEL, Const.DEFAULT_LEVEL).orEmpty().ifEmpty { Const.DEFAULT_LEVEL }
        set(value) = sp.edit().putString(KEY_LEVEL, value).apply()

    var autoTranslate: Boolean
        get() = sp.getBoolean(KEY_TRANSLATE, true)
        set(value) = sp.edit().putBoolean(KEY_TRANSLATE, value).apply()

    /** 安装级设备标识。首次访问时生成并持久化，用于兑换码绑定与风控。 */
    val deviceId: String
        get() {
            val existing = sp.getString(KEY_DEVICE, null)
            if (!existing.isNullOrEmpty()) return existing
            val created = UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE, created).apply()
            return created
        }

    val isActivated: Boolean get() = accessToken.isNotEmpty()

    fun clearCredentials() {
        sp.edit().remove(KEY_TOKEN).remove(KEY_ACCOUNT).apply()
    }

    private companion object {
        const val TAG = "SpeakMatePrefs"
        const val FILE_SECURE = "speakmate_secure"
        const val FILE_PLAIN = "speakmate_plain"
        const val KEY_TOKEN = "access_token"
        const val KEY_ACCOUNT = "account_id"
        const val KEY_GATEWAY = "gateway_base"
        const val KEY_VOICE = "voice"
        const val KEY_LEVEL = "level"
        const val KEY_TRANSLATE = "auto_translate"
        const val KEY_DEVICE = "device_id"
    }
}
