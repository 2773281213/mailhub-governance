package com.mailhub.client.data.net

import com.mailhub.client.data.FakeCipher
import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.data.session.SessionStore
import com.mailhub.client.data.testSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 令牌按 origin 绑定：绑定 A 的客户端不会把令牌发给 B，旧客户端的 401 也不影响当前会话。 */
class ApiClientOriginTest {

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
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
            .body("""{"detail":"未登录"}""")
            .build()

    @Before
    fun setUp() {
        serverA = MockWebServer()
        serverA.start()
        serverB = MockWebServer()
        serverB.start()
        store = SessionStore(InMemoryDataStore(), FakeCipher())
        manager = SessionManager(store)
    }

    @After
    fun tearDown() {
        serverA.close()
        serverB.close()
    }

    @Test
    fun eachClientSendsOnlyItsOwnToken() = runBlocking {
        serverA.enqueue(ok())
        serverB.enqueue(ok())

        val clientA = manager.newClient(serverA.url("/"), "tok-A")
        val clientB = manager.newClient(serverB.url("/"), "tok-B")
        clientA.call { it.logout() }.getOrThrow()
        clientB.call { it.logout() }.getOrThrow()

        assertEquals("Bearer tok-A", serverA.takeRequest().headers["Authorization"])
        assertEquals("Bearer tok-B", serverB.takeRequest().headers["Authorization"])
    }

    @Test
    fun boundClientDoesNotSendTokenToAnotherServer() {
        serverA.enqueue(ok())
        serverB.enqueue(ok())
        val clientA = manager.newClient(serverA.url("/"), "tok-A")

        // 用绑定 A 的拦截器处理发往 B 的请求：不同源，不能带令牌
        probe(AuthInterceptor(serverA.url("/"), "tok-A", {}, { clientA }), serverB.url("/api/meta").toString())
        assertNull(serverB.takeRequest().headers["Authorization"])

        // 同源请求仍然带令牌
        probe(AuthInterceptor(serverA.url("/"), "tok-A", {}, { clientA }), serverA.url("/api/meta").toString())
        assertEquals("Bearer tok-A", serverA.takeRequest().headers["Authorization"])
    }

    @Test
    fun anonymousClientSendsNoToken() = runBlocking {
        serverA.enqueue(ok())

        manager.newClient(serverA.url("/"), null).call { api -> api.logout() }.getOrThrow()

        assertNull(serverA.takeRequest().headers["Authorization"])
    }

    @Test
    fun unauthorizedFromOldServerKeepsCurrentSession() = runBlocking {
        // 当前会话在 B
        val session = testSession(serverB.url("/").toString(), "tok-B")
        val current = manager.newClient(serverB.url("/"), "tok-B")
        manager.activate(session, current)
        // 切换前创建的 A 客户端还在手上
        val old = manager.newClient(serverA.url("/"), "tok-A")

        serverA.enqueue(unauthorized())
        val result = old.call { it.meta() }

        assertTrue("error=${result.exceptionOrNull()}", result.exceptionOrNull() is ApiError.Unauthorized)
        delay(300)
        assertTrue(manager.state.value is SessionState.LoggedIn)
        assertNotNull(store.session.first())
        assertEquals("tok-B", store.session.first()?.token)
    }

    private fun probe(interceptor: AuthInterceptor, url: String) {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        client.newCall(Request.Builder().url(url).build()).execute().close()
    }
}
