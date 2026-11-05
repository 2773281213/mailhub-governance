package com.mailhub.client.work

import com.mailhub.client.data.model.MessageSummary
import com.mailhub.client.data.model.senderDisplay

/**
 * 三个通知渠道。[publicTitle] 是锁屏上显示的版本：只说收到了什么，不带发件人、主题和验证码。
 */
enum class NotifyChannel(val id: String, val label: String, val publicTitle: String) {
    Important("important", "重要邮件", "收到重要邮件"),
    Security("security", "安全提醒", "收到安全提醒"),
    Otp("otp", "验证码", "收到新验证码"),
}

/** 后端的分类名（governance.py 写入的 category）。 */
private const val CategoryImportant = "重要"
private const val CategorySecurity = "安全"
private const val CategorySuspicious = "可疑"
private const val CategoryOtp = "验证码"

/** importance 达到这个值就算重要邮件，和网页端「重要未读」的口径一致。 */
private const val ImportantLevel = 4

/** 主题为空时的占位，与收件箱一致。 */
private const val EmptySubject = "(无主题)"

/**
 * 这封邮件走哪个渠道；不需要通知时返回 null。
 *
 * - 已读的邮件不通知（在别的客户端读过了）；
 * - 验证码优先：分类是「验证码」且确实带了验证码；
 * - 其次是安全提醒：分类是「安全」或「可疑」；
 * - 最后是重要邮件：分类是「重要」，或 importance ≥ 4。
 */
fun channelFor(item: MessageSummary): NotifyChannel? = when {
    !item.isUnread -> null
    item.category == CategoryOtp && item.otpCode.isNotBlank() -> NotifyChannel.Otp
    item.category == CategorySecurity || item.category == CategorySuspicious -> NotifyChannel.Security
    item.category == CategoryImportant || item.importance >= ImportantLevel -> NotifyChannel.Important
    else -> null
}

/** 一条通知的文字。[publicTitle] 用于锁屏，不含任何邮件内容。 */
data class NotificationText(
    val title: String,
    val text: String,
    val publicTitle: String,
)

/**
 * 通知文字：标题是分类名（验证码通知在后面接上验证码），正文是「发件人 · 主题」。
 * 发件人按 [senderDisplay] 的规则：伪造的显示名、风险邮件一律显示真实地址。
 */
fun notificationTextOf(item: MessageSummary, channel: NotifyChannel): NotificationText {
    val category = item.displayCategory.ifBlank { item.category }.ifBlank { channel.label }
    val title = if (channel == NotifyChannel.Otp) "$category ${item.otpCode.trim()}" else category
    val subject = item.subject.ifBlank { EmptySubject }
    val sender = item.senderDisplay
    val text = if (sender.isEmpty()) subject else "$sender · $subject"
    return NotificationText(title = title, text = text, publicTitle = channel.publicTitle)
}

/** 通知 id 就是邮件 id：同一封邮件再通知一次只会覆盖，不会叠出第二条。 */
fun notificationIdOf(messageId: Long): Int = (messageId % Int.MAX_VALUE).toInt()
