package com.mailhub.client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Overview(
    val stats: OverviewStats = OverviewStats(),
    val days: List<String> = emptyList(),
    // 每天只包含当天有邮件的分类，键不固定（例如「重要账单」）
    val daily: List<Map<String, JsonElement>> = emptyList(),
    val categories: List<String> = emptyList(),
    val important: List<ImportantItem> = emptyList(),
    val digest: Digest? = null,
    @SerialName("ai_enabled") val aiEnabled: Boolean = false,
)

@Serializable
data class OverviewStats(
    val total: Int = 0,
    val unread: Int = 0,
    val today: Int = 0,
    @SerialName("today_otp") val todayOtp: Int = 0,
    @SerialName("important_unread") val importantUnread: Int = 0,
    @SerialName("by_category") val byCategory: Map<String, Int> = emptyMap(),
)

@Serializable
data class ImportantItem(
    val id: Long = 0,
    val subject: String = "",
    @SerialName("sender_addr") val senderAddr: String = "",
    @SerialName("date_ts") val dateTs: Double = 0.0,
    val category: String = "",
    @SerialName("bill_tier") val billTier: String = "",
    val importance: Int = 0,
    val summary: String = "",
    val unread: Int = 0,
    @SerialName("account_name") val accountName: String = "",
) {
    val isUnread: Boolean get() = unread == 1
}

@Serializable
data class Digest(
    val day: String = "",
    val content: String = "",
    @SerialName("created_ts") val createdTs: Double = 0.0,
)
