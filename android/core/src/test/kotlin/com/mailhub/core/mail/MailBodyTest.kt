package com.mailhub.core.mail

import com.mailhub.core.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 金标覆盖不到的两类行为：
 *
 * 1. **最坏输入**：邮件正文由攻击者控制，`html_to_text` 的正则、MIME 的递归深度与 part 数量
 *    都必须在上限内返回（Python 会抛 `RecursionError` 的地方，Kotlin 抛普通异常并走
 *    `unparsable_message()`）。每个用例都要求 2 秒内返回。
 * 2. **累加上限**：`body_html` 在追加之前只判断「当前长度 < 300000」，所以结果会被截断到 300000
 *    码点（与 Python 的 `body_html[:300000]` 一致）。这一条金标里用 400 KB 的夹具不划算，
 *    在这里用生成的输入钉住。
 *
 * 期望值都是对着 Python 3.13 的 `mail_client` 行为算出来的。
 */
class MailBodyTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val clock: Clock = Clock { CLOCK_VALUE }

    @Test
    fun htmlToTextReturnsQuicklyOnPathologicalInput() {
        val inputs = listOf(
            // _TAG_RE 的平方级输入：大量开始标签、始终没有闭合标签
            "<script a".repeat(200_000),
            "<style b".repeat(200_000),
            // [^>]+ 找不到结尾
            "<" + "a".repeat(2_000_000),
            // _URL_RE 第二个分支的平方级输入
            "<https://".repeat(200_000),
            "<http://x".repeat(200_000),
            // \s* 吃掉一大段空白
            "<br" + " ".repeat(1_000_000),
            "<p>x</p>".repeat(200_000),
            "&amp;".repeat(200_000),
        )
        for (input in inputs) {
            val started = System.nanoTime()
            htmlToText(input)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("html_to_text 花了 ${elapsedMs}ms，输入长度 ${input.length}", elapsedMs < 2_000)
        }
    }

    @Test
    fun parseMessageReturnsQuicklyOnPathologicalHtml() {
        val html = "<script>" + "<script a".repeat(100_000) + "<p>x</p>".repeat(50_000)
        val raw = message(
            "Content-Type: multipart/alternative; boundary=\"b\"",
            "--b\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" + html + "\r\n--b--\r\n",
        )
        val started = System.nanoTime()
        val parsed = parseMessage(raw, clock = clock, zone = utc)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("parseMessage 花了 ${elapsedMs}ms", elapsedMs < 2_000)
        assertTrue(parsed.bodyText.isNotEmpty())
    }

    @Test
    fun tooDeepNestingFallsBackToUnparsableMessage() {
        var body = "leaf\r\n"
        for (index in 149 downTo 0) {
            val boundary = "deep$index"
            body = "Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n" +
                "--$boundary\r\n" + body + "\r\n--$boundary--\r\n"
        }
        val raw = body.toByteArray(Charsets.UTF_8)

        val started = System.nanoTime()
        val parsed = parseMessage(raw, clock = clock, zone = utc)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("parseMessage 花了 ${elapsedMs}ms", elapsedMs < 2_000)
        assertEquals(UNPARSABLE_TEXT, parsed.bodyText)
        assertEquals("（无法解析的邮件）", parsed.subject)
        assertEquals(CLOCK_VALUE, parsed.dateTs, 0.0)
    }

    @Test
    fun tooManyPartsFallsBackToUnparsableMessage() {
        val builder = StringBuilder("Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n")
        repeat(MAX_MIME_PARTS + 10) {
            builder.append("--b\r\nContent-Type: text/plain\r\n\r\nx\r\n")
        }
        builder.append("--b--\r\n")

        val parsed = parseMessage(message(builder.toString().trimEnd('\r', '\n')), clock = clock, zone = utc)
        assertEquals(UNPARSABLE_TEXT, parsed.bodyText)
    }

    @Test
    fun accumulateConditionsAreCheckedBeforeAppending() {
        // 两段 HTML 都是 200000 码点：第二段追加前长度仍小于 300000，所以结果超限后被截断
        val part = "<p>" + "a".repeat(199_996) + "</p>"
        val raw = message(
            "Content-Type: multipart/alternative; boundary=\"b\"",
            "--b\r\nContent-Type: text/html\r\n\r\n$part\r\n" +
                "--b\r\nContent-Type: text/html\r\n\r\n$part\r\n--b--\r\n",
        )

        val parsed = parseMessage(raw, clock = clock, zone = utc)
        assertEquals(300_000, parsed.bodyHtml.length)
        assertTrue(parsed.bodyText.isNotEmpty())
    }

    @Test
    fun textPlainStopsAccumulatingAfterSixtyThousandCodePoints() {
        val raw = message(
            "Content-Type: multipart/mixed; boundary=\"b\"",
            "--b\r\nContent-Type: text/plain\r\n\r\n" + "A".repeat(60_001) + "\r\n" +
                "--b\r\nContent-Type: text/plain\r\n\r\nTAIL\r\n--b--\r\n",
        )

        val parsed = parseMessage(raw, clock = clock, zone = utc)
        assertEquals("A".repeat(50_000), parsed.bodyText)
    }

    /** 拼一封邮件的原始字节：头部 + 空行 + 正文。 */
    private fun message(headers: String, body: String = ""): ByteArray =
        (headers + "\r\n\r\n" + body).toByteArray(Charsets.UTF_8)

    private companion object {
        const val CLOCK_VALUE = 1700000000.0
        const val UNPARSABLE_TEXT = "MailHub 无法解析这封邮件，请在邮箱网页版查看原文。"
    }
}
