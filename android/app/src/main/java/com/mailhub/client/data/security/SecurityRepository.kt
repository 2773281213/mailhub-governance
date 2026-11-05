package com.mailhub.client.data.security

import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.callService

/** 风险观察取多少封，与任务约定一致。 */
const val ReviewLimit: Int = 50

/** 回收站取多少封。 */
const val TrashLimit: Int = 100

/** 审计每页多少条。 */
const val AuditPageSize: Int = 50

/**
 * 安全中心的薄封装：客户端始终取自当前会话，失败时 Result 里是 [ApiError]。
 * 页面层自己创建这个 Repository，不改 [com.mailhub.client.AppContainer]。
 */
class SecurityRepository(private val sessionManager: SessionManager) {

    suspend fun review(): Result<List<ReviewItem>> = call { it.review(ReviewLimit) }

    suspend fun trash(): Result<List<TrashItem>> = call { it.trash(TrashLimit) }

    suspend fun restore(ids: List<Long>): Result<RestoreResult> = call { it.restore(RestoreRequest(ids)) }

    suspend fun audit(offset: Int): Result<AuditPage> = call { it.audit(AuditPageSize, offset) }

    suspend fun undo(auditId: Long): Result<UndoResult> = call { it.undo(auditId) }

    suspend fun sessions(): Result<List<DeviceSession>> = call { it.sessions().items }

    suspend fun revoke(sessionId: String): Result<RevokeResult> = call { it.revoke(sessionId) }

    suspend fun revokeAll(): Result<RevokeAllResult> = call { it.revokeAll() }

    /** 退出其他所有设备后原地换上新令牌；当前不在登录态时返回 false。令牌不写日志。 */
    suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean =
        sessionManager.replaceToken(token, sessionId, expiresTs)

    private suspend fun <T> call(block: suspend (SecurityApi) -> T): Result<T> =
        sessionManager.callService(SecurityApi::class.java, block)
}
