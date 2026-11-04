package com.mailhub.client.data.session

import com.mailhub.client.data.FakeCipher
import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.testSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 令牌原地更换：退出所有设备、改密码返回新令牌后，客户端与存储都要换成新令牌，
 * 旧客户端残留的 401 不能把新会话踢掉。
 */
class SessionManagerReplaceTokenTest {

    private lateinit var server: MockWebServer
    private lateinit var dataStore: InMemoryDataStore
    private lateinit var store: SessionStore
    private lateinit var manager: SessionManager

    private fun ok(): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body("""{"ok":true}""")
            .build()

    private fun unauthorized(): MockResponse =
        MockResponse.Builder()
            .code(401)
            .addHeader("Content-Type", "application/json")
            .body("""{"detail":"令牌已吊销"}""")
            .build()

    /** 造一个「已登录」的旧会话，返回绑定旧令牌的客户端。 */
    private suspend fun activateOldSession(): ApiClient {
        val stored = testSession(
            server.url("/").toString(),
            "tok-old",
            sessionId = "sid-old",
            expiresTs = 100.0,
        )
        val bound = manager.newClient(server.url("/"), "tok-old")
        manager.activate(stored, bound)
        return bound
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dataStore = InMemoryDataStore()
        store = SessionStore(dataStore, FakeCipher())
        manager = SessionManager(store)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun replaceTokenSwitchesToClientCarryingNewToken() = runBlocking {
        activateOldSession()

        val replaced = manager.replaceToken("tok-new", "sid-new", 9_000.0)

        assertTrue(replaced)
        server.enqueue(ok())
        manager.currentClient()!!.call { it.meta() }.getOrThrow()
        assertEquals("Bearer tok-new", server.takeRequest().headers["Authorization"])

        val state = manager.state.value
        assertTrue("state=$state", state is SessionState.LoggedIn)
        val loggedIn = state as SessionState.LoggedIn
        assertEquals("tok-new", loggedIn.session.token)
        assertEquals("sid-new", loggedIn.session.sessionId)
        assertEquals(9_000.0, loggedIn.session.expiresTs, 0.0)
        // 服务器地址和设备名沿用旧会话
        assertEquals(server.url("/").toString(), loggedIn.session.serverUrl)
        assertEquals("Pixel 8", loggedIn.session.deviceName)
    }

    @Test
    fun replaceTokenSavesNewSessionToStore() = runBlocking {
        val old = activateOldSession()

        manager.replaceToken("tok-new", "sid-new", 9_000.0)

        val stored = store.session.first()
        assertNotNull(stored)
        assertEquals("tok-new", stored?.token)
        assertEquals("sid-new", stored?.sessionId)
        assertEquals(9_000.0, stored?.expiresTs ?: -1.0, 0.0)
        assertEquals("Pixel 8", stored?.deviceName)
        // 客户端也换成了新的，旧客户端不再等于当前客户端
        assertNotSame(old, manager.currentClient())
    }

    @Test
    fun unauthorizedFromOldClientAfterReplaceKeepsSession() = runBlocking {
        val old = activateOldSession()

        manager.replaceToken("tok-new", "sid-new", 9_000.0)
        server.enqueue(unauthorized())
        val result = old.call { it.meta() }

        val error = result.exceptionOrNull()
        assertTrue("error=$error", error is ApiError.Unauthorized)
        // 401 处理是异步的，等一会儿确认状态没被踢成未登录
        delay(300)
        val state = manager.state.value
        assertTrue("state=$state", state is SessionState.LoggedIn)
        assertEquals("tok-new", (state as SessionState.LoggedIn).session.token)
        assertEquals("tok-new", store.session.first()?.token)
        assertEquals(0, dataStore.clearCount.get())
    }

    @Test
    fun replaceTokenWithoutLoginReturnsFalse() = runBlocking {
        // 还没恢复、也没登录
        assertFalse(manager.replaceToken("tok-new", "sid-new", 9_000.0))
        assertEquals(SessionState.Restoring, manager.state.value)

        activateOldSession()
        manager.clearForLogout()

        assertFalse(manager.replaceToken("tok-new", "sid-new", 9_000.0))
        assertEquals(SessionState.LoggedOut(SessionState.Reason.UserLogout), manager.state.value)
        assertNull(store.session.first())
    }
}
