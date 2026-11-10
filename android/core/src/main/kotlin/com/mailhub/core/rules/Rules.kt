package com.mailhub.core.rules

import com.mailhub.core.text.Py
import com.mailhub.core.text.PyRegex
import com.mailhub.core.text.cpIndex
import com.mailhub.core.text.cpLen
import com.mailhub.core.text.cpSlice

/**
 * 规则引擎的一次判定结果，对应 Python `rules._result` 组装的 dict
 * `{category, otp, confidence, hits}`。hits 在原实现里始终只有一个元素。
 */
data class RuleResult(
    val category: String,
    val otp: String,
    val confidence: Double,
    val hits: List<String>,
)

/** 用户自定义规则，对应数据库 `rules` 表的 field / pattern / category 三列。 */
data class CustomRule(
    val field: String,
    val pattern: String,
    val category: String,
)

/** 每个分类的策略属性，对应 Python `rules.CATEGORY_POLICY` 里的一项。 */
data class CategoryPolicy(
    val risk: String,
    val action: String,
    val keep: Int,
    val autoDel: Boolean,
    val cloudAi: Boolean,
    val alert: Boolean,
)

/**
 * 本地分类规则引擎：验证码提取 + 关键词/邮件头信号分类。
 *
 * 逐函数移植 Python `rules.py`（`load_custom_rules` 依赖数据库，留给 L8）。
 * Python 是规格：同一个输入必须给出完全相同的输出，金标语料见
 * `android/core/src/test/resources/golden/rules.json`（由 `scripts/golden/rules.py` 导出）。
 */
object Rules {

    /** 分类固定集合，与前后端和 AI 提示词保持一致。对应 Python `CATEGORIES`。 */
    val CATEGORIES: List<String> = listOf("验证码", "重要", "账单", "安全", "可疑", "订阅", "通知", "其他")

    /**
     * 每个分类的策略属性。对应 Python `CATEGORY_POLICY`。
     * cloudAi 只在 AI 判定范围为「仅规则未识别的邮件」时生效。
     */
    val CATEGORY_POLICY: Map<String, CategoryPolicy> = linkedMapOf(
        "验证码" to CategoryPolicy("low", "label", 7, autoDel = true, cloudAi = false, alert = false),
        "重要" to CategoryPolicy("low", "notify", 0, autoDel = false, cloudAi = true, alert = true),
        "账单" to CategoryPolicy("low", "remind", 0, autoDel = false, cloudAi = false, alert = false),
        "安全" to CategoryPolicy("high", "notify", 0, autoDel = false, cloudAi = false, alert = true),
        "可疑" to CategoryPolicy("high", "quarantine", 0, autoDel = false, cloudAi = false, alert = true),
        "订阅" to CategoryPolicy("low", "archive", 30, autoDel = true, cloudAi = true, alert = false),
        "通知" to CategoryPolicy("low", "digest", 30, autoDel = true, cloudAi = true, alert = false),
        "其他" to CategoryPolicy("low", "none", 0, autoDel = false, cloudAi = true, alert = false),
    )

    /** 规则置信度。对应 Python `CONF_*`，治理流水线据此决定是否交 AI 复核。 */
    const val CONF_USER_RULE: Double = 0.95
    const val CONF_OTP: Double = 0.95
    const val CONF_STRONG: Double = 0.9
    const val CONF_MEDIUM: Double = 0.8
    const val CONF_BULK_STRONG: Double = 0.75
    const val CONF_WEAK: Double = 0.6
    const val CONF_DEFAULT: Double = 0.5

    /** 正文截断：验证码只在正文前 2000 字符里找，分类只看前 3000 字符。都按码点算。 */
    private const val OTP_BODY_CHARS = 2000
    private const val CLASSIFY_BODY_CHARS = 3000

    /** `_pick_code` 里关键词前后取窗口的范围（`text[max(0, start-60): end+120]`）。 */
    private const val WINDOW_BEFORE = 60
    private const val WINDOW_AFTER = 120

