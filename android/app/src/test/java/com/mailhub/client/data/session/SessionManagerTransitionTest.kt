package com.mailhub.client.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.mailhub.client.data.FakeCipher
import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.testSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话切换与清空存储的竞态：旧令牌的 401 处理与 [SessionManager.replaceToken] 交错时，
 * 存储与状态必须保持一致，而且用户不能被自己的「退出所有设备 / 改密码」踢下线。
 */
class SessionManagerTransitionTest {

    /**
     * 能在「清空存储」时卡住的 DataStore：[gate] 不为 null 时，清空操作先通知 [clearStarted]，
     * 再等 [gate] 放行。保存操作不受影响。
     */
    private class GatedDataStore(private val inner: InMemoryDataStore) : DataStore<Preferences> {
        @Volatile
        var gate: CompletableDeferred<Unit>? = null
        val clearStarted = CompletableDeferred<Unit>()

        override val data: Flow<Preferences> get() = inner.data

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            val preview = transform(inner.data.first())
            val pending = gate
            if (preview.asMap().isEmpty() && pending != null) {
                clearStarted.complete(Unit)
                pending.await()
            }
            return inner.updateData(transform)
        }
    }

    private val serverUrl = "https://mail.example.test/"
    private lateinit var dataStore: GatedDataStore
    private lateinit var store: SessionStore
    private lateinit var scope: CoroutineScope
    private lateinit var manager: SessionManager

    /** 记下每个客户端的 401 回调，测试里直接调用，不必真的发请求。 */
    private val callbacks = ConcurrentHashMap<ApiClient, (ApiClient) -> Unit>()

    @Before
    fun setUp() {
        dataStore = GatedDataStore(InMemoryDataStore())
        store = SessionStore(dataStore, FakeCipher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = SessionManager(
            store = store,
            scope = scope,
            clientFactory = { url, token, onUnauthorized ->
                ApiClient(url, token, onUnauthorized).also { callbacks[it] = onUnauthorized }
            },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 以旧令牌登录，返回绑定旧令牌的客户端。 */
    private suspend fun loginOld(): ApiClient {
        val client = manager.newClient(serverUrl.toHttpUrl(), "tok-old")
        manager.activate(testSession(serverUrl, "tok-old", sessionId = "sid-old"), client)
        return client
    }

    /** 模拟这个客户端收到了一个普通接口的 401。 */
    private fun fire401(client: ApiClient) {
        callbacks.getValue(client).invoke(client)
    }

    private suspend fun awaitState(predicate: (SessionState) -> Boolean): SessionState =
        withTimeout(5_000) { manager.state.first(predicate) }

    @Test
    fun replaceTokenDuringOldTokenExpiryEndsWithNewTokenStored() = runBlocking {
        val old = loginOld()
        val gate = CompletableDeferred<Unit>()
        dataStore.gate = gate

        // 旧令牌的 401 处理已经开始清存储，但还卡在清空操作里
        fire401(old)
        withTimeout(5_000) { dataStore.clearStarted.await() }

        // 这时「退出所有设备」的响应回来了，拿着新令牌换会话
        val replaced = async { manager.replaceToken("tok-new", "sid-new", 200.0) }
        delay(50)
        gate.complete(Unit)

        assertTrue(withTimeout(5_000) { replaced.await() })
        val state = awaitState { it is SessionState.LoggedIn }
        assertEquals("tok-new", (state as SessionState.LoggedIn).session.token)
        assertEquals("sid-new", state.session.sessionId)
        // 存储与状态一致：新令牌已落盘，没有被旧 401 的清空操作抹掉
        assertEquals("tok-new", store.refresh()?.token)
    }

    @Test
    fun oldClientUnauthorizedAfterReplaceIsIgnored() = runBlocking {
        val old = loginOld()
        assertTrue(manager.replaceToken("tok-new", "sid-new", 200.0))

        fire401(old)
        delay(200)

        val state = manager.state.value
        assertTrue("state=$state", state is SessionState.LoggedIn)
        assertEquals("tok-new", (state as SessionState.LoggedIn).session.token)
        assertEquals("tok-new", store.refresh()?.token)
    }

    @Test
    fun unauthorizedWithoutContentionStillExpiresTheSession() = runBlocking {
        val old = loginOld()

        fire401(old)

        assertEquals(
            SessionState.LoggedOut(SessionState.Reason.Expired),
            awaitState { it is SessionState.LoggedOut },
        )
        assertNull(store.refresh())
    }

    @Test
    fun replaceTokenAfterUserLogoutDoesNothing() = runBlocking {
        loginOld()
        manager.clearForLogout()

        assertFalse(manager.replaceToken("tok-new", "sid-new", 200.0))
        assertEquals(SessionState.LoggedOut(SessionState.Reason.UserLogout), manager.state.value)
        assertNull(store.refresh())
    }
}
