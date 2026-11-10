package com.mailhub.client.data.local

import com.mailhub.client.data.model.LoginRequest
import com.mailhub.client.data.model.OkResponse
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.net.ApiError
import com.mailhub.core.api.LocalRequest
import com.mailhub.core.api.LocalResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 拦截器的契约：把 Retrofit / OkHttp 的完整链路转成 [LocalRequest]，再把 [LocalResponse]
 * 照抄成 OkHttp 响应，**一次真实网络请求都不发**。
 *
 * 这里用一个可控的 [LocalRequestHandler] 代替真实 `LocalApi`：App 的单元测试跑在桌面 JVM 上，
 * `:app` 解析到的是 `androidx.sqlite` 的 **Android** 变体，打包 SQLite 的本地库加载不了
 * （`UnsatisfiedLinkError`），所以拿不到真实 `LocalApi` 的临时库。真实 `LocalApi` 的语义由
 * `:core` 的 `LocalApiTest` / 金标测试覆盖，这里只钉拦截器的转换规则。
 */
class LocalApiInterceptorTest {

    /** 记录拦截器交给 LocalApi 的每个请求，并按用例给出响应。 */
    private val seen = mutableListOf<LocalRequest>()

    private fun clientFor(handler: LocalRequestHandler, token: String? = "tok-local"): ApiClient =
        ApiClient(
            serverUrl = LOCAL_SERVER_URL.toHttpUrl(),
            token = token,
            extraInterceptors = listOf(LocalApiInterceptor(handler)),
        )

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    /** OkHttp 的异步调用遇到非 IOException 会把进程带崩，打开本机库失败就是这种情况。 */
    @Test
    fun backendFailureBecomesANetworkErrorInsteadOfCrashing() = runBlocking {
        for (failure in listOf(IllegalStateException("/data/secret/path"), UnsatisfiedLinkError("/data/secret/path"))) {
            val client = clientFor(LocalRequestHandler { throw failure })

            val error = client.call { it.meta() }.exceptionOrNull()

            assertTrue("应当是网络错误，实际 $error", error is ApiError.Network)
            assertTrue("不能带异常原文：${error?.message}", error?.message?.contains("/data/secret") != true)
        }
    }

    @Test
    fun successIsAnsweredInProcessWithoutAnyNetworkRequest() = runBlocking {
        // 保留域名 .invalid 永远解析不到服务器：这里能拿到响应，就说明请求没有出网
        val client = clientFor(
            LocalRequestHandler { request ->
                seen += request
                LocalResponse(200, mapOf("Content-Type" to "application/json"), json("""{"api_version":1,"tz":"Asia/Shanghai"}"""))
            },
        )

        val meta = client.call { it.meta() }.getOrThrow()

        assertEquals(1, meta.apiVersion)
        assertEquals(1, seen.size)
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("/api/meta", request.path)
        assertTrue("查询参数应为空：${request.query}", request.query.isEmpty())
        assertNull("GET 没有请求体", request.body)
    }

    @Test
    fun forbiddenResponseCarriesProtectedHeaderThroughToApiError() = runBlocking {
        val client = clientFor(
            LocalRequestHandler {
                LocalResponse(
                    status = 403,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-MailHub-Protected" to "1",
                    ),
                    body = json("""{"detail":"受保护邮件不能批量删除"}"""),
                )
            },
        )

        val error = client.call { it.messages() }.exceptionOrNull()

        assertTrue("error=$error", error is ApiError.Forbidden)
        error as ApiError.Forbidden
        assertEquals("受保护邮件不能批量删除", error.detail)
        assertTrue("X-MailHub-Protected 必须映射成 protected=true", error.protected)
    }

    @Test
    fun authorizationHeaderAndJsonBodyReachLocalApi() = runBlocking {
        val client = clientFor(
            LocalRequestHandler { request ->
                seen += request
                LocalResponse(200, body = json("""{"ok":true}"""))
            },
        )

        client.call { it.login(LoginRequest(password = "pw", deviceName = "Pixel 8")) }.getOrThrow()

        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/api/login", request.path)
        // AuthInterceptor 排在前面，所以本机接口看到的是已经加上令牌的请求
        assertEquals("Bearer tok-local", request.headers["Authorization"])
        val body = request.body.orEmpty()
        assertTrue("body=$body", body.contains("\"password\":\"pw\""))
        assertTrue("body=$body", body.contains("\"device_name\":\"Pixel 8\""))
    }

    @Test
    fun chinesePathSegmentAndQueryValueAreDecoded() = runBlocking {
        val client = clientFor(
            LocalRequestHandler { request ->
                seen += request
                LocalResponse(200, body = json("""{"ok":true}"""))
            },
        )

        val result = client.callService(ProbeApi::class.java) {
            it.probe(sid = "会话 一", q = "验证码 测试", limit = 7)
        }

        assertTrue("result=$result", result.isSuccess)
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("/api/sessions/会话 一", request.path)
        assertEquals("验证码 测试", request.query["q"])
        assertEquals("7", request.query["limit"])
    }

    @Test
    fun otherHostsAreRejectedInsteadOfGoingToTheNetwork() = runBlocking {
        var handled = false
        val client = ApiClient(
            serverUrl = "https://mail.example.invalid/".toHttpUrl(),
            token = "tok",
            extraInterceptors = listOf(
                LocalApiInterceptor(
                    LocalRequestHandler {
                        handled = true
                        LocalResponse(200, body = json("""{}"""))
                    },
                ),
            ),
        )

        val error = client.call { it.meta() }.exceptionOrNull()

        assertTrue("error=$error", error is ApiError.Network)
        assertTrue("错误应由拦截器抛出，而不是网络失败", error?.cause?.message?.contains("本机拦截器") == true)
        assertTrue("非本机主机不能交给 LocalApi", !handled)
    }

    /** 只用来验证 Retrofit 生成的路径与查询参数：中文路径段 + 中文查询值 + 数字查询值。 */
    private interface ProbeApi {

        @GET("api/sessions/{sid}")
        suspend fun probe(
            @Path("sid") sid: String,
            @Query("q") q: String,
            @Query("limit") limit: Int,
        ): OkResponse
    }
}
