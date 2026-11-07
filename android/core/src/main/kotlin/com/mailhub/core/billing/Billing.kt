package com.mailhub.core.billing

import com.mailhub.core.text.Py
import com.mailhub.core.text.PyRegex
import com.mailhub.core.text.cpIndex
import com.mailhub.core.text.cpLen
import com.mailhub.core.text.cpSlice
import com.mailhub.core.text.pyStrip
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import java.util.regex.Pattern

/** 金额，对应 Python `billing.extract_amount` 返回的 `(金额, 币种)`。两者都可能为 null。 */
data class Amount(val value: Double?, val currency: String?)

/** 定级结果，对应 Python `billing.decide_tier` 返回的 `(等级, 理由)`；理由只用于审计和日志。 */
data class TierDecision(val tier: String, val reason: String)

/**
 * 账单重要性分级：先描述交易本身（交易画像），再按通用策略定级。
 *
 * 逐函数移植 Python `billing.py`（查询数据库的例行次数与用户偏好在 `governance.py`，不在这里）。
 * 画像只用与平台无关的通用措辞识别，策略里不出现任何品牌名或发件域名：
 * - direction  资金方向：out 支出 / in 收入 / refund 退款 / none 无资金 / unknown
 * - initiator  发起方：self 本人主动 / auto 自动续费扣款 / other 他人触发 / unknown
 * - acquired   获得物：entitlement 长期权益 / physical 实物 / service 服务期 /
 *   virtual_item 虚拟物品流转 / balance 余额 / unknown
 * - action     是否需要收件人处理（付款失败、待支付、订阅需要处理……）
 * - amount / currency / cny  金额、币种与粗略折算的人民币
 * - channel    支付渠道：real 真实支付 / balance 平台余额 / unknown
 *
 * 画像用 `Map<String, Any?>` 表示（与 Python 的 dict 一一对应，可以双向写进 JSON），
 * [storedFacets] 的输出会原样写进数据库。金标语料见
 * `android/core/src/test/resources/golden/billing.json`（由 `scripts/golden/billing.py` 导出）。
 */
object Billing {

    /** 对应 Python `TIER_IMPORTANT`。 */
    const val TIER_IMPORTANT: String = "重要"

    /** 对应 Python `TIER_MINOR`。 */
    const val TIER_MINOR: String = "一般"

    /** 对应 Python `TIERS`（顺序有意义：`preference in TIERS` 用它判断合法取值）。 */
    val TIERS: List<String> = listOf(TIER_IMPORTANT, TIER_MINOR)

    /** 对应 Python `DIRECTIONS`。 */
    val DIRECTIONS: List<String> = listOf("out", "in", "refund", "none", "unknown")

    /** 对应 Python `INITIATORS`。 */
    val INITIATORS: List<String> = listOf("self", "auto", "other", "unknown")

    /** 对应 Python `ACQUIRED`。 */
    val ACQUIRED: List<String> = listOf(
        "entitlement", "physical", "service", "virtual_item", "balance", "unknown",
    )

    /** 对应 Python `CHANNELS`。 */
    val CHANNELS: List<String> = listOf("real", "balance", "unknown")

    /**
     * 策略默认值，对应 Python `DEFAULTS`；设置页可以覆盖。
     * `amount_threshold` 折合人民币超过即重要；`routine_count` 同类模板近 30 天达到即视为例行流水。
     */
    val DEFAULTS: Map<String, Any> = linkedMapOf(
        "amount_threshold" to 200.0,
        "routine_count" to 10,
        "auto_renew" to TIER_IMPORTANT,
        "refund" to TIER_MINOR,
    )

    /** 例行流水的统计窗口：30 天。对应 Python `ROUTINE_WINDOW_SECONDS`。 */
    const val ROUTINE_WINDOW_SECONDS: Int = 30 * 86400

    /** 金额上限：超过 1000 万的不参与定级（多半是解析错误）。对应 Python `10_000_000`。 */
    private const val MAX_AMOUNT = 10_000_000.0

    /** 抽取「总计」类提示词时，往前看的码点数。对应 Python 里硬编码的 24。 */
    private const val HINT_WINDOW = 24

    /** 正文只看前 20000 个码点。对应 Python 的 `[:20000]`。 */
    private const val BODY_CHARS = 20000

    /** 模板键里标题截断到 80 个码点，整个键截断到 160 个码点。对应 Python 的 `[:80]`、`[:160]`。 */
    private const val TEMPLATE_SUBJECT_CHARS = 80
    private const val TEMPLATE_KEY_CHARS = 160

