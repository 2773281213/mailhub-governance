package com.mailhub.core.mail

import com.mailhub.core.text.PyHtml
import com.mailhub.core.text.PyRegex
import com.mailhub.core.text.cpLen
import com.mailhub.core.text.cpSlice
import com.mailhub.core.text.isPySpace
import com.mailhub.core.text.pyStrip
import java.util.regex.Pattern

/**
 * `mail_client` 里的正文辅助函数，逐个对应 Python 的同名函数：
 * [htmlToText]、[isStubText]、[tidyBody]、[makeSnippet]、[readableBody]。
 *
 * 参数可以是 null，对应 Python 的 `None`（各函数里都是 `x or ""`）。
 *
 * **有意偏差：三个正则改成手写线性扫描。** Python 用的是
 * `_TAG_RE = r"<(script|style)[^>]*>.*?</\1>"`、`_HTML_RE = r"<[^>]+>"` 和
 * `_URL_RE = r"https?://\S+|<https?://[^>]*>"`。邮件正文由攻击者控制，`.*?` 与 `[^>]*>`
 * 在「很多次开头、始终没有结尾」的输入上是平方级：一兆字节的 `<script` 或 `<https://`
 * 就能让正则跑上几分钟。这里用单遍扫描实现同一语义（同样的最左匹配、同样的非重叠替换），
 * 最坏输入线性。
 */

/** `mail_client.html_to_text`：`<script>` / `<style>` 整段删掉，换行标签转换行，其余标签换空格，再解实体。 */
fun htmlToText(html: String?): String {
    var text = removeScriptAndStyle(html ?: "")
    text = replaceLineBreakTags(text)
    text = removeTags(text)
    return PyHtml.unescape(text)
}

/**
 * `mail_client.is_stub_text`：纯文本部分是否只是「请在浏览器中查看」之类的占位。
 *
 * 去掉链接后很短且带占位提示，或几乎只有链接，同时 HTML 转出的文字明显更长。
 */
fun isStubText(text: String?, htmlText: String?): Boolean {
    val plain = collapseSpaces(subUrls(text ?: "")).pyStrip()
    val rich = collapseSpaces(htmlText ?: "").pyStrip()
    if (rich.cpLen() < maxOf(200, 2 * plain.cpLen())) return false
    return plain.cpLen() < 80 || (plain.cpLen() < 600 && STUB_HINT.matcher(plain).find())
}

/** `mail_client.tidy_body`：连续的空格 / 制表符 / 裸 CR 折成一个空格，3 个以上换行折成两个，去掉首尾空白，截断到 50000 码点。 */
fun tidyBody(text: String?): String {
    val collapsed = collapseHorizontalSpace(text ?: "")
    return collapseNewlines(collapsed).pyStrip().cpSlice(0, 50000)
}

/** `mail_client.make_snippet`：空白折成单个空格，取前 200 码点（不 strip）。 */
fun makeSnippet(text: String?): String = collapseSpaces(text ?: "").cpSlice(0, 200)

/**
 * `mail_client.readable_body`：供分类使用的正文。
 * 纯文本是占位或为空时改用 HTML 转出的文字；没有 HTML 时直接用纯文本。
 *
 * 返回 null 只在「[bodyText] 为 null、[bodyHtml] 非空、且不判占位」时出现，
 * 对应 Python 的 `return body_text`（[tidyBody] 会把 null 当成空串，与 Python 相同）。
 */
fun readableBody(bodyText: String?, bodyHtml: String?): String? {
    if (bodyHtml.isNullOrEmpty()) return bodyText ?: ""
    val htmlText = htmlToText(bodyHtml)
    if ((bodyText ?: "").pyStrip().isEmpty() || isStubText(bodyText, htmlText)) return htmlText
    return bodyText
}

/** `_STUB_HINT`：Python 源码里的原串，`re.I`。 */
private val STUB_HINT: Pattern = PyRegex.compile(
    "在(网页|浏览器)(上|中)?查看|要查看详情|请访问|查看完整|无法(正常)?显示" +
        "|view\\s+(this|it|the)?\\s*(e-?mail|message)?\\s*(in|on)\\s+(your|a|the)?\\s*(web\\s*)?browser" +
        "|view\\s+(it\\s+)?online|not\\s+support\\s+html|open\\s+(this|the)\\s+(e-?mail|message)\\s+in" +
        "|to\\s+view\\s+(this|the)\\s+(e-?mail|message)",
    ignoreCase = true,
)

