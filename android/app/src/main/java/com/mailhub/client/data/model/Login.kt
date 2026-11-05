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
) {
    /** 令牌不进日志：data class 默认的 toString 会把 token 打出来，这里换成 ***。 */
    override fun toString(): String =
        "LoginResponse(ok=$ok, token=${maskedToken(token)}, sessionId=$sessionId, expiresTs=$expiresTs)"
}

/** 令牌只显示 ***；没有令牌时显示空串。 */
private fun maskedToken(token: String): String = if (token.isEmpty()) "" else "***"

@Serializable
data class OkResponse(val ok: Boolean = false)
