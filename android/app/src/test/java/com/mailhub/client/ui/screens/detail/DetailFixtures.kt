package com.mailhub.client.ui.screens.detail

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.MessageDetail
import com.mailhub.client.ui.screens.common.PageMeta

/** 服务器时间 2026-11-04 08:54 UTC（上海 16:54），与其它截图共用同一时刻。 */
const val DetailFixtureNowTs: Double = 1793782440.0

/** 截图用的页头元信息。 */
val DetailFixtureMeta: PageMeta = PageMeta(year = 2026, place = "上海", zoneId = "Asia/Shanghai")

/** 账户按列表顺序编 A1、A2。 */
val DetailFixtureAccounts: List<Account> = listOf(
    Account(id = 1, name = "工作邮箱", provider = "imap", email = "work@example.com"),
    Account(id = 2, name = "私有邮箱", provider = "imap", email = "me@example.com"),
)

/** 与 /api/meta 的 display_categories 一致，分类编号按这个顺序。 */
val DetailFixtureCategories: List<String> = listOf(
    "重要账单", "一般账单", "验证码", "重要", "安全", "可疑", "订阅", "通知",
)

/** 一封普通的通知邮件：无风险、无验证码、正文是纯文本。 */
fun plainDetail(): MessageDetail = MessageDetail(
    id = 91,
    accountId = 1,
    subject = "本周产品更新：收件箱筛选与多选",
    senderName = "MailHub 产品团队",
    senderAddr = "product@mailhub.dev",
    toAddr = "work@example.com",
    dateTs = DetailFixtureNowTs - 1500,
    snippet = "本周上线了收件箱筛选、多选批量操作与邮件详情页。",
    category = "通知",
    billTier = "",
    otpCode = "",
    importance = 2,
    summary = "本周上线了收件箱筛选、多选批量操作与邮件详情页。",
    unread = 0,
    hasAttach = 0,
    accountName = "工作邮箱",
    bodyText = "你好：\n\n本周更新包含三项改动：\n" +
        "一、收件箱支持按未读、分类与账户筛选，搜索结果 300 毫秒防抖。\n" +
        "二、长按一行进入多选，可以批量标记已读、未读或删除。\n" +
        "三、邮件详情页直接显示风险提示与验证码，受保护邮件的删除需要二次确认。\n\n" +
        "如有问题，直接回复这封邮件即可。",
    bodyHtml = "",
    riskLevel = "none",
    riskReasons = "",
    confidence = 0.62,
    aiReason = "",
    governanceMinistry = "礼部",
    governanceSource = "规则",
    governanceReason = "发件域名属于已知订阅名单，命中「通知」规则。",
    displayCategory = "通知",
    bodyTruncated = 1,
)

/** 一封高风险邮件：带风险平涂块、验证码、判定依据，正文仍是纯文本。 */
fun riskyDetail(): MessageDetail = MessageDetail(
    id = 42,
    accountId = 1,
    subject = "账户存在异常登录，请立即验证你的身份",
    senderName = "PayPa1 安全中心",
    senderAddr = "notice@paypa1-secure.example",
    toAddr = "work@example.com",
    dateTs = DetailFixtureNowTs,
    snippet = "我们检测到你的账户在新设备上登录，请在 24 小时内完成验证。",
    category = "可疑",
    billTier = "",
    otpCode = "482913",
    importance = 5,
    summary = "我们检测到你的账户在新设备上登录，请在 24 小时内完成验证。",
    unread = 0,
    hasAttach = 1,
    accountName = "工作邮箱",
    bodyText = "尊敬的用户：\n\n我们检测到你的账户在一台新的设备上登录。如果不是你本人操作，" +
        "请立即点击下面的链接完成身份验证，否则账户将在 24 小时后被冻结。\n\n" +
        "为了验证身份，请输入你刚刚收到的验证码 482913。\n\nPayPa1 安全中心",
    bodyHtml = "",
    riskLevel = "high",
    riskReasons = "发件域名与官方域名仅差一个字符；正文索取验证码并催促立即操作。",
    confidence = 0.87,
    aiReason = "域名近似官方域名，且正文直接索取验证码，判定为钓鱼邮件。",
    governanceMinistry = "户部",
    governanceSource = "AI",
    governanceReason = "",
    displayCategory = "可疑",
    bodyTruncated = 0,
)

/** 截图用的详情状态：数据已就绪，没有任何确认面板打开。 */
fun detailFixtureState(message: MessageDetail): DetailUiState = DetailUiState(
    messageId = message.id,
    loading = false,
    error = null,
    message = message,
    accounts = DetailFixtureAccounts,
    categories = DetailFixtureCategories,
)
