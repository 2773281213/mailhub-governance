package com.mailhub.client.data.settings

import com.mailhub.client.data.model.LoginResponse
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.session.SessionManager

/**
 * 设置功能的薄封装，写法与 [com.mailhub.client.data.accounts.AccountsRepository] 一致：
 * 客户端始终取自当前会话，失败时 Result 里是 [ApiError]。
 *
 * 页面层自己创建这个 Repository，不改 [com.mailhub.client.AppContainer]。
 */
class SettingsRepository(private val sessionManager: SessionManager) {

    suspend fun settings(): Result<SettingsDto> = call { it.settings() }

    /** 保存一批改动；改密码时返回值里带新令牌，由调用方交给 [replaceToken]。 */
    suspend fun update(body: SettingsUpdate): Result<LoginResponse> = call { it.update(body) }

    suspend fun testPush(): Result<PushTestResult> = call { it.testPush(PushTestBody()) }

    /** 改密码成功后原地换上新令牌；当前不在登录态时返回 false。令牌不写日志。 */
    suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean =
        sessionManager.replaceToken(token, sessionId, expiresTs)

    private suspend fun <T> call(block: suspend (SettingsApi) -> T): Result<T> {
        val client = sessionManager.currentClient()
            ?: return Result.failure(ApiError.Network("尚未登录"))
        return client.callService(SettingsApi::class.java, block)
    }
}
