package com.mailhub.client.data.repo

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.BatchRequest
import com.mailhub.client.data.model.BatchResult
import com.mailhub.client.data.model.GovernanceRunRequest
import com.mailhub.client.data.model.GovernanceStatus
import com.mailhub.client.data.model.MessageDetail
import com.mailhub.client.data.model.MessagePage
import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.model.OkResponse
import com.mailhub.client.data.model.OtpItem
import com.mailhub.client.data.model.Overview
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.net.MailHubApi

/** 各接口的薄封装，失败时 Result 里是 [ApiError]。 */
class MailRepository(private val clientProvider: () -> ApiClient?) {

    suspend fun meta(): Result<Meta> = call { it.meta() }

    suspend fun overview(): Result<Overview> = call { it.overview() }

    suspend fun governanceStatus(): Result<GovernanceStatus> = call { it.governanceStatus() }

    suspend fun runGovernance(days: Int = 0): Result<GovernanceStatus> =
        call { it.governanceRun(GovernanceRunRequest(days)) }

    suspend fun accounts(): Result<List<Account>> = call { it.accounts() }

    suspend fun syncAll(): Result<OkResponse> = call { it.syncAll() }

    suspend fun messages(
        category: String? = null,
        account: Int? = null,
        unread: Int? = null,
        query: String? = null,
        billTier: String? = null,
        pageSize: Int? = null,
        cursor: String? = null,
    ): Result<MessagePage> = call {
        it.messages(
            category = category,
            account = account,
            unread = unread,
            q = query,
            billTier = billTier,
            pageSize = pageSize,
            cursor = cursor,
        )
    }

    suspend fun message(id: Long, markRead: Boolean = false): Result<MessageDetail> =
        call { it.message(id, markRead) }

    suspend fun batch(request: BatchRequest): Result<BatchResult> = call { it.batch(request) }

    suspend fun otp(limit: Int? = null): Result<List<OtpItem>> = call { it.otp(limit) }

    private suspend fun <T> call(block: suspend (MailHubApi) -> T): Result<T> {
        val client = clientProvider() ?: return Result.failure(ApiError.Network("尚未登录"))
        return client.call(block)
    }
}
