package com.mailhub.client.ui.screens.otp

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.OtpItem
import com.mailhub.client.data.model.senderLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 一次刷新取多少条：最新一条 + 更早的 23 条。 */
const val OtpListLimit: Int = 24

/** 「更早」区块最多显示多少条，正好是 [OtpListLimit] 去掉最新一条。 */
const val OtpEarlierLimit: Int = OtpListLimit - 1

/** 刷新间隔：页面可见期间每 10 秒拉一次 /api/otp。 */
const val OtpRefreshIntervalMillis: Long = 10_000L

/** 时钟步长：每秒把「现在」推进一格，「N 分钟前」和红色都不用等下一次网络刷新。 */
const val OtpClockTickMillis: Long = 1_000L

/** 收到多久以内算「刚收到」：5 分钟，数字用红色。 */
const val OtpFreshWindowSeconds: Double = 300.0

/** 超过这个位数就降一档字号（displaySmall 换成 title）。 */
const val OtpDisplayDigits: Int = 6

/** 刷新失败时页面底部的一行说明。 */
const val OtpRefreshFailedText: String = "刷新失败，稍后重试"

/** 复制成功后的提示。 */
const val OtpCopiedNotice: String = "已复制，60 秒后自动清除"

/** 空状态的标题与说明。 */
const val OtpEmptyTitle: String = "暂无验证码"
const val OtpEmptyBody: String = "收到验证码邮件后会显示在这里"

/** 缺值时统一用这个占位符，与列表页保持一致。 */
private const val OtpEmptyText: String = "—"

/**
 * 验证码看板界面状态。[nowTs] 由 ViewModel 的时钟每秒推进一次，
 * 红色判定和「N 分钟前」都读它，所以不刷新网络也会自己变。
 */
data class OtpUiState(
    val items: List<OtpItem> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val loading: Boolean = true,
    /** 最近一次刷新是否失败；失败时保留已有内容，只在底部加一行说明。 */
    val refreshFailed: Boolean = false,
    /** 「现在」的秒数。 */
    val nowTs: Double = 0.0,
    /** 一次性提示（复制成功），显示成 Snackbar。 */
    val notice: String? = null,
) {
    /**
     * 大字区显示的最新一条：只看没有风险的条目。
     * 风险的「验证码」可能是钓鱼邮件里伪造的，不能当成最新验证码摆在最显眼的位置。
     */
    val latest: OtpItem? get() = items.firstOrNull { !it.isRisky }

    /** 其余条目（最新一条除外，风险条目也在里面），最多 [OtpEarlierLimit] 条。 */
    val earlier: List<OtpItem> get() =
        items.filterNot { it === latest }.take(OtpEarlierLimit)
}

sealed interface OtpEvent {

    /** 复制一个验证码：剪贴板写入由页面层复用 SensitiveClipboard 完成。 */
    data class Copy(val code: String) : OtpEvent

    data object DismissNotice : OtpEvent
}

/** 大号验证码的字号档位：6 位及以内用 displaySmall，更长降到 title。 */
enum class OtpCodeSize { Display, Title }

/** 位数降档：6 位用 displaySmall（80sp），超过 6 位用 title（56sp）。 */
fun otpCodeSizeFor(code: String): OtpCodeSize =
    if (code.length > OtpDisplayDigits) OtpCodeSize.Title else OtpCodeSize.Display

/**
 * 刚收到的验证码：距「现在」不到 5 分钟就返回 true，数字用红色。
 * 时间戳缺失（≤ 0）一律按不新鲜处理，不用红色。
 */
fun isOtpFresh(dateTs: Double, nowTs: Double): Boolean {
    if (dateTs <= 0.0) return false
    return nowTs - dateTs < OtpFreshWindowSeconds
}

private val otpClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 时区名不可用时退回设备时区，不让整行失败。 */
private fun otpZone(zoneId: String): ZoneId =
    runCatching { ZoneId.of(zoneId) }.getOrElse { ZoneId.systemDefault() }

/** 收到时间，固定 HH:mm，按服务器时区。 */
fun otpClockText(dateTs: Double, zoneId: String): String {
    if (dateTs <= 0.0) return OtpEmptyText
    return Instant.ofEpochSecond(dateTs.toLong()).atZone(otpZone(zoneId)).format(otpClockFormat)
}

/** 「N 分钟前」；不到一分钟说明刚刚收到。 */
fun otpAgeText(dateTs: Double, nowTs: Double): String {
    if (dateTs <= 0.0) return OtpEmptyText
    val minutes = ((nowTs - dateTs).coerceAtLeast(0.0) / 60.0).toInt()
    return if (minutes <= 0) "刚刚" else "$minutes 分钟前"
}

/**
 * 发件人：与列表页共用 [senderLabel] 的规则——风险邮件，或显示名里嵌着另一个地址时，
 * 一律显示真实地址，不能把攻击者写的显示名摆在发件人位置。
 */
fun otpSenderText(item: OtpItem): String =
    senderLabel(item.senderName, item.senderAddr, item.isRisky)

/** 风险条目在来源栏里的标注。 */
const val OtpRiskyMark: String = "有风险"

/**
 * 「更早」一行里来源栏（第 6–10 栏）的文字：有风险的条目只写「有风险」，
 * 真实地址放到下一行（见 [otpEarlierAddressText]）。来源栏只有 5 栏宽，标注和长地址
 * 挤在一起会把地址截掉，而地址恰恰是判断真假最要紧的部分。
 */
fun otpEarlierSourceText(item: OtpItem): String =
    if (item.isRisky) OtpRiskyMark else otpSenderText(item)

/** 有风险的条目在下一行完整显示的真实地址；没有风险时为 null，不占这一行。 */
fun otpEarlierAddressText(item: OtpItem): String? =
    if (item.isRisky) otpSenderText(item) else null

/**
 * 账户编号 A1、A2 …：按账户列表顺序编号。
 * /api/otp 不下发 account_id，所以先用账户邮箱对上列表，邮箱对不上再用账户名。
 */
fun otpAccountNumber(accounts: List<Account>, item: OtpItem): String {
    val email = item.accountEmail.trim()
    var index = if (email.isEmpty()) {
        -1
    } else {
        accounts.indexOfFirst { it.email.equals(email, ignoreCase = true) }
    }
    if (index < 0 && item.accountName.isNotEmpty()) {
        index = accounts.indexOfFirst { it.name == item.accountName }
    }
    return if (index >= 0) "A${index + 1}" else ""
}

/** 最新一条下面那行说明：发件人 · 账户 · 时间 · N 分钟前，缺的字段直接省掉。 */
fun otpCaptionText(
    item: OtpItem,
    accounts: List<Account>,
    zoneId: String,
    nowTs: Double,
): String = listOf(
    otpSenderText(item),
    otpAccountNumber(accounts, item),
    otpClockText(item.dateTs, zoneId),
    otpAgeText(item.dateTs, nowTs),
).filter { it.isNotEmpty() }.joinToString(" · ")
