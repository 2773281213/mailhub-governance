package com.mailhub.core.providers

import com.mailhub.core.text.pyStrip

/**
 * 邮箱服务商注册表：每个服务商的域名、IMAP 主机和可用的认证方式只在这里定义。
 * 对应 `providers.py`，字段名与顺序一一对应，行为（含服务商顺序）也必须一致。
 *
 * 认证方式只有四种，前后端统一用这几个名字：
 * - [OAUTH_BROWSER]  浏览器 OAuth 登录（Gmail / Outlook 用授权码 + PKCE，网易用合作方 implicit flow）
 * - [OAUTH_DEVICE]   设备码登录（Outlook）
 * - [APP_PASSWORD]   服务商生成的授权码 / 应用专用密码 / 客户端专用密码
 * - [PASSWORD]       自定义 IMAP 的普通密码
 *
 * 账户表里的 auth_type 仍只区分 "oauth" 和 "password"（后两种都按密码登录 IMAP）。
 * OAuth 是否可用取决于部署者是否配置了自有客户端，由 `oauth_auth` 判断。
 */
const val OAUTH_BROWSER: String = "oauth_browser"
const val OAUTH_DEVICE: String = "oauth_device"
const val APP_PASSWORD: String = "app_password"
const val PASSWORD: String = "password"

/** 以密码登录 IMAP 的两种方式。 */
val SECRET_METHODS: List<String> = listOf(APP_PASSWORD, PASSWORD)

/**
 * 以密码登录 IMAP 的方式：授权码、应用专用密码或普通密码。对应 `providers.SecretAuth`。
 *
 * @param kind [APP_PASSWORD] 或 [PASSWORD]
 * @param inputLabel 输入框标题
 * @param setupUrl 去哪里开启 IMAP / 生成授权码
 * @param setupLabel 打开 [setupUrl] 的按钮文案
 * @param steps 操作步骤
 * @param prereqLinks 在 [setupUrl] 之前要先打开的页面，例如 Gmail 需要先开启两步验证：(标题, 地址)
 * @param troubleshoot 常见报错的解释
 */
data class SecretAuth(
    val kind: String,
    val inputLabel: String,
    val setupUrl: String = "",
    val setupLabel: String = "",
    val steps: List<String> = emptyList(),
    val prereqLinks: List<Pair<String, String>> = emptyList(),
    val troubleshoot: String = "",
)

/**
 * 一个邮箱服务商。对应 `providers.Provider`。
 *
 * @param domains 自动识别用的域名；企业自有域名留空
 * @param oauth "" 表示不支持 OAuth；"standard" 为 Google/Microsoft 授权码流程；"netease" 为网易合作方流程
 * @param oauthImapHost 网易 OAuth 账户连接 imapmail.* 主机，授权码账户连接 imap.* 主机
 * @param oauthMissingHint 部署者没有配置 OAuth 客户端时给用户的提示
 * @param neteaseWeb 网易授权入口所在的网页域名
 * @param sendImapId 认证后发送 RFC 2971 ID（网易不发会返回 Unsafe Login）
 */
data class Provider(
    val key: String,
    val label: String,
    val imapHost: String,
    val domains: List<String> = emptyList(),
    val imapPort: Int = 993,
    val secret: SecretAuth? = null,
    val oauth: String = "",
    val oauthLabel: String = "",
    val deviceFlow: Boolean = false,
    val oauthImapHost: String = "",
    val oauthMissingHint: String = "",
    val neteaseWeb: String = "",
    val sendImapId: Boolean = false,
    val note: String = "",
) {

    /** 对应 `Provider.host_for`：OAuth 账户连网易的 imapmail.* 主机。 */
    fun hostFor(authType: String): String {
        if (authType == "oauth" && oauthImapHost.isNotEmpty()) return oauthImapHost
        return imapHost
    }

    /** 对应 `Provider.netease_authorize_url`：只有网易服务商给授权入口。 */
    val neteaseAuthorizeUrl: String
        get() = if (oauth != "netease") {
            ""
        } else {
            "$neteaseWeb/fgw/mailsrv-oauth2-fapi/oauth2/authorize/entry"
        }
}

private val NETEASE_STEPS = listOf(
    "登录网页版邮箱 → 设置 → POP3/SMTP/IMAP，开启 IMAP/SMTP 服务",
    "按提示生成「客户端授权密码」（不是网页登录密码）",
    "回到这里，用完整邮箱地址和授权密码登录",
)

/**
 * 网易系列服务商。域名、授权入口和 imapmail 主机来自 Outlook Android 5.2606.0 的 NetEase.kt，
 * 授权密码用的 imap 主机来自网易邮箱大师；不要凭记忆改动。
 */
