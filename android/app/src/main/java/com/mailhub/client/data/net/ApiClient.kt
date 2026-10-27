package com.mailhub.client.data.net

import com.mailhub.client.data.session.SessionStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
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

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    // client、device_name 这类默认值也要编码进请求体
    encodeDefaults = true
}

/**
 * 一台服务器的 Retrofit 客户端。登录拿到令牌后由 [AuthInterceptor] 自动带上
 * Authorization；普通接口 401 时清空会话并发出 [sessionExpired]。
 */
class ApiClient(
    serverUrl: HttpUrl,
    private val sessionStore: SessionStore,
    private val sessionExpiredFlow: MutableSharedFlow<Unit>,
) {
    private val baseUrl = serverUrl.toString().trimEnd('/') + "/"

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(sessionStore, sessionExpiredFlow))
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttp)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()

    val api: MailHubApi = retrofit.create(MailHubApi::class.java)

    val sessionExpired: SharedFlow<Unit> = sessionExpiredFlow

    suspend fun <T> call(block: suspend (MailHubApi) -> T): Result<T> = try {
        Result.success(block(api))
    } catch (e: HttpException) {
        Result.failure(e.toApiError())
    } catch (e: IOException) {
        Result.failure(ApiError.Network(e.message ?: "网络错误", e))
    }
}

private class AuthInterceptor(
    private val sessionStore: SessionStore,
    private val sessionExpired: MutableSharedFlow<Unit>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = sessionStore.cached?.token
        val request = if (token != null) {
            original.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            original
        }
        val response = chain.proceed(request)
        // 登录接口的 401 是密码错误，不是会话过期，不能清会话
        if (response.code == 401 && !original.url.isLoginEndpoint()) {
            runBlocking { sessionStore.clear() }
            sessionExpired.tryEmit(Unit)
        }
        return response
    }
}

private fun HttpUrl.isLoginEndpoint(): Boolean =
    encodedPath.trimEnd('/').endsWith("/api/login")

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
