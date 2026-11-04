package com.mailhub.client.data.net

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val HTTP_UNAUTHORIZED = 401

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    // client、device_name 这类默认值也要编码进请求体
    encodeDefaults = true
    // 后端偶发返回未加引号的字符串时也能解码
    isLenient = true
}

/**
 * 一台服务器 + 一个令牌的 Retrofit 客户端。
 *
 * 令牌在构造时绑定且不可变：未登录用的客户端传 null，切换服务器后旧客户端
 * 仍然只带自己的令牌，不会把 A 服务器的令牌发给 B。
 *
 * 普通接口 401 时只回调 [onUnauthorized]，由会话管理方决定这个客户端是否
 * 仍是当前客户端；拦截器里不读写会话存储，也不阻塞线程。
 */
class ApiClient(
    val serverUrl: HttpUrl,
    private val token: String? = null,
    private val onUnauthorized: (ApiClient) -> Unit = {},
) {
    private val baseUrl = serverUrl.toString().trimEnd('/') + "/"

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(serverUrl, token, onUnauthorized) { this })
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    val api: MailHubApi by lazy { retrofit.create(MailHubApi::class.java) }

    /** 所有接口统一走这里：失败时 Result 里是 [ApiError]，不会把解码异常抛给调用方。 */
    suspend fun <T> call(block: suspend (MailHubApi) -> T): Result<T> = try {
        Result.success(block(api))
    } catch (e: HttpException) {
        Result.failure(e.toApiError())
    } catch (e: IOException) {
        Result.failure(ApiError.Network(e.message ?: "网络错误", e))
    } catch (e: SerializationException) {
        // 后端字段类型与 DTO 不一致（或返回了非法 JSON）
        Result.failure(ApiError.Decode("服务器返回的数据格式不正确", e))
    } catch (e: IllegalArgumentException) {
        // kotlinx 的部分解码错误包装成 IllegalArgumentException 抛出
        Result.failure(ApiError.Decode("服务器返回的数据格式不正确", e))
    }
}

/**
 * 只在请求与客户端绑定的服务器同源时附加 Bearer 令牌。
 * 401 且不是登录/退出接口时回调 [onUnauthorized]，不在这里改动会话。
 */
internal class AuthInterceptor(
    private val serverUrl: HttpUrl,
    private val token: String?,
    private val onUnauthorized: (ApiClient) -> Unit,
    private val client: () -> ApiClient,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val sameOrigin = original.url.isSameOriginAs(serverUrl)
        val request = if (token != null && sameOrigin) {
            original.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            original
        }
        val response = chain.proceed(request)
        // 登录、退出接口的 401 是业务错误，不是会话过期；跨源响应也不影响本会话
        if (response.code == HTTP_UNAUTHORIZED && sameOrigin && !original.url.isAuthEndpoint()) {
            onUnauthorized(client())
        }
        return response
    }
}

/** scheme、host、port 三者完全一致才算同源（scheme 与 host 不区分大小写）。 */
internal fun HttpUrl.isSameOriginAs(other: HttpUrl): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        port == other.port

/** 登录与退出接口：这里的 401 是业务错误，不能当成会话过期。 */
internal fun HttpUrl.isAuthEndpoint(): Boolean {
    val path = encodedPath.trimEnd('/')
    return path.endsWith("/api/login") || path.endsWith("/api/logout")
}

private fun HttpException.toApiError(): ApiError {
    val detail = runCatching {
        val body = response()?.errorBody()?.string()
        if (body.isNullOrBlank()) {
            null
        } else {
            ApiJson.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()
    val response = response()
    return when (code()) {
        401 -> ApiError.Unauthorized(detail)
        403 -> ApiError.Forbidden(detail, response?.headers()?.get("X-MailHub-Protected") == "1")
        409 -> ApiError.Conflict(detail)
        429 -> ApiError.TooManyRequests(response?.headers()?.get("Retry-After")?.toIntOrNull() ?: 0)
        else -> ApiError.Server(code(), detail)
    }
}
