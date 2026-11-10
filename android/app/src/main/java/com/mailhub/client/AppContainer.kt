package com.mailhub.client

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mailhub.client.data.local.LOCAL_MAX_REQUESTS_PER_HOST
import com.mailhub.client.data.local.LocalApiInterceptor
import com.mailhub.client.data.local.LocalRuntime
import com.mailhub.client.data.local.LocalSync
import com.mailhub.client.data.local.initializeLocalCursor
import com.mailhub.client.data.local.isLocal
import com.mailhub.client.data.local.isLocalMode
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.repo.AuthRepository
import com.mailhub.client.data.repo.MailRepository
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.session.SessionStore
import com.mailhub.client.data.session.TokenCipher
import com.mailhub.client.work.ChangesAction
import com.mailhub.client.work.ChangesCursorStore
import com.mailhub.client.work.ChangesScheduleBinding
import com.mailhub.client.work.ChangesScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Interceptor

/**
 * 手写依赖注入：只负责组装会话状态与各 Repository，不持有会话本身。
 *
 * 启动恢复由页面层调用 `sessionManager.restore()` 完成：`sessionState` 在恢复完成前
 * 一直是 [SessionState.Restoring]，界面不会把恢复期误当成未登录，也不会和登录竞态。
 *
 * **本机模式（`LOCAL_MODE.md` 3.1）**：`clientFactory` 看到本机会话的保留主机时，给
 * [ApiClient] 额外挂一个 [LocalApiInterceptor]，请求在进程内由 `:core` 的 `LocalApi` 回答；
 * 服务器模式不加任何拦截器，行为与现在完全一致。
 *
 * 本机后端（[LocalRuntime]）是延迟创建的：服务器模式的用户不会因此打开本机数据库，也不会
 * 加载打包的 SQLite 本地库。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        appContext.preferencesDataStoreFile("mailhub_session")
    }

    private val sessionStore = SessionStore(dataStore, TokenCipher())

    /**
     * 本机后端，第一次真正用到时才创建；用可空字段 + 同步块而不是 `by lazy`，
     * 这样「退出登录时取消同步」不会在本机模式从未启用时反而把它创建出来。
     */
    @Volatile
    private var localRuntimeOrNull: LocalRuntime? = null

    val sessionManager = SessionManager(
        store = sessionStore,
        clientFactory = { url, token, onUnauthorized ->
            // 本机会话必须挂拦截器：否则请求会发到永远解析不到的保留域名上。
            // 服务器模式不加任何拦截器，行为与现在完全一致。
            val extra = if (url.isLocalMode()) {
                listOf<Interceptor>(LocalApiInterceptor(api = { runtime().api() }))
            } else {
                emptyList()
            }
            ApiClient(url, token, onUnauthorized, extra, LOCAL_MAX_REQUESTS_PER_HOST.takeIf { url.isLocalMode() })
        },
    )

    /** 页面层订阅的会话状态。 */
    val sessionState: StateFlow<SessionState> get() = sessionManager.state

    val authRepository = AuthRepository(sessionManager) {
        // 进入本机模式：先打开本机库，把通知游标设成当前 max(id)，再由 AuthRepository 激活会话
        initializeLocalCursor(ChangesCursorStore.of(appContext)) {
            runtime().maxMessageId()
        }
    }

    val mailRepository = MailRepository(sessionManager)

    /**
     * 后台拉取的调度只跟会话走，与有没有界面无关：
     * 登录进入 [SessionState.LoggedIn] 时调度，退出或过期进入 [SessionState.LoggedOut] 时取消。
     * 放在容器的 scope 里，按返回键关掉 App、系统回收 Activity 都不会取消已经排好的周期任务。
     */
    init {
        ChangesScheduleBinding(
            states = sessionManager.state,
            scope = scope,
            onAction = { action ->
                when (action) {
                    ChangesAction.Schedule -> ChangesScheduler.schedule(appContext)
                    ChangesAction.Cancel -> ChangesScheduler.cancel(appContext)
                    ChangesAction.None -> Unit
                }
            },
        ).start()
        scope.launch {
            sessionManager.state.collect { state ->
                // 退出本机模式只停掉进行中的同步：数据库不关、数据不删（LOCAL_MODE.md 3.1）。
                // 本机后端还没创建时什么都不做，服务器模式也不会因此打开本机库。
                if (state is SessionState.LoggedOut) {
                    localRuntimeOrNull?.cancelSync()
                }
            }
        }
    }

    /**
     * 回到前台（`MainActivity.onStart`）：本机会话时触发一次前台同步。
     *
     * 冷启动时会话可能还在恢复，所以先 `restore()` 再判断；60 秒的节流在 [LocalSync] 里。
     * 服务器模式直接返回，不碰本机库。
     */
    fun onForeground() {
        scope.launch {
            sessionManager.restore()
            if (isLocalSession()) {
                runtime().sync().onForeground()
            }
        }
    }

    /**
     * 后台任务用：当前是本机会话时返回同步入口，否则返回 `null`（服务器模式不碰本机库）。
     *
     * 先 `restore()`：JobScheduler 可能在 App 进程刚起来、界面还没恢复完时触发任务。
     */
    suspend fun localSyncOrNull(): LocalSync? {
        sessionManager.restore()
        return if (isLocalSession()) runtime().sync() else null
    }

    /** 当前会话是不是本机会话；恢复中、未登录都算「不是」。 */
    private fun isLocalSession(): Boolean =
        (sessionManager.state.value as? SessionState.LoggedIn)?.session?.isLocal == true

    /** 取本机后端，第一次用到时创建（创建过程本身在 IO 线程上打开数据库）。 */
    private fun runtime(): LocalRuntime = localRuntimeOrNull ?: synchronized(this) {
        localRuntimeOrNull ?: LocalRuntime(appContext, sessionManager).also {
            localRuntimeOrNull = it
        }
    }
}