    /**
     * 粗略汇率（1 单位外币 ≈ 多少人民币），只用于和阈值比较大小，不追求精确。
     * 对应 Python `_CNY_RATE`。
     */
    private val CNY_RATE: Map<String, Double> = linkedMapOf(
        "CNY" to 1.0, "USD" to 7.2, "EUR" to 7.8, "GBP" to 9.2, "HKD" to 0.92, "TWD" to 0.22,
        "JPY" to 0.048, "KRW" to 0.0052, "SGD" to 5.3, "AUD" to 4.7, "CAD" to 5.2, "UAH" to 0.17,
        "RUB" to 0.08, "INR" to 0.085, "TRY" to 0.21, "BRL" to 1.3, "KZT" to 0.015,
        "PHP" to 0.125, "MXN" to 0.38, "ARS" to 0.0075, "PLN" to 1.8, "CHF" to 8.2,
        "NZD" to 4.3, "THB" to 0.2, "VND" to 0.00028, "IDR" to 0.00045, "MYR" to 1.55,
    )

    // ---------------- 文本特征：正则全部照抄 billing.py ----------------

    /** 对应 Python `_ACTION`：需要收件人处理的措辞。 */
    private val ACTION = PyRegex.compile(
        """付款失败|支付失败|扣款失败|交易失败|续费失败|未能(扣款|支付|续费)|待支付|待付款|未支付|请尽快(支付|付款)""" +
            """|即将(扣费|扣款|续费|到期)|将(自动)?(扣费|扣款)|欠费|逾期|需要(您)?处理|请更新(您的)?(付款|支付)方式""" +
            """|payment\s+(failed|declined|unsuccessful)|(card|charge)\s+(was\s+)?declined|past\s+due|overdue""" +
            """|action\s+required|update\s+(your\s+)?payment|unable\s+to\s+(process|charge)""" +
            """|requires?\s+(your\s+)?attention|will\s+be\s+charged|upcoming\s+(charge|payment|renewal)""",
        ignoreCase = true,
    )

    /** 对应 Python `_REFUND`：标题里的退款。 */
    private val REFUND = PyRegex.compile("""退款|已退还|退回|refund(ed)?|reimburse""", ignoreCase = true)

    /** 对应 Python `_REFUND_BODY`：正文里只认「已经发生的退款」，不认「了解退款政策」一类页脚。 */
    private val REFUND_BODY = PyRegex.compile(
        """已(将款项)?退(款|还|回)|退款(已|成功|总计|金额)|退还(到|至)|款项已退""" +
            """|refund\s+(has\s+been|was|is)\s+(issued|processed|approved|completed)""" +
            """|(we('ve| have)|has\s+been)\s+refunded|refunded\s+to""",
        ignoreCase = true,
    )

    /** 对应 Python `_IN_SUBJECT`：标题里的收入。 */
    private val IN_SUBJECT = PyRegex.compile(
        """售出|卖出|已获得|收到.{0,4}(款|付款|转账)|到账|入账|收款|返现|奖励金""" +
            """|\bsold\b|payout|you('ve| have)?\s+(received|earned)|credited|cash\s*back""",
        ignoreCase = true,
    )

    /** 对应 Python `_OUT_SUBJECT`：标题里的支出。 */
    private val OUT_SUBJECT = PyRegex.compile(
        """购买|购物|订单|收据|付款|支付|扣款|扣费|充值|续费|续订|账单|发票|消费""" +
            """|receipt|purchase|\border|payment|charged|invoice|\bbill\b|renew|top[- ]?up""",
        ignoreCase = true,
    )

    /** 对应 Python `_OUT_BODY`：正文里只认「强」证据，避免页脚模板词（消费记录、获得积分）误导方向。 */
    private val OUT_BODY = PyRegex.compile(
        """共计花费|总计花费|实付|实际支付|已支付|已付款|支付金额|付款金额|扣款金额|订单(总额|金额)|支付方式|付款方式""" +
            """|amount\s+paid|total\s+charged|you\s+paid|payment\s+method|order\s+total|charged\s+to""",
        ignoreCase = true,
    )

    /** 对应 Python `_IN_BODY`。 */
    private val IN_BODY = PyRegex.compile(
        """(钱包|余额|账户)已?(获得|收到|增加)|已到账|已入账|收款金额|已售出|已经售出""" +
            """|you\s+(received|earned)|has\s+been\s+credited|credited\s+to|payout|\bsold\b""",
        ignoreCase = true,
    )

