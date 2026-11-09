package com.mailhub.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源上限与 3.14 解析语义的回归测试。
 *
 * 邮件内容由攻击者控制：这里的每条最坏输入都必须在 2 秒内返回、不抛异常，
 * 并且「能提取到链接的要提取到」——解析器退化时不能悄悄把整封信的链接检查关掉。
 * 期望值来自 Python 3.14.3 的 `html.parser`（金标语料里有对应用例）。
 */
class SecurityScanLimitsTest {

    private fun assertWithinTwoSeconds(what: String, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("$what 用了 ${elapsedMs}ms，超过 2000ms", elapsedMs < 2000)
    }

    @Test
    fun hidingTricksDoNotDisableLinkChecks() {
        val bareIp = "<a href=\"http://203.0.113.9/login\">click</a>"
        for (prefix in listOf("<![x]>", "<script>x</script y>", "<style>a{}</style/>")) {
            val html = prefix + bareIp
            assertEquals(html, listOf("http://203.0.113.9/login" to "click"),
                SecurityScan.extractHtmlLinks(html))
            assertEquals(html, SecurityScan.RISK_HIGH,
                SecurityScan.scan("s", "b", "n", "a@b.example", html = html).risk)
        }
        // 注释的 `--!>` 与后面的 `-->`：3.13 会一路吞到第二个注释的结尾
        val comment = "<!-- c --!>" + bareIp + "<!-- -->"
        assertEquals(
            listOf("http://203.0.113.9/login" to "click"),
            SecurityScan.extractHtmlLinks(comment),
        )
        assertEquals(
            SecurityScan.RISK_HIGH,
            SecurityScan.scan("s", "b", "n", "a@b.example", html = comment).risk,
        )
    }

    @Test
    fun titleAndTextareaContentsAreTextNotTags() {
        assertEquals(
            listOf("http://a.example/after" to "a"),
            SecurityScan.extractHtmlLinks(
                "<title><a href=\"http://a.example/t\">t</a></title>" +
                    "<a href=\"http://a.example/after\">a</a>"),
        )
        assertEquals(
            listOf("http://a.example/after" to "a"),
            SecurityScan.extractHtmlLinks(
                "<textarea><a href=\"http://a.example/t\">t</a></textarea>" +
                    "<a href=\"http://a.example/after\">a</a>"),
        )
        // 结束标签名后面必须是空白、斜杠或 >，</titley> 不算结束，后面的链接仍是文本
        assertEquals(
            emptyList<Pair<String, String>>(),
            SecurityScan.extractHtmlLinks(
                "<title>x</titley><a href=\"http://a.example/after\">a</a>"),
        )
    }

    @Test
    fun attributeCharRefsFollowPython314() {
        // 3.14 的属性值只转换完整实体名：&notit; 原样保留（3.13 会变成 ¬it;）
        assertEquals(
            listOf("&notit;" to "x"),
            SecurityScan.extractHtmlLinks("<a href=\"&notit;\">x</a>"),
        )
        // 完整实体名照常转换
        assertEquals(
            listOf("\u00ac" to "x"),
            SecurityScan.extractHtmlLinks("<a href=\"&not;\">x</a>"),
        )
        // 数字引用总是转换
        assertEquals(
            listOf("&" to "x"),
            SecurityScan.extractHtmlLinks("<a href=\"&#38;\">x</a>"),
        )
    }

    @Test
    fun normalInputIsReportedAsComplete() {
        assertTrue(SecurityScan.extractHtmlLinksResult("<a href=\"http://a.example/x\">x</a>").complete)
    }