    /** `_plausible_code` 里检查候选码前后文的范围（前 12 个码点、后 6 个码点）。 */
    private const val CODE_CONTEXT_BEFORE = 12
    private const val CODE_CONTEXT_AFTER = 6

    // ---------------- 正则：全部照抄 rules.py，编译一律走 PyRegex ----------------

    /** 对应 Python `OTP_KEYWORDS`。 */
    private val OTP_KEYWORDS = PyRegex.compile(
        """验证码|校验码|动态密码|动态码|驗證碼|确认码|激活码|取件码|提取码|安全码|(登录|安全|验证)代码""" +
            """|verification\s*code|verify\s+(with\s+)?(this|the\s+following|your)\s+code|security\s*code""" +
            """|one[-\s]?time\s+(pass)?(code|password|pin)|otp\b|passcode|auth(entication)?\s*code""" +
            """|login\s*code|sign[-\s]?in\s*code|access\s*code|confirmation\s*code|guard\s*code""" +
            """|码为|code\s*(is|:)""",
        ignoreCase = true,
    )

    /** 对应 Python `_PROMO_NEAR`：关键词附近出现这些词说明是优惠码/礼品码。 */
    private val PROMO_NEAR = PyRegex.compile(
        """promo|coupon|discount|voucher|gift\s*card|referral|\d+\s*%\s*off""" +
            """|优惠|折扣|兑换|礼品|满减|领券""",
        ignoreCase = true,
    )

    /** 对应 Python `_NUM_CODE`：纯数字 4-8 位，前后不能是数字/连字符，也不能是小数。 */
    private val NUM_CODE = PyRegex.compile("""(?<![\d.\-])(\d{4,8})(?![\d\-]|\.\d)""")

    /** 对应 Python `_ALNUM_CODE`：字母数字混合 5-8 位，且必须同时含数字和字母。 */
    private val ALNUM_CODE = PyRegex.compile("""\b(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])([A-Z0-9]{5,8})\b""")

    /** 对应 Python `_BAD_NUMS`：年份。 */
    private val BAD_NUMS = PyRegex.compile("""^(19|20)\d{2}$""")

    /** 对应 Python `_NOT_CODE_BEFORE`：候选码前面是这些词时它不是验证码。 */
    private val NOT_CODE_BEFORE = PyRegex.compile(
        """([¥$€£￥]|订单号?|单号|运单|快递|电话|手机|尾号|卡号|账号|工号|编号""" +
            """|order|ref|invoice|account|tel|phone|no\.?|#)\s*[:：#]?\s*$""",
        ignoreCase = true,
    )

    /** 对应 Python `_NOT_CODE_AFTER`：候选码后面是这些单位时它是金额/数量/日期。 */
    private val NOT_CODE_AFTER = PyRegex.compile(
        """^\s*(元|块|美元|USD|RMB|CNY|%|折|件|人|天|小时|GB|MB|KB|万|次|年|月|日)""",
        ignoreCase = true,
    )

    /** 对应 Python `_SECURITY`。 */
    private val SECURITY = PyRegex.compile(
        """异常登录|异地登录|安全提醒|安全警告|风险提示|账号异常|账户异常|修改密码|密码已(被)?修改|冻结""" +
            """|新设备|新的设备|登录提醒|登录通知|新登录""" +
            """|security\s*alert|suspicious|unusual\s*(sign|activity|login)|new\s*(device|sign[-\s]?in|login)""" +
            """|password\s*(was\s*)?(changed|reset)|two[-\s]?factor|2fa""",
        ignoreCase = true,
    )

    /** 对应 Python `_BILL`。 */
    private val BILL = PyRegex.compile(
        """账单|对账|发票|扣费|扣款|支付成功|付款|充值|余额|欠费|续费|租金|价格调整|还款|应还""" +
            """|订单.{0,4}(确认|已提交|支付|付款|已付)""" +
            """|invoice|receipt|billing|payment\s*(received|due|confirm)|charged|subscription\s*renew""" +
            """|order\s*(confirm|placed)""",
        ignoreCase = true,
    )

