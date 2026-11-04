package com.mailhub.client.data.accounts

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.OkResponse
import com.mailhub.client.data.model.SyncState
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.session.SessionManager

/**
 * 账户功能的薄封装，写法与 [com.mailhub.client.data.repo.MailRepository] 一致：
 * 客户端始终取自当前会话，失败时 Result 里是 [ApiError]。
 *
 * 页面层自己创建这个 Repository，不改 [com.mailhub.client.AppContainer]。
 */
class AccountsRepository(private val sessionManager: SessionManager) {

    suspend fun accounts(): Result<List<Account>> = call { it.accounts() }

    /** 新建授权码 / 密码账户，成功时返回后端给的账户 id。 */
    suspend fun createAccount(body: AccountBody): Result<AccountCreated> =
        call { it.createAccount(body) }

    suspend fun updateAccount(id: Int, body: AccountBody): Result<OkResponse> =
        call { it.updateAccount(id, body) }

    suspend fun deleteAccount(id: Int): Result<OkResponse> = call { it.deleteAccount(id) }

    suspend fun syncAccount(id: Int): Result<OkResponse> = call { it.syncAccount(id) }

    suspend fun testAccount(id: Int): Result<ConnectionTest> = call { it.testAccount(id) }

    suspend fun syncAll(): Result<OkResponse> = call { it.syncAll() }

    suspend fun syncStatus(): Result<Map<String, SyncState>> = call { it.syncStatus() }

    suspend fun providers(): Result<OAuthProvidersResponse> = call { it.providers() }

    /** 发起浏览器 OAuth；client 固定 android。 */
    suspend fun startBrowserOAuth(
        provider: String,
        email: String,
        name: String = "",
    ): Result<OAuthStartResponse> =
        call { it.oauthStart(OAuthStartBody(provider = provider, email = email, name = name)) }

    suspend fun oauthTransaction(transactionId: String): Result<OAuthTransaction> =
        call { it.oauthTransaction(transactionId) }

    /** 发起 Outlook 设备码登录。 */
    suspend fun startDeviceCode(
        provider: String,
        email: String,
        name: String = "",
    ): Result<DeviceCodeStart> =
        call { it.deviceStart(OAuthStartBody(provider = provider, email = email, name = name)) }

    suspend fun pollDeviceCode(transactionId: String): Result<DeviceCodePoll> =
        call { it.devicePoll(DevicePollBody(transactionId)) }

    /** 未登录时直接失败，与 MailRepository 的处理一致。 */
    private suspend fun <T> call(block: suspend (AccountsApi) -> T): Result<T> {
        val client = sessionManager.currentClient()
            ?: return Result.failure(ApiError.Network("尚未登录"))
        return client.callService(AccountsApi::class.java, block)
    }
}
