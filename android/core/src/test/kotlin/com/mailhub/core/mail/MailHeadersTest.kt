package com.mailhub.core.mail

import com.mailhub.core.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        // Python 的 headerRE 只允许 ASCII 可打印字符直接接冒号；`X-Name :` 里的空格让整行
        // 都不匹配，头部块在它这里结束，所以其实一个头都没有，`get` 两种写法都取不到。
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

    @Test
    fun bareCarriageReturnEndsAHeaderLine() {
        // Python 的 BufferedSubFile 按 \r\n|\r|\n 切行：裸 \r 也断行，伪造的 From 是独立的头
        val headers = MimeHeaders.parse(
            ("Subject: x\rFrom: forged@evil.example\r\nFrom: real@bank.example\r\n\r\nbody").toByteArray(),
        )
        assertEquals("x", headers.get("Subject"))
        assertEquals(listOf("forged@evil.example", "real@bank.example"), headers.getAll("From"))
    }

    @Test
    fun bareCarriageReturnBlankLineEndsTheHeaderBlock() {
        val headers = MimeHeaders.parse(("A: 1\r\rB: 2\r\n\r\nbody").toByteArray())
        assertEquals("1", headers.get("A"))
        assertEquals(null, headers.get("B"))
    }

    @Test
    fun eightBitHeaderValueIsMarked() {
        val headers = MimeHeaders.parse(
            "X-8bit: caf\u00E9\r\nX-Ascii: cafe\r\n\r\nbody".toByteArray(Charsets.ISO_8859_1),
        )
        assertEquals(true, headers.field("X-8bit")?.has8Bit)
        assertEquals(false, headers.field("X-Ascii")?.has8Bit)
    }

    @Test
    fun eightBitFromAndReplyToParseAsEmpty() {
        // Python 的 msg.get 返回 Header 对象，parseaddr 对非 str 直接返回 ("", "")
        val raw = (
            "From: \u00E9 <forged@bank.example>\r\n" +
                "Reply-To: \u00E9 <reply@bank.example>\r\n" +
                "To: bob@corp.example\r\n" +
                "Subject: 8bit sender\r\n\r\nbody"
            ).toByteArray(Charsets.ISO_8859_1)
        val parsed = parseHeaders(raw, clock = clock(), zone = utc)
        assertEquals("", parsed.senderName)
        assertEquals("", parsed.senderAddr)
        assertEquals("", parsed.replyTo)
    }

    @Test
    fun eightBitDateFallsBackToTheClock() {
        // Python 抛 AttributeError（Header 没有 split），parse_message 的 except 吞掉后回退 time.time()
        val raw = (
            "From: notice@shop.example\r\n" +
                "Date: Tue, 05 Nov 2024 10:30:00 +0800 (\u00B1\u00B1)\r\n\r\nbody"
            ).toByteArray(Charsets.ISO_8859_1)
        assertEquals(CLOCK_VALUE, parseHeaders(raw, clock = clock(), zone = utc).dateTs, 0.0)
    }

    @Test
    fun eightBitMessageIdIsUnparsable() {
        // Python 的 (msg.get("Message-ID") or "").strip() 抛 AttributeError，整封走 unparsable_message()
        val raw = (
            "From: notice@shop.example\r\n" +
                "Message-ID: <\u00E9@mail.shop.example>\r\n\r\nbody"
            ).toByteArray(Charsets.ISO_8859_1)
        try {
            parseHeaders(raw, clock = clock(), zone = utc)
            fail("含 8 位字节的 Message-ID 应抛 UnparsableMessageException")
        } catch (_: UnparsableMessageException) {
            // 对应 Python 的 AttributeError
        }
    }

    @Test(timeout = 2000)
    fun deeplyNestedCommentsFailFastWithoutStackOverflow() {
        // 10 万层嵌套注释是远程可触发的输入：必须抛普通 Exception，不能是 StackOverflowError
        val nested = "(".repeat(100_000) + ")".repeat(100_000)
        val thrown = try {
            parseAddr(nested)
            null
        } catch (e: Error) {
            e
        } catch (e: Exception) {
            e
        }
        assertTrue("应抛 AddressRecursionException，实际是 $thrown", thrown is AddressRecursionException)
    }

    @Test(timeout = 2000)
    fun deeplyNestedGroupsFailFastWithoutStackOverflow() {
        val nested = (1..100_000).joinToString(":") { "a" }
        val thrown = try {
            parseAddr(nested)
            null
        } catch (e: Error) {
            e
        } catch (e: Exception) {
            e
        }
        assertTrue("应抛 AddressRecursionException，实际是 $thrown", thrown is AddressRecursionException)
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
