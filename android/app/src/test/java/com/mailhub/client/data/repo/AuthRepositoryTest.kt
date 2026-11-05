package com.mailhub.client.data.repo

import com.mailhub.client.data.FakeCipher
import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.session.SessionStore
import com.mailhub.client.data.testSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 登录与退出的错误路径：退出遇到 401 算已退出；登录校验失败要吊销新令牌且不改变状态。 */
class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var dataStore: InMemoryDataStore
    private lateinit var store: SessionStore
    private lateinit var manager: SessionManager
    private lateinit var auth: AuthRepository

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun loginOk(token: String): MockResponse = jsonResponse(
        200,
        """{"ok":true,"token":"$token","session_id":"sid-new","expires_ts":1900000000.0}""",
    )

    private val metaOk = """{"api_version":1,"tz":"Asia/Shanghai"}"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dataStore = InMemoryDataStore()
        store = SessionStore(dataStore, FakeCipher())
        manager = SessionManager(store)
        auth = AuthRepository(manager)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun logoutWithUnauthorizedIsUserLogout() = runBlocking {
        val session = testSession(server.url("/").toString(), "tok-1")
        val client = manager.newClient(server.url("/"), "tok-1")
        manager.activate(session, client)

        // 服务端已经没有这个会话：401 视为已退出，不是过期
        server.enqueue(jsonResponse(401, """{"detail":"未登录"}"""))
        auth.logout()

        val request = server.takeRequest()
        assertEquals("Bearer tok-1", request.headers["Authorization"])
        assertTrue("target=${request.target}", request.target.endsWith("/api/logout"))
        assertEquals(SessionState.LoggedOut(SessionState.Reason.UserLogout), manager.state.value)
        assertNull(store.session.first())
    }

    @Test
    fun logoutClearsSessionOnSuccess() = runBlocking {
        val session = testSession(server.url("/").toString(), "tok-1")
        val client = manager.newClient(server.url("/"), "tok-1")
        manager.activate(session, client)

        server.enqueue(jsonResponse(200, """{"ok":true}"""))
        auth.logout()

        assertEquals(SessionState.LoggedOut(SessionState.Reason.UserLogout), manager.state.value)
        assertNull(store.session.first())
    }

    @Test
    fun loginFailureOnMetaRevokesNewToken() = runBlocking {
        manager.restore() // 未登录状态：LoggedOut()
        val before = manager.state.value

        server.enqueue(loginOk("tok-new"))
        server.enqueue(jsonResponse(500, """{"detail":"服务器内部错误"}"""))
        server.enqueue(jsonResponse(200, """{"ok":true}"""))

        val result = auth.login(server.url("/").toString(), "pw", "Pixel 8")

        assertTrue("result=$result", result.isFailure)
        server.takeRequest() // 登录请求
        val metaRequest = server.takeRequest()
        val revokeRequest = server.takeRequest()
        assertEquals("Bearer tok-new", metaRequest.headers["Authorization"])
        assertTrue("target=${revokeRequest.target}", revokeRequest.target.endsWith("/api/logout"))
        assertEquals("Bearer tok-new", revokeRequest.headers["Authorization"])
        // 服务器只收到一次吊销请求
        assertEquals(3, server.requestCount)
        assertNull(store.session.first())
        assertEquals(before, manager.state.value)
    }

    @Test
    fun loginFailureOnOldApiVersionRevokesNewToken() = runBlocking {
        manager.restore()
        val before = manager.state.value

        server.enqueue(loginOk("tok-old-server"))
        server.enqueue(jsonResponse(200, """{"api_version":0,"tz":"Asia/Shanghai"}"""))
        server.enqueue(jsonResponse(200, """{"ok":true}"""))

        val result = auth.login(server.url("/").toString(), "pw", "Pixel 8")

        assertTrue("result=$result", result.isFailure)
        server.takeRequest()
        server.takeRequest()
        val revokeRequest = server.takeRequest()
        assertEquals("Bearer tok-old-server", revokeRequest.headers["Authorization"])
        assertTrue("target=${revokeRequest.target}", revokeRequest.target.endsWith("/api/logout"))
        assertEquals(3, server.requestCount)
        assertNull(store.session.first())
        assertEquals(before, manager.state.value)
    }

    @Test
    fun loginSendsNoTokenAndActivatesOnSuccess() = runBlocking {
        manager.restore()
        server.enqueue(loginOk("tok-new"))
        server.enqueue(jsonResponse(200, metaOk))

        val meta = auth.login(server.url("/").toString(), "pw", "Pixel 8").getOrThrow()

        assertEquals(1, meta.apiVersion)
        val loginRequest = server.takeRequest()
        assertNull(loginRequest.headers["Authorization"])
        val metaRequest = server.takeRequest()
        assertEquals("Bearer tok-new", metaRequest.headers["Authorization"])
        val state = manager.state.value
        assertTrue("state=$state", state is SessionState.LoggedIn)
        assertEquals("tok-new", (state as SessionState.LoggedIn).session.token)
        assertEquals("tok-new", store.session.first()?.token)
    }

    @Test
    fun loginWithBadPasswordKeepsPreviousSession() = runBlocking {
        val session = testSession(server.url("/").toString(), "tok-keep")
        val client = manager.newClient(server.url("/"), "tok-keep")
        manager.activate(session, client)

        server.enqueue(jsonResponse(401, """{"detail":"密码错误"}"""))
        val result = auth.login(server.url("/").toString(), "bad", "Pixel 8")

        assertTrue("result=$result", result.isFailure)
        assertEquals("密码错误", (result.exceptionOrNull() as com.mailhub.client.data.net.ApiError.Unauthorized).detail)
        assertEquals("tok-keep", store.session.first()?.token)
        assertTrue(manager.state.value is SessionState.LoggedIn)
    }
}
