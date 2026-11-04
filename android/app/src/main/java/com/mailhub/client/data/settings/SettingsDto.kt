package com.mailhub.client.data.settings

import com.mailhub.client.data.net.ApiJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 后端 ai.AI_SCOPES 的三个取值。 */
const val AiScopeAll: String = "all"
const val AiScopeUncertain: String = "uncertain"
const val AiScopeOther: String = "other"

/**
 * GET /api/settings 的返回，只取 App 用得到的字段（其余字段由 ignoreUnknownKeys 忽略）。
 * 默认值与 app.py / ai.ai_config() 的默认值一致。
 *
 * 凭据一律只有「是否已设置」：Bark 只回显主机名，Telegram 令牌、Onepush 配置、AI Key 都不回显。
 */
@Serializable
data class SettingsDto(
    @SerialName("ai_enabled") val aiEnabled: Boolean = false,
    @SerialName("ai_base_url") val aiBaseUrl: String = "",
    @SerialName("ai_key_set") val aiKeySet: Boolean = false,
    @SerialName("ai_model") val aiModel: String = "",
    @SerialName("ai_send_body") val aiSendBody: Boolean = true,
    @SerialName("ai_scope") val aiScope: String = AiScopeAll,
    @SerialName("ai_backfill_days") val aiBackfillDays: Int = 30,
    @SerialName("clean_otp_days") val cleanOtpDays: Int = 0,
    @SerialName("clean_server") val cleanServer: Boolean = false,
    /** 已读状态双向同步；旧版后端没有这个字段，为 null 时页面隐藏整块。 */
    @SerialName("sync_read_state") val syncReadState: Boolean? = null,
    @SerialName("clean_bill_minor_days") val cleanBillMinorDays: Int = 0,
    @SerialName("digest_hour") val digestHour: Int = 8,
    @SerialName("notify_bark_set") val notifyBarkSet: Boolean = false,
    @SerialName("notify_bark_host") val notifyBarkHost: String = "",
    @SerialName("notify_tg_token_set") val notifyTgTokenSet: Boolean = false,
    @SerialName("notify_tg_chat") val notifyTgChat: String = "",
    @SerialName("notify_onepush_set") val notifyOnepushSet: Boolean = false,
    @SerialName("notify_onepush_provider") val notifyOnepushProvider: String = "",
    @SerialName("notify_important") val notifyImportant: Boolean = true,
    @SerialName("notify_otp") val notifyOtp: Boolean = false,
    @SerialName("body_keep_days") val bodyKeepDays: Int = 90,
)

/**
 * PUT /api/settings 的请求体：所有字段可选，null 的字段不会出现在 JSON 里
 * （ApiJson 设了 explicitNulls = false），后端也就不会改它。
 *
 * 有意不包含任何凭据字段（AI Key、推送地址、令牌、Onepush 配置、OAuth 客户端）：
 * 这些只在网页端修改，App 不提供输入。
 */
@Serializable
data class SettingsUpdate(
    @SerialName("ai_enabled") val aiEnabled: Boolean? = null,
    @SerialName("ai_send_body") val aiSendBody: Boolean? = null,
    @SerialName("ai_scope") val aiScope: String? = null,
    @SerialName("ai_backfill_days") val aiBackfillDays: Int? = null,
    @SerialName("clean_otp_days") val cleanOtpDays: Int? = null,
    @SerialName("clean_server") val cleanServer: Boolean? = null,
    @SerialName("clean_bill_minor_days") val cleanBillMinorDays: Int? = null,
    @SerialName("sync_read_state") val syncReadState: Boolean? = null,
    @SerialName("digest_hour") val digestHour: Int? = null,
    @SerialName("old_password") val oldPassword: String? = null,
    @SerialName("new_password") val newPassword: String? = null,
    @SerialName("notify_important") val notifyImportant: Boolean? = null,
    @SerialName("notify_otp") val notifyOtp: Boolean? = null,
    @SerialName("body_keep_days") val bodyKeepDays: Int? = null,
) {
    /** 密码不进 toString，避免被日志或崩溃报告带出去；其余字段按请求体的 JSON 原样显示。 */
    override fun toString(): String {
        val masked = copy(
            oldPassword = oldPassword?.let { MaskedSecret },
            newPassword = newPassword?.let { MaskedSecret },
        )
        return "SettingsUpdate" + ApiJson.encodeToString(serializer(), masked)
    }
}

/** toString 里替代密码的占位。 */
private const val MaskedSecret: String = "***"

/** POST /api/notify/test 的请求体：App 不提交未保存的 Onepush 配置，固定为空串。 */
@Serializable
data class PushTestBody(
    @SerialName("onepush_config") val onepushConfig: String = "",
)

/** POST /api/notify/test 的返回：成功 {ok:true,msg:"已发送到 …"}，失败也是 200 但 ok=false。 */
@Serializable
data class PushTestResult(
    val ok: Boolean = false,
    val msg: String = "",
)