    /** 对应 Python `_PAYMENT`：有这些词才算真正的交易。 */
    private val PAYMENT = PyRegex.compile(
        """支付|付款|扣款|扣费|金额|已付|invoice|receipt|payment|charged|refund|退款""",
        ignoreCase = true,
    )

    /** 对应 Python `_SHIPPING`。 */
    private val SHIPPING = PyRegex.compile(
        """发货|已发出|快递|物流|派送|派件|签收|运单|配送|出库|揽收""" +
            """|shipped|shipping\s+(update|confirmation)|out\s+for\s+delivery|delivered|tracking|in\s+transit""" +
            """|your\s+(package|parcel)""",
        ignoreCase = true,
    )

    /** 对应 Python `_SUBSCRIBE`。 */
    private val SUBSCRIBE = PyRegex.compile(
        """退订|取消订阅|newsletter|unsubscribe|weekly\s*digest|promo(tion)?|优惠|折扣|特惠|活动预告""" +
            """|限时|大促|双1[12]|黑五|sale\b|deal\b|coupon|周刊|月刊|简报""" +
            """|\d+\s*%\s*off|special\s+offer|limited\s+time|free\s+shipping|满减|秒杀|包邮|领券|买一送一""",
        ignoreCase = true,
    )

    /** 对应 Python `_NOTIFY_SENDER`。 */
    private val NOTIFY_SENDER = PyRegex.compile(
        """noreply|no-reply|no_reply|notification|notify|donotreply|updates?@|news@|info@""" +
            """|github\.com|gitlab|atlassian|jira|slack\.com|discord|telegram|linkedin|docker\.com""",
        ignoreCase = true,
    )

    /** 对应 Python `_NOTIFY_PLATFORM`：群发邮件里这些平台发件人更像「通知」。 */
    private val NOTIFY_PLATFORM = PyRegex.compile(
        """notifications?@|notify@|github\.com|gitlab|atlassian|jira|slack\.com|discord|docker\.com""",
        ignoreCase = true,
    )

    /** 对应 Python `_IMPORTANT_STRONG`。 */
    private val IMPORTANT_STRONG = PyRegex.compile(
        """面试|录用|录取|offer\s*letter|合同|签署|法院|传票|税务|海关|签证|入职""" +
            """|visa\s*(appointment|approved|interview)""",
        ignoreCase = true,
    )

    /** 对应 Python `_IMPORTANT_WEAK`。 */
    private val IMPORTANT_WEAK = PyRegex.compile(
        """\boffer\b|deadline|urgent|重要通知|逾期|到期提醒|续签|截止""",
        ignoreCase = true,
    )

    // ---------------- 对外接口 ----------------

    /** 对应 Python `rules.allows_cloud_ai`：验证码/账单/安全/可疑类默认不外送云端 AI。 */
    fun allowsCloudAi(category: String): Boolean = CATEGORY_POLICY[category]?.cloudAi ?: true

    /** 对应 Python `rules.is_bulk`：退订头、邮件列表头、Precedence: bulk、群发平台投递、Feedback-ID。 */
    fun isBulk(signals: Map<String, Boolean>?, hasUnsubscribe: Boolean = false): Boolean =
        hasUnsubscribe ||
            Py.truthy(signals?.get("list_id")) ||
            Py.truthy(signals?.get("precedence_bulk")) ||
            Py.truthy(signals?.get("feedback_id")) ||
            Py.truthy(signals?.get("esp"))

    /**
     * 对应 Python `rules.extract_otp`：主题优先（很多服务把码直接放主题里），
     * 其次正文前 2000 码点。
     */
    fun extractOtp(subject: String, body: String): String {
        val bodyHead = body.cpSlice(0, OTP_BODY_CHARS)
        if (OTP_KEYWORDS.matcher(subject).find()) {
            val fromSubject = pickCode(subject)
            if (fromSubject.isNotEmpty()) return fromSubject
            // 主题带关键词但码在正文
            val fromBody = pickCode(bodyHead)
            if (fromBody.isNotEmpty()) return fromBody
        }
        return pickCode(bodyHead)
    }