    /** 对应 Python `_FREE`。 */
    private val FREE = PyRegex.compile(
        """免费|零元|free\s+(items?|games?|gift|trial)|at\s+no\s+cost""",
        ignoreCase = true,
    )

    /** 对应 Python `_OTHER`：他人购买（售出、买家）。 */
    private val OTHER = PyRegex.compile(
        """售出|卖出|买家|购买了您的|\bsold\b|buyer|purchased\s+your""",
        ignoreCase = true,
    )

    /** 对应 Python `_AUTO`：自动续费、定期扣款。 */
    private val AUTO = PyRegex.compile(
        """自动续费|自动扣费|自动续订|续订成功|续费成功|已续订|已续费|定期扣款|代扣""" +
            """|auto[- ]?renew|renewed|recurring|subscription\s+(renewal|renewed|charge)""",
        ignoreCase = true,
    )

    /** 对应 Python `_SELF`：本人主动下单。 */
    private val SELF = PyRegex.compile(
        """购买|下单|您的订单|订购|充值|感谢.{0,8}(购买|订购|惠顾|交易)""" +
            """|thank\s+you\s+for\s+your\s+(\S+\s+){0,3}(purchase|order)|your\s+(order|purchase)""" +
            """|you\s+(bought|purchased|ordered)|order\s+(confirm|placed)|receipt\s+(for|from)|top[- ]?up""",
        ignoreCase = true,
    )

    /** 对应 Python `_ENTITLEMENT`：长期权益（游戏、软件授权）。 */
    private val ENTITLEMENT = PyRegex.compile(
        """(添加|加入)到.{0,12}(库|账户|帐户)|激活码|序列号|产品密钥|授权|许可证|兑换码|已准备就绪""" +
            """|added\s+to\s+your\s+(\S+\s+){0,2}(library|account|collection)|activation\s+key""" +
            """|product\s+key|license|game\s+key|ready\s+to\s+(play|download)""",
        ignoreCase = true,
    )

    /** 对应 Python `_PHYSICAL`：实物商品。 */
    private val PHYSICAL = PyRegex.compile(
        """发货|配送|收货(地址|人)|快递|物流|shipping|deliver(y|ed)|shipped""",
        ignoreCase = true,
    )

    /** 对应 Python `_SERVICE`：服务期、会员、订阅。 */
    private val SERVICE = PyRegex.compile(
        """会员|订阅|套餐|服务期|有效期至|subscription|membership|premium|\bplan\b""",
        ignoreCase = true,
    )

    /** 对应 Python `_VIRTUAL`：只认市场买卖和游戏内道具的说法（「物品」本身太宽泛）。 */
    private val VIRTUAL = PyRegex.compile(
        """饰品|道具|皮肤|卡牌|社区市场|交易市场|物品已售出|上架的.{0,4}物品""" +
            """|in-game\s+(item|currency)|marketplace|community\s+market|items?\s+(you\s+)?(listed|sold)""" +
            """|trading\s+card|\bskins?\b""",
        ignoreCase = true,
    )

    /** 对应 Python `_BALANCE`：余额、充值。 */
    private val BALANCE = PyRegex.compile(
        """充值|余额|钱包|wallet|balance|top[- ]?up|add(ed)?\s+funds""",
        ignoreCase = true,
    )

    /** 对应 Python `_CHANNEL_BALANCE`：支付方式里写明用余额/礼品卡。 */
    private val CHANNEL_BALANCE = PyRegex.compile(
        """(支付方式|付款方式|payment\s+method|paid\s+(with|using))[^。\n]{0,20}""" +
            """(钱包|余额|礼品卡|点数|wallet|balance|gift\s*card|credits?)""",
        ignoreCase = true,
    )

    /** 对应 Python `_CHANNEL_REAL`：真实支付渠道。 */
    private val CHANNEL_REAL = PyRegex.compile(
        """信用卡|借记卡|银行卡|储蓄卡|支付宝|微信支付|云闪付|银联|paypal|visa|mastercard|amex|apple\s*pay""" +
            """|google\s*pay|card\s+ending|ending\s+in\s+\d{4}|尾号\s*\d{4}""",
        ignoreCase = true,
    )

    // ---------------- 金额 ----------------

