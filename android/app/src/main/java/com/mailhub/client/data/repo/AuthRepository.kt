package com.mailhub.client.data.repo

import com.mailhub.client.data.local.LOCAL_SERVER_URL
import com.mailhub.client.data.model.LoginRequest
import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.net.ServerUrl
import com.mailhub.client.data.session.Session
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionState
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * 登录与退出。
 *
 * 登录顺序：不带令牌登录 → 用新令牌读 api/meta 校验服务端版本 → 校验通过才保存会话。
 * 校验失败时尽力用新令牌吊销服务端会话，本地不保存、不改变当前状态。
 *
 * 退出顺序：调用 /api/logout（401 视为已经退出）→ 清本地存储 → LoggedOut(UserLogout)。
 *
 * 本机模式走 [enterLocalMode]：不登录、不发请求、不写「上次服务器」，见 `LOCAL_MODE.md` 3.1。
 *
 * @param prepareLocal 进入本机模式前的本机侧准备（打开本机库、把通知游标设成当前 `max(id)`）。
 *   在 IO 线程上调用，**必须**在会话激活之前完成；服务器模式不会调用它，默认什么都不做。
 */
class AuthRepository(
    private val sessionManager: SessionManager,
    private val prepareLocal: suspend () -> Unit = {},
) {

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

    /**
     * 进入本机模式，见 `LOCAL_MODE.md` 3.1。
     *
     * - 生成一个新的随机令牌（32 字节十六进制），会话永不过期（`expiresTs = 0.0`）；
     * - 不调用 `/api/login`，也不写登录页的「上次服务器」偏好（那是服务器登录成功后写的，
     *   本机模式不该把保留地址预填进「连接服务器」表单）；
     * - 先把通知游标设成本机库当前的 `max(id)`（空库为 0），**再**激活会话：反过来会让
     *   首次同步导入的邮件被当成历史基线吞掉（`LOCAL_MODE.md` 第五节）。
     *
     * 本机库的准备放在 IO 线程上；准备失败时不改变当前会话，返回失败。
     */
    suspend fun enterLocalMode(deviceName: String): Result<Unit> {
        val token = newLocalToken()
        val session = Session(
            serverUrl = LOCAL_SERVER_URL,
            token = token,
            sessionId = LOCAL_SESSION_ID,
            expiresTs = 0.0,
            deviceName = deviceName,
        )
        return try {
            withContext(Dispatchers.IO) { prepareLocal() }
            // 保留地址是常量，解析失败只可能是代码被改坏，交给调用方当失败处理
            val url = LOCAL_SERVER_URL.toHttpUrl()
            sessionManager.activate(session, sessionManager.newClient(url, token))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(ApiError.Server(0, "本机模式初始化失败：${e.message ?: e::class.java.simpleName}"))
        }
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

    /**
     * 生成 32 字节随机令牌的十六进制串（64 个字符）。
     *
     * 本机会话的令牌不会被任何人校验（`LocalApi` 不校验令牌、从不返回 401），它的作用只是让
     * 会话存储里有一个不可预测的值，与服务器模式下「令牌不落日志」的约定保持一致。
     */
    private fun newLocalToken(): String {
        val bytes = ByteArray(LOCAL_TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        val builder = StringBuilder(LOCAL_TOKEN_BYTES * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4])
            builder.append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    private companion object {
        /** 本机会话的令牌长度（字节）：32 字节 = 256 位随机数。 */
        const val LOCAL_TOKEN_BYTES = 32

        /** 本机会话的 session_id：与服务器会话区分开，只在本机有意义。 */
        const val LOCAL_SESSION_ID = "local"

        /** 小写十六进制数字表；不用 `String.format`，避免受默认区域设置影响。 */
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
