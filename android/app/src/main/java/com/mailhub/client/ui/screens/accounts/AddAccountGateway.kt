package com.mailhub.client.ui.screens.accounts

import com.mailhub.client.data.accounts.AccountBody
import com.mailhub.client.data.accounts.AccountsRepository
import com.mailhub.client.data.accounts.DeviceCodePoll
import com.mailhub.client.data.accounts.DeviceCodeStart
import com.mailhub.client.data.accounts.OAuthStartResponse
import com.mailhub.client.data.accounts.OAuthTransaction
import com.mailhub.client.data.accounts.ProviderInfo
import com.mailhub.client.data.model.Account

/**
 * 添加账户的数据接口：真实实现转发到 [AccountsRepository]，
 * 测试里注入假实现，可以按顺序摆出「等待中 → 完成 / 失败 / 过期」的事务状态。
 */
interface AddAccountGateway {

    suspend fun providers(): Result<Map<String, ProviderInfo>>

    /** 授权码 / 密码方式：后端会先探测 IMAP，成功时返回新账户 id。 */
    suspend fun addAccount(body: AccountBody): Result<Int>

    /** 添加成功后重新拉一次列表，用来把新账户算成 A? 编号。 */
    suspend fun accounts(): Result<List<Account>>

    suspend fun startBrowserOAuth(provider: String, email: String): Result<OAuthStartResponse>

    suspend fun oauthTransaction(transactionId: String): Result<OAuthTransaction>

    suspend fun startDeviceCode(provider: String, email: String): Result<DeviceCodeStart>

    suspend fun pollDeviceCode(transactionId: String): Result<DeviceCodePoll>
}

/** 真实实现：把 Repository 的 DTO 收敛成页面真正关心的字段。 */
fun addAccountGatewayOf(repository: AccountsRepository): AddAccountGateway =
    object : AddAccountGateway {

        override suspend fun providers(): Result<Map<String, ProviderInfo>> =
            repository.providers().map { it.providers }

        override suspend fun addAccount(body: AccountBody): Result<Int> =
            repository.createAccount(body).map { it.id }

        override suspend fun accounts(): Result<List<Account>> = repository.accounts()

        override suspend fun startBrowserOAuth(
            provider: String,
            email: String,
        ): Result<OAuthStartResponse> = repository.startBrowserOAuth(provider, email)

        override suspend fun oauthTransaction(transactionId: String): Result<OAuthTransaction> =
            repository.oauthTransaction(transactionId)

        override suspend fun startDeviceCode(
            provider: String,
            email: String,
        ): Result<DeviceCodeStart> = repository.startDeviceCode(provider, email)

        override suspend fun pollDeviceCode(transactionId: String): Result<DeviceCodePoll> =
            repository.pollDeviceCode(transactionId)
    }