    /**
     * 对应 Python `rules.classify_detailed`：返回 {category, otp, confidence, hits}。
     *
     * @param customRules 数据库里用户自定义的规则（已按 priority 排序）。
     * @param signals mail_client.parseMessage 解析出的 header_signals（全为布尔值）。
     */
    fun classifyDetailed(
        subject: String,
        body: String,
        senderAddr: String,
        hasUnsubscribe: Boolean,
        customRules: List<CustomRule>,
        signals: Map<String, Boolean>? = null,
    ): RuleResult {
        // body[:3000]、subject + "\n" + body_head 都按码点算
        val bodyHead = body.cpSlice(0, CLASSIFY_BODY_CHARS)
        val text = subject + "\n" + bodyHead
        val bulk = isBulk(signals, hasUnsubscribe)
        val promo = SUBSCRIBE.matcher(text).find()

        // 1. 用户自定义规则优先
        for (rule in customRules) {
            val target = when (rule.field) {
                "subject" -> subject
                "sender" -> senderAddr
                "body" -> bodyHead
                else -> ""
            }
            // Python 是在 re.search 时编译并抛 re.error，这里编译失败就当这条规则不存在
            val pattern = PyRegex.compileUser(rule.pattern, ignoreCase = true) ?: continue
            if (pattern.matcher(target).find()) {
                val otp = if (rule.category == "验证码") extractOtp(subject, bodyHead) else ""
                return result(rule.category, CONF_USER_RULE, "custom", otp)
            }
        }

        // 2. 验证码：能提取到码才算，防止「验证您的邮箱」类营销误判
        val otp = extractOtp(subject, bodyHead)
        if (otp.isNotEmpty() && OTP_KEYWORDS.matcher(text).find()) {
            return result("验证码", CONF_OTP, "otp", otp)
        }

        // 3. 强重要信号（群发且带营销词时不算，例如「签证办理限时优惠」）
        if (IMPORTANT_STRONG.matcher(subject).find() && !(bulk && promo)) {
            return result("重要", if (bulk) CONF_BULK_STRONG else CONF_STRONG, "important_strong")
        }
        // 4. 安全告警：主题命中
        if (SECURITY.matcher(subject).find()) {
            return result("安全", CONF_STRONG, "security_subject")
        }
        // 5. 账单/交易
        if (BILL.matcher(subject).find()) {
            return result("账单", if (bulk) CONF_MEDIUM else CONF_STRONG, "bill")
        }
        // 6. 发货/物流：不是账单，也不是营销
        if (SHIPPING.matcher(subject).find() && !PAYMENT.matcher(subject).find()) {
            return result("通知", CONF_MEDIUM, "shipping")
        }
        // 7. 安全告警只在正文出现：群发（周刊、营销）里提到 2FA/改密码不算
        if (!bulk && SECURITY.matcher(bodyHead).find()) {
            return result("安全", CONF_WEAK, "security_body")
        }
        // 8. 弱重要信号：非群发、无营销词
        if (IMPORTANT_WEAK.matcher(subject).find() && !bulk && !promo) {
            return result("重要", CONF_WEAK, "important_weak")
        }
        // 9. 群发：开发平台的通知归「通知」，其余归「订阅」
        val bulkHeader = hasUnsubscribe ||
            Py.truthy(signals?.get("list_id")) ||
            Py.truthy(signals?.get("precedence_bulk"))
        if (bulkHeader) {
            if (NOTIFY_PLATFORM.matcher(senderAddr).find() && !promo) {
                return result("通知", CONF_MEDIUM, "bulk_platform")
            }
            return result("订阅", CONF_MEDIUM, "bulk_header")
        }
        if (promo) {
            return result("订阅", CONF_WEAK, "promo_keyword")
        }
        // 10. 机器通知类发件人（0.7 是原实现里的字面量，不是 CONF_* 常量）
        val autoSubmitted = Py.truthy(signals?.get("auto_submitted"))
        if (NOTIFY_SENDER.matcher(senderAddr).find() || autoSubmitted) {
            return result("通知", if (autoSubmitted) CONF_MEDIUM else 0.7, "notify_sender")
        }
        // 11. 交给 AI（或保持「其他」）
        return result("其他", CONF_DEFAULT, "none")
    }

