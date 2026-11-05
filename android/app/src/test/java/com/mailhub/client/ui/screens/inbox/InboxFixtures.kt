package com.mailhub.client.ui.screens.inbox

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.MessageSummary
import com.mailhub.client.ui.screens.common.PageMeta

/** 服务器时间 2026-11-04 08:54 UTC（上海 16:54），与总览截图共用同一时刻。 */
const val InboxFixtureNowTs: Double = 1793782440.0

/** 截图用的页头元信息。 */
val InboxFixtureMeta: PageMeta = PageMeta(year = 2026, place = "上海", zoneId = "Asia/Shanghai")

/** 与 /api/meta 的 display_categories 一致，分类编号按这个顺序。 */
val InboxFixtureCategories: List<String> = listOf(
    "重要账单", "一般账单", "验证码", "重要", "安全", "可疑", "订阅", "通知",
)

/** 账户按列表顺序编 A1、A2。 */
val InboxFixtureAccounts: List<Account> = listOf(
    Account(id = 1, name = "工作邮箱", provider = "imap", email = "work@example.com", total = 820, unread = 42),
    Account(id = 2, name = "私有邮箱", provider = "imap", email = "me@example.com", total = 316, unread = 9),
)

/** 一封列表邮件；分类同时写进 category 与 display_category，与后端一致。 */
fun inboxSummary(
    id: Long,
    subject: String = "邮件主题 $id",
    senderName: String = "发件人 $id",
    category: String = "通知",
    unread: Boolean = false,
    dateTs: Double = InboxFixtureNowTs - id * 1800,
    snippet: String = "这是一段示例摘要，用来说明这封邮件的要点，最多显示两行。",
    hasAttach: Boolean = false,
    senderAddr: String = "sender$id@example.com",
    riskLevel: String = "none",
): MessageSummary = MessageSummary(
    id = id,
    accountId = 1,
    subject = subject,
    senderName = senderName,
    senderAddr = senderAddr,
    dateTs = dateTs,
    snippet = snippet,
    category = category,
    billTier = "",
    otpCode = "",
    importance = 3,
    summary = snippet,
    unread = if (unread) 1 else 0,
    hasAttach = if (hasAttach) 1 else 0,
    accountName = "工作邮箱",
    riskLevel = riskLevel,
    displayCategory = category,
)

/**
 * 截图用的收件箱状态：包含未读、已读、带红色方块的分类（重要 / 安全 / 可疑）、
 * 不带方块的分类（账单 / 验证码 / 订阅 / 通知），以及一封前几天的邮件。
 */
fun inboxFixtureState(
    selectMode: Boolean = false,
    selected: Set<Long> = emptySet(),
): InboxUiState = InboxUiState(
    filters = InboxFilters(),
    items = listOf(
        inboxSummary(
            id = 1,
            subject = "账户存在异常登录，请立即验证你的身份",
            senderName = "PayPa1 安全中心",
            category = "可疑",
            unread = true,
            // 高风险邮件显示真实地址：地址太长，走「发件人独占一行」的分支
            senderAddr = "notice@paypa1-secure.example",
            riskLevel = "high",
        ),
        inboxSummary(
            id = 2,
            subject = "本月账单已生成",
            senderName = "云服务账单",
            category = "重要账单",
            unread = true,
        ),
        inboxSummary(
            id = 3,
            subject = "登录验证码",
            senderName = "MailHub",
            category = "验证码",
            snippet = "你的登录验证码是 482913，5 分钟内有效。",
        ),
        inboxSummary(
            id = 4,
            subject = "新的登录设备",
            senderName = "安全中心",
            category = "安全",
            unread = true,
        ),
        inboxSummary(
            id = 5,
            subject = "季度对账提醒",
            senderName = "财务部",
            category = "重要",
        ),
        inboxSummary(
            id = 6,
            subject = "订阅即将续费",
            senderName = "音乐订阅",
            category = "订阅",
            dateTs = InboxFixtureNowTs - 3 * 86400,
        ),
        inboxSummary(
            id = 7,
            subject = "本周产品更新",
            senderName = "产品团队",
            category = "通知",
            dateTs = InboxFixtureNowTs - 4 * 86400,
        ),
    ),
    categories = InboxFixtureCategories,
    accounts = InboxFixtureAccounts,
    loading = false,
    selectMode = selectMode,
    selected = selected,
)