    @Test
    fun incompleteHtmlParseIsMediumRisk() {
        // 注入「解析不完整」的结果：保留已经提取到的链接，并追加中风险理由
        val result = SecurityScan.scanInternal(
            subject = "周会纪要",
            body = "详见正文",
            senderName = "李四",
            senderAddr = "lisi@corp.com",
            replyTo = "",
            attachments = null,
            html = "<a href=\"http://x.example/\">x</a>",
            authResults = "",
            linkExtractor = { HtmlLinksResult(listOf("http://x.example/" to "x"), complete = false) },
        )
        assertEquals(SecurityScan.RISK_MEDIUM, result.risk)
        assertEquals(listOf("HTML 无法完整解析"), result.reasons)
    }

    @Test
    fun worstCaseInputsReturnWithinTwoSeconds() {
        val link = "<a href=\"http://x.example/\">t</a>"
        assertWithinTwoSeconds("标签名后十万个空格") {
            assertEquals(
                listOf("http://x.example/" to "t"),
                SecurityScan.extractHtmlLinks("<i" + " ".repeat(100000) + ">" + link),
            )
        }
        assertWithinTwoSeconds("超长链接文字") {
            val links = SecurityScan.extractHtmlLinks(
                "<a href=\"http://x.example/\">" + "a.".repeat(140000) + "</a>")
            assertEquals(1, links.size)
            assertEquals("http://x.example/", links[0].first)
        }
        // 上千个恰好 253 字符、且最后一段不是字母的候选：旧的 _DOMAIN_TEXT_RE 会逐个平方级回溯
        val candidateChunk = "a".repeat(126) + "." + "1".repeat(126) + " "
        val candidateText = candidateChunk.repeat(1100)
        assertWithinTwoSeconds("上千个 253 字符的候选") {
            val links = SecurityScan.extractHtmlLinks(
                "<a href=\"http://x.example/\">" + candidateText + "</a>")
            assertEquals(1, links.size)
        }
        assertWithinTwoSeconds("扫描上千个 253 字符的候选") {
            SecurityScan.scan(
                "s", "b", "n", "a@b.example",
                html = "<a href=\"http://x.example/\">" + candidateText + "</a>",
            )
        }
        assertWithinTwoSeconds("十五万个 <a") {
            assertEquals(
                emptyList<Pair<String, String>>(),
                SecurityScan.extractHtmlLinks("<a".repeat(150000)),
            )
        }
        assertWithinTwoSeconds("七万五千个 <!--") {
            assertEquals(
                emptyList<Pair<String, String>>(),
                SecurityScan.extractHtmlLinks("<!--".repeat(75000)),
            )
        }
        assertWithinTwoSeconds("十五万个 <?") {
            assertEquals(
                emptyList<Pair<String, String>>(),
                SecurityScan.extractHtmlLinks("<?".repeat(150000)),
            )
        }
        assertWithinTwoSeconds("十万码点的显示名") {
            assertEquals(
                SecurityScan.RISK_NONE,
                SecurityScan.scan("", "", "a".repeat(100000), "a@b.example").risk,
            )
        }
        val fullwidth = "<" + "\u3000".repeat(100000)
        assertWithinTwoSeconds("十万个全角空格作主题") {
            assertEquals(
                SecurityScan.RISK_NONE,
                SecurityScan.scan(subject = fullwidth, body = "", senderName = "", senderAddr = "").risk,
            )
        }
        assertWithinTwoSeconds("十万个全角空格作正文") {
            assertEquals(
                SecurityScan.RISK_NONE,
                SecurityScan.scan(subject = "", body = fullwidth, senderName = "", senderAddr = "").risk,
            )
        }
        assertWithinTwoSeconds("十万个全角空格的注入检测") {
            TextGuard.hasInjection(fullwidth)
        }
    }

    @Test
    fun injectionTagWhitespaceIsBounded() {
        // `<` 与标签名之间是两个 `{0,64}` 量词，所以 128 个空白仍算标签、129 个不命中
        // （与 Python 的有意偏差，见 TextGuard 的 KDoc）
        assertTrue(TextGuard.hasInjection("<" + " ".repeat(128) + "system>"))
        assertFalse(TextGuard.hasInjection("<" + " ".repeat(129) + "system>"))
    }
}
