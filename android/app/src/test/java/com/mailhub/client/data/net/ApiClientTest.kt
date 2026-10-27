package com.mailhub.client.data.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.mailhub.client.data.model.LoginRequest
import com.mailhub.client.data.repo.AuthRepository
import com.mailhub.client.data.session.Cipher
import com.mailhub.client.data.session.Session
import com.mailhub.client.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: SessionStore
    private lateinit var expiredFlow: MutableSharedFlow<Unit>
    private lateinit var client: ApiClient

    private class FakeCipher : Cipher {
        override fun encrypt(plain: String): String = "enc:$plain"
        override fun decrypt(blob: String): String = blob.removePrefix("enc:")
    }

    /**
     * 内存版 DataStore。真实 DataStore 在 Windows 上用 renameTo 覆盖文件会失败，
     * 单元测试用内存实现，生产代码仍然用 PreferenceDataStoreFactory。
     */
    private class InMemoryDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: StateFlow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock {
                val next = transform(state.value)
                state.value = next
                next
            }
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun session(token: String) = Session(
        serverUrl = server.url("/").toString(),
        token = token,
        sessionId = "sid-1",
        expiresTs = 123.0,
        deviceName = "Pixel",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = SessionStore(InMemoryDataStore(), FakeCipher())
        expiredFlow = MutableSharedFlow(extraBufferCapacity = 1)
        client = ApiClient(server.url("/"), store, expiredFlow)
    }

    @After
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    @Test
    fun loginSavesTokenAndLaterRequestsCarryBearer() = runBlocking {
        server.enqueue(jsonResponse(200, """{"ok":true,"token":"tok-123","session_id":"sid-1","expires_ts":123.0}"""))
        server.enqueue(jsonResponse(200, """{"api_version":1,"tz":"Asia/Shanghai"}"""))
        server.enqueue(jsonResponse(200, """{"ok":true}"""))

        var current: ApiClient? = null
        val auth = AuthRepository(
            sessionStore = store,
            clientFactory = { url -> ApiClient(url, store, expiredFlow) },
            clientProvider = { current },
            onClientReady = { current = it },
        )

        val meta = auth.login(server.url("/").toString(), "pw", "Pixel 8").getOrThrow()
        assertEquals(1, meta.apiVersion)
        assertEquals("tok-123", store.session.first()?.token)

        current!!.call { it.logout() }.getOrThrow()

        val loginRequest = server.takeRequest()
        val metaRequest = server.takeRequest()
        val logoutRequest = server.takeRequest()

        val loginBody = loginRequest.body?.utf8().orEmpty()
        assertTrue("body=$loginBody", loginBody.contains("\"client\":\"android\""))
        assertTrue("body=$loginBody", loginBody.contains("\"device_name\":\"Pixel 8\""))
        assertEquals("Bearer tok-123", metaRequest.headers["Authorization"])
        assertEquals("Bearer tok-123", logoutRequest.headers["Authorization"])
        assertNull(logoutRequest.headers["Origin"])
    }

    @Test
    fun unauthorizedOnNormalCallClearsSessionAndEmitsOnce() = runBlocking {
        store.save(session("tok-1"))
        val events = mutableListOf<Unit>()
        val job = launch(Dispatchers.Default) { expiredFlow.collect { events += it } }
        delay(100)

        server.enqueue(jsonResponse(401, """{"detail":"会话已失效"}"""))
        val result = client.call { it.meta() }

        val error = result.exceptionOrNull()
        assertTrue("error=$error", error is ApiError.Unauthorized)
        assertEquals("会话已失效", (error as ApiError.Unauthorized).detail)
        assertNull(store.session.first())

        delay(200)
        assertEquals(1, events.size)
        job.cancel()
    }

    @Test
    fun unauthorizedOnLoginDoesNotClearSession() = runBlocking {
        store.save(session("tok-keep"))
        val events = mutableListOf<Unit>()
        val job = launch(Dispatchers.Default) { expiredFlow.collect { events += it } }
        delay(100)

        server.enqueue(jsonResponse(401, """{"detail":"密码错误"}"""))
        val result = client.call { it.login(LoginRequest(password = "bad", deviceName = "Pixel")) }

        val error = result.exceptionOrNull()
        assertTrue("error=$error", error is ApiError.Unauthorized)
        assertEquals("密码错误", (error as ApiError.Unauthorized).detail)
        assertNotNull(store.session.first())

        delay(200)
        assertEquals(0, events.size)
        job.cancel()
    }

    @Test
    fun forbiddenCarriesProtectedHeader() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-MailHub-Protected", "1")
                .body("""{"detail":"受保护邮件不能批量删除"}""")
                .build(),
        )
        val result = client.call { it.messages() }

        val error = result.exceptionOrNull() as ApiError.Forbidden
        assertEquals("受保护邮件不能批量删除", error.detail)
        assertTrue(error.protected)
    }

    @Test
    fun tooManyRequestsCarriesRetryAfter() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "120")
                .body("""{"detail":"尝试过多"}""")
                .build(),
        )
        val result = client.call { it.messages() }

        val error = result.exceptionOrNull() as ApiError.TooManyRequests
        assertEquals(120, error.retryAfterSec)
    }

    @Test
    fun parsesOverviewWithVariableDailyKeys() = runBlocking {
        val body = """
            {"stats":{"total":10,"unread":2,"today":3,"today_otp":1,"important_unread":1,
              "by_category":{"重要账单":4}},
             "days":["10-01"],
             "daily":[{"day":"10-01","重要账单":3,"验证码":2}],
             "categories":["重要账单"],
             "important":[{"id":7,"subject":"账单","sender_addr":"a@b.c","date_ts":1.0,
               "category":"账单","bill_tier":"重要","importance":5,"summary":"","unread":1,
               "account_name":"A1"}],
             "digest":{"day":"10-01","content":"摘要","created_ts":1.0},
             "ai_enabled":true}
        """.trimIndent()
        server.enqueue(jsonResponse(200, body))

        val overview = client.call { it.overview() }.getOrThrow()

        assertEquals(10, overview.stats.total)
        assertEquals(4, overview.stats.byCategory["重要账单"])
        assertEquals(3, overview.daily[0]["重要账单"]?.jsonPrimitive?.int)
        assertEquals(2, overview.daily[0]["验证码"]?.jsonPrimitive?.int)
        assertTrue(overview.important[0].isUnread)
        assertEquals("摘要", overview.digest?.content)
        assertTrue(overview.aiEnabled)
    }

    @Test
    fun pagesWithOpaqueCursorOnly() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"total":null,"items":[{"id":5,"subject":"x","unread":1}],
                   "next_cursor":{"before_ts":123.456,"before_id":5,"token":"tok-abc"}}""",
            ),
        )
        server.enqueue(jsonResponse(200, """{"total":1,"items":[],"next_cursor":null}"""))

        val page1 = client.call { it.messages(pageSize = 20) }.getOrThrow()
        assertEquals("tok-abc", page1.nextCursor?.token)
        assertEquals(123.456, page1.nextCursor?.beforeTs ?: 0.0, 0.0)
        assertTrue(page1.items[0].isUnread)

        val page2 = client.call { it.messages(pageSize = 20, cursor = page1.nextCursor?.token) }.getOrThrow()
        assertNull(page2.nextCursor)

        server.takeRequest()
        val second = server.takeRequest()
        assertTrue(second.target, second.target.contains("cursor=tok-abc"))
        assertFalse(second.target, second.target.contains("before_ts"))
    }

    @Test
    fun ignoresUnknownFields() = runBlocking {
        server.enqueue(jsonResponse(200, """{"ok":true,"unknown_field":{"a":1},"extra":[1,2]}"""))
        val result = client.call { it.logout() }.getOrThrow()
        assertTrue(result.ok)
    }
}