/** Python 的 `re.sub(r"\s+", " ", text)`：每段 [isPySpace] 空白折成一个空格。 */
private fun collapseSpaces(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    var pendingSpace = false
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (isPySpace(codePoint)) {
            pendingSpace = true
        } else {
            if (pendingSpace) out.append(' ')
            pendingSpace = false
            out.appendCodePoint(codePoint)
        }
        index += Character.charCount(codePoint)
    }
    if (pendingSpace) out.append(' ')
    return out.toString()
}

/** Python 的 `re.sub(r"[ \t\r]+", " ", text)`。 */
private fun collapseHorizontalSpace(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    var pending = false
    while (index < text.length) {
        val ch = text[index]
        if (ch == ' ' || ch == '\t' || ch == '\r') {
            pending = true
        } else {
            if (pending) out.append(' ')
            pending = false
            out.append(ch)
        }
        index++
    }
    if (pending) out.append(' ')
    return out.toString()
}

/** Python 的 `re.sub(r"\n{3,}", "\n\n", text)`。 */
private fun collapseNewlines(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        if (text[index] != '\n') {
            out.append(text[index])
            index++
            continue
        }
        var end = index
        while (end < text.length && text[end] == '\n') end++
        if (end - index >= 3) out.append("\n\n") else out.append(text, index, end)
        index = end
    }
    return out.toString()
}

private const val SCRIPT = "script"
private const val STYLE = "style"

/**
 * `_TAG_RE.sub(" ", h)`：删掉 `<script …>…</script>` / `<style …>…</style>`（大小写不敏感，`.` 含换行）。
 *
 * 逐个候选位置判断：`<` 后面是 script 或 style，下一个 `>` 结束开始标签，之后找同一个名字的
 * `</name>`。找不到闭合标签时 Python 的这个位置不匹配，扫描从下一个字符继续——这里用
 * 「某个名字的闭合标签一旦找不到，后面也不可能再有」来避免重复扫描，保证线性。
 */
private fun removeScriptAndStyle(text: String): String {
    val out = StringBuilder(text.length)
    var copied = 0
    var index = 0
    var scriptCloseGone = false
    var styleCloseGone = false
    while (true) {
        val lt = text.indexOf('<', index)
        if (lt < 0) break
        val name = when {
            text.regionMatches(lt + 1, "script", 0, SCRIPT.length, ignoreCase = true) -> SCRIPT
            text.regionMatches(lt + 1, "style", 0, STYLE.length, ignoreCase = true) -> STYLE
            else -> {
                index = lt + 1
                continue
            }
        }
        if ((name == SCRIPT && scriptCloseGone) || (name == STYLE && styleCloseGone)) {
            index = lt + 1
            continue
        }
        // `[^>]*>`：开始标签在第一个 `>` 处结束
        val tagEnd = text.indexOf('>', lt + 1 + name.length)
        if (tagEnd < 0) {
            // 后面已经没有 `>`，这个位置和更靠后的位置都不会匹配
            scriptCloseGone = true
            styleCloseGone = true
            index = lt + 1
            continue
        }
        val closeTag = "</$name>"
        val close = indexOfIgnoreCase(text, closeTag, tagEnd + 1)
        if (close < 0) {
            if (name == SCRIPT) scriptCloseGone = true else styleCloseGone = true
            index = lt + 1
            continue
        }
        out.append(text, copied, lt)
        out.append(' ')
        copied = close + closeTag.length
        index = copied
    }
    out.append(text, copied, text.length)
    return out.toString()
}

/** 大小写不敏感的 `indexOf`：先找小写化文本里的小写 needle，再回原串取位置。 */
private fun indexOfIgnoreCase(text: String, needle: String, from: Int): Int {
    if (from > text.length) return -1
    // 只在 ASCII 上做大小写折叠（标签名都是 ASCII），逐位置比较，均摊线性
    var index = from
    val last = text.length - needle.length
    while (index <= last) {
        if (text.regionMatches(index, needle, 0, needle.length, ignoreCase = true)) return index
        index++
    }
    return -1
}