    /** 对应 Python `_SYMBOLS`：货币符号与中文币种名 → 币种代码。 */
    private val SYMBOLS: Map<String, String> = linkedMapOf(
        "US$" to "USD", "HK$" to "HKD", "NT$" to "TWD", "A$" to "AUD", "C$" to "CAD", "S$" to "SGD",
        "R$" to "BRL", "¥" to "CNY", "￥" to "CNY", "$" to "USD", "€" to "EUR", "£" to "GBP",
        "₴" to "UAH", "₽" to "RUB", "₹" to "INR", "₩" to "KRW", "₺" to "TRY", "₸" to "KZT",
        "₱" to "PHP", "元" to "CNY", "人民币" to "CNY", "美元" to "USD", "欧元" to "EUR",
        "英镑" to "GBP", "港币" to "HKD", "日元" to "JPY",
    )

    /**
     * 对应 Python `_CODES`：`"|".join(sorted(_CNY_RATE, key=len, reverse=True)) + "|RMB|UAH"`。
     * 都是三个字母，所以排序结果就是字典的插入顺序；RMB 不在汇率表里，单独补在最后。
     */
    private val CODES: String =
        CNY_RATE.keys.sortedByDescending { it.cpLen() }.joinToString("|") + "|RMB|UAH"

    /** 对应 Python `_SYM`：按长度降序（稳定排序）拼接转义后的货币符号，长符号优先匹配。 */
    private val SYM: String =
        SYMBOLS.keys.sortedByDescending { it.cpLen() }.joinToString("|") { PyRegex.escape(it) }

