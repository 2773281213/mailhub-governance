package com.mailhub.client.ui.screens.inbox

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.BatchRequest
import com.mailhub.client.data.model.BatchResult
import com.mailhub.client.data.model.MessagePage
import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.repo.MailRepository

/**
 * 收件箱的数据接口：真实实现转发到 [MailRepository]，
 * 测试里注入假实现，直接断言发出的筛选条件、游标和批量操作参数。
 */
interface InboxGateway {

    /** 读一页邮件；[cursor] 为 null 表示第一页。 */
    suspend fun messages(filters: InboxFilters, cursor: String?): Result<MessagePage>

    /** /api/meta，取 display_categories 给分类编号。 */
    suspend fun meta(): Result<Meta>

    /** 账户列表，用于 A1 A2 编号与账户筛选。 */
    suspend fun accounts(): Result<List<Account>>

    /** 批量操作（已读 / 未读 / 删除）。删除只走本地软删除，不传 server。 */
    suspend fun batch(request: BatchRequest): Result<BatchResult>
}

/** 真实实现：把筛选条件翻译成 /api/messages 的查询参数。 */
fun inboxGatewayOf(repository: MailRepository): InboxGateway = object : InboxGateway {

    override suspend fun messages(filters: InboxFilters, cursor: String?): Result<MessagePage> {
        val (category, billTier) = categoryParams(filters.category)
        return repository.messages(
            category = category,
            account = filters.accountId.takeIf { it > 0 },
            unread = if (filters.unreadOnly) 1 else null,
            query = filters.query.trim().takeIf { it.isNotEmpty() },
            billTier = billTier,
            pageSize = InboxPageSize,
            cursor = cursor,
        )
    }

    override suspend fun meta(): Result<Meta> = repository.meta()

    override suspend fun accounts(): Result<List<Account>> = repository.accounts()

    override suspend fun batch(request: BatchRequest): Result<BatchResult> =
        repository.batch(request)
}