    /**
     * 对应 Python `rules.classify`：返回 (分类, 验证码)；需要置信度时用 [classifyDetailed]。
     */
    fun classify(
        subject: String,
        body: String,
        senderAddr: String,
        hasUnsubscribe: Boolean,
        customRules: List<CustomRule>,
        signals: Map<String, Boolean>? = null,
    ): Pair<String, String> {
        val detailed = classifyDetailed(subject, body, senderAddr, hasUnsubscribe, customRules, signals)
        return detailed.category to detailed.otp
    }

    // ---------------- 内部实现 ----------------

    /** 对应 Python `rules._result`。 */
    private fun result(category: String, confidence: Double, hit: String, otp: String = "") =
        RuleResult(category, otp, confidence, listOf(hit))

    /** 对应 Python `rules._pick_code`：在关键词附近窗口内挑选最像验证码的 token，优先 6 位数字。 */
    private fun pickCode(text: String): String {
        if (text.isEmpty()) return ""
        val candidates = mutableListOf<String>()
        val keyword = OTP_KEYWORDS.matcher(text)
        while (keyword.find()) {
            // 正则下标是 UTF-16 的，先换成码点下标再切片
            val start = text.cpIndex(keyword.start())
            val end = text.cpIndex(keyword.end())
            val window = text.cpSlice(maxOf(0, start - WINDOW_BEFORE), end + WINDOW_AFTER)
            if (PROMO_NEAR.matcher(window).find()) continue
            val numeric = NUM_CODE.matcher(window)
            while (numeric.find()) {
                val tok = numeric.group(1)
                if (BAD_NUMS.matcher(tok).lookingAt()) continue
                if (!isPlausibleCode(window, numeric.start(1), numeric.end(1))) continue
                candidates += tok
            }
            val alnum = ALNUM_CODE.matcher(window)
            while (alnum.find()) {
                if (isPlausibleCode(window, alnum.start(1), alnum.end(1))) candidates += alnum.group(1)
            }
        }
        if (candidates.isEmpty()) return ""
        // 6 位数字 > 4-8 位数字 > 混合码
        for (tok in candidates) {
            if (tok.cpLen() == 6 && tok.isDecimalDigits()) return tok
        }
        for (tok in candidates) {
            if (tok.isDecimalDigits()) return tok
        }
        return candidates[0]
    }

    /**
     * 对应 Python `rules._plausible_code`。start / end 是 window 上的 UTF-16 下标，
     * 前后文切片按码点算（`window[max(0, start-12):start]`、`window[end:end+6]`）。
     */
    private fun isPlausibleCode(window: String, start: Int, end: Int): Boolean {
        val cpStart = window.cpIndex(start)
        val cpEnd = window.cpIndex(end)
        if (NOT_CODE_BEFORE.matcher(window.cpSlice(maxOf(0, cpStart - CODE_CONTEXT_BEFORE), cpStart)).find()) {
            return false
        }
        if (NOT_CODE_AFTER.matcher(window.cpSlice(cpEnd, cpEnd + CODE_CONTEXT_AFTER)).lookingAt()) {
            return false
        }
        return true
    }
}

/**
 * 对应 Python 的 `str.isdigit()`，但只在「token 由 `\d` 或 `[A-Z0-9]` 匹配出来」这个前提下等价：
 * Python 的 `\d` 只匹配 Unicode 十进制数字（Nd），ASCII 字母数字里也只有 0-9 是 Nd，
 * 所以这里只需判断「每个码点都是 Nd」。
 * （`²`（U+00B2）的 isdigit() 是 True 却不是 Nd，但 `\d` 匹配不到它，不会进到 token 里。）
 */
private fun String.isDecimalDigits(): Boolean {
    if (isEmpty()) return false
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        if (Character.getType(cp) != Character.DECIMAL_DIGIT_NUMBER.toInt()) return false
        i += Character.charCount(cp)
    }
    return true
}
