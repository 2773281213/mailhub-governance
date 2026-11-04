package com.mailhub.client.ui.screens.detail

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.BatchRequest
import com.mailhub.client.data.model.BatchResult
import com.mailhub.client.data.model.MessageDetail
import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.repo.MailRepository

/**
 * 详情页的数据接口：真实实现转发到 [MailRepository]，
 * 测试里注入假实现，直接断言 batch 请求里的 ids 与 override_protection。
 */
interface DetailGateway {

    /** 读一封邮件的详情；[markRead] 为真时服务端就地标已读。 */
    suspend fun message(id: Long, markRead: Boolean): Result<MessageDetail>

    /** /api/meta，取 display_categories 给分类编号。 */
    suspend fun meta(): Result<Meta>

    /** 账户列表，用于「A1」这种账户编号。 */
    suspend fun accounts(): Result<List<Account>>

    /** 批量接口，删除与标未读都走它。 */
    suspend fun batch(request: BatchRequest): Result<BatchResult>
}

/** 真实实现：全部转发到邮件仓库。 */
fun detailGatewayOf(repository: MailRepository): DetailGateway = object : DetailGateway {

    override suspend fun message(id: Long, markRead: Boolean): Result<MessageDetail> =
        repository.message(id, markRead)

    override suspend fun meta(): Result<Meta> = repository.meta()

    override suspend fun accounts(): Result<List<Account>> = repository.accounts()

    override suspend fun batch(request: BatchRequest): Result<BatchResult> =
        repository.batch(request)
}
