package com.mailhub.client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GovernanceRunRequest(val days: Int = 0)

@Serializable
data class GovernanceStatus(
    val version: Int = 0,
    val running: Boolean = false,
    val pending: Int = 0,
    val ministries: List<String> = emptyList(),
    @SerialName("last_run") val lastRun: GovernanceRun? = null,
)

@Serializable
data class GovernanceRun(
    val status: String = "",
    @SerialName("started_ts") val startedTs: Double = 0.0,
    @SerialName("finished_ts") val finishedTs: Double = 0.0,
    @SerialName("processed_count") val processedCount: Int = 0,
    @SerialName("ai_count") val aiCount: Int = 0,
    @SerialName("fallback_count") val fallbackCount: Int = 0,
    @SerialName("suspicious_count") val suspiciousCount: Int = 0,
    @SerialName("ministry_counts") val ministryCounts: Map<String, Int> = emptyMap(),
    @SerialName("category_counts") val categoryCounts: Map<String, Int> = emptyMap(),
)