    /** 对应 Python `_NUM`：带千分位的数字，或普通数字。 */
    private const val NUM: String =
        """\d{1,3}(?:[.,\s]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""

    /** 对应 Python `_AMOUNT`：符号/代码 + 数字，或数字 + 符号/代码。 */
    private val AMOUNT: Pattern = PyRegex.compile(
        "(?P<pre>$SYM|\\b(?:$CODES)\\b)\\s?(?P<n1>$NUM)" +
            "|(?P<n2>$NUM)\\s?(?P<post>$SYM|\\b(?:$CODES)\\b)",
    )

    /** 对应 Python `_CODE_AFTER`。 */
    private val CODE_AFTER: Pattern = PyRegex.compile("""\s?\b($CODES)\b""")

    /** 对应 Python `_TOTAL_HINT`：金额前面的「总计」类提示词。 */
    private val TOTAL_HINT: Pattern = PyRegex.compile(
        """总计|合计|共计|实付|花费|总额|金额|应付|退款总计|total|amount|charged|paid|grand""",
        ignoreCase = true,
    )

    /** 对应 Python `_to_float` 里的 `re.search(r"[.,](\d{1,2})$", s)`。 */
    private val DECIMAL_TAIL: Pattern = PyRegex.compile("""[.,](\d{1,2})$""")

    /** 对应 Python `_to_float` 里的 `re.sub(r"[.,]", "", whole)`。 */
    private val SEPARATORS: Pattern = PyRegex.compile("""[.,]""")

    /**
     * 对应 Python `extract_amount`：返回 (金额, 币种)。
     * 优先取「总计/实付/total」附近的金额，否则取最大的一个（按折合人民币比大小）。
     */
    fun extractAmount(text: String): Amount {
        var bestHint: Money? = null
        var bestAny: Money? = null
        var prevEnd = 0
        val matcher = AMOUNT.matcher(text)
        while (matcher.find()) {
            // 正则下标是 UTF-16 的，切片按码点，所以先换算
            val start = text.cpIndex(matcher.start())
            val end = text.cpIndex(matcher.end())
            // 关键词必须紧挨在这个金额前面（中间没有别的金额），否则「总计 12 元，赠送 500 元」会误取 500
            val window = text.cpSlice(maxOf(prevEnd, start - HINT_WINDOW), start)
            prevEnd = end
            val raw = matcher.group("n1") ?: matcher.group("n2")
            val pre = matcher.group("pre")
            var cur = currency(pre ?: matcher.group("post") ?: "")
            // 「$88.00 HKD」：通用的 $ 符号后面跟着明确的币种代码时，以代码为准
            if (pre == "$" || pre == "¥" || pre == "￥") {
                val suffix = codeAfter(text, matcher.end())
                if (suffix != null) cur = currency(suffix)
            }
            val value = toFloat(raw ?: "") ?: continue
            if (value > MAX_AMOUNT || cur !in CNY_RATE) continue
            val item = Money(value * CNY_RATE.getValue(cur), value, cur)
            if (bestAny == null || item > bestAny) bestAny = item
            if (TOTAL_HINT.matcher(window).find()) {
                if (bestHint == null || item > bestHint) bestHint = item
            }
        }
        val pick = bestHint ?: bestAny ?: return Amount(null, null)
        return Amount(pick.value, pick.currency)
    }

    /**
     * 对应 Python `to_cny`：粗略折算人民币并保留两位小数；无法折算时返回 null。
     * 乘法的顺序与 Python 相同（`amount * _CNY_RATE[currency]`），结果按 == 精确比较。
     */
    fun toCny(amount: Double?, currency: String?): Double? {
        if (amount == null || currency.isNullOrEmpty() || currency !in CNY_RATE) return null
        return Py.round(amount * CNY_RATE.getValue(currency), 2)
    }

    // ---------------- 交易画像 ----------------

    /**
     * 对应 Python `extract_facets`：从标题和正文抽取交易画像。
     * 正文应已是可读文本（HTML 邮件先转成文字），只取前 20000 个码点。
     */
    fun extractFacets(subject: String?, body: String?): Map<String, Any?> {
        val subjectText = subject ?: ""
        val bodyText = (body ?: "").cpSlice(0, BODY_CHARS)
        val text = subjectText + "\n" + bodyText
        val amount = extractAmount(text)
        val direction = direction(subjectText, bodyText, amount.value)
        val initiator = if (direction == "in" || OTHER.matcher(subjectText).find()) {
            if (OTHER.matcher(text).find()) "other" else "unknown"
        } else {
            firstFacet(
                text,
                listOf("auto" to AUTO, "self" to SELF),
            )
        }
        val acquired = firstFacet(
            text,
            listOf(
                "entitlement" to ENTITLEMENT,
                "physical" to PHYSICAL,
                "service" to SERVICE,
                "virtual_item" to VIRTUAL,
                "balance" to BALANCE,
            ),
        )
        val channel = when {
            CHANNEL_BALANCE.matcher(text).find() -> "balance"
            CHANNEL_REAL.matcher(text).find() -> "real"
            else -> "unknown"
        }
        return linkedMapOf(
            "direction" to direction,
            "initiator" to initiator,
            "acquired" to acquired,
            "action" to ACTION.matcher(text).find(),
            "amount" to amount.value,
            "currency" to amount.currency,
            "cny" to toCny(amount.value, amount.currency),
            "channel" to channel,
        )
    }

    /**
     * 对应 Python `merge_ai_facets`：本地抽取优先；AI 只填补本地识别为 unknown 的维度（金额只信本地）。
     * `aiFacets` 不是字典（含 null）时原样返回本地画像。
     */
    fun mergeAiFacets(local: Map<String, Any?>, aiFacets: Any?): Map<String, Any?> {
        val merged = LinkedHashMap(local)
        val ai = aiFacets as? Map<*, *> ?: return merged
        val dimensions = listOf(
            "direction" to DIRECTIONS,
            "initiator" to INITIATORS,
            "acquired" to ACQUIRED,
        )
        for ((key, allowed) in dimensions) {
            val value = ai[key]
            if (merged[key] == "unknown" && value is String && value in allowed && value != "unknown") {
                merged[key] = value
                aiFilled(merged).add(key)
            }
        }
        if (ai["action"] == true && !Py.truthy(merged["action"])) {
            merged["action"] = true
            aiFilled(merged).add("action")
        }
        return merged
    }

    // ---------------- 模板与例行程度 ----------------

    /** 对应 Python `_MONTHS`：英文月份与星期的各种写法。 */
    private val MONTHS = PyRegex.compile(
        """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\b""" +
            """|\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\b""",
        ignoreCase = true,
    )

    /** 对应 Python `template_key` 里去掉括号内容的正则：`[\[【(（][^\]】)）]{0,40}[\]】)）]`。 */
    private val BRACKETED = PyRegex.compile("""[\[【(（][^\]】)）]{0,40}[\]】)）]""")

    /** 对应 Python `template_key` 里的 `re.sub(r"\d+", "#", s)`。 */
    private val DIGITS = PyRegex.compile("""\d+""")

    /**
     * 对应 Python `template_key` 里的 `re.sub(r"[^\w#<>一-鿿]+", " ", s)`。
     * `\w` 由 [PyRegex] 展开成 Python 定义的单词字符（含全角字母和汉字）。
     */
    private val NON_WORD = PyRegex.compile("""[^\w#<>一-鿿]+""")

    /** 对应 Python `template_key` 里的 `re.sub(r"\s+", " ", s)`。 */
    private val SPACES = PyRegex.compile("""\s+""")

    /**
     * 对应 Python `template_key`：发件域名 + 去掉数字、日期、编号后的标题，用来识别「同一类邮件」。
     * 标题截断到 80 个码点，整个键截断到 160 个码点。
     */
    fun templateKey(senderAddr: String?, subject: String?): String {
        // Python 是 (sender_addr or "").rsplit("@", 1)[-1]，即最后一个 @ 之后的部分
        val addr = senderAddr ?: ""
        val at = addr.lastIndexOf('@')
        val domain = (if (at >= 0) addr.substring(at + 1) else addr).pyStrip().lowercase(Locale.ROOT)
        var s = (subject ?: "").lowercase(Locale.ROOT)
        s = BRACKETED.matcher(s).replaceAll(" ")   // 去掉括号里的编号、日期
        s = MONTHS.matcher(s).replaceAll("<m>")
        s = DIGITS.matcher(s).replaceAll("#")
        s = NON_WORD.matcher(s).replaceAll(" ")
        s = SPACES.matcher(s).replaceAll(" ")
        s = s.pyStrip().cpSlice(0, TEMPLATE_SUBJECT_CHARS)
        return "$domain|$s".cpSlice(0, TEMPLATE_KEY_CHARS)
    }

    // ---------------- 定级策略 ----------------

    /** 对应 Python `_config`：把设置里的覆盖值并到默认值上，非法值回退默认。 */
    private data class TierConfig(
        val amountThreshold: Double,
        val routineCount: Long,
        val autoRenew: String,
        val refund: String,
    )

    /**
     * 对应 Python `decide_tier`：按顺序套用策略，命中即停。返回 (等级, 理由)；理由只用于审计和日志。
     *
     * @param routineCount 同类模板近 30 天的出现次数（查询在 `governance.py`）。
     * @param preference 用户对同类邮件的标记，取值是 [TIERS] 之一时优先于一切策略。
     * @param cfg 设置覆盖值，值可能是字符串、整数或浮点数。
     */
    fun decideTier(
        facets: Map<String, Any?>,
        routineCount: Long = 0,
        preference: String = "",
        cfg: Map<String, Any?>? = null,
    ): TierDecision {
        val c = config(cfg)
        val direction = facets["direction"]
        val initiator = facets["initiator"]
        val acquired = facets["acquired"]
        val cny = facets["cny"]
        val cnyValue = (cny as? Number)?.toDouble()

        if (preference in TIERS) return TierDecision(preference, "按用户对同类邮件的标记")
        if (Py.truthy(facets["action"])) {
            return TierDecision(TIER_IMPORTANT, "需要处理（付款失败、待支付或即将扣费等）")
        }
        if (cny != null && c.amountThreshold > 0 && cnyValue != null &&
            cnyValue >= c.amountThreshold && direction != "none"
        ) {
            return TierDecision(
                TIER_IMPORTANT,
                "金额约 ¥${formatPyG(cnyValue)}，不低于阈值 ¥${formatPyG(c.amountThreshold)}",
            )
        }
        if (direction == "out" && initiator == "self") {
            if (acquired == "virtual_item" && facets["channel"] == "balance") {
                return TierDecision(TIER_MINOR, "用平台余额买卖虚拟物品，属于余额内流转")
            }
            val label = (acquired as? String)?.let { ACQUIRED_LABEL[it] } ?: "商品或服务"
            return TierDecision(TIER_IMPORTANT, "本人主动支出，获得$label")
        }
        if (direction == "out" && initiator == "auto") {
            return TierDecision(c.autoRenew, "自动续费扣款（按设置）")
        }
        if (direction == "refund") return TierDecision(c.refund, "退款（按设置）")
        if (direction == "in") return TierDecision(TIER_MINOR, "收入或到账，不需要处理")
        if (direction == "none") return TierDecision(TIER_MINOR, "零金额（免费领取等）")
        if (acquired == "virtual_item" && facets["channel"] == "balance") {
            return TierDecision(TIER_MINOR, "平台余额内的虚拟物品流转")
        }
        if (c.routineCount > 0 && routineCount >= c.routineCount) {
            return TierDecision(TIER_MINOR, "同类邮件近 30 天 $routineCount 封，属于例行流水")
        }
        return TierDecision(TIER_IMPORTANT, "信息不足，保守视为重要")
    }

    /** 对应 Python `_ACQUIRED_LABEL`：定级理由里对获得物的中文说法。 */
    private val ACQUIRED_LABEL: Map<String, String> = linkedMapOf(
        "entitlement" to "长期权益",
        "physical" to "实物商品",
        "service" to "服务或会员",
        "balance" to "账户余额",
        "virtual_item" to "虚拟物品",
    )

    /**
     * 对应 Python `tier_importance`：重要度随等级调整。
     * 需要处理的账单升到 4（会推送），一般账单不超过 2。
     */
    fun tierImportance(tier: String, facets: Map<String, Any?>, current: Int): Int {
        if (tier == TIER_MINOR) return minOf(if (current != 0) current else 2, 2)
        if (Py.truthy(facets["action"])) return maxOf(if (current != 0) current else 0, 4)
        return maxOf(if (current != 0) current else 0, 3)
    }

    /** 对应 Python `stored_facets`：写入数据库的画像，只含枚举、布尔和金额，不含任何原文。 */
    fun storedFacets(facets: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "direction" to facets.getOrDefault("direction", "unknown"),
        "initiator" to facets.getOrDefault("initiator", "unknown"),
        "acquired" to facets.getOrDefault("acquired", "unknown"),
        "action" to Py.truthy(facets["action"]),
        "channel" to facets.getOrDefault("channel", "unknown"),
        "amount" to facets["amount"],
        "currency" to facets["currency"],
        "cny" to facets["cny"],
    )

    // ---------------- 内部实现 ----------------

    /** 元组比较用的 `(折合人民币, 金额, 币种)`；对应 Python 里 `item > best_any` 的字典序比较。 */
    private data class Money(val cny: Double, val value: Double, val currency: String) :
        Comparable<Money> {
        override fun compareTo(other: Money): Int {
            cny.compareTo(other.cny).let { if (it != 0) return it }
            value.compareTo(other.value).let { if (it != 0) return it }
            // 币种代码都是 ASCII，Kotlin 的字符串比较与 Python 的码点序一致
            return currency.compareTo(other.currency)
        }
    }

    /** 对应 Python `_to_float`：兼容 `1,299.00` / `1.299,00` / `1 299,00` / `12,5` 等写法。 */
    private fun toFloat(raw: String): Double? {
        // Python 只去掉 ASCII 空格和 &nbsp;，所以细空格、全角空格会让 float() 失败
        val s = raw.replace(" ", "").replace("\u00a0", "")
        val matcher = DECIMAL_TAIL.matcher(s)
        val wholeRaw: String
        val frac: String
        if (matcher.find()) {
            wholeRaw = s.cpSlice(0, s.cpIndex(matcher.start()))
            frac = matcher.group(1)
        } else {
            wholeRaw = s
            frac = ""
        }
        val whole = SEPARATORS.matcher(wholeRaw).replaceAll("")
        return Py.float(if (frac.isNotEmpty()) "$whole.$frac" else whole)
    }

    /** 对应 Python `_currency`。 */
    private fun currency(token: String): String {
        val t = token.pyStrip()
        if (t.uppercase(Locale.ROOT) == "RMB") return "CNY"
        return SYMBOLS[t] ?: t.uppercase(Locale.ROOT)
    }

    /**
     * 对应 Python 的 `_CODE_AFTER.match(text, pos)`：带 pos 的匹配仍然能看到 pos 之前的字符
     * （`\b` 要看前一个字符），所以用 region + 透明边界模拟。
     * 返回匹配到的币种代码，没匹配上返回 null。
     */
    private fun codeAfter(text: String, utf16Pos: Int): String? {
        val matcher = CODE_AFTER.matcher(text)
        matcher.region(utf16Pos, text.length)
        matcher.useTransparentBounds(true)
        matcher.useAnchoringBounds(false)
        return if (matcher.lookingAt()) matcher.group(1) else null
    }

    /**
     * 对应 Python 的 `merged.setdefault("ai_filled", []).append(key)`：
     * 没有这个键就先放一个空列表，再往同一份列表里追加（与 Python 一样复用已存在的列表）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun aiFilled(merged: MutableMap<String, Any?>): MutableList<Any?> =
        merged.getOrPut("ai_filled") { mutableListOf<Any?>() } as MutableList<Any?>

    /** 对应 Python `_first`：按顺序找第一个命中的枚举值。 */
    private fun firstFacet(
        text: String,
        ordered: List<Pair<String, Pattern>>,
        default: String = "unknown",
    ): String {
        for ((value, pattern) in ordered) {
            if (pattern.matcher(text).find()) return value
        }
        return default
    }

    /** 对应 Python `_direction`：标题是邮件的主旨，优先按标题判断；退款 > 收入 > 支出。 */
    private fun direction(subject: String, body: String, amount: Double?): String {
        if (REFUND.matcher(subject).find()) return "refund"
        if (IN_SUBJECT.matcher(subject).find()) return "in"
        if (FREE.matcher(subject).find() || (amount != null && amount == 0.0)) return "none"
        if (OUT_SUBJECT.matcher(subject).find()) {
            // 标题像支出，但正文只有收入证据（例如「订单收款通知」）时以正文为准
            if (IN_BODY.matcher(body).find() && !OUT_BODY.matcher(body).find()) return "in"
            return "out"
        }
        if (REFUND_BODY.matcher(body).find()) return "refund"
        val outs = countMatches(OUT_BODY, body)
        val ins = countMatches(IN_BODY, body)
        if (outs > ins) return "out"
        if (ins > outs) return "in"
        if (FREE.matcher(body).find()) return "none"
        return "unknown"
    }

    /** 对应 Python 的 `len(pattern.findall(text))`：非重叠匹配的个数。 */
    private fun countMatches(pattern: Pattern, text: String): Int {
        val matcher = pattern.matcher(text)
        var count = 0
        while (matcher.find()) count++
        return count
    }

    /**
     * 对应 Python `_config`：`value not in (None, "")` 才覆盖默认值；
     * `float()` / `int()` 失败时回退默认值；等级取值不在 [TIERS] 里也回退。
     */
    private fun config(cfg: Map<String, Any?>?): TierConfig {
        val merged = LinkedHashMap<String, Any?>(DEFAULTS)
        cfg?.forEach { (key, value) ->
            // Python 的 `value not in (None, "")` 是 == 比较：0、False、空列表都算合法覆盖值
            if (key in merged && value != null && value != "") merged[key] = value
        }
        val threshold = asDouble(merged["amount_threshold"])
            ?: (DEFAULTS["amount_threshold"] as Double)
        val routine = asLong(merged["routine_count"])
            ?: (DEFAULTS["routine_count"] as Int).toLong()
        fun tierOrDefault(key: String): String {
            val value = merged[key]
            return if (value is String && value in TIERS) value else DEFAULTS.getValue(key) as String
        }
        return TierConfig(threshold, routine, tierOrDefault("auto_renew"), tierOrDefault("refund"))
    }

    /** 对应 Python 的 `float(value)`：失败（TypeError / ValueError）返回 null。 */
    private fun asDouble(value: Any?): Double? = when (value) {
        is Boolean -> if (value) 1.0 else 0.0
        is Double -> value
        is Float -> value.toDouble()
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        is java.math.BigInteger -> value.toDouble()
        is String -> Py.float(value)
        else -> null
    }

    /** 对应 Python 的 `int(value)`：失败（TypeError / ValueError）返回 null；小数向零截断。 */
    private fun asLong(value: Any?): Long? = when (value) {
        is Boolean -> if (value) 1L else 0L
        is Double -> value.toLong()
        is Float -> value.toDouble().toLong()
        is Long -> value
        is Int -> value.toLong()
        is java.math.BigInteger -> value.toLong()
        is String -> Py.int(value)?.toLong()
        else -> null
    }
}

