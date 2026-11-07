package com.mailhub.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可读的分支覆盖：`classifyDetailed` 的每条分支（hits 的每种取值）各一例，
 * 外加验证码提取与规则常量的关键点。期望值同样来自 Python 原实现。
 */
class RulesTest {

    private fun classify(
        subject: String,
        body: String = "",
        sender: String = "someone@corp.example",
        unsubscribe: Boolean = false,
        rules: List<CustomRule> = emptyList(),
        signals: Map<String, Boolean>? = null,
    ): RuleResult = Rules.classifyDetailed(subject, body, sender, unsubscribe, rules, signals)

    private fun assertResult(
        result: RuleResult,
        category: String,
        confidence: Double,
        hit: String,
    ) {
        assertEquals(category, result.category)
        assertEquals(confidence, result.confidence, 0.0)
        assertEquals(listOf(hit), result.hits)
    }

    @Test
    fun customRuleComesFirst() {
        val result = classify(
            "随便一个标题",
            sender = "a@b.com",
            rules = listOf(CustomRule("sender", "a@b", "通知")),
        )
        assertResult(result, "通知", Rules.CONF_USER_RULE, "custom")
        assertEquals("", result.otp)
    }

    @Test
    fun customRuleWithBadRegexIsSkipped() {
        // Python 在 re.search 时抛 re.error，跳过这条规则继续往下判
        val result = classify("hello", rules = listOf(CustomRule("subject", "([a-", "安全")))
        assertResult(result, "其他", Rules.CONF_DEFAULT, "none")
    }

    @Test
    fun customRuleForOtpAlsoExtractsCode() {
        val result = classify(
            "[ALERT] 验证码 394857",
            rules = listOf(CustomRule("subject", "\\[ALERT\\]", "验证码")),
        )
        assertResult(result, "验证码", Rules.CONF_USER_RULE, "custom")
        assertEquals("394857", result.otp)
    }

    @Test
    fun otpBranchNeedsExtractableCode() {
        assertResult(classify("验证码 384756"), "验证码", Rules.CONF_OTP, "otp")
        // 有关键词但取不到码（年份不算验证码），不能判成验证码
        assertResult(classify("年度报告 2024"), "其他", Rules.CONF_DEFAULT, "none")
    }

    @Test
    fun importantStrongBranchAndItsBulkPenalty() {
        assertResult(classify("面试邀请：后端工程师"), "重要", Rules.CONF_STRONG, "important_strong")
        assertResult(
            classify("面试邀请", unsubscribe = true),
            "重要", Rules.CONF_BULK_STRONG, "important_strong",
        )
    }

    @Test
    fun securitySubjectBranch() {
        assertResult(classify("异常登录提醒"), "安全", Rules.CONF_STRONG, "security_subject")
    }

    @Test
    fun billBranchAndItsBulkPenalty() {
        assertResult(classify("AWS 账单已出"), "账单", Rules.CONF_STRONG, "bill")
        assertResult(classify("账单已出", unsubscribe = true), "账单", Rules.CONF_MEDIUM, "bill")
    }

    @Test
    fun shippingBranch() {
        // 有物流词、没有支付词：归通知而不是账单
        assertResult(classify("您的订单已发货"), "通知", Rules.CONF_MEDIUM, "shipping")
    }

    @Test
    fun securityBodyBranch() {
        assertResult(
            classify("Weekly note", body = "检测到异常登录"),
            "安全", Rules.CONF_WEAK, "security_body",
        )
    }

    @Test
    fun importantWeakBranch() {
        assertResult(classify("Urgent: call me"), "重要", Rules.CONF_WEAK, "important_weak")
    }

    @Test
    fun bulkPlatformBranch() {
        val result = classify(
            "[GitHub] A new comment on PR #123",
            body = "reply",
            sender = "notifications@github.com",
            unsubscribe = true,
            signals = mapOf("list_id" to true),
        )
        assertResult(result, "通知", Rules.CONF_MEDIUM, "bulk_platform")
    }

