package com.mailhub.client.ui.screens.detail

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.MessageDetail
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.ui.screens.common.accountLabel
import com.mailhub.client.ui.screens.common.categoryLabel
import com.mailhub.client.ui.screens.common.displayCategoryNumber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 缺值占位符。 */
const val DetailEmptyValue: String = "—"

/**
 * 详情页界面状态。[deleted] / [markedRead] / [markedUnread] 是给外壳看的一次性信号：
 * 外壳据此把收件箱那一行同步掉，再把页面弹回去。
 */
data class DetailUiState(
    val messageId: Long = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val message: MessageDetail? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<String> = emptyList(),
    /** 第一次删除确认（普通邮件）。 */
    val confirmingDelete: Boolean = false,
    /** 受保护邮件的二次确认。 */
    val confirmingProtected: Boolean = false,
    /** 待用户确认在浏览器中打开的链接。 */
    val pendingLink: String? = null,
    /** 用户确认后真正要在外部浏览器打开的链接，由页面层打开并清空。 */
    val linkToOpen: String? = null,
    val deleting: Boolean = false,
    /** 用户为本封邮件放开了外部图片（只对本次打开有效）。 */
    val showExternalImages: Boolean = false,
    val markedRead: Boolean = false,
    val markedUnread: Boolean = false,
    val deleted: Boolean = false,
    val notice: String? = null,
)

sealed interface DetailEvent {

    data object Reload : DetailEvent

    data object MarkUnread : DetailEvent

    data object RequestDelete : DetailEvent

    data object ConfirmDelete : DetailEvent

    data object DismissDelete : DetailEvent

    /** 受保护邮件：用户看完说明后确认删除，重发时带 override_protection。 */
    data object ConfirmProtectedDelete : DetailEvent

    data object DismissProtected : DetailEvent

    /** 放开本封邮件的外部图片（网络加载从这一下开始才允许）。 */
    data object ShowExternalImages : DetailEvent

    /** 正文里的链接被点击。 */
    data class TapLink(val url: String) : DetailEvent

    data object ConfirmOpenLink : DetailEvent

    data object DismissOpenLink : DetailEvent

    data object DismissNotice : DetailEvent
}

/** 服务器时区；时区名不可用时退回设备时区。 */
private fun zoneOrSystem(zoneId: String): ZoneId =
    runCatching { ZoneId.of(zoneId) }.getOrElse { ZoneId.systemDefault() }

private val detailTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 详情里的完整时间，按服务器时区显示到分钟。 */
fun detailTimeText(dateTs: Double, zoneId: String): String {
    if (dateTs <= 0.0) return DetailEmptyValue
    return Instant.ofEpochSecond(dateTs.toLong()).atZone(zoneOrSystem(zoneId)).format(detailTimeFormat)
}

/** 置信度百分比；没有置信度时显示占位符。 */
fun confidenceText(confidence: Double): String {
    if (confidence <= 0.0) return DetailEmptyValue
    return "${(confidence * 100).roundToInt()}%"
}

/** 发件人一行：「名称 <地址>」。 */
fun senderText(detail: MessageDetail): String {
    val name = detail.senderName.trim()
    val addr = detail.senderAddr.trim()
    return when {
        addr.isEmpty() -> name.ifEmpty { DetailEmptyValue }
        name.isEmpty() -> addr
        else -> "$name <$addr>"
    }
}

/** 账户：「A1 工作邮箱」；账户列表还没到或对不上时退回服务端给的账户名。 */
fun accountText(detail: MessageDetail, accounts: List<Account>): String {
    val label = accountLabel(accounts, detail.accountId)
    val name = detail.accountName.ifBlank {
        accounts.firstOrNull { it.id == detail.accountId }?.name.orEmpty()
    }
    return when {
        label.isEmpty() -> name.ifEmpty { DetailEmptyValue }
        name.isEmpty() -> label
        else -> "$label $name"
    }
}

/** 分类：「编号 + 名称」，编号取自 /api/meta 的下发顺序。 */
fun detailCategoryText(detail: MessageDetail, categories: List<String>): String {
    val name = detail.displayCategory.ifBlank { detail.category }
    if (name.isBlank()) return DetailEmptyValue
    return categoryLabel(displayCategoryNumber(categories, name), name)
}

/** 判定依据：优先 AI 给出的原因，没有再用治理原因。 */
fun judgeReasonText(detail: MessageDetail): String {
    if (detail.aiReason.isNotBlank()) return detail.aiReason
    if (detail.governanceReason.isNotBlank()) return detail.governanceReason
    return DetailEmptyValue
}

/**
 * 是否显示验证码区块。
 *
 * 钓鱼邮件里的「验证码」是攻击者写进正文的，把它做成醒目的大号数字再加一个「复制」按钮，
 * 等于在引导用户配合攻击。服务端入库时已经清空了中高风险邮件的 otp_code，
 * 客户端这里再挡一层：只要是风险邮件就一律不显示，即使 [otpCode] 不为空。
 */
fun showsOtpBlock(isRisky: Boolean, otpCode: String): Boolean = !isRisky && otpCode.isNotBlank()

/** 取详情的失败文案，一律用黑色正文说明。 */
fun detailErrorText(error: Throwable): String = when (error) {
    is ApiError.Network -> "无法连接服务器，请检查网络后重试"
    is ApiError.Unauthorized -> "登录已过期，请重新登录"
    is ApiError.Decode -> "服务器返回的数据格式不正确"
    is ApiError.TooManyRequests -> "请求过于频繁，请稍后再试"
    is ApiError.Forbidden -> error.detail ?: "操作被拒绝"
    is ApiError.Conflict -> error.detail ?: "服务器状态冲突，请稍后再试"
    is ApiError.Server -> error.detail ?: "服务器错误 ${error.code}"
    else -> "加载失败，请重试"
}