/**
 * `re.sub(r"<br\s*` + `/?>|</p>|</div>|</tr>", "\n", h, flags=re.I)`（Python 源串被拆开写，
 * 避免这里的注释里出现注释结束符）。
 * 四个分支的首字符组合不同，同一位置最多匹配一个；每个分支只向前看固定长度（`\s*` 除外，
 * 但它扫过的空白不会再被别的 `<` 扫到），所以整体线性。
 */
private fun replaceLineBreakTags(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        if (text[index] == '<') {
            val matched = matchLineBreakTag(text, index)
            if (matched > 0) {
                out.append('\n')
                index += matched
                continue
            }
        }
        out.append(text[index])
        index++
    }
    return out.toString()
}

/** 在 [start] 处匹配换行标签，返回匹配长度；不匹配返回 0。 */
private fun matchLineBreakTag(text: String, start: Int): Int {
    var index = start + 1
    if (text.regionMatches(index, "br", 0, 2, ignoreCase = true)) {
        index += 2
        while (index < text.length && isPySpace(text.codePointAt(index))) {
            index += Character.charCount(text.codePointAt(index))
        }
        if (index < text.length && text[index] == '/') index++
        if (index < text.length && text[index] == '>') return index + 1 - start
        return 0
    }
    for (name in CLOSING_TAGS) {
        if (text.regionMatches(index, name, 0, name.length, ignoreCase = true)) {
            val end = index + name.length + 1
            if (end <= text.length && text[end - 1] == '>') return end - start
        }
    }
    return 0
}

/** `</p>`、`</div>`、`</tr>` 去掉结尾的 `>` 之后的部分。 */
private val CLOSING_TAGS = listOf("/p", "/div", "/tr")

/** `_HTML_RE.sub(" ", h)`：`<[^>]+>`（至少一个非 `>` 字符）换成空格。 */
private fun removeTags(text: String): String {
    val out = StringBuilder(text.length)
    var copied = 0
    var index = 0
    while (index < text.length) {
        if (text[index] == '<') {
            var end = index + 1
            while (end < text.length && text[end] != '>') end++
            if (end >= text.length) break // 后面再没有 `>`，不可能再匹配
            if (end > index + 1) {
                out.append(text, copied, index)
                out.append(' ')
                copied = end + 1
                index = end + 1
                continue
            }
        }
        index++
    }
    out.append(text, copied, text.length)
    return out.toString()
}

/**
 * `_URL_RE.sub(" ", text)`：`https?://\S+| <https?://[^>]*>`（大小写不敏感）。
 * 第二个分支找不到 `>` 时，后面也不会再有，用一个标志避免重复扫描。
 */
private fun subUrls(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    var noMoreGt = false
    while (index < text.length) {
        val scheme = matchHttpScheme(text, index)
        if (scheme > 0) {
            var end = index + scheme
            while (end < text.length) {
                val codePoint = text.codePointAt(end)
                if (isPySpace(codePoint)) break
                end += Character.charCount(codePoint)
            }
            if (end > index + scheme) {
                out.append(' ')
                index = end
                continue
            }
        }
        if (text[index] == '<' && !noMoreGt) {
            val inner = matchHttpScheme(text, index + 1)
            if (inner > 0) {
                val gt = text.indexOf('>', index + 1 + inner)
                if (gt < 0) {
                    noMoreGt = true
                } else {
                    out.append(' ')
                    index = gt + 1
                    continue
                }
            }
        }
        out.append(text[index])
        index++
    }
    return out.toString()
}

/** 在 [at] 处匹配 `https?://`，返回方案长度；不匹配返回 -1。 */
private fun matchHttpScheme(text: String, at: Int): Int {
    if (!text.regionMatches(at, "http", 0, 4, ignoreCase = true)) return -1
    var index = at + 4
    if (index < text.length && (text[index] == 's' || text[index] == 'S')) index++
    if (index + 3 <= text.length && text[index] == ':' && text[index + 1] == '/' && text[index + 2] == '/') {
        return index + 3 - at
    }
    return -1
}
