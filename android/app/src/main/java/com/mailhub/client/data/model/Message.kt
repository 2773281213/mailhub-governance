package com.mailhub.client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MessageSummary(
    val id: Long = 0,
    @SerialName("account_id") val accountId: Int = 0,
    val subject: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("sender_addr") val senderAddr: String = "",
    @SerialName("date_ts") val dateTs: Double = 0.0,
    val snippet: String = "",
    val category: String = "",
    @SerialName("bill_tier") val billTier: String = "",
    @SerialName("otp_code") val otpCode: String = "",
    val importance: Int = 0,
    val summary: String = "",
    val unread: Int = 0,
    @SerialName("has_attach") val hasAttach: Int = 0,
    @SerialName("account_name") val accountName: String = "",
    // 后端 messages.risk_level 是 TEXT，取值 none | medium | high
    @SerialName("risk_level") val riskLevel: String = "none",
    // 界面展示用的分类名：账单按等级展开成「重要账单」「一般账单」
    @SerialName("display_category") val displayCategory: String = "",
) {
    val isUnread: Boolean get() = unread == 1
    val hasAttachment: Boolean get() = hasAttach == 1

    /** 是否被安全扫描判定为需要提醒的邮件。 */
    val isRisky: Boolean get() = riskLevelIsRisky(riskLevel)
}

@Serializable
data class MessagePage(
    val total: Int? = null,
    val items: List<MessageSummary> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Cursor? = null,
)

/** 服务端下发的不透明游标。翻页时只把 token 传回 cursor 参数。 */
@Serializable
data class Cursor(
    @SerialName("before_ts") val beforeTs: Double = 0.0,
    @SerialName("before_id") val beforeId: Long = 0,
    val token: String = "",
)

@Serializable
data class MessageDetail(
    val id: Long = 0,
    @SerialName("account_id") val accountId: Int = 0,
    val subject: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("sender_addr") val senderAddr: String = "",
    @SerialName("to_addr") val toAddr: String = "",
    @SerialName("date_ts") val dateTs: Double = 0.0,
    val snippet: String = "",
    val category: String = "",
    @SerialName("bill_tier") val billTier: String = "",
    @SerialName("otp_code") val otpCode: String = "",
    val importance: Int = 0,
    val summary: String = "",
    val unread: Int = 0,
    @SerialName("has_attach") val hasAttach: Int = 0,
    @SerialName("account_name") val accountName: String = "",
    @SerialName("body_text") val bodyText: String = "",
    @SerialName("body_html") val bodyHtml: String = "",
    // 后端 messages.risk_level 是 TEXT，取值 none | medium | high
    @SerialName("risk_level") val riskLevel: String = "none",
    @SerialName("risk_reasons") val riskReasons: String = "",
    val confidence: Double = 0.0,
    @SerialName("ai_reason") val aiReason: String = "",
    @SerialName("governance_ministry") val governanceMinistry: String = "",
    @SerialName("governance_source") val governanceSource: String = "",
    @SerialName("governance_reason") val governanceReason: String = "",
    // 界面展示用的分类名，与列表里的 display_category 同义
    @SerialName("display_category") val displayCategory: String = "",
    // 正文过大时后端只同步开头部分，1 表示被截断
    @SerialName("body_truncated") val bodyTruncated: Int = 0,
) {
    val isUnread: Boolean get() = unread == 1
    val hasAttachment: Boolean get() = hasAttach == 1

    /** 正文字段被截断，界面要注明只同步了开头。 */
    val isBodyTruncated: Boolean get() = bodyTruncated == 1

    /** 是否被安全扫描判定为需要提醒的邮件。 */
    val isRisky: Boolean get() = riskLevelIsRisky(riskLevel)
}

@Serializable
data class BatchRequest(
    val ids: List<Long>,
    val action: String,
    val server: Boolean = false,
    val confirmed: Boolean = false,
    @SerialName("override_protection") val overrideProtection: Boolean = false,
)

@Serializable
data class BatchResult(
    val ok: Boolean = false,
    val count: Int = 0,
    val blocked: List<BlockedItem> = emptyList(),
    val errors: List<JsonElement> = emptyList(),
    @SerialName("audit_id") val auditId: Long? = null,
    val reversible: Boolean = false,
)

@Serializable
data class BlockedItem(
    val id: Long = 0,
    val reason: String = "",
    @SerialName("protected") val protected: Boolean = false,
)

@Serializable
data class OtpItem(
    val id: Long = 0,
    val subject: String = "",
    @SerialName("sender_addr") val senderAddr: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("date_ts") val dateTs: Double = 0.0,
    @SerialName("otp_code") val otpCode: String = "",
    val unread: Int = 0,
    @SerialName("account_name") val accountName: String = "",
    @SerialName("account_email") val accountEmail: String = "",
) {
    val isUnread: Boolean get() = unread == 1
}
