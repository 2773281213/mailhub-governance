package com.mailhub.client.ui.screens.otp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看板的纯函数：红色判定、位数降档、说明行拼装。
 * 「现在」一律作为参数传入，所以这些断言不依赖真实时钟。
 */
class OtpStateTest {

    /** 固定的「现在」：与截图夹具同一时刻。 */
    private val nowTs: Double = OtpFixtureNowTs

    @Test
    fun fourMinutesFiftyNineSecondsIsStillFresh() {
        assertTrue(isOtpFresh(dateTs = nowTs - (4 * 60 + 59), nowTs = nowTs))
    }

    @Test
    fun fiveMinutesAndOneSecondIsNoLongerFresh() {
        assertFalse(isOtpFresh(dateTs = nowTs - (5 * 60 + 1), nowTs = nowTs))
    }

    @Test
    fun missingTimestampIsNeverFresh() {
        assertFalse(isOtpFresh(dateTs = 0.0, nowTs = nowTs))
    }

    @Test
    fun sixDigitsKeepsTheDisplaySize() {
        assertEquals(OtpCodeSize.Display, otpCodeSizeFor("482913"))
    }

    @Test
    fun eightDigitsStepsDownToTitle() {
        assertEquals(OtpCodeSize.Title, otpCodeSizeFor("92840147"))
    }

    @Test
    fun clockTextUsesTheServerZone() {
        // 上海是 UTC+8：08:54 UTC 收到，显示 16:54
        assertEquals("16:54", otpClockText(dateTs = nowTs, zoneId = "Asia/Shanghai"))
    }

    @Test
    fun ageTextCountsWholeMinutes() {
        assertEquals("2 分钟前", otpAgeText(dateTs = nowTs - 120, nowTs = nowTs))
        assertEquals("59 分钟前", otpAgeText(dateTs = nowTs - 3_599, nowTs = nowTs))
        assertEquals("刚刚", otpAgeText(dateTs = nowTs - 30, nowTs = nowTs))
    }

    @Test
    fun captionJoinsSenderAccountClockAndAge() {
        val item = otpFixtureItem(id = 1, code = "482913", senderName = "Cloudflare", ageSeconds = 120.0)
        assertEquals(
            "Cloudflare · A1 · 16:52 · 2 分钟前",
            otpCaptionText(item = item, accounts = OtpFixtureAccounts, zoneId = "Asia/Shanghai", nowTs = nowTs),
        )
    }

    @Test
    fun accountNumberFollowsTheAccountOrder() {
        val second = otpFixtureItem(
            id = 4,
            code = "314059",
            senderName = "Steam",
            ageSeconds = 60.0,
            accountIndex = 1,
        )
        assertEquals("A2", otpAccountNumber(OtpFixtureAccounts, second))
        // 不在账户列表里时留空，说明行直接省掉这一段
        assertEquals("", otpAccountNumber(emptyList(), second))
    }

    @Test
    fun earlierListStopsAtTwentyThree() {
        val items = List(OtpListLimit + 4) { index ->
            otpFixtureItem(id = index.toLong(), code = "482913", senderName = "S$index", ageSeconds = 60.0)
        }
        val state = OtpUiState(items = items, nowTs = nowTs)
        // 「更早」最多 23 条，多出来的直接不显示
        assertEquals(OtpEarlierLimit, state.earlier.size)
        // 第一项是最新一条，不进「更早」
        assertEquals(items[1].id, state.earlier.first().id)
        assertEquals(items[OtpEarlierLimit].id, state.earlier.last().id)
    }

    /** 伪造的显示名（写着官方域名）不能出现在来源位置，只能显示真实地址。 */
    @Test
    fun forgedDisplayNameShowsTheRealAddress() {
        val item = otpFixtureItem(
            id = 9,
            code = "551904",
            senderName = "service@paypal.com",
            senderAddr = "notice@paypa1-secure.example",
            ageSeconds = 60.0,
        )
        assertEquals("notice@paypa1-secure.example", otpSenderText(item))
    }

    /** 有风险的条目不进大字区，留在下面的列表里，来源前面标「有风险 · 」。 */
    @Test
    fun riskyItemNeverBecomesTheLatest() {
        val risky = otpFixtureItem(
            id = 9,
            code = "551904",
            senderName = "Google",
            senderAddr = "alert@google-verify.example",
            ageSeconds = 60.0,
            riskLevel = "medium",
        )
        val safe = otpFixtureItem(id = 10, code = "734156", senderName = "GitHub", ageSeconds = 120.0)
        val state = OtpUiState(items = listOf(risky, safe), nowTs = nowTs)

        assertEquals(safe.id, state.latest?.id)
        assertEquals(listOf(risky.id), state.earlier.map { it.id })
        assertEquals("有风险 · alert@google-verify.example", otpEarlierSourceText(risky))
        // 没有风险的条目不需要标注
        assertEquals("GitHub", otpEarlierSourceText(safe))
    }

    /** 全部都有风险时大字区没有最新一条，风险条目仍然都留在列表里。 */
    @Test
    fun allRiskyLeavesNoLatest() {
        val first = otpFixtureItem(
            id = 9,
            code = "551904",
            senderName = "Google",
            senderAddr = "alert@google-verify.example",
            ageSeconds = 60.0,
            riskLevel = "medium",
        )
        val second = otpFixtureItem(
            id = 10,
            code = "482913",
            senderName = "银行助手",
            senderAddr = "notice@bank-verify.example",
            ageSeconds = 120.0,
            riskLevel = "high",
        )
        val state = OtpUiState(items = listOf(first, second), nowTs = nowTs)

        assertNull(state.latest)
        assertEquals(listOf(first.id, second.id), state.earlier.map { it.id })
    }
}
