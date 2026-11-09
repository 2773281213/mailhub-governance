package com.mailhub.core.billing

import java.io.File
import java.math.BigDecimal
import java.math.BigInteger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手写的可读用例：挑几条最能说明契约的输入，逐条写清期望。
 * 与 Python 的逐字一致性由 [BillingGoldenTest] 保证，这里只保证实现意图没跑偏。
 */
class BillingTest {

    @Test
    fun amountPrefersTheOneNearTotalHint() {
        // 「赠送 500 元代金券」离「总计」很远，不能被当成账单金额
        val amount = Billing.extractAmount("小计 10 元，总计 12 元，赠送 500 元代金券")
        assertEquals(12.0, amount.value!!, 0.0)
        assertEquals("CNY", amount.currency)

        // $ 后面跟着明确的币种代码时以代码为准
        assertEquals(Amount(25.0, "USD"), Billing.extractAmount("Total: $25.00 USD"))
    }

    @Test
    fun amountAcceptsFullWidthDigitsAndEuropeanStyle() {
        assertEquals(Amount(99.0, "CNY"), Billing.extractAmount("￥９９"))
        assertEquals(Amount(1299.0, "EUR"), Billing.extractAmount("Order total: €1.299,00"))
        assertEquals(Amount(12.5, "EUR"), Billing.extractAmount("Total: 12,5 €"))
    }

    @Test
    fun amountSkipsHugeAndUnknownCurrency() {
        assertEquals(Amount(null, null), Billing.extractAmount("Total: ¥12,000,000.00"))
        assertEquals(Amount(null, null), Billing.extractAmount("Total: 100 XYZ"))
    }

    @Test
    fun toCnyRoundsToTwoDecimals() {
        assertEquals(720.0, Billing.toCny(100.0, "USD")!!, 0.0)
        assertEquals(8.33, Billing.toCny(49.0, "UAH")!!, 0.0)
        assertNull(Billing.toCny(null, "USD"))
        assertNull(Billing.toCny(100.0, "XYZ"))
        assertNull(Billing.toCny(100.0, ""))
    }

    @Test
    fun templateKeyGroupsSameKindOfMail() {
        val a = Billing.templateKey("noreply@shop.example", "Your Order Receipt from Oct 23, 2026")
        val b = Billing.templateKey("NoReply@SHOP.EXAMPLE", "Your Order Receipt from Sep 3, 2026")
        val other = Billing.templateKey("other@mail.example", "Your Order Receipt from Oct 23, 2026")
        assertEquals(a, b)
        assertEquals("mail.example|your order receipt from <m> # #", other)
        assertTrue(a.startsWith("shop.example|"))
    }

    @Test
    fun facetsCoverDirectionAndAcquired() {
        val sale = Billing.extractFacets(
            "您在社区市场上售出了一件物品",
            "物品已售出。您的钱包已获得 4.07 UAH。总计： 4.07 UAH",
        )
        assertEquals("in", sale["direction"])
        assertEquals("other", sale["initiator"])
        assertEquals(4.07, sale["amount"])
        assertEquals("UAH", sale["currency"])

        val purchase = Billing.extractFacets(
            "感谢您在商店的购买！",
            "以下物品已添加到您的游戏库。共计花费： 49₴",
        )
        assertEquals("out", purchase["direction"])
        assertEquals("self", purchase["initiator"])
        assertEquals("entitlement", purchase["acquired"])
    }

    @Test
    fun actionRequiredBillIsImportant() {
        val facets = Billing.extractFacets(
            "Payment failed for your subscription",
            "We were unable to charge your card ending in 4242.",
        )
        assertEquals(true, facets["action"])
        assertEquals(Billing.TIER_IMPORTANT, Billing.decideTier(facets).tier)
    }

    @Test
    fun tierPolicyCanBeOverriddenByConfig() {
        val expense = mapOf<String, Any?>("direction" to "out", "initiator" to "self", "cny" to 10)
        // 用户偏好优先于一切
        assertEquals(Billing.TIER_MINOR, Billing.decideTier(expense, preference = "一般").tier)
        // 金额阈值：字符串、整数都能覆盖，非法值回退默认 200
        val big = mapOf<String, Any?>("direction" to "in", "cny" to 500)
        assertEquals(Billing.TIER_IMPORTANT, Billing.decideTier(big).tier)
        assertEquals(
            Billing.TIER_MINOR,
            Billing.decideTier(big, cfg = mapOf("amount_threshold" to 0)).tier,
        )
        assertEquals(
            Billing.TIER_MINOR,
            Billing.decideTier(big, cfg = mapOf("amount_threshold" to "900")).tier,
        )
        assertEquals(
            Billing.TIER_IMPORTANT,
            Billing.decideTier(big, cfg = mapOf("amount_threshold" to "abc")).tier,
        )
        // 自动续费与退款按设置
        val renew = mapOf<String, Any?>("direction" to "out", "initiator" to "auto", "cny" to 10)
        assertEquals(
            Billing.TIER_MINOR,
            Billing.decideTier(renew, cfg = mapOf("auto_renew" to "一般")).tier,
        )
    }

