package com.mailhub.client.ui.screens.security

import com.mailhub.client.data.security.AuditPage
import com.mailhub.client.data.security.DeviceSession
import com.mailhub.client.data.security.RestoreResult
import com.mailhub.client.data.security.ReviewItem
import com.mailhub.client.data.security.RevokeAllResult
import com.mailhub.client.data.security.SecurityRepository
import com.mailhub.client.data.security.TrashItem
import com.mailhub.client.data.security.UndoResult

/**
 * 安全中心的数据接口：真实实现转发到 [SecurityRepository]，
 * 测试里注入假实现，直接断言发出的 ids、是否换了令牌。
 */
interface SecurityGateway {

    suspend fun review(): Result<List<ReviewItem>>

    suspend fun trash(): Result<List<TrashItem>>

    suspend fun restore(ids: List<Long>): Result<RestoreResult>

    suspend fun audit(offset: Int): Result<AuditPage>

    suspend fun undo(auditId: Long): Result<UndoResult>

    suspend fun devices(): Result<List<DeviceSession>>

    suspend fun revoke(sessionId: String): Result<Unit>

    suspend fun revokeAll(): Result<RevokeAllResult>

    /** 退出其他所有设备后原地换上新令牌；返回 false 表示当前已不在登录态。 */
    suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean
}

fun securityGatewayOf(repository: SecurityRepository): SecurityGateway = object : SecurityGateway {

    override suspend fun review(): Result<List<ReviewItem>> = repository.review()

    override suspend fun trash(): Result<List<TrashItem>> = repository.trash()

    override suspend fun restore(ids: List<Long>): Result<RestoreResult> = repository.restore(ids)

    override suspend fun audit(offset: Int): Result<AuditPage> = repository.audit(offset)

    override suspend fun undo(auditId: Long): Result<UndoResult> = repository.undo(auditId)

    override suspend fun devices(): Result<List<DeviceSession>> = repository.sessions()

    override suspend fun revoke(sessionId: String): Result<Unit> = repository.revoke(sessionId).map { }

    override suspend fun revokeAll(): Result<RevokeAllResult> = repository.revokeAll()

    override suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean =
        repository.replaceToken(token, sessionId, expiresTs)
}
