package com.mailhub.core.mail

import com.mailhub.core.text.Py
import com.mailhub.core.text.pySplit
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * `email.utils.parsedate_tz` / `parsedate_to_datetime(...).timestamp()` 的移植。
 *
 * Python 的 `_parseaddr._parsedate_tz` 是 rfc822 时代那套「按空白切分、逐段猜」的实现，
 * 两位年份、RFC 850 写法、时区缩写、`-0000` 这类写法都认，所以这里照抄它的分支，
 * 不做任何「更规范」的改写。
 *
 * 时间换算与 Python 一致：
 * - 带明确时区（数字或缩写）时是 aware datetime，`.timestamp()` 是绝对时间；
 * - `-0000` 或没有时区时是 naive datetime，`.timestamp()` 按**进程本地时区**换算，
 *   所以这里用 `TimeZone.getDefault()`（或调用方注入的时区），不写死 UTC。
 */
internal object MailDate {

    /**
     * `email._parseaddr.parsedate_tz(data)`：
     * 解析失败返回 null（Python 返回 None，`parsedate_to_datetime` 据此抛 ValueError）。
     */
    fun parsedateTz(data: String?): ParsedDate? {
        if (data == null || data.isEmpty()) return null
        val fields = data.pySplit().toMutableList()
        if (fields.isEmpty()) return null
        // 星期几后面的空白是可选的，所以先按逗号 / 星期名判断
        if (fields[0].endsWith(",") || fields[0].lowercase() in DAY_NAMES) {
            fields.removeAt(0)
        } else {
            val comma = fields[0].lastIndexOf(',')
            if (comma >= 0) fields[0] = fields[0].substring(comma + 1)
        }
        if (fields.isEmpty()) return null
        // RFC 850 写法（已废弃）：`06-Nov-94 08:49:37 GMT`
        if (fields.size == 3) {
            val parts = fields[0].split("-")
            if (parts.size == 3) {
                val rest = fields.drop(1)
                fields.clear()
                fields += parts
                fields += rest
            }
        }
        if (fields.size == 4) {
            val last = fields[3]
            var index = last.indexOf('+')
            if (index == -1) index = last.indexOf('-')
            if (index > 0) {
                fields.removeAt(3)
                fields.add(3, last.substring(0, index))
                fields.add(4, last.substring(index))
            } else {
                fields += "" // 补一个空时区
            }
        }
        if (fields.size < 5) return null
        while (fields.size > 5) fields.removeAt(5)
        var day = fields[0]
        var monthText = fields[1]
        var year = fields[2]
        var time = fields[3]
        var zone = fields[4]
        if (day.isEmpty() || monthText.isEmpty() || year.isEmpty()) return null
        // 月份可能写在日的前面（`Sep 12` 与 `12 Sep` 两种写法）
        var monthName = monthText.lowercase()
        if (monthName !in MONTH_NAMES) {
            val swap = day
            day = monthText
            monthName = swap.lowercase()
            if (monthName !in MONTH_NAMES) return null
        }
        var month = MONTH_NAMES.indexOf(monthName) + 1
        if (month > 12) month -= 12
        if (day.endsWith(",")) day = day.dropLast(1)
        if (year.indexOf(':') > 0) {
            val swap = year
            year = time
            time = swap
        }
        if (year.endsWith(",")) {
            year = year.dropLast(1)
            if (year.isEmpty()) return null
        }
        if (!year[0].isDigit()) {
            val swap = year
            year = zone
            zone = swap
        }
        if (time.endsWith(",")) time = time.dropLast(1)
        var timeParts = time.split(":")
        var hours: String
        var minutes: String
        var seconds: String
        when {
            timeParts.size == 2 -> {
                hours = timeParts[0]
                minutes = timeParts[1]
                seconds = "0"
            }
            timeParts.size == 3 -> {
                hours = timeParts[0]
                minutes = timeParts[1]
                seconds = timeParts[2]
            }
            // 有些不合规范的客户端用 `.` 分隔时分秒
            timeParts.size == 1 && '.' in timeParts[0] -> {
                val dotted = timeParts[0].split(".")
                if (dotted.size == 2) {
                    hours = dotted[0]
                    minutes = dotted[1]
                    seconds = "0"
                } else if (dotted.size == 3) {
                    hours = dotted[0]
                    minutes = dotted[1]
                    seconds = dotted[2]
                } else {
                    return null
                }
            }
            else -> return null
        }
        val yearValue = intValue(year) ?: return null
        val dayValue = intValue(day) ?: return null
        val hourValue = intValue(hours) ?: return null
        val minuteValue = intValue(minutes) ?: return null
        val secondValue = intValue(seconds) ?: return null
        // 两位年份按 POSIX 规则补全：69–99 → 19xx，00–68 → 20xx
        val fullYear = if (yearValue < 100) yearValue + if (yearValue > 68) 1900 else 2000 else yearValue
        var offset: Int? = null
        val zoneText = zone.uppercase()
        if (zoneText in TIMEZONES) {
            offset = TIMEZONES.getValue(zoneText)
        } else {
            val numeric = intValue(zoneText)
            if (numeric != null) {
                offset = numeric
                if (offset == 0 && zoneText.startsWith("-")) offset = null
            }
        }
        // 把 -0500 换算成秒：-18000
        if (offset != null && offset != 0) {
            val sign = if (offset < 0) -1 else 1
            val absolute = if (offset < 0) -offset else offset
            offset = sign * ((absolute / 100) * 3600 + (absolute % 100) * 60)
        }
        return ParsedDate(fullYear, month, dayValue, hourValue, minuteValue, secondValue, offset)
    }

    /**
     * `email.utils.parsedate_to_datetime(...).timestamp()`：返回 Unix 时间戳（秒）。
     * 解析失败、日期本身非法（Python 抛 ValueError）或换算溢出时返回 null。
     */
    fun timestamp(dateHeader: String?, zone: TimeZone = TimeZone.getDefault()): Double? {
        val parsed = parsedateTz(dateHeader) ?: return null
        return try {
            val local = LocalDateTime.of(
                parsed.year, parsed.month, parsed.day, parsed.hour, parsed.minute, parsed.second,
            )
            val offset = parsed.utcOffsetSeconds
            val seconds = if (offset != null) {
                local.toEpochSecond(ZoneOffset.ofTotalSeconds(offset))
            } else {
                // 没有时区信息：Python 按本地时区解释 naive datetime
                local.atZone(zone.toZoneId()).toEpochSecond()
            }
            seconds.toDouble()
        } catch (_: DateTimeException) {
            null
        }
    }

    /** Python 的 `int(s)`：解不出来返回 null（对应 ValueError）；超出 Int 范围的也当失败。 */
    private fun intValue(text: String): Int? {
        val value = Py.int(text) ?: return null
        if (value.bitLength() > 31) return null
        return value.toInt()
    }

    private val MONTH_NAMES = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december",
    )

    private val DAY_NAMES = setOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** `email._parseaddr._timezones`：只认 RFC 1123 推荐的几个缩写。 */
    private val TIMEZONES = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0,
        "AST" to -400, "ADT" to -300,
        "EST" to -500, "EDT" to -400,
        "CST" to -600, "CDT" to -500,
        "MST" to -700, "MDT" to -600,
        "PST" to -800, "PDT" to -700,
    )
}

/** `parsedate_tz` 的结果。[utcOffsetSeconds] 为 null 表示 `-0000` 或没有时区（naive datetime）。 */
internal data class ParsedDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val utcOffsetSeconds: Int?,
)