    @Test
    fun routineCountBoundary() {
        val unknown = mapOf<String, Any?>("direction" to "unknown")
        assertEquals(Billing.TIER_IMPORTANT, Billing.decideTier(unknown, routineCount = 9).tier)
        assertEquals(Billing.TIER_MINOR, Billing.decideTier(unknown, routineCount = 10).tier)
        assertEquals(Billing.TIER_IMPORTANT, Billing.decideTier(unknown, routineCount = 0).tier)
        // routine_count 设成 0 等于关闭「例行流水」降级
        assertEquals(
            Billing.TIER_IMPORTANT,
            Billing.decideTier(unknown, routineCount = 99, cfg = mapOf("routine_count" to 0)).tier,
        )
    }

    @Test
    fun tierReasonMentionsAmountAndThresholdWithPythonGFormat() {
        val big = mapOf<String, Any?>("direction" to "out", "cny" to 9216.0)
        assertEquals("金额约 ¥9216，不低于阈值 ¥200", Billing.decideTier(big).reason)
        val huge = mapOf<String, Any?>("direction" to "out", "cny" to 12345678.0)
        assertEquals("金额约 ¥1.23457e+07，不低于阈值 ¥200", Billing.decideTier(huge).reason)
    }

    @Test
    fun aiOnlyFillsUnknownDimensions() {
        val local = mapOf<String, Any?>(
            "direction" to "out",
            "initiator" to "unknown",
            "acquired" to "unknown",
            "action" to false,
        )
        val merged = Billing.mergeAiFacets(
            local,
            mapOf("direction" to "in", "initiator" to "self", "acquired" to "bogus", "action" to true),
        )
        assertEquals("out", merged["direction"])
        assertEquals("self", merged["initiator"])
        assertEquals("unknown", merged["acquired"])
        assertEquals(true, merged["action"])
        assertEquals(listOf("initiator", "action"), merged["ai_filled"])
    }

    @Test
    fun storedFacetsContainNoRawText() {
        val facets = Billing.extractFacets("您的订单收据", "已支付 39 元，支付方式：银行卡")
        val stored = Billing.storedFacets(facets)
        assertEquals(
            setOf("direction", "initiator", "acquired", "action", "channel", "amount", "currency", "cny"),
            stored.keys,
        )
        assertFalse(stored.toString().contains("银行卡"))
        assertEquals("real", stored["channel"])
    }

    @Test
    fun importanceFollowsTierAndAction() {
        assertEquals(2, Billing.tierImportance(Billing.TIER_MINOR, emptyMap(), 0))
        assertEquals(2, Billing.tierImportance(Billing.TIER_MINOR, emptyMap(), 5))
        assertEquals(3, Billing.tierImportance(Billing.TIER_IMPORTANT, emptyMap(), 0))
        assertEquals(4, Billing.tierImportance(Billing.TIER_IMPORTANT, mapOf("action" to true), 0))
        assertEquals(5, Billing.tierImportance(Billing.TIER_IMPORTANT, mapOf("action" to true), 5))
    }

    @Test
    fun tierReasonFormatsRoundAmountsLikePythonG() {
        // 阈值 100000：Python 的 `f"{100000.0:g}"` 是 "100000"，删掉整数末尾的 0 会变成 "1"
        fun reason(cny: Double) = Billing.decideTier(
            mapOf<String, Any?>("direction" to "out", "initiator" to "self", "cny" to cny),
            cfg = mapOf("amount_threshold" to 100000),
        ).reason
        assertEquals("金额约 ¥100000，不低于阈值 ¥100000", reason(100000.0))
        assertEquals("金额约 ¥180000，不低于阈值 ¥100000", reason(180000.0))
        assertEquals("金额约 ¥123450，不低于阈值 ¥100000", reason(123450.5))
        assertEquals("金额约 ¥1.23456e+06，不低于阈值 ¥100000", reason(1234565.0))
        // 不到阈值：继续走后一条策略
        assertEquals("本人主动支出，获得商品或服务", reason(99999.99))
        assertEquals("本人主动支出，获得商品或服务", reason(0.0000125))
    }

    @Test
    fun routineCountBeyondLongRangeIsNotRoutine() {
        val unknown = mapOf<String, Any?>("direction" to "unknown")
        // Python 的 int() 得到大整数；截断回绕会变成 5，从而误判成「例行流水」
        assertEquals(
            Billing.TIER_IMPORTANT,
            Billing.decideTier(
                unknown,
                routineCount = 12,
                cfg = mapOf("routine_count" to "18446744073709551621"),
            ).tier,
        )
        assertEquals(
            Billing.TIER_IMPORTANT,
            Billing.decideTier(
                unknown,
                routineCount = 12,
                cfg = mapOf("routine_count" to BigInteger("18446744073709551621")),
            ).tier,
        )
    }

