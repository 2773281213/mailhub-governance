package com.mailhub.client.ui.screens.overview

/**
 * 晨报正文的一段。解析结果只描述结构，不包含任何排版信息，
 * 渲染（[OverviewScreen] 里的晨报区块）负责把段落摆到栏线上。
 */
sealed interface DigestBlock {

    /** 标题：`#` 到 `####` 开头的行，去掉井号后作为整段加粗的文字。 */
    data class Heading(val spans: List<DigestSpan>) : DigestBlock

    /** 列表项：`- ` 或 `* ` 开头的行，渲染时标记「—」占第 1 栏、文字从第 2 栏起排。 */
    data class Bullet(val spans: List<DigestSpan>) : DigestBlock

    /** 普通段落。 */
    data class Paragraph(val spans: List<DigestSpan>) : DigestBlock

    /** 空行：只贡献一个段落间距（[com.mailhub.client.ui.swiss.SwissSpace.S]）。 */
    data object Gap : DigestBlock
}

/** 一段文字：同一行里连续的加粗或非加粗片段。 */
data class DigestSpan(val text: String, val bold: Boolean)

/** 列表标记，渲染时放在第 1 栏。 */
const val DigestBulletMark: String = "—"

/**
 * 晨报的极简 Markdown 解析，规则与网页端 `static/app.js` 的 `miniMd` 一致：
 *
 * - `#` 到 `####` 开头的行：去掉井号，作为加粗段落（标题）；
 * - `- ` 或 `* ` 开头的行：列表项；
 * - 行内 `**…**`：加粗片段；
 * - 空行：段落间距，连续的空行只算一个；
 * - 其余内容按纯文本显示，链接、图片、HTML、其它 Markdown 语法一律原样保留，不做解析。
 *
 * 纯函数，不依赖 Android 或 Compose 运行时，方便直接断言。
 */
fun parseDigest(source: String): List<DigestBlock> {
    val blocks = mutableListOf<DigestBlock>()
    source.split("\n").forEach { rawLine ->
        val line = rawLine.trimEnd('\r')
        if (line.isBlank()) {
            // 连续空行只产生一个间距，避免一大段空白
            if (blocks.isNotEmpty() && blocks.last() != DigestBlock.Gap) {
                blocks += DigestBlock.Gap
            }
            return@forEach
        }
        val bullet = bulletText(line)
        if (bullet != null) {
            blocks += DigestBlock.Bullet(parseInline(bullet))
            return@forEach
        }
        val heading = headingText(line)
        if (heading != null) {
            blocks += DigestBlock.Heading(parseInline(heading))
            return@forEach
        }
        blocks += DigestBlock.Paragraph(parseInline(line))
    }
    // 文末的空行不算段落间距
    while (blocks.isNotEmpty() && blocks.last() == DigestBlock.Gap) {
        blocks.removeAt(blocks.lastIndex)
    }
    return blocks
}

/** 列表项的匹配规则，与网页端 miniMd 的 `/^\s*[-*]\s+(.*)/` 一致。 */
private val BulletRegex = Regex("^\\s*[-*]\\s+(.*)$")

/** 1–4 级标题的匹配规则，与网页端 miniMd 的 `/^#{1,4}\s/` 一致。 */
private val HeadingRegex = Regex("^#{1,4}\\s")

/** 标题去掉开头井号的规则，与网页端 miniMd 一致（先吃掉开头的井号和空白）。 */
private val HeadingMarkRegex = Regex("^#+\\s*")

/** 列表项的文字部分；不是列表项时返回 null。 */
private fun bulletText(line: String): String? =
    BulletRegex.matchEntire(line)?.groupValues?.get(1)

/** 标题文字：去掉开头的井号；不是 1–4 级标题时返回 null。 */
private fun headingText(line: String): String? {
    if (!HeadingRegex.containsMatchIn(line)) return null
    return line.replace(HeadingMarkRegex, "")
}

/**
 * 把一行文字按 `**…**` 切成若干片段。没有配对的 `**` 原样保留，
 * 不会吞掉后面的文字。
 */
fun parseInline(line: String): List<DigestSpan> {
    val spans = mutableListOf<DigestSpan>()
    val plain = StringBuilder()
    var index = 0
    while (index < line.length) {
        val end = if (line.startsWith("**", index)) line.indexOf("**", index + 2) else -1
        // 空的一对 ** （前后紧挨）不当加粗，保持原样
        if (end > index + 2) {
            if (plain.isNotEmpty()) {
                spans += DigestSpan(plain.toString(), bold = false)
                plain.clear()
            }
            spans += DigestSpan(line.substring(index + 2, end), bold = true)
            index = end + 2
            continue
        }
        plain.append(line[index])
        index++
    }
    if (plain.isNotEmpty()) spans += DigestSpan(plain.toString(), bold = false)
    return spans
}
