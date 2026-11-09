package com.mailhub.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可读的分支覆盖：[SecurityScan] 的每类检查各几条断言，外加 HTML 分词与 URL 主机名的关键点。
 * 期望值全部来自 Python 原实现（金标语料里有逐字用例，这里只挑能读懂意图的几条）。
 */
class SecurityScanTest {

    private fun scan(
        subject: String = "",
        body: String = "",
        senderName: String = "",
        senderAddr: String = "",
        replyTo: String = "",
        attachments: List<String>? = null,
        html: String = "",
        authResults: String = "",
    ): ScanResult = SecurityScan.scan(
        subject, body, senderName, senderAddr, replyTo, attachments, html, authResults)

    @Test
    fun cleanMailHasNoRisk() {
        val result = scan("周会纪要", "详见正文，无附件。", "李四", "lisi@corp.com")
        assertEquals(SecurityScan.RISK_NONE, result.risk)
        assertEquals(emptyList<String>(), result.reasons)
    }

    @Test
    fun displayNameWithAnotherDomainIsHighRisk() {
        val result = scan("账户提醒", "请点击", "PayPal service@paypal.com", "attacker@evil-domain.tk")
        assertEquals(SecurityScan.RISK_HIGH, result.risk)
        assertEquals(
            listOf(
                "显示名伪装成 paypal.com，实际发件域为 evil-domain.tk",
                "自称「paypal」但发件域 evil-domain.tk 不属于其官方域名",
            ),
            result.reasons,
        )
    }

    @Test
    fun brandMatchNeedsWordBoundary() {
        // "Pineapple" 是整词，不该命中 apple
        assertEquals(
            SecurityScan.RISK_NONE,
            scan("Weekly menu", "", "Pineapple Kitchen", "hello@kitchen.example").risk,
        )
        assertEquals(
            SecurityScan.RISK_HIGH,
            scan("您的 Apple ID 异常", "", "Apple 支持", "no-reply@apple-id-verify.xyz").risk,
        )
        // 官方域名的子域不算冒用
        assertEquals(
            SecurityScan.RISK_NONE,
            scan("Receipt", "", "GitHub", "noreply@mail.github.com").risk,
        )
    }

    @Test
    fun attachmentsByExtension() {
        assertEquals(
            listOf("含可执行附件 invoice.pdf.exe（不会自动打开）"),
            scan("发票", "", "财务", "a@b.example", attachments = listOf("invoice.pdf.exe")).reasons,
        )
        assertEquals(
            listOf("含压缩包附件 docs.zip，内容无法预先检查"),
            scan("资料", "", "同事", "a@b.example", attachments = listOf("docs.zip")).reasons,
        )
        // 大写扩展名同样命中；没有扩展名的不算
        assertEquals(
            SecurityScan.RISK_HIGH,
            scan("x", "", "a", "a@b.example", attachments = listOf("A.SCR")).risk,
        )
        assertEquals(
            SecurityScan.RISK_NONE,
            scan("x", "", "a", "a@b.example", attachments = listOf("README")).risk,
        )
    }

    @Test
    fun bareIpAndConfusableHosts() {
        assertEquals(
            listOf("正文含指向裸 IP 的链接 203.0.113.9"),
            scan("请登录", "访问 http://203.0.113.9/login 完成验证", "IT", "it@corp.com").reasons,
        )
        assertEquals(
            SecurityScan.RISK_HIGH,
            scan("x", "", "a", "a@b.example", html = "<a href=\"http://203.0.113.7/x\">t</a>").risk,
        )
        // 西里尔字母 а（U+0430）
        assertEquals(
            listOf("发件域 sberb\u0430nk.com 含非拉丁同形字符，疑似仿冒域名"),
            scan("通知", "", "银行", "svc@sberb\u0430nk.com").reasons,
        )
    }

    @Test
    fun replyToAndAuthResults() {
        assertEquals(
            SecurityScan.RISK_MEDIUM,
            scan("合作", "", "张三", "zhang@corp.com", replyTo = "x@gmail.com").risk,
        )
        assertEquals(
            SecurityScan.RISK_MEDIUM,
            scan("周会纪要", "详见正文", "李四", "lisi@corp.com",
                authResults = "mx.example; spf=fail smtp.mailfrom=b.com; dkim=none").risk,
        )
        assertEquals(
            SecurityScan.RISK_NONE,
            scan("周会纪要", "详见正文", "李四", "lisi@corp.com",
                authResults = "mx.example; spf=fail smtp.mailfrom=b.com; dkim=pass header.d=b.com").risk,
        )
    }

