package com.mailhub.client.ui.screens.login

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/**
 * 页面层自己的偏好存储：只保存上次成功登录的服务器地址，用来预填输入框。
 * 会话本体的存取仍由 data 层负责，这里不碰 token。
 */
interface LastServerStore {

    suspend fun read(): String?

    suspend fun write(value: String)
}

/** 独立的小 DataStore（mailhub_ui），和会话存储分开，避免碰到会话文件的加解密逻辑。 */
class DataStoreLastServerStore(context: Context) : LastServerStore {

    private val appContext = context.applicationContext

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            appContext.preferencesDataStoreFile("mailhub_ui")
        }
    }

    override suspend fun read(): String? = runCatching { dataStore.data.first()[KeyLastServer] }.getOrNull()

    override suspend fun write(value: String) {
        // 预填失败不影响登录，直接吞掉异常
        runCatching { dataStore.edit { prefs -> prefs[KeyLastServer] = value } }
    }

    private companion object {
        val KeyLastServer = stringPreferencesKey("last_server")
    }
}

/** DataStore 同一个文件只能有一个实例，这里用单例收口。 */
object LastServerStoreProvider {

    @Volatile
    private var instance: LastServerStore? = null

    fun of(context: Context): LastServerStore = instance ?: synchronized(this) {
        instance ?: DataStoreLastServerStore(context).also { instance = it }
    }
}
