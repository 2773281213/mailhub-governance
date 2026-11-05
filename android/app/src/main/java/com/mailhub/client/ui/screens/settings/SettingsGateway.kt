package com.mailhub.client.ui.screens.settings

import com.mailhub.client.data.model.LoginResponse
import com.mailhub.client.data.settings.PushTestResult
import com.mailhub.client.data.settings.SettingsDto
import com.mailhub.client.data.settings.SettingsRepository
import com.mailhub.client.data.settings.SettingsUpdate

/**
 * 设置页的数据接口：真实实现转发到 [SettingsRepository]，
 * 测试里注入假实现，直接断言提交了哪些字段、是否换了令牌。
 */
interface SettingsGateway {

    suspend fun load(): Result<SettingsDto>

    /** 提交一批改动；改密码时返回值里带新令牌。 */
    suspend fun save(update: SettingsUpdate): Result<LoginResponse>

    /** 测试推送：发送失败时返回 ok=false 的结论，不是异常。 */
    suspend fun testPush(): Result<PushTestResult>

    /** 改密码成功后原地换上新令牌；返回 false 表示当前已不在登录态。 */
    suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean
}

fun settingsGatewayOf(repository: SettingsRepository): SettingsGateway = object : SettingsGateway {

    override suspend fun load(): Result<SettingsDto> = repository.settings()

    override suspend fun save(update: SettingsUpdate): Result<LoginResponse> =
        repository.update(update)

    override suspend fun testPush(): Result<PushTestResult> = repository.testPush()

    override suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean =
        repository.replaceToken(token, sessionId, expiresTs)
}
