package com.mailhub.client

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.repo.AuthRepository
import com.mailhub.client.data.repo.MailRepository
import com.mailhub.client.data.session.SessionStore
import com.mailhub.client.data.session.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 手写依赖注入：持有 SessionStore、当前服务器的 ApiClient 和各 Repository。
 * 启动时从 DataStore 恢复会话；令牌失效时通过 [sessionExpired] 通知界面回登录页。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        appContext.preferencesDataStoreFile("mailhub_session")
    }

    val sessionStore = SessionStore(dataStore, TokenCipher())

    val sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    var client: ApiClient? = null
        private set

    val authRepository = AuthRepository(
        sessionStore = sessionStore,
        clientFactory = { url -> ApiClient(url, sessionStore, sessionExpired) },
        clientProvider = { client },
        onClientReady = { client = it },
    )

    val mailRepository = MailRepository(clientProvider = { client })

    init {
        scope.launch {
            runCatching {
                val session = sessionStore.refresh() ?: return@launch
                val url = session.serverUrl.toHttpUrlOrNull() ?: return@launch
                client = ApiClient(url, sessionStore, sessionExpired)
            }
        }
    }
}