    @Test
    fun urgentTalkNeedsWordsNotJustLinks() {
        assertEquals(
            listOf("含账户风险类紧迫话术且带外部链接，符合钓鱼特征"),
            scan("您的账户异常，请立即验证", "点击 https://short.tk/x", "客服", "a@b.example").reasons,
        )
        assertEquals(
            listOf("含账户风险类紧迫话术"),
            scan("日历提醒", "限时 3 天处理，逾期失效。", "客服", "a@b.example").reasons,
        )
        assertEquals(
            SecurityScan.RISK_NONE,
            scan("本周上新", "详见 https://shop.example.com/new", "Shop", "news@shop.example.com").risk,
        )
    }

    @Test
    fun riskOrderAndRegistrableDomain() {
        assertTrue(SecurityScan.riskAtLeast(SecurityScan.RISK_HIGH, SecurityScan.RISK_MEDIUM))
        assertTrue(SecurityScan.riskAtLeast(SecurityScan.RISK_LOW, SecurityScan.RISK_LOW))
        assertFalse(SecurityScan.riskAtLeast("bogus", SecurityScan.RISK_LOW))
        assertEquals("example.com", SecurityScan.registrableDomain("mail.example.com"))
        assertEquals("example.co.uk", SecurityScan.registrableDomain("a.b.example.co.uk"))
        assertEquals("example.com.cn", SecurityScan.registrableDomain("shop.example.com.cn"))
        assertEquals("localhost", SecurityScan.registrableDomain("localhost"))
        assertEquals("example.com", SecurityScan.registrableDomain("...EXAMPLE.com..."))
    }

    @Test
    fun extractLinksHandlesHtmlQuirks() {
        assertEquals(
            listOf("http://a.example/x" to "T"),
            SecurityScan.extractHtmlLinks("<A HREF=\"http://a.example/x\">T</A>"),
        )
        // 重复的 href 以最后一个为准
        assertEquals(
            listOf("http://a.example/2" to "t"),
            SecurityScan.extractHtmlLinks("<a href=\"http://a.example/1\" href=\"http://a.example/2\">t</a>"),
        )
        // 属性值与文本里的字符引用都会转换
        assertEquals(
            listOf("http://a.example/?a=1&b=2" to "x y&z"),
            SecurityScan.extractHtmlLinks("<a href=\"http://a.example/?a=1&amp;b=2\">x&nbsp;y&amp;z</a>"),
        )
        // <script> 里的内容不算，</a> 之后的 HTML 照常解析
        assertEquals(
            listOf("http://a.example/t" to "t"),
            SecurityScan.extractHtmlLinks(
                "<script>var a = \"<a href='http://a.example/s'>\";</script>" +
                    "<a href=\"http://a.example/t\">t</a>"),
        )
        // 自闭合的 <a/> 记一条空文字的链接；截断的标签不产生链接
        assertEquals(
            listOf("http://a.example/h" to ""),
            SecurityScan.extractHtmlLinks("<a href=\"http://a.example/h\"/>"),
        )
        assertEquals(emptyList<Pair<String, String>>(), SecurityScan.extractHtmlLinks("<a href=\"http://a.example/i\""))
        // limit 生效
        assertEquals(
            listOf("http://a.example/1" to "1"),
            SecurityScan.extractHtmlLinks(
                "<a href=\"http://a.example/1\">1</a><a href=\"http://a.example/2\">2</a>", limit = 1),
        )
    }

    @Test
    fun linkHostFollowsUrlparse() {
        assertEquals("example.com", SecurityScan.linkHost("https://Example.COM:8080/p"))
        assertEquals("example.com", SecurityScan.linkHost("https://user:pass@example.com/y"))
        assertEquals("::1", SecurityScan.linkHost("http://[::1]:8080/x"))
        assertEquals("fe80::1%25eth0", SecurityScan.linkHost("http://[fe80::1%25ETH0]/x"))
        // 不合法的方括号（Python 抛 ValueError）、非 http(s)、相对链接都返回空串
        assertEquals("", SecurityScan.linkHost("http://[foo]/x"))
        assertEquals("", SecurityScan.linkHost("http://[1.2.3.4]/x"))
        assertEquals("", SecurityScan.linkHost("http://[::1"))
        assertEquals("", SecurityScan.linkHost("ftp://example.com/x"))
        assertEquals("", SecurityScan.linkHost("/relative/path"))
    }

    @Test
    fun authenticationResultsKeepsFirstVerdict() {
        assertEquals(
            mapOf("dmarc" to "fail"),
            SecurityScan.parseAuthenticationResults("x; dmarc=fail dmarc=pass"),
        )
        assertEquals(
            mapOf("dkim" to "pass", "spf" to "pass", "dmarc" to "fail"),
            SecurityScan.parseAuthenticationResults(
                "mx.google.com; dkim=pass header.i=@a.com; spf=pass; dmarc=fail (p=REJECT)"),
        )
        assertEquals(emptyMap<String, String>(), SecurityScan.parseAuthenticationResults(""))
        assertEquals(emptyMap<String, String>(), SecurityScan.parseAuthenticationResults(null))
    }
}
