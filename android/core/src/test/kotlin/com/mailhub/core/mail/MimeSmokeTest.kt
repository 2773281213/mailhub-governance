package com.mailhub.core.mail

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Angus Mail 的中文邮件解析冒烟测试。
 *
 * 夹具是三个合成 .eml（`src/test/resources/mime/`），全部虚构，域名只用 `.example`：
 * 1. UTF-8 的 multipart/alternative；
 * 2. 只有 text/html、charset=GB18030（正文真的用 GB18030 编码）；
 * 3. multipart/mixed，附件名分别是 RFC 2231 和 RFC 2047 编码。
 */
class MimeSmokeTest {
    @Test
    fun parsesUtf8MultipartAlternative() {
        val message = parse("utf8_alternative.eml")

        assertEquals("本季度账单汇总", message.subject)
        val from = sender(message)
        assertEquals("finance@corp.example", from.address)
        assertEquals("财务部", from.personal)

        val body = textPlain(message)
        assertTrue("正文应包含「本季度账单已生成」，实际是：$body", body.contains("本季度账单已生成"))
        // text/html 那一段也要能解出来，确认 alternative 结构完整。
        val html = message.content as Multipart
        val htmlPart = (0 until html.count).map { html.getBodyPart(it) }
            .first { it.isMimeType("text/html") }
        assertTrue("html 段应包含「本季度账单已生成」", (htmlPart.content as String).contains("本季度账单已生成"))
    }

    @Test
    fun parsesGb18030HtmlBodyAndGb2312Subject() {
        val message = parse("gb18030_html.eml")

        assertEquals("账户安全提醒", message.subject)
        val from = sender(message)
        assertEquals("notify@bank.example", from.address)
        assertEquals("银行通知", from.personal)

        assertEquals("text/html", message.contentType.substringBefore(';'))
        val body = message.content as String
        assertTrue("GB18030 正文应包含「您的验证码为 552910」，实际是：$body", body.contains("您的验证码为 552910"))
    }

    @Test
    fun decodesRfc2231AndRfc2047AttachmentNames() {
        val message = parse("attachments.eml")

        assertEquals("本月账单与对账单", message.subject)
        val from = sender(message)
        assertEquals("billing@shop.example", from.address)
        assertEquals("账单中心", from.personal)

        assertEquals("multipart/mixed", message.contentType.substringBefore(';'))
        val multipart = message.content as Multipart
        assertEquals("正文 + 两个附件", 3, multipart.count)

        val body = textPlain(message)
        assertTrue("正文应包含「附件是本月的账单与对账单」", body.contains("附件是本月的账单与对账单"))

        val names = attachmentNames(message)
        assertEquals(listOf("账单.pdf", "对账单_2026.pdf"), names)
    }

    private fun parse(name: String): MimeMessage {
        val raw = checkNotNull(javaClass.getResourceAsStream("/mime/$name")) {
            "找不到测试资源 /mime/$name"
        }.use { it.readBytes() }
        return Mime.parseMime(raw)
    }

    private fun sender(message: MimeMessage): InternetAddress {
        val from = message.from
        assertEquals("From 应只有一个地址", 1, from.size)
        return from[0] as InternetAddress
    }

    /** 取整封邮件里第一段 text/plain 的正文（本任务不做正文提取逻辑，只是读出来断言）。 */
    private fun textPlain(part: Part): String {
        if (part.isMimeType("text/plain")) {
            return part.content as String
        }
        if (part.content is Multipart) {
            val multipart = part.content as Multipart
            for (index in 0 until multipart.count) {
                val nested = multipart.getBodyPart(index)
                if (!nested.isMimeType("text/plain")) continue
                return nested.content as String
            }
        }
        return ""
    }

    private fun attachmentNames(part: Part): List<String> {
        val content = part.content
        if (content !is Multipart) return emptyList()
        val names = mutableListOf<String>()
        for (index in 0 until content.count) {
            val nested = content.getBodyPart(index)
            val fileName = nested.fileName
            if (fileName != null && Part.ATTACHMENT.equals(nested.disposition, ignoreCase = true)) {
                names += fileName
            } else {
                names += attachmentNames(nested)
            }
        }
        return names
    }
}
