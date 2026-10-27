package com.mailhub.client.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val password: String,
    @SerialName("client") val client: String = "android",
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class LoginResponse(
    val ok: Boolean = false,
    val token: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("expires_ts") val expiresTs: Double = 0.0,
)

@Serializable
data class OkResponse(val ok: Boolean = false)