    @Test
    fun nonFiniteRoutineCountFallsBackToDefault() {
        val unknown = mapOf<String, Any?>("direction" to "unknown")
        // int(nan) / int(inf) 在 Python 里抛异常，回退默认值 10；Double.toLong() 会给 0
        for (value in listOf<Any>(Double.NaN, Double.POSITIVE_INFINITY, -Double.NEGATIVE_INFINITY,
                Float.NaN, "nan", "inf")) {
            assertEquals(
                "routine_count = $value",
                Billing.TIER_MINOR,
                Billing.decideTier(unknown, routineCount = 10, cfg = mapOf("routine_count" to value)).tier,
            )
            assertEquals(
                "routine_count = $value",
                Billing.TIER_IMPORTANT,
                Billing.decideTier(unknown, routineCount = 9, cfg = mapOf("routine_count" to value)).tier,
            )
        }
        // 阈值是 nan / inf：比较不出大小，金额这条不命中
        val out = mapOf<String, Any?>("direction" to "out", "cny" to 1000)
        assertEquals(
            "信息不足，保守视为重要",
            Billing.decideTier(out, cfg = mapOf("amount_threshold" to Double.NaN)).reason,
        )
        assertEquals(
            "信息不足，保守视为重要",
            Billing.decideTier(out, cfg = mapOf("amount_threshold" to Double.POSITIVE_INFINITY)).reason,
        )
    }

    @Test
    fun configAcceptsOtherNumberTypes() {
        val out = mapOf<String, Any?>("direction" to "out", "cny" to 260)
        val unknown = mapOf<String, Any?>("direction" to "unknown")
        // amount_threshold：Short / Byte / BigDecimal / BigInteger 与 float() 结果相同
        assertEquals(
            "金额约 ¥260，不低于阈值 ¥250",
            Billing.decideTier(out, cfg = mapOf("amount_threshold" to 250.toShort())).reason,
        )
        assertEquals(
            "金额约 ¥260，不低于阈值 ¥100",
            Billing.decideTier(out, cfg = mapOf("amount_threshold" to 100.toByte())).reason,
        )
        assertEquals(
            "金额约 ¥260，不低于阈值 ¥250",
            Billing.decideTier(out, cfg = mapOf("amount_threshold" to BigInteger("250"))).reason,
        )
        assertEquals(
            "金额约 ¥260，不低于阈值 ¥250.5",
            Billing.decideTier(out, cfg = mapOf("amount_threshold" to BigDecimal("250.5"))).reason,
        )
        // routine_count：同样按 int() 处理，小数向零截断
        for (value in listOf<Any>(12.toShort(), 12.toByte(), BigInteger("12"), BigDecimal("12.9"))) {
            assertEquals(
                "routine_count = $value",
                Billing.TIER_MINOR,
                Billing.decideTier(unknown, routineCount = 12, cfg = mapOf("routine_count" to value)).tier,
            )
        }
    }

    @Test
    fun aiFilledAppendsToExistingReadOnlyList() {
        // 调用方传进来的 ai_filled 是只读 List：不能强转成可变列表再 add
        val local = mapOf<String, Any?>(
            "direction" to "unknown",
            "initiator" to "unknown",
            "acquired" to "unknown",
            "ai_filled" to listOf("acquired"),
        )
        val merged = Billing.mergeAiFacets(
            local,
            mapOf("direction" to "in", "initiator" to "auto", "acquired" to "service"),
        )
        assertEquals("in", merged["direction"])
        assertEquals("auto", merged["initiator"])
        assertEquals("service", merged["acquired"])
        // 与 Python 一样追加到原列表末尾
        assertEquals(listOf("acquired", "direction", "initiator", "acquired"), merged["ai_filled"])
        // 复制后写回，原字典里的列表不被改动
        assertEquals(listOf("acquired"), local["ai_filled"])
    }

    @Test
    fun policyCodeIsNotTiedToAnyBrandOrSender() {
        // 与 tests/test_billing.py::test_policy_code_is_not_tied_to_any_brand_or_sender 等价：
        // 定级只依赖通用的交易画像，源码里不允许出现品牌名或发件域名
        val source = billingSource().lowercase()
        // 支付方式名称（visa、paypal、apple pay 等）是通用的渠道特征，不算商户品牌
        for (brand in listOf(
            "steam", "valve", "google play", "epic", "gog", "jd.com", "taobao", "amazon",
            "playstation", "xbox", "nintendo", "humble", "oracle", "aihub", "green man",
        )) {
            assertFalse("Billing.kt 里出现了品牌名 $brand", source.contains(brand))
        }
        assertFalse(
            "Billing.kt 里出现了发件域名",
            Regex("""[a-z0-9-]+\.(com|net|org|cn|top|io|xyz)\b""").containsMatchIn(source),
        )
    }

    /** 读 `Billing.kt` 源码：Gradle 的工作目录就是模块目录，往上也找几级以兼容其它入口。 */
    private fun billingSource(): String {
        val relative = listOf(
            "src/main/kotlin/com/mailhub/core/billing/Billing.kt",
            "core/src/main/kotlin/com/mailhub/core/billing/Billing.kt",
        )
        var base: File? = File(System.getProperty("user.dir")).absoluteFile
        while (base != null) {
            for (path in relative) {
                val file = File(base, path)
                if (file.isFile) return file.readText()
            }
            base = base.parentFile
        }
        throw AssertionError("找不到 Billing.kt，工作目录是 ${System.getProperty("user.dir")}")
    }
}
