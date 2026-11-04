package com.mailhub.client.ui.screens.otp

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.OtpItem
import com.mailhub.client.data.repo.MailRepository

/**
 * 验证码看板的数据接口：真实实现转发到 [MailRepository]，
 * 测试里注入假实现，直接断言刷新次数、条数上限和返回内容。
 */
interface OtpGateway {

    /** /api/otp：按收到时间倒序，最多 [limit] 条。 */
    suspend fun otp(limit: Int): Result<List<OtpItem>>

    /** 账户列表，用于把账户名换成 A1 / A2 这种编号。 */
    suspend fun accounts(): Result<List<Account>>
}

/** 真实实现：只是把调用转给邮件仓库。 */
fun otpGatewayOf(repository: MailRepository): OtpGateway = object : OtpGateway {

    override suspend fun otp(limit: Int): Result<List<OtpItem>> = repository.otp(limit = limit)

    override suspend fun accounts(): Result<List<Account>> = repository.accounts()
}
