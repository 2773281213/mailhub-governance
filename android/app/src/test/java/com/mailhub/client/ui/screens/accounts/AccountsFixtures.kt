package com.mailhub.client.ui.screens.accounts

import com.mailhub.client.data.accounts.AuthMethod
import com.mailhub.client.data.accounts.AuthLink
import com.mailhub.client.data.accounts.OAuthAvailability
import com.mailhub.client.data.accounts.ProviderInfo
import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.SyncState

/**
 * 截图与单元测试共用的示例数据：结构与 /api/oauth/providers、/api/accounts 一致，
 * 全部写死，不访问网络。服务商的顺序就是后端下发顺序，域名识别按这个顺序取第一个命中的。
 */
val FixtureProviders: Map<String, ProviderInfo> = linkedMapOf(
    "qq" to ProviderInfo(
        label = "QQ 邮箱",
        domains = listOf("qq.com", "foxmail.com"),
        host = "imap.qq.com",
        port = 993,
        note = "QQ 邮箱的 IMAP 只接受授权码。",
        methods = listOf(qqAppPasswordMethod()),
    ),
    "gmail" to ProviderInfo(
        label = "Gmail",
        domains = listOf("gmail.com", "googlemail.com"),
        host = "imap.gmail.com",
        port = 993,
        note = "Gmail 需要应用专用密码。",
        methods = listOf(
            browserMethod("使用 Google 登录"),
            gmailAppPasswordMethod(),
        ),
        oauth = OAuthAvailability(browser = true, device = false),
    ),
    "outlook" to ProviderInfo(
        label = "Outlook / Hotmail",
        domains = listOf("outlook.com", "hotmail.com"),
        host = "outlook.office365.com",
        port = 993,
        methods = listOf(deviceMethod()),
        oauth = OAuthAvailability(browser = false, device = true),
    ),
    "netease" to ProviderInfo(
        label = "网易企业邮",
        domains = listOf("qiye163.com"),
        host = "imap.qiye.163.com",
        port = 993,
        methods = emptyList(),
        oauthHint = "本站还没有配置网易 OAuth 客户端，请在设置页填写 App ID。",
        unavailable = "",
    ),
    "custom" to ProviderInfo(
        label = "自定义 IMAP",
        domains = emptyList(),
        host = "",
        port = 993,
        methods = listOf(customPasswordMethod()),
    ),
)

/** 未配置 OAuth 的 Outlook：后端会把 unavailable 写成一句完整说明。 */
val UnavailableOutlookInfo: ProviderInfo = ProviderInfo(
    label = "Outlook / Hotmail",
    domains = listOf("outlook.com", "hotmail.com"),
    host = "outlook.office365.com",
    port = 993,
    methods = emptyList(),
    unavailable = "Outlook 只能通过 Microsoft 登录添加，但本站还没有配置 Microsoft OAuth 客户端",
)

/** QQ 邮箱的授权码方式。 */
fun qqAppPasswordMethod(): AuthMethod = AuthMethod(
    type = "app_password",
    label = "使用授权码",
    inputLabel = "QQ 邮箱授权码",
    setupUrl = "https://mail.qq.com/",
    setupLabel = "打开 QQ 邮箱",
    steps = listOf(
        "登录网页版 QQ 邮箱 → 设置 → 账号，开启 IMAP/SMTP 服务",
        "按提示生成 16 位「授权码」",
        "回到这里粘贴授权码，空格可以不删",
    ),
    links = listOf(AuthLink(label = "打开 QQ 邮箱", url = "https://mail.qq.com/")),
)

/** Gmail 的应用专用密码方式。 */
fun gmailAppPasswordMethod(): AuthMethod = AuthMethod(
    type = "app_password",
    label = "使用应用专用密码",
    inputLabel = "Google 应用专用密码（16 位）",
    setupUrl = "https://myaccount.google.com/apppasswords",
    setupLabel = "创建应用专用密码",
    steps = listOf(
        "先为 Google 账号开启两步验证",
        "在应用专用密码页面随便填一个名称并创建",
        "把显示的 16 位密码粘贴到下方",
    ),
)

/** 自定义 IMAP 的密码方式。 */
fun customPasswordMethod(): AuthMethod = AuthMethod(
    type = "password",
    label = "使用密码",
    inputLabel = "IMAP 密码",
    steps = listOf(
        "填写 IMAP 服务器地址与端口",
        "用完整邮箱地址和密码登录",
    ),
)

/** 浏览器 OAuth 方式。 */
fun browserMethod(label: String): AuthMethod = AuthMethod(type = "oauth_browser", label = label)

/** 设备码方式。 */
fun deviceMethod(): AuthMethod = AuthMethod(type = "oauth_device", label = "用设备码登录")

/** 服务器时间 2026-11-04 08:54 UTC，换算到上海是 16:54。 */
const val AccountsFixtureNowTs: Double = 1793782440.0

/** 账户按服务器时区显示上次同步时间。 */
const val AccountsFixtureZone: String = "Asia/Shanghai"

/** 三种同步状态各一个账户：空闲（16:42）、同步中、出错。 */
val FixtureAccounts: List<Account> = listOf(
    Account(
        id = 1,
        name = "工作邮箱",
        provider = "qq",
        email = "work@example.com",
        total = 820,
        unread = 42,
        syncState = SyncState(
            state = SyncStateIdle,
            ts = AccountsFixtureNowTs - 12 * 60,
            newCount = 3,
        ),
    ),
    Account(
        id = 2,
        name = "私有邮箱",
        provider = "gmail",
        email = "me@gmail.com",
        total = 316,
        unread = 9,
        syncState = SyncState(
            state = SyncStateSyncing,
            msg = "拉取 12 封",
            ts = AccountsFixtureNowTs,
        ),
    ),
    Account(
        id = 3,
        name = "客户邮箱",
        provider = "outlook",
        email = "hello@studio.example",
        total = 64,
        unread = 0,
        syncState = SyncState(
            state = SyncStateError,
            msg = "连接测试失败: AUTHENTICATE 被服务商拒绝，请确认授权码没有过期",
            ts = AccountsFixtureNowTs - 3600,
        ),
    ),
)

/** 账户列表截图用的状态：默认展开 A1，好把三个操作按钮也录进截图。 */
fun accountsFixtureState(expandedId: Int = 1): AccountsUiState = AccountsUiState(
    accounts = FixtureAccounts,
    loading = false,
    expandedId = expandedId,
)

/** 添加流程第 1 步：邮箱已经识别出服务商。 */
fun addEmailFixtureState(): AddAccountUiState {
    val email = "work@qq.com"
    return AddAccountUiState(
        step = AddStep.Email,
        email = email,
        providers = FixtureProviders,
        target = detectTarget(email, FixtureProviders),
    )
}

/** 添加流程第 3 步：授权码方式。 */
fun addPasswordFixtureState(): AddAccountUiState {
    val email = "work@qq.com"
    return AddAccountUiState(
        step = AddStep.Execute,
        email = email,
        providers = FixtureProviders,
        target = detectTarget(email, FixtureProviders),
        selectedMethod = 0,
    )
}

/** 添加流程第 3 步：设备码方式。 */
fun addDeviceFixtureState(): AddAccountUiState {
    val email = "hello@outlook.com"
    return AddAccountUiState(
        step = AddStep.Execute,
        email = email,
        providers = FixtureProviders,
        target = detectTarget(email, FixtureProviders),
        selectedMethod = 0,
        deviceCode = DeviceCodeUi(
            code = "QZ7XK4JN9",
            verificationUri = "https://microsoft.com/devicelogin",
        ),
    )
}
