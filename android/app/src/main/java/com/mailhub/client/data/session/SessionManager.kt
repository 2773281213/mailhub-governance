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

    /**
     * 状态切换锁：「改存储 → 切状态」必须作为一个整体执行。
     *
     * 否则旧客户端的 401 处理可能在 [replaceToken] 落盘新令牌之后才清空存储，
     * 界面显示已登录，存储里却什么都没有，下次启动就变成未登录。
     * 持锁期间不做网络请求。
     */
    private val transitionMutex = Mutex()

    /**
     * 刚因 401 过期的会话（只在内存里，仅由 [transitionMutex] 保护）。
     *
     * 退出所有设备、改密码时，服务器先吊销旧令牌再返回新令牌；同一时刻在途的旧令牌请求
     * 收到 401 会先把会话判为过期。随后到达的 [replaceToken] 用它恢复到新令牌，
     * 避免用户被自己的操作踢下线。主动退出、重新登录都会清掉它。
     */
    private var lastExpired: Session? = null

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
        transitionMutex.withLock {
            store.save(session)
            lastExpired = null
            expiring.set(false)
            _state.value = SessionState.LoggedIn(session, client)
        }
    }

    /**
     * 令牌原地更换：退出所有设备、改密码这类接口会返回新令牌并吊销旧令牌。
     *
     * 已登录时：用同一个服务器地址和新令牌建新客户端，把新会话落盘，再把状态切到
     * [SessionState.LoggedIn]；切换后旧客户端的 401 会因为不再是当前客户端而被忽略。
     *
     * 旧令牌在途请求的 401 抢先把会话判为过期时，用 [lastExpired] 恢复到新令牌。
     * 未登录、主动退出等其他状态下什么都不做，返回 false。
     *
     * [token] 不写日志。
     */
    suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean =
        transitionMutex.withLock {
            val base = when (val now = _state.value) {
                is SessionState.LoggedIn -> now.session
                is SessionState.LoggedOut ->
                    lastExpired.takeIf { now.reason == SessionState.Reason.Expired }
                SessionState.Restoring -> null
            } ?: return@withLock false
            // 服务器地址被改坏时没有可信的来源，按不可切换处理
            val url = base.serverUrl.toHttpUrlOrNull() ?: return@withLock false
            val session = base.copy(
                token = token,
                sessionId = sessionId,
                expiresTs = expiresTs,
            )
            val client = newClient(url, token)
            store.save(session)
            lastExpired = null
            expiring.set(false)
            _state.value = SessionState.LoggedIn(session, client)
            true
        }

    /** 退出登录：清本地存储，状态转为 [SessionState.Reason.UserLogout]。 */
    suspend fun clearForLogout() {
        transitionMutex.withLock {
            try {
                store.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 本地清理失败也要把状态切到未登录，否则界面会卡在登录态
            }
            lastExpired = null
            _state.value = SessionState.LoggedOut(SessionState.Reason.UserLogout)
        }
    }

    private suspend fun readStoredSession(): Session? = try {
        store.refresh()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 读取或解密失败按「没有会话」处理
        null
    }

    /**
     * 恢复过程中的终态只在还处于 [SessionState.Restoring] 时写入，避免覆盖刚完成的登录。
     * 清存储与写终态在 [transitionMutex] 里一起完成：否则清存储可能抹掉刚登录落盘的新会话。
     */
    private suspend fun settleRestore(target: SessionState, clearStore: Boolean) {
        transitionMutex.withLock {
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
            _state.value = target
        }
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
                transitionMutex.withLock {
                    // 拿到锁后再确认一次：等锁期间可能已经换了令牌或重新登录，
                    // 那样这个 401 属于旧客户端，必须忽略，不能清掉新会话
                    val now = _state.value
                    if (now is SessionState.LoggedIn && now.client === client) {
                        try {
                            store.clear()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 存储清理失败也要让界面回到未登录
                        }
                        lastExpired = now.session
                        _state.value = SessionState.LoggedOut(SessionState.Reason.Expired)
                    }
                }
            } finally {
                expiring.set(false)
            }
        }
    }
}
