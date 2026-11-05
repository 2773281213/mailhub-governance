package com.mailhub.client.ui.screens

import com.mailhub.client.data.model.GovernanceRun
import com.mailhub.client.ui.screens.overview.OverviewChartBars
import com.mailhub.client.ui.screens.overview.overviewBars
import com.mailhub.client.ui.screens.overview.governanceResultText
import com.mailhub.client.ui.screens.overview.governanceTimeText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 总览数据换算：只取最后 12 天、分类求和、可疑作为风险、缺数据记 0。 */
class OverviewDataTest {

    private val zone = "Asia/Shanghai"

    private fun day(label: String, vararg counts: Pair<String, Int>): Map<String, JsonElement> =
        buildMap {
            put("day", JsonPrimitive(label))
            counts.forEach { (category, value) -> put(category, JsonPrimitive(value)) }
        }

    private fun labels(count: Int): List<String> =
        List(count) { index -> "10-${(index + 1).toString().padStart(2, '0')}" }

    @Test
    fun fourteenDaysAreTrimmedToTwelve() {
        val days = labels(14)
        val daily = List(14) { index -> day(days[index], "通知" to index) }

        val bars = overviewBars(days = days, daily = daily)

        assertEquals(OverviewChartBars, bars.size)
        assertEquals(days.takeLast(12), bars.map { it.label })
        // 第 3 天（index=2）对应取出来的第一根柱子
        assertEquals(2, bars.first().total)
    }

    @Test
    fun totalIsSumOfCategoriesAndRiskIsSuspiciousOnly() {
        val days = listOf("11-03", "11-04")
        val daily = listOf(
            day("11-03", "通知" to 4, "订阅" to 3, "可疑" to 2),
            day("11-04", "验证码" to 5, "重要" to 1),
        )

        val bars = overviewBars(days = days, daily = daily)

        assertEquals(9, bars[0].total)
        assertEquals(2, bars[0].risk)
        assertEquals(6, bars[1].total)
        assertEquals(0, bars[1].risk)
    }

    @Test
    fun missingDayCountsAsZero() {
        val days = listOf("11-02", "11-03", "11-04")
        val daily = listOf(
            day("11-02", "通知" to 7),
            emptyMap(),
            day("11-04", "通知" to 1),
        )

        val bars = overviewBars(days = days, daily = daily)

        assertEquals(7, bars[0].total)
        assertEquals(0, bars[1].total)
        assertEquals(0, bars[1].risk)
        assertEquals(1, bars[2].total)
        // 缺数据的那天仍然占一根柱子，标签来自 days
        assertEquals("11-03", bars[1].label)
    }

    @Test
    fun dailyShorterThanDaysStillProducesAllBars() {
        val days = listOf("11-03", "11-04")
        val daily = listOf(day("11-03", "通知" to 2))

        val bars = overviewBars(days = days, daily = daily)

        assertEquals(2, bars.size)
        assertEquals(0, bars[1].total)
        assertEquals("11-04", bars[1].label)
    }

    @Test
    fun emptyOverviewProducesNoBars() {
        assertTrue(overviewBars(days = emptyList(), daily = emptyList()).isEmpty())
    }

    @Test
    fun governanceTimeUsesServerZone() {
        val run = GovernanceRun(status = "done", finishedTs = SampleData.FinishedTs)

        assertEquals("16:54", governanceTimeText(run, zone))
        assertEquals("08:54", governanceTimeText(run, "UTC"))
        assertEquals("—", governanceTimeText(null, zone))
        assertEquals("—", governanceTimeText(GovernanceRun(status = "done"), zone))
    }

    @Test
    fun unknownZoneFallsBackInsteadOfFailing() {
        val run = GovernanceRun(status = "done", finishedTs = SampleData.FinishedTs)

        val text = governanceTimeText(run, "Not/AZone")

        assertTrue("时区不可用时也要给出 HH:mm，实际 $text", text.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun governanceResultSummarisesLastRun() {
        assertEquals("尚未运行", governanceResultText(null))
        assertEquals(
            "已完成 128 封",
            governanceResultText(GovernanceRun(status = "done", processedCount = 128)),
        )
        assertEquals(
            "进行中 12 封",
            governanceResultText(GovernanceRun(status = "running", processedCount = 12)),
        )
    }
}