/**
 * Python 的 `f"{value:g}"`（默认 6 位有效数字）：按二进制精确值四舍六入五成双，
 * 去掉尾随的零；指数小于 -4 或不小于精度时改用科学计数法，指数至少两位。
 *
 * Py.kt 里没有对应工具（`formatFixed` 是 `f"{x:.2f}"`），所以放在这个包里，
 * 只用于拼定级理由里的金额文案。
 */
private fun formatPyG(value: Double, precision: Int = 6): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    if (value == 0.0) return if (negative) "-0" else "0"
    val rounded = BigDecimal(value).abs().round(MathContext(precision, RoundingMode.HALF_EVEN))
    // 最高有效位对应的 10 的幂
    val exponent = rounded.precision() - rounded.scale() - 1
    val body = if (exponent < -4 || exponent >= precision) {
        // 科学计数法：尾数补齐到 precision 位有效数字后去掉尾随的零
        val digits = rounded.unscaledValue().toString().padEnd(precision, '0').trimEnd('0')
            .ifEmpty { "0" }
        val fraction = if (digits.length > 1) "." + digits.substring(1) else ""
        val sign = if (exponent < 0) "-" else "+"
        "${digits.substring(0, 1)}$fraction" + "e" + sign + Math.abs(exponent).toString().padStart(2, '0')
    } else {
        val fractionDigits = precision - 1 - exponent
        val text = rounded.setScale(fractionDigits, RoundingMode.HALF_EVEN).toPlainString()
            .trimEnd('0')
        if (text.endsWith('.')) text.dropLast(1) else text
    }
    return if (negative) "-$body" else body
}
