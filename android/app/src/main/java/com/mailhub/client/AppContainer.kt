package com.mailhub.client

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mailhub.client.data.repo.AuthRepository
import com.mailhub.client.data.repo.MailRepository
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.session.SessionStore
import com.mailhub.client.data.session.TokenCipher
import com.mailhub.client.work.ChangesAction
import com.mailhub.client.work.ChangesScheduleBinding
import com.mailhub.client.work.ChangesScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * 手写依赖注入：只负责组装会话状态与各 Repository，不持有会话本身。
 *
 * 启动恢复由页面层调用 `sessionManager.restore()` 完成：`sessionState` 在恢复完成前
 * 一直是 [SessionState.Restoring]，界面不会把恢复期误当成未登录，也不会和登录竞态。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        appContext.preferencesDataStoreFile("mailhub_session")
    }

    private val sessionStore = SessionStore(dataStore, TokenCipher())

    val sessionManager = SessionManager(sessionStore)

    /** 页面层订阅的会话状态。 */
    val sessionState: StateFlow<SessionState> get() = sessionManager.state

    val authRepository = AuthRepository(sessionManager)

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
    }
}
