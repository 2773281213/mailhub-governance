package com.mailhub.client.data.session

import com.mailhub.client.data.net.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** 会话状态。页面层只读 [SessionManager.state]，不再各自猜测是否已登录。 */
sealed interface SessionState {

    /** 还没从本地存储恢复完，界面应显示启动态而不是未登录 */
    data object Restoring : SessionState

    /** 未登录，[reason] 说明是主动退出还是令牌过期 */
    data class LoggedOut(val reason: Reason = Reason.None) : SessionState

    /** 已登录。[client] 绑定这台服务器和这个令牌。 */
    data class LoggedIn(val session: Session, val client: ApiClient) : SessionState

    enum class Reason {
        /** 从未登录过，或本地没有可用会话 */
        None,

        /** 令牌过期或被服务端判定失效 */
        Expired,

        /** 用户主动退出 */
        UserLogout,
    }
}

/**
 * 会话状态的唯一持有者：启动恢复、登录成功、退出、令牌过期都在这里收口。
 *
 * 不依赖 Android Context，可以在 JVM 单元测试里直接跑。
 */
class SessionManager(
    private val store: SessionStore,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clientFactory: (HttpUrl, String?, (ApiClient) -> Unit) -> ApiClient =
        { url, token, onUnauthorized -> ApiClient(url, token, onUnauthorized) },
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val restoreMutex = Mutex()
    private var restored = false

    /** 并发 401 只处理一次，避免重复清存储、重复发事件。 */
    private val expiring = AtomicBoolean(false)

    /** 当前登录的客户端；未登录、恢复中为 null。 */
    fun currentClient(): ApiClient? = (_state.value as? SessionState.LoggedIn)?.client

    /**
     * 按服务器与令牌新建客户端。令牌为 null 时用于未登录的登录请求。
     * 这类客户端与当前会话互不干扰：它的 401 只有在它就是当前客户端时才生效。
     */
    fun newClient(serverUrl: HttpUrl, token: String?): ApiClient =
        clientFactory(serverUrl, token) { handleUnauthorized(it) }

    /**
     * 从本地存储恢复会话，用 [restoreMutex] 保证只执行一次，重复调用直接返回。
     * 恢复期间如果已经登录成功（例如用户在启动动画里就完成了登录），不覆盖新会话。
     */
    suspend fun restore() {
        restoreMutex.withLock {
            if (restored) {
                return
            }
            doRestore()
            restored = true
        }
    }

    /** 真正的恢复逻辑；被取消时不置 [restored]，下次还能重试。 */
    private suspend fun doRestore() {
        val session = readStoredSession()
        if (session == null) {
            settleRestore(SessionState.LoggedOut(), clearStore = false)
            return
        }
        val url = session.serverUrl.toHttpUrlOrNull()
        if (url == null) {
            // 存储里的地址被改坏了，等于没有可用会话
            settleRestore(SessionState.LoggedOut(), clearStore = true)
            return
        }
        if (session.expiresTs > 0 && session.expiresTs <= clock()) {
            settleRestore(SessionState.LoggedOut(SessionState.Reason.Expired), clearStore = true)
            return
        }
        val client = newClient(url, session.token)
        _state.compareAndSet(SessionState.Restoring, SessionState.LoggedIn(session, client))
    }

    /** 登录成功后调用：先落盘，再切换到已登录状态。 */
    suspend fun activate(session: Session, client: ApiClient) {
        store.save(session)
        expiring.set(false)
        _state.value = SessionState.LoggedIn(session, client)
    }

    /** 退出登录：清本地存储，状态转为 [SessionState.Reason.UserLogout]。 */
    suspend fun clearForLogout() {
        try {
            store.clear()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 本地清理失败也要把状态切到未登录，否则界面会卡在登录态
        }
        _state.value = SessionState.LoggedOut(SessionState.Reason.UserLogout)
    }

    private suspend fun readStoredSession(): Session? = try {
        store.refresh()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 读取或解密失败按「没有会话」处理
        null
    }

    /** 恢复过程中的终态只在还处于 [SessionState.Restoring] 时写入，避免覆盖刚完成的登录。 */
    private suspend fun settleRestore(target: SessionState, clearStore: Boolean) {
        if (_state.value !is SessionState.Restoring) {
            return
        }
        if (clearStore) {
            try {
                store.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 清不掉也不影响状态判定，下次启动会再次尝试
            }
        }
        // 清存储是挂起操作，挂起期间可能已经完成了新的登录，直接赋值会把它覆盖掉，
        // 所以只在状态仍是 Restoring 时写入终态。
        _state.compareAndSet(SessionState.Restoring, target)
    }

    /**
     * 401 回调。只有回调里的客户端就是当前会话的客户端时才判定为过期；
     * 切换服务器后旧客户端的 401 一律忽略。
     */
    private fun handleUnauthorized(client: ApiClient) {
        val current = _state.value as? SessionState.LoggedIn ?: return
        if (current.client !== client) {
            return
        }
        if (!expiring.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            try {
                val now = _state.value
                if (now is SessionState.LoggedIn && now.client === client) {
                    try {
                        store.clear()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 存储清理失败也要让界面回到未登录
                    }
                    _state.compareAndSet(
                        now,
                        SessionState.LoggedOut(SessionState.Reason.Expired),
                    )
                }
            } finally {
                expiring.set(false)
            }
        }
    }
}
