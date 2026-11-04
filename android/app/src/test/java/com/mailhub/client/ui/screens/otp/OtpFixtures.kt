package com.mailhub.client.ui.screens.otp

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.OtpItem
import com.mailhub.client.ui.screens.common.PageMeta

/** 服务器时间 2026-11-04 08:54 UTC（上海 16:54），与收件箱、总览截图共用同一时刻。 */
const val OtpFixtureNowTs: Double = 1793782440.0

/** 截图用的页头元信息。 */
val OtpFixtureMeta: PageMeta = PageMeta(year = 2026, place = "上海", zoneId = "Asia/Shanghai")

/** 账户按列表顺序编 A1、A2；看板用邮箱 / 名称把 OtpItem 对回这里。 */
val OtpFixtureAccounts: List<Account> = listOf(
    Account(id = 1, name = "工作邮箱", provider = "imap", email = "work@example.com"),
    Account(id = 2, name = "私有邮箱", provider = "imap", email = "me@example.com"),
)

/** 一条验证码；[ageSeconds] 是距截图时刻多久收到的。 */
fun otpFixtureItem(
    id: Long,
    code: String,
    senderName: String,
    ageSeconds: Double,
    accountIndex: Int = 0,
): OtpItem {
    val account = OtpFixtureAccounts[accountIndex]
    return OtpItem(
        id = id,
        subject = "登录验证码",
        senderAddr = "noreply$id@example.com",
        senderName = senderName,
        dateTs = OtpFixtureNowTs - ageSeconds,
        otpCode = code,
        unread = 1,
        accountName = account.name,
        accountEmail = account.email,
    )
}

/**
 * 截图用的看板状态：最新一条 2 分钟前（红色大数字），
 * 后面五条更早的条目，发件人、账户、位数各不相同。
 */
fun otpFixtureState(): OtpUiState = OtpUiState(
    items = listOf(
        otpFixtureItem(id = 1, code = "482913", senderName = "Cloudflare", ageSeconds = 120.0),
        otpFixtureItem(id = 2, code = "734156", senderName = "GitHub", ageSeconds = 600.0),
        otpFixtureItem(id = 3, code = "92840147", senderName = "阿里云", ageSeconds = 1_500.0),
        otpFixtureItem(id = 4, code = "314059", senderName = "Steam", ageSeconds = 3_600.0, accountIndex = 1),
        otpFixtureItem(id = 5, code = "660182", senderName = "腾讯云", ageSeconds = 7_200.0),
        otpFixtureItem(id = 6, code = "508731", senderName = "银行助手", ageSeconds = 86_400.0, accountIndex = 1),
    ),
    accounts = OtpFixtureAccounts,
    loading = false,
    nowTs = OtpFixtureNowTs,
)

/** 空状态：一次都没收到验证码。 */
fun otpEmptyFixtureState(): OtpUiState = OtpUiState(
    items = emptyList(),
    accounts = OtpFixtureAccounts,
    loading = false,
    nowTs = OtpFixtureNowTs,
)
