package com.mailhub.client.data.repo

import com.mailhub.client.data.model.LoginRequest
import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.net.ServerUrl
import com.mailhub.client.data.session.Session
import com.mailhub.client.data.session.SessionStore
import okhttp3.HttpUrl

/**
 * 登录流程：校验地址 → 请求登录 → 保存会话 → 请求 api/meta 校验服务端版本。
 * 退出登录先调接口（失败也继续），再清本地会话。
 */
class AuthRepository(
    private val sessionStore: SessionStore,
    private val clientFactory: (HttpUrl) -> ApiClient,
    private val clientProvider: () -> ApiClient?,
    private val onClientReady: (ApiClient) -> Unit,
) {

    suspend fun login(serverUrl: String, password: String, deviceName: String): Result<Meta> {
        val url = ServerUrl.parse(serverUrl).getOrElse { error ->
            return Result.failure(ApiError.Network("服务器地址不正确：${error.message}"))
        }
        val client = clientFactory(url)
        val response = client.call {
            it.login(LoginRequest(password = password, deviceName = deviceName))
        }.getOrElse { return Result.failure(it) }

        if (!response.ok || response.token.isBlank()) {
            return Result.failure(ApiError.Unauthorized("登录失败"))
        }
        sessionStore.save(
            Session(
                serverUrl = url.toString(),
                token = response.token,
                sessionId = response.sessionId,
                expiresTs = response.expiresTs,
                deviceName = deviceName,
            ),
        )
        onClientReady(client)

        val meta = client.call { it.meta() }.getOrElse {
            sessionStore.clear()
            return Result.failure(it)
        }
        if (meta.apiVersion < 1) {
            sessionStore.clear()
            return Result.failure(ApiError.Server(0, "服务器版本过旧，请先升级 MailHub 服务端"))
        }
        return Result.success(meta)
    }

    suspend fun logout() {
        runCatching { clientProvider()?.api?.logout() }
        sessionStore.clear()
    }
}
