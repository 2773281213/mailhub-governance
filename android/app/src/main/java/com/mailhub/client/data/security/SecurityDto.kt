package com.mailhub.client.data.security

import com.mailhub.client.data.model.IntAsBooleanSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/review 的一行：风险观察（分类为「可疑」或 risk_level 为 medium / high 的邮件）。
 * 字段按 app.py `review_queue` 的 SELECT 核对，只取页面用得到的。
 */
@Serializable
data class ReviewItem(
    val id: Long = 0,
    val subject: String = "",
    @SerialName("sender_addr") val senderAddr: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("date_ts") val dateTs: Double = 0.0,
    val category: String = "",
    @SerialName("ai_reason") val aiReason: String = "",
    // 后端 messages.risk_level 是 TEXT，取值 none | medium | high
    @SerialName("risk_level") val riskLevel: String = "none",
    @SerialName("risk_reasons") val riskReasons: String = "",
    @SerialName("account_name") val accountName: String = "",
)

/** GET /api/trash 的一行。deleted_ts 是移入回收站的时间。 */
@Serializable
data class TrashItem(
    val id: Long = 0,
    val subject: String = "",
    @SerialName("sender_addr") val senderAddr: String = "",
    @SerialName("date_ts") val dateTs: Double = 0.0,
    val category: String = "",
    @SerialName("deleted_ts") val deletedTs: Double = 0.0,
    @SerialName("account_name") val accountName: String = "",
)

/** POST /api/trash/restore 的请求体。后端一次最多处理 500 个。 */
@Serializable
data class RestoreRequest(val ids: List<Long>)

/** POST /api/trash/restore 的返回：count 是实际恢复的封数。 */
@Serializable
data class RestoreResult(
    val ok: Boolean = false,
    val count: Int = 0,
)

/** GET /api/audit 的返回。 */
@Serializable
data class AuditPage(
    val total: Int = 0,
    val items: List<AuditEntry> = emptyList(),
)

/** 一条审计记录；allowed / reversible / undone 是 SQLite INTEGER 0/1。 */
@Serializable
data class AuditEntry(
    val id: Long = 0,
    val ts: Double = 0.0,
    val actor: String = "",
    val action: String = "",
    /** 动作等级 A / B / C，旧记录可能为空。 */
    val tier: String = "",
    @SerialName("target_count") val targetCount: Int = 0,
    val reason: String = "",
    /** false 表示被策略引擎拦截，没有执行。 */
    @Serializable(with = IntAsBooleanSerializer::class) val allowed: Boolean = true,
    @Serializable(with = IntAsBooleanSerializer::class) val reversible: Boolean = false,
    @Serializable(with = IntAsBooleanSerializer::class) val undone: Boolean = false,
)

/** POST /api/audit/{id}/undo 的返回。 */
@Serializable
data class UndoResult(
    val ok: Boolean = false,
    val restored: Int = 0,
)

/** GET /api/sessions 的返回。 */
@Serializable
data class SessionsPage(
    val items: List<DeviceSession> = emptyList(),
)

/** 一个已登录的设备。id 是会话哈希的 16 位前缀，不是令牌。 */
@Serializable
data class DeviceSession(
    val id: String = "",
    val label: String = "",
    /** android / web */
    val client: String = "web",
    @SerialName("created_ts") val createdTs: Double = 0.0,
    @SerialName("last_seen_ts") val lastSeenTs: Double = 0.0,
    @SerialName("expires_ts") val expiresTs: Double = 0.0,
    /** 发出这个请求的就是它，也就是本机。 */
    val current: Boolean = false,
)

/** DELETE /api/sessions/{id} 的返回。 */
@Serializable
data class RevokeResult(
    val ok: Boolean = false,
    val revoked: Int = 0,
)

/**
 * POST /api/sessions/revoke-all 的返回。revoked 包含本机原来的会话；
 * Bearer 会话会拿到新令牌，旧令牌随即失效。
 */
@Serializable
data class RevokeAllResult(
    val ok: Boolean = false,
    val revoked: Int = 0,
    val token: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("expires_ts") val expiresTs: Double = 0.0,
) {
    /** 令牌不进 toString，避免被日志或崩溃报告带出去。 */
    override fun toString(): String =
        "RevokeAllResult(ok=$ok, revoked=$revoked, token=${if (token.isEmpty()) "" else "***"}, " +
            "sessionId=$sessionId, expiresTs=$expiresTs)"
}