    @Test
    fun bulkHeaderBranch() {
        assertResult(
            classify("Team update", sender = "list@club.example", unsubscribe = true),
            "订阅", Rules.CONF_MEDIUM, "bulk_header",
        )
        assertResult(
            classify("Team update", sender = "list@club.example", signals = mapOf("precedence_bulk" to true)),
            "订阅", Rules.CONF_MEDIUM, "bulk_header",
        )
    }

    @Test
    fun promoKeywordBranch() {
        assertResult(classify("双11 大促预告"), "订阅", Rules.CONF_WEAK, "promo_keyword")
    }

    @Test
    fun notifySenderBranch() {
        assertResult(
            classify("系统维护公告", sender = "noreply@service.example"),
            "通知", 0.7, "notify_sender",
        )
        assertResult(
            classify("Build failed: main", sender = "builds@ci.example",
                signals = mapOf("auto_submitted" to true)),
            "通知", Rules.CONF_MEDIUM, "notify_sender",
        )
    }

    @Test
    fun noneBranch() {
        assertResult(classify("会议纪要", body = "附件为本周会议纪要，请查收"), "其他", Rules.CONF_DEFAULT, "none")
    }

    @Test
    fun extractOtpPrefersSubjectThenBody() {
        assertEquals("384756", Rules.extractOtp("验证码 384756", "无关正文"))
        assertEquals("121212", Rules.extractOtp("Sign in to Example", "验证码 121212"))
        assertEquals("", Rules.extractOtp("你好", ""))
    }

    @Test
    fun extractOtpMatchesPythonOnFullWidthDigitsAndEmoji() {
        // Python 的 \d 认全角数字，结果原样返回全角
        assertEquals("３８４７５６", Rules.extractOtp("验证码 ３８４７５６", ""))
        // 营销词在按码点算的窗口内：Python 认为这是优惠码，取不到验证码
        val promo = "\uD83D\uDD25".repeat(10) + "优惠" + "\uD83D\uDD25".repeat(50) + "验证码 384756"
        assertEquals("", Rules.extractOtp(promo, ""))
        // 正文前 2000 码点截断：1000 个 emoji（2000 个 UTF-16 码元）+ 验证码仍然取得到
        assertEquals("384756", Rules.extractOtp("", "\uD83D\uDE00".repeat(1000) + "验证码 384756"))
        // 验证码整个在 2000 码点之外
        assertEquals("", Rules.extractOtp("", "\uD83D\uDE00".repeat(2000) + "验证码 384756"))
    }

    @Test
    fun bulkSignals() {
        assertFalse(Rules.isBulk(null))
        assertFalse(Rules.isBulk(emptyMap()))
        assertTrue(Rules.isBulk(null, hasUnsubscribe = true))
        assertTrue(Rules.isBulk(mapOf("list_id" to true)))
        assertTrue(Rules.isBulk(mapOf("precedence_bulk" to true)))
        assertTrue(Rules.isBulk(mapOf("feedback_id" to true)))
        assertTrue(Rules.isBulk(mapOf("esp" to true)))
        // auto_submitted 不是群发信号
        assertFalse(Rules.isBulk(mapOf("auto_submitted" to true)))
    }

    @Test
    fun policyTableAndCloudAi() {
        assertEquals(Rules.CATEGORIES.toSet(), Rules.CATEGORY_POLICY.keys)
        assertFalse(Rules.allowsCloudAi("验证码"))
        assertFalse(Rules.allowsCloudAi("账单"))
        assertFalse(Rules.allowsCloudAi("安全"))
        assertFalse(Rules.allowsCloudAi("可疑"))
        assertTrue(Rules.allowsCloudAi("重要"))
        assertTrue(Rules.allowsCloudAi("未知分类"))
    }

    @Test
    fun classifyWrapperMatchesDetailed() {
        val detailed = classify("验证码 384756")
        assertEquals(
            detailed.category to detailed.otp,
            Rules.classify("验证码 384756", "", "someone@corp.example", false, emptyList()),
        )
    }
}