private fun netease(
    key: String,
    label: String,
    domain: String,
    web: String,
    imapHost: String,
    oauthImapHost: String,
): Provider = Provider(
    key = key,
    label = label,
    domains = listOf(domain),
    imapHost = imapHost,
    secret = SecretAuth(
        kind = APP_PASSWORD,
        inputLabel = "网易客户端授权密码",
        setupUrl = "$web/",
        setupLabel = "打开网易邮箱设置",
        steps = NETEASE_STEPS,
    ),
    oauth = "netease",
    oauthLabel = "使用网易邮箱登录",
    oauthImapHost = oauthImapHost,
    neteaseWeb = web,
    sendImapId = true,
)

/** 服务商列表，顺序与 `providers._ALL` 相同（`detect` 依赖这个顺序）。 */
val PROVIDERS_ALL: List<Provider> = listOf(
    Provider(
        key = "qq",
        label = "QQ 邮箱",
        domains = listOf("qq.com", "foxmail.com"),
        imapHost = "imap.qq.com",
        secret = SecretAuth(
            kind = APP_PASSWORD,
            inputLabel = "QQ 邮箱授权码",
            setupUrl = "https://mail.qq.com/",
            setupLabel = "打开 QQ 邮箱",
            steps = listOf(
                "登录网页版 QQ 邮箱 → 设置 → 账号，开启 IMAP/SMTP 服务",
                "按提示生成 16 位「授权码」",
            ),
        ),
        note = "QQ 邮箱的 IMAP 只接受授权码；QQ 密码和 QQ 互联登录都不能用于 IMAP。",
    ),
    netease("163", "网易 163", "163.com", "https://mail.163.com", "imap.163.com", "imapmail.163.com"),
    netease("126", "网易 126", "126.com", "https://mail.126.com", "imap.126.com", "imapmail.126.com"),
    netease(
        "yeah", "网易 yeah.net", "yeah.net", "https://mail.yeah.net", "imap.yeah.net", "imapmail.yeah.net",
    ),
    netease("188", "网易 188", "188.com", "https://mail.188.com", "imap.188.com", "imapmail.188.com"),
    netease(
        "vip163", "网易 VIP 163", "vip.163.com", "https://vip.163.com",
        "imap.vip.163.com", "imapmail.vip.163.com",
    ),
    netease(
        "vip126", "网易 VIP 126", "vip.126.com", "https://vip.126.com",
        "imap.vip.126.com", "imapmail.vip.126.com",
    ),
    // APK 只给出 vip.188.com 的授权入口，OAuth 的 IMAP 仍复用 188 的 imapmail 主机
    netease(
        "vip188", "网易 VIP 188", "vip.188.com", "https://vip.188.com",
        "imap.vip.188.com", "imapmail.188.com",
    ),
    Provider(
        key = "exmail",
        label = "腾讯企业邮",
        imapHost = "imap.exmail.qq.com",
        secret = SecretAuth(
            kind = APP_PASSWORD,
            inputLabel = "企业邮客户端专用密码",
            setupUrl = "https://exmail.qq.com/",
            setupLabel = "打开腾讯企业邮",
            steps = listOf(
                "登录企业邮网页版 → 设置 → 客户端专用密码，生成一个专用密码",
                "回到这里，用完整邮箱地址和专用密码登录",
            ),
        ),
        note = "企业自有域名无法自动识别，请在服务商里手动选择「腾讯企业邮」。",
    ),
    Provider(
        key = "qiye163",
        label = "网易企业邮",
        imapHost = "imap.qiye.163.com",
        sendImapId = true,
        secret = SecretAuth(
            kind = APP_PASSWORD,
            inputLabel = "企业邮客户端授权密码",
            setupUrl = "https://qiye.163.com/",
            setupLabel = "打开网易企业邮",
            steps = listOf(
                "请管理员在网易企业邮后台开启 IMAP/SMTP",
                "在邮箱设置里生成客户端授权密码",
                "回到这里，用完整邮箱地址和授权密码登录",
            ),
        ),
        note = "企业自有域名无法自动识别，请在服务商里手动选择「网易企业邮」。",
    ),
    Provider(
        key = "gmail",
        label = "Gmail",
        domains = listOf("gmail.com", "googlemail.com"),
        imapHost = "imap.gmail.com",
        secret = SecretAuth(
            kind = APP_PASSWORD,
            inputLabel = "Google 应用专用密码（16 位）",
            setupUrl = "https://myaccount.google.com/apppasswords",
            setupLabel = "创建应用专用密码",
            steps = listOf(
                "先为 Google 账号开启两步验证（没开时创建页面会提示「您的账号不支持您正在尝试的设置」）",
                "在应用专用密码页面随便填一个名称（如 MailHub）并创建",
                "把显示的 16 位密码粘贴到下方，空格可以不删",
            ),
            prereqLinks = listOf("开启两步验证" to "https://myaccount.google.com/signinoptions/twosv"),
            troubleshoot = "开启两步验证后仍提示不支持：工作或学校账号可能被管理员禁用了应用专用密码；" +
                "两步验证只用安全密钥、或开启了高级保护的账号也无法创建。这些账号请改用 Google 登录。",
        ),
        oauth = "standard",
        oauthLabel = "使用 Google 登录",
        oauthMissingHint = "不想开两步验证？管理员在「设置 → Google 登录导入」填写本站的 Google OAuth 客户端后，" +
            "这里会出现「使用 Google 登录」，直接用 Google 账号授权即可添加。",
    ),
    Provider(
        // IMAP OAuth 需要向 Yahoo 商务申请批准，不对普通开发者开放，因此只支持应用专用密码。
        // Yahoo! JAPAN（yahoo.co.jp）是独立服务、服务器不同，不在此列。
        key = "yahoo",
        label = "Yahoo 邮箱",
        imapHost = "imap.mail.yahoo.com",
        domains = listOf(
            "yahoo.com", "ymail.com", "rocketmail.com", "myyahoo.com",
            "yahoo.co.uk", "yahoo.ca", "yahoo.com.au", "yahoo.co.nz", "yahoo.ie", "yahoo.co.in",
            "yahoo.com.sg", "yahoo.com.my", "yahoo.com.ph", "yahoo.co.id", "yahoo.com.vn",
            "yahoo.com.hk", "yahoo.com.tw", "yahoo.co.th", "yahoo.fr", "yahoo.de", "yahoo.it",
            "yahoo.es", "yahoo.se", "yahoo.gr", "yahoo.com.br", "yahoo.com.mx", "yahoo.com.ar",
        ),
        secret = SecretAuth(
            kind = APP_PASSWORD,
            inputLabel = "Yahoo 应用专用密码",
            setupUrl = "https://login.yahoo.com/account/security",
            setupLabel = "打开 Yahoo 账户安全",
            steps = listOf(
                "登录 Yahoo 账户安全页面，在「外部连接」中选择「生成应用专用密码」",
                "随便填一个应用名称（如 MailHub）并生成",
                "把生成的密码粘贴到下方（不是 Yahoo 登录密码）",
            ),
            troubleshoot = "生成页面打不开或提示异常时，换一个最近常用、已登录 Yahoo 的浏览器再试，" +
                "不要用无痕模式。",
        ),
        note = "Yahoo 的 IMAP 只接受应用专用密码，账户登录密码不能用于 IMAP。",
    ),
    Provider(
        key = "outlook",
        label = "Outlook / Hotmail",
        domains = listOf("outlook.com", "hotmail.com", "live.com", "msn.com"),
        imapHost = "outlook.office365.com",
        oauth = "standard",
        oauthLabel = "使用 Microsoft 登录",
        deviceFlow = true,
        note = "Microsoft 已停用个人邮箱的 IMAP 密码登录，只能通过 OAuth 添加。",
        oauthMissingHint = "管理员在「设置 → Microsoft 登录导入」填写自己在 Microsoft Entra 注册的应用后即可添加；" +
            "只填 Client ID 就能用设备码登录，不需要 Client Secret。",
    ),
    Provider(
        key = "custom",
        label = "自定义 IMAP",
        imapHost = "",
        secret = SecretAuth(kind = PASSWORD, inputLabel = "邮箱密码或服务商要求的应用专用密码"),
        note = "填写支持 IMAP SSL 的服务器地址和端口。",
    ),
)

