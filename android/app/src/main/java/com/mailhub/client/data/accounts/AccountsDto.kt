package com.mailhub.client.data.accounts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 发起 OAuth 时固定传的客户端标识：后端据此在回调页给出 mailhub:// 返回链接。 */
const val AndroidOAuthClient: String = "android"

/** 认证方式的类型，与 providers.py 里的常量一致。 */
const val MethodOAuthBrowser: String = "oauth_browser"
const val MethodOAuthDevice: String = "oauth_device"
const val MethodAppPassword: String = "app_password"
const val MethodPassword: String = "password"

/**
 * POST /api/accounts 与 PUT /api/accounts/{id} 的请求体，
 * 字段与 app.py 的 AccountBody 一一对应。
 */
@Serializable
data class AccountBody(
    val name: String = "",
    val provider: String = "auto",
    val email: String,
    /** 授权码 / 应用专用密码；OAuth 账户不用。 */
    val secret: String = "",
    @SerialName("imap_host") val imapHost: String = "",
    @SerialName("imap_port") val imapPort: Int = 993,
    @SerialName("poll_interval") val pollInterval: Int = 300,
    val color: String = "#38bdf8",
    val enabled: Boolean = true,
)

/** POST /api/accounts 的返回：{ok, id}。 */
@Serializable
data class AccountCreated(
    val ok: Boolean = false,
    val id: Int = 0,
)

/** POST /api/accounts/{id}/test 的返回：成功 {ok:true,msg:"连接正常"}，失败也是 200 但 ok=false。 */
@Serializable
data class ConnectionTest(
    val ok: Boolean = false,
    val msg: String = "",
)

/** GET /api/oauth/providers 的返回：{providers: {服务商键: 服务商信息}}。 */
@Serializable
data class OAuthProvidersResponse(
    val providers: Map<String, ProviderInfo> = emptyMap(),
)

/**
 * 服务商的公开信息，对应 oauth_auth.public_providers()。
 * 键顺序即后端 PROVIDERS 的顺序，域名识别按这个顺序取第一个命中的服务商。
 */
@Serializable
data class ProviderInfo(
    val label: String = "",
    val domains: List<String> = emptyList(),
    val host: String = "",
    val port: Int = 993,
    val note: String = "",
    /** 当前部署可用的认证方式，顺序由后端决定（OAuth 优先）。 */
    val methods: List<AuthMethod> = emptyList(),
    val oauth: OAuthAvailability = OAuthAvailability(),
    /** 一种 OAuth 方式都没配置时的提示（提示管理员去设置页配置），可能为空串。 */
    @SerialName("oauth_hint") val oauthHint: String = "",
    /** 该服务商完全不可用时的说明（例如没配置 Microsoft OAuth 的 Outlook），可能为空串。 */
    val unavailable: String = "",
)

/** 一种认证方式；只有授权码 / 密码类方式才带 input_label、setup_* 和 steps。 */
@Serializable
data class AuthMethod(
    val type: String = "",
    val label: String = "",
    @SerialName("input_label") val inputLabel: String = "",
    @SerialName("setup_url") val setupUrl: String = "",
    @SerialName("setup_label") val setupLabel: String = "",
    val steps: List<String> = emptyList(),
    val links: List<AuthLink> = emptyList(),
    val troubleshoot: String = "",
) {
    /** 授权码 / 密码类方式：走 POST /api/accounts，需要用户填凭据。 */
    val usesSecret: Boolean get() = type == MethodAppPassword || type == MethodPassword
}

/** 授权码方式里的一个帮助链接。 */
@Serializable
data class AuthLink(
    val label: String = "",
    val url: String = "",
)

/** 该服务商是否配置了 OAuth 客户端：{"browser": bool, "device": bool}。 */
@Serializable
data class OAuthAvailability(
    val browser: Boolean = false,
    val device: Boolean = false,
)

/**
 * POST /api/oauth/start 与 POST /api/oauth/outlook/device/start 共用的请求体，
 * 对应 oauth_auth.OAuthStartBody；[client] 固定传 android。
 */
@Serializable
data class OAuthStartBody(
    val provider: String,
    val email: String,
    val name: String = "",
    val color: String = "#38bdf8",
    @SerialName("poll_interval") val pollInterval: Int = 300,
    @SerialName("account_id") val accountId: Int = 0,
    val client: String = AndroidOAuthClient,
)

/** POST /api/oauth/start 的返回：事务 id + 授权页地址。 */
@Serializable
data class OAuthStartResponse(
    val ok: Boolean = false,
    @SerialName("transaction_id") val transactionId: String = "",
    @SerialName("authorization_url") val authorizationUrl: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
)

/** GET /api/oauth/transactions/{id} 的返回：状态只可能是 pending / consumed / done / failed / expired。 */
@Serializable
data class OAuthTransaction(
    val id: String = "",
    val provider: String = "",
    val status: String = "pending",
    @SerialName("account_id") val accountId: Int = 0,
    val message: String = "",
    @SerialName("expires_ts") val expiresTs: Double = 0.0,
)

/** 事务已完成。 */
const val TransactionDone: String = "done"

/** 事务失败，原因在 message 里。 */
const val TransactionFailed: String = "failed"

/** 事务已过期。 */
const val TransactionExpired: String = "expired"

/** POST /api/oauth/outlook/device/start 的返回。 */
@Serializable
data class DeviceCodeStart(
    val ok: Boolean = false,
    @SerialName("transaction_id") val transactionId: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("verification_uri_complete") val verificationUriComplete: String = "",
    /** 后端已经做过 max(5, …) 的下限保护，这里再兜一次底。 */
    val interval: Int = 5,
    @SerialName("expires_in") val expiresIn: Int = 900,
)

/** POST /api/oauth/outlook/device/poll 的请求体。 */
@Serializable
data class DevicePollBody(
    @SerialName("transaction_id") val transactionId: String,
)

/**
 * POST /api/oauth/outlook/device/poll 的返回。
 * 等待中是 {pending:true,interval:n}（slow_down 时 interval 会变大），
 * 成功是 {ok:true,id:账户 id}；失败由后端返回 400 + detail。
 */
@Serializable
data class DeviceCodePoll(
    val pending: Boolean = false,
    val interval: Int = 0,
    val ok: Boolean = false,
    val id: Int = 0,
)
