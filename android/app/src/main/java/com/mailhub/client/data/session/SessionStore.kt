package com.mailhub.client.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 加解密接口：真机用 [TokenCipher]，单元测试里用假实现。 */
interface Cipher {
    fun encrypt(plain: String): String
    fun decrypt(blob: String): String
}

data class Session(
    val serverUrl: String,
    val token: String,
    val sessionId: String,
    val expiresTs: Double,
    val deviceName: String,
)

/**
 * DataStore Preferences 里的会话存储。server_url 明文，token 经 [Cipher] 加密后保存。
 * 拦截器需要同步读取当前令牌，所以额外维护一个 [cached] 内存副本。
 */
class SessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: Cipher,
) {
    @Volatile
    var cached: Session? = null
        private set

    val session: Flow<Session?> = dataStore.data.map { it.toSession() }

    suspend fun refresh(): Session? {
        cached = dataStore.data.first().toSession()
        return cached
    }

    suspend fun save(session: Session) {
        dataStore.edit { prefs ->
            prefs[KEY_SERVER_URL] = session.serverUrl
            prefs[KEY_TOKEN_ENC] = cipher.encrypt(session.token)
            prefs[KEY_SESSION_ID] = session.sessionId
            prefs[KEY_EXPIRES_TS] = session.expiresTs
            prefs[KEY_DEVICE_NAME] = session.deviceName
        }
        cached = session
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
        cached = null
    }

    private fun Preferences.toSession(): Session? {
        val serverUrl = this[KEY_SERVER_URL] ?: return null
        val tokenEnc = this[KEY_TOKEN_ENC] ?: return null
        val token = runCatching { cipher.decrypt(tokenEnc) }.getOrNull() ?: return null
        return Session(
            serverUrl = serverUrl,
            token = token,
            sessionId = this[KEY_SESSION_ID] ?: "",
            expiresTs = this[KEY_EXPIRES_TS] ?: 0.0,
            deviceName = this[KEY_DEVICE_NAME] ?: "",
        )
    }

    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_TOKEN_ENC = stringPreferencesKey("token_enc")
        val KEY_SESSION_ID = stringPreferencesKey("session_id")
        val KEY_EXPIRES_TS = doublePreferencesKey("expires_ts")
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
    }
}
