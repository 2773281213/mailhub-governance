package com.mailhub.core.mail

import com.mailhub.core.Clock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * 金标覆盖不到的两类行为，在这里用固定时区 / 注入时钟单独钉住：
 *
 * 1. **没有时区的日期**（`-0000` 或完全没写时区）：Python 得到的是 naive datetime，
 *    `.timestamp()` 按进程本地时区换算，所以 Kotlin 必须用注入的 `TimeZone` 而不是写死 UTC。
 * 2. **日期缺失或非法**：`parse_message` 回退到 `time.time()`，Kotlin 回退到注入的 [Clock]。
 *
 * 期望值是对着 Python 3.13 算出来的：
 * `2024-11-05 10:30:00` 在 `Asia/Shanghai`（+08:00）是 `1730773800`，在 `UTC` 是 `1730802600`。
 */
class MailHeadersTest {

    private val shanghai: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun naivelyDatedMessageUsesTheGivenTimeZone() {
        val raw = message("Date: Tue, 05 Nov 2024 10:30:00 -0000")

        assertEquals(1730773800.0, parseHeaders(raw, zone = shanghai).dateTs, 0.0)
        assertEquals(1730802600.0, parseHeaders(raw, zone = utc).dateTs, 0.0)
    }

    @Test
    fun dateWithoutTimeZoneIsTreatedAsLocalTime() {
        val raw = message("Date: Tue, 05 Nov 2024 10:30:00")

        assertEquals(1730773800.0, parseHeaders(raw, zone = shanghai).dateTs, 0.0)
        assertEquals(1730802600.0, parseHeaders(raw, zone = utc).dateTs, 0.0)
    }

    @Test
    fun explicitTimeZoneIgnoresTheGivenTimeZone() {
        val raw = message("Date: Tue, 05 Nov 2024 10:30:00 +0800")

        assertEquals(1730773800.0, parseHeaders(raw, zone = shanghai).dateTs, 0.0)
        assertEquals(1730773800.0, parseHeaders(raw, zone = utc).dateTs, 0.0)
    }

    @Test
    fun missingDateFallsBackToTheClock() {
        val raw = message()
        assertEquals(CLOCK_VALUE, parseHeaders(raw, clock = clock(), zone = utc).dateTs, 0.0)
    }

    @Test
    fun invalidDatesFallBackToTheClock() {
        val invalid = listOf(
            "Date: not a date",                          // 完全不是日期
            "Date: Tue, 32 Nov 2024 10:30:00 +0800",     // 日超出范围
            "Date: Tue, 05 Nov 2024 25:30:00 +0800",     // 时超出范围
            "Date: Tue, 05 Nov 2024 10:30:60 +0800",     // 秒超出范围（闰秒 Python 也拒绝）
            "Date: ",                                    // 存在但为空
        )
        for (header in invalid) {
            val raw = message(header)
            assertEquals(
                "头为「$header」时应回退到注入的时钟",
                CLOCK_VALUE,
                parseHeaders(raw, clock = clock(), zone = utc).dateTs,
                0.0,
            )
        }
    }

    @Test
    fun headerValueKeepsFoldingVerbatim() {
        val headers = MimeHeaders.parse(("X-Fold: a\r\n b\r\n\tc\r\n\r\nbody").toByteArray())
        assertEquals("a\r\n b\r\n\tc", headers.get("X-Fold"))
    }

    @Test
    fun headerValueKeepsBareLfFolding() {
        val headers = MimeHeaders.parse(("X-Fold: a\n b\n\nbody").toByteArray())
        assertEquals("a\n b", headers.get("X-Fold"))
    }

    @Test
    fun headerNamesAreCaseInsensitive() {
        val headers = MimeHeaders.parse(("x-lower: Value\r\n\r\nbody").toByteArray())
        assertEquals("Value", headers.get("X-Lower"))
        assertEquals("Value", headers.get("x-lower"))
    }

    @Test
    fun duplicateHeadersFirstWinsButGetAllKeepsOrder() {
        val headers = MimeHeaders.parse(("X-Dup: one\r\nX-Dup: two\r\n\r\nbody").toByteArray())
        assertEquals("one", headers.get("X-Dup"))
        assertEquals(listOf("one", "two"), headers.getAll("X-Dup"))
    }

    @Test
    fun eightBitHeaderBytesBecomeReplacementChars() {
        // Python 用 surrogateescape 解出代理字符，取值时 encode('ascii','surrogateescape')
        // 再 decode('ascii','replace')，每个 8 位字节换成一个 U+FFFD
        val headers = MimeHeaders.parse("X-8bit: caf\u00E9\r\n\r\nbody".toByteArray(Charsets.ISO_8859_1))
        assertEquals("caf\uFFFD", headers.get("X-8bit"))
    }

    @Test
    fun missingHeaderIsNull() {
        val headers = MimeHeaders.parse(("X-A: 1\r\n\r\nbody").toByteArray())
        assertEquals(null, headers.get("X-Missing"))
        assertEquals(emptyList<String>(), headers.getAll("X-Missing"))
    }

    @Test
    fun trailingSpaceInHeaderNameIsNotStripped() {
        // Python 的 header_source_parse 只取冒号前的内容，`X-Name :` 的头名带一个空格
        val headers = MimeHeaders.parse(("X-Name : v\r\n\r\nbody").toByteArray())
        assertEquals(null, headers.get("X-Name"))
        assertEquals(null, headers.get("X-Name "))
    }

    @Test
    fun firstLineContinuationIsDiscarded() {
        val headers = MimeHeaders.parse((" continuation\r\nX-After: b\r\n\r\nbody").toByteArray())
        assertEquals("b", headers.get("X-After"))
    }

    @Test
    fun lineWithoutColonEndsTheHeaderBlock() {
        val headers = MimeHeaders.parse(("X-A: v\r\nno-colon-line\r\nX-B: c\r\n\r\nbody").toByteArray())
        assertEquals("v", headers.get("X-A"))
        assertEquals(null, headers.get("X-B"))
    }

    /** 拼一封只关心头部的合成邮件；[extraHeaders] 里可以放 Date 头，也可以什么都不放。 */
    private fun message(vararg extraHeaders: String): ByteArray {
        val lines = mutableListOf("From: notice@shop.example", "To: bob@corp.example", "Subject: 测试")
        lines += extraHeaders
        return (lines.joinToString("\r\n") + "\r\n\r\n正文\r\n").toByteArray(Charsets.UTF_8)
    }

    private fun clock(): Clock = Clock { CLOCK_VALUE }

    private companion object {
        const val CLOCK_VALUE = 1700000000.0
    }
}
