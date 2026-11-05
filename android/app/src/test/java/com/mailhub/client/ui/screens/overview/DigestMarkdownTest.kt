package com.mailhub.client.ui.screens.overview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 晨报极简 Markdown 的解析规则，与网页端 `static/app.js` 的 `miniMd` 一致：
 * 标题、列表、行内加粗、空行，以及不认识的语法原样保留。
 *
 * 真机验收时晨报把 `# 2026-11-04 晨报`、`## 一句话速览`、`- 可疑邮件 12 封…`
 * 这些标记原样显示了出来，这一层就是防止再退回去。
 */
class DigestMarkdownTest {

    @Test
    fun headingsLoseTheirHashes() {
        val blocks = parseDigest("# 2026-11-04 晨报\n## 一句话速览\n#### 四级标题")

        assertEquals(3, blocks.size)
        assertEquals(
            listOf(
                "2026-11-04 晨报",
                "一句话速览",
                "四级标题",
            ),
            blocks.map { it as DigestBlock.Heading }.map { heading -> heading.spans.joinToString("") { it.text } },
        )
        // 标题不带井号，也不会被当成加粗片段切开（渲染时整段加粗）
        blocks.forEach { block ->
            assertTrue(block is DigestBlock.Heading)
            assertTrue((block as DigestBlock.Heading).spans.none { it.bold })
        }
    }

    @Test
    fun fiveHashesIsNotAHeading() {
        val blocks = parseDigest("##### 这不是标题")

        assertEquals(listOf<DigestBlock>(DigestBlock.Paragraph(listOf(DigestSpan("##### 这不是标题", false)))), blocks)
    }

    @Test
    fun dashAndStarLinesBecomeBullets() {
        val blocks = parseDigest("- 可疑邮件 12 封\n* 新设备登录 1 次")

        assertEquals(2, blocks.size)
        assertEquals(listOf("可疑邮件 12 封", "新设备登录 1 次"), blocks.map { it as DigestBlock.Bullet }.map { it.spans.single().text })
        // 列表标记不进入文字，渲染时另外放「—」
        assertEquals("—", DigestBulletMark)
    }

    @Test
    fun inlineBoldIsSplitIntoSpans() {
        val blocks = parseDigest("昨夜 **12 封** 可疑")

        val paragraph = blocks.single() as DigestBlock.Paragraph
        assertEquals(
            listOf(
                DigestSpan("昨夜 ", false),
                DigestSpan("12 封", true),
                DigestSpan(" 可疑", false),
            ),
            paragraph.spans,
        )
    }

    @Test
    fun blankLineBecomesOneParagraphGap() {
        val blocks = parseDigest("# 标题\n\n\n正文第一段")

        assertEquals(DigestBlock.Heading(listOf(DigestSpan("标题", false))), blocks[0])
        // 连续空行只算一个间距，文末的空行不产生间距
        assertEquals(DigestBlock.Gap, blocks[1])
        assertEquals(DigestBlock.Paragraph(listOf(DigestSpan("正文第一段", false))), blocks[2])
        assertEquals(3, blocks.size)
    }

    @Test
    fun unknownSyntaxIsKeptAsPlainText() {
        val line = "[链接](https://example.com) 与 <b>HTML</b> 与 **未闭合的加粗"
        val blocks = parseDigest(line)

        val paragraph = blocks.single() as DigestBlock.Paragraph
        // 没配对的 ** 不当加粗，链接、图片、HTML 一律原样保留
        assertEquals(listOf(DigestSpan(line, false)), paragraph.spans)
        assertEquals(line, paragraph.spans.joinToString("") { it.text })
    }

    @Test
    fun realDigestFromTheScreenshotCaseIsParsed() {
        val source = "# 2026-11-04 晨报\n" +
            "## 一句话速览\n" +
            "- 可疑邮件 12 封，其中 3 封与支付平台有关\n" +
            "- 两张账单本周到期\n" +
            "\n" +
            "**建议**：先处理可疑邮件。"

        val blocks = parseDigest(source)

        assertEquals(
            listOf(
                DigestBlock.Heading(listOf(DigestSpan("2026-11-04 晨报", false))),
                DigestBlock.Heading(listOf(DigestSpan("一句话速览", false))),
                DigestBlock.Bullet(listOf(DigestSpan("可疑邮件 12 封，其中 3 封与支付平台有关", false))),
                DigestBlock.Bullet(listOf(DigestSpan("两张账单本周到期", false))),
                DigestBlock.Gap,
                DigestBlock.Paragraph(
                    listOf(DigestSpan("建议", true), DigestSpan("：先处理可疑邮件。", false)),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun emptySourceHasNoBlocks() {
        assertTrue(parseDigest("").isEmpty())
        assertTrue(parseDigest("\n\n  \n").isEmpty())
    }
}
