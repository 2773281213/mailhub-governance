package com.mailhub.client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Meta(
    @SerialName("api_version") val apiVersion: Int = 0,
    @SerialName("server_time") val serverTime: Double = 0.0,
    val tz: String = "",
    val categories: List<String> = emptyList(),
    @SerialName("display_categories") val displayCategories: List<String> = emptyList(),
    @SerialName("bill_tiers") val billTiers: List<String> = emptyList(),
    val ministries: List<String> = emptyList(),
    @SerialName("governance_version") val governanceVersion: Int = 0,
    val features: Map<String, Boolean> = emptyMap(),
)
