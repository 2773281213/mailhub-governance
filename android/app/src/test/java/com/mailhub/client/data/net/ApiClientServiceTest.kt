package com.mailhub.client.data.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.http.GET

/**
 * 测试用的功能自有接口：按新约定，功能在自己的目录里声明接口，
 * 用 [ApiClient.service] / [ApiClient.callService] 取实例，不再改 MailHubApi。
 */
interface ProbeApi {

    @GET("api/probe")
    suspend fun probe(): ProbeResponse
}

@Serializable
data class ProbeResponse(val ok: Boolean = false, val count: Int = 0)

/** 功能自有接口的创建与调用：错误映射必须和 [ApiClient.call] 完全一致。 */
class ApiClientServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    /** 401 回调次数：由拦截器在请求线程上直接回调。 */
    private var unauthorizedCount = 0

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(server.url("/"), "tok-1") { unauthorizedCount++ }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun callServiceDecodesResponse() = runBlocking {
        server.enqueue(jsonResponse(200, """{"ok":true,"count":7}"""))

        val body = client.callService(ProbeApi::class.java) { it.probe() }.getOrThrow()

        assertTrue(body.ok)
        assertEquals(7, body.count)
        assertEquals("/api/probe", server.takeRequest().target)
    }

    @Test
    fun callServiceOn401TriggersCallback() = runBlocking {
        server.enqueue(jsonResponse(401, """{"detail":"会话已失效"}"""))

        val result = client.callService(ProbeApi::class.java) { it.probe() }

        val error = result.exceptionOrNull()
        assertTrue("error=$error", error is ApiError.Unauthorized)
        assertEquals("会话已失效", (error as ApiError.Unauthorized).detail)
        assertEquals(1, unauthorizedCount)
    }

    @Test
    fun callServiceDecodeFailureBecomesDecodeError() = runBlocking {
        // 布尔字段收到对象：解码失败，但结果里是 ApiError.Decode 而不是抛异常
        server.enqueue(jsonResponse(200, """{"ok":{"nested":1},"count":0}"""))

        val result = client.callService(ProbeApi::class.java) { it.probe() }

        assertTrue("result=$result", result.isFailure)
        assertTrue("error=${result.exceptionOrNull()}", result.exceptionOrNull() is ApiError.Decode)
    }

    @Test
    fun callServiceForbiddenCarriesProtectedHeader() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-MailHub-Protected", "1")
                .body("""{"detail":"需要二次验证"}""")
                .build(),
        )

        val result = client.callService(ProbeApi::class.java) { it.probe() }

        val error = result.exceptionOrNull()
        assertTrue("error=$error", error is ApiError.Forbidden)
        val forbidden = error as ApiError.Forbidden
        assertEquals("需要二次验证", forbidden.detail)
        assertTrue(forbidden.protected)
    }

    @Test
    fun callServiceTooManyRequestsCarriesRetryAfter() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Content-Type", "application/json")
                .addHeader("Retry-After", "120")
                .body("""{"detail":"尝试过多"}""")
                .build(),
        )

        val result = client.callService(ProbeApi::class.java) { it.probe() }

        val error = result.exceptionOrNull()
        assertTrue("error=$error", error is ApiError.TooManyRequests)
        assertEquals(120, (error as ApiError.TooManyRequests).retryAfterSec)
    }

    @Test
    fun serviceReturnsSameInstanceForSameType() {
        val first = client.service(ProbeApi::class.java)
        val second = client.service(ProbeApi::class.java)

        assertSame(first, second)
        // 内置接口同样走缓存，和 api 属性是同一个对象
        assertSame(client.service(MailHubApi::class.java), client.api)
    }
}
