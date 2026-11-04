package com.mailhub.client.ui.screens.overview

import com.mailhub.client.data.model.GovernanceRun
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 图表最多 12 根柱子，正好一栏一根。 */
const val OverviewChartBars: Int = 12

/** 红色风险部分取自这个分类。 */
const val RiskCategory: String = "可疑"

/** 某一天的柱状数据：总量 + 其中属于「可疑」的风险部分。 */
data class DayBar(val label: String, val total: Int, val risk: Int) {
    /** 柱下刻度只用日的两位数字：一根柱子只占一栏，MM-dd 放不下会被裁切。 */
    val tick: String
        get() {
            val day = label.substringAfterLast('-')
            return if (day.isBlank()) "" else day.padStart(2, '0')
        }
}

/**
 * 把 /api/overview 的 days 与 daily 换算成柱状图数据。
 *
 * 只取最后 [count] 天；每天的总量是该天各分类计数之和，「可疑」分类的计数同时作为
 * 红色风险部分；某天没有数据时总量为 0。日标签用后端给的 MM-dd。
 */
fun overviewBars(
    days: List<String>,
    daily: List<Map<String, JsonElement>>,
    count: Int = OverviewChartBars,
): List<DayBar> {
    val size = maxOf(days.size, daily.size)
    if (size == 0) return emptyList()
    val from = maxOf(0, size - count)
    return (from until size).map { index ->
        val buckets = daily.getOrNull(index)
        var total = 0
        var risk = 0
        if (buckets != null) {
            for ((category, element) in buckets) {
                // daily 里混着 "day" 字段，不是分类计数
                if (category == DayKey) continue
                val value = (element as? JsonPrimitive)?.intOrNull ?: 0
                total += value
                if (category == RiskCategory) risk += value
            }
        }
        val label = days.getOrNull(index)
            ?: (buckets?.get(DayKey) as? JsonPrimitive)?.content
            ?: ""
        DayBar(label = label, total = total, risk = risk)
    }
}

/** daily 每行里的日期字段名。 */
private const val DayKey = "day"

/** 服务器时区；时区名不可用时退回设备时区，不让整页失败。 */
private fun zoneOrSystem(zoneId: String): ZoneId =
    runCatching { ZoneId.of(zoneId) }.getOrElse { ZoneId.systemDefault() }

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val clockWithDayFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 上次运行时间，按服务器时区显示 HH:mm；没有运行记录时显示占位符。 */
fun governanceTimeText(run: GovernanceRun?, zoneId: String): String {
    val finished = run?.finishedTs ?: 0.0
    if (finished <= 0.0) return EmptyValue
    return Instant.ofEpochSecond(finished.toLong())
        .atZone(zoneOrSystem(zoneId))
        .format(clockFormat)
}

/** 本次治理结果的一行说明。 */
fun governanceResultText(run: GovernanceRun?): String {
    if (run == null) return "尚未运行"
    return "${governanceStatusText(run.status)} ${run.processedCount} 封"
}

/** 邮件时间，按服务器时区显示日期和钟点。 */
fun mailClockText(dateTs: Double, zoneId: String): String {
    if (dateTs <= 0.0) return EmptyValue
    return Instant.ofEpochSecond(dateTs.toLong())
        .atZone(zoneOrSystem(zoneId))
        .format(clockWithDayFormat)
}

/** 表里缺值时的占位符，不用破折号以外的字符。 */
private const val EmptyValue = "—"

private fun governanceStatusText(status: String): String = when (status) {
    "done", "ok", "success" -> "已完成"
    "running" -> "进行中"
    "failed", "error" -> "失败"
    "" -> "已结束"
    else -> status
}
