package com.mailhub.client.data.repo

import com.mailhub.client.data.model.LoginRequest
import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.net.ServerUrl
import com.mailhub.client.data.session.Session
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionState
import kotlinx.coroutines.CancellationException

/**
 * 登录与退出。
 *
 * 登录顺序：不带令牌登录 → 用新令牌读 api/meta 校验服务端版本 → 校验通过才保存会话。
 * 校验失败时尽力用新令牌吊销服务端会话，本地不保存、不改变当前状态。
 *
 * 退出顺序：调用 /api/logout（401 视为已经退出）→ 清本地存储 → LoggedOut(UserLogout)。
 */
class AuthRepository(private val sessionManager: SessionManager) {

    suspend fun login(serverUrl: String, password: String, deviceName: String): Result<Meta> {
        val url = ServerUrl.parse(serverUrl).getOrElse { error ->
            return Result.failure(ApiError.Network("服务器地址不正确：${error.message}"))
        }

        // 1. 登录请求不带任何旧令牌，避免切换服务器时把旧服务器的令牌发出去
        val anonymous = sessionManager.newClient(url, null)
        val response = anonymous.call {
            it.login(LoginRequest(password = password, deviceName = deviceName))
        }.getOrElse { return Result.failure(it) }
        if (!response.ok || response.token.isBlank()) {
            return Result.failure(ApiError.Unauthorized("登录失败"))
        }

        // 2. 用新令牌校验服务端能力，通过之后才认为登录成功
        val session = Session(
            serverUrl = url.toString(),
            token = response.token,
            sessionId = response.sessionId,
            expiresTs = response.expiresTs,
            deviceName = deviceName,
        )
        val client = sessionManager.newClient(url, response.token)
        val meta = client.call { it.meta() }.getOrElse { error ->
            revokeQuietly(client)
            return Result.failure(error)
        }
        if (meta.apiVersion < 1) {
            revokeQuietly(client)
            return Result.failure(ApiError.Server(0, "服务器版本过旧，请先升级 MailHub 服务端"))
        }

        // 3. 校验通过：保存会话并切换状态
        sessionManager.activate(session, client)
        return Result.success(meta)
    }

    suspend fun logout() {
        val current = sessionManager.state.value as? SessionState.LoggedIn
        if (current != null) {
            try {
                current.client.api.logout()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 401 说明服务端已经没有这个会话，其他失败也不影响本地退出
            }
        }
        sessionManager.clearForLogout()
    }

    /** 吊销刚拿到但没通过校验的令牌；失败直接忽略。 */
    private suspend fun revokeQuietly(client: ApiClient) {
        try {
            client.api.logout()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 吊销失败不影响登录结果，服务端会话会自然过期
        }
    }
}