/** `providers.PROVIDERS`：按 key 索引，保留列表顺序。 */
val PROVIDERS: Map<String, Provider> = PROVIDERS_ALL.associateBy { it.key }

/** 对应 `providers.get(key)`：key 去空白、转小写后查表；查不到返回 null。 */
fun get(key: String?): Provider? = PROVIDERS[(key ?: "").pyStrip().lowercase()]

/**
 * 对应 `providers.detect(email_addr)`：按邮箱域名识别服务商，识别不出来时返回 "custom"。
 *
 * Python 用的是 `rpartition('@')[2]`：没有 `@` 时它返回**整个字符串**（不是空串），
 * 所以 `detect("qq.com")` 得到 `"qq"`。这里照抄，不要改回「找不到 @ 就当空域名」。
 */
fun detect(emailAddr: String?): String {
    val text = (emailAddr ?: "").pyStrip().lowercase()
    val domain = text.substring(text.lastIndexOf('@') + 1)
    for (provider in PROVIDERS_ALL) {
        if (domain in provider.domains) return provider.key
    }
    return "custom"
}

/** 对应 `providers.needs_imap_id(key)`：认证后是否要发 RFC 2971 ID。 */
fun needsImapId(key: String?): Boolean = get(key)?.sendImapId ?: false

/** 对应 `providers.netease_keys()`：所有走网易合作方 OAuth 流程的服务商 key。 */
fun neteaseKeys(): List<String> = PROVIDERS_ALL.filter { it.oauth == "netease" }.map { it.key }
