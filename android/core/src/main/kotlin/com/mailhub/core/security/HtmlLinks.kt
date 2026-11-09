package com.mailhub.core.security

import com.mailhub.core.text.PyHtml
import com.mailhub.core.text.PyRegex
import com.mailhub.core.text.isPySpace
import com.mailhub.core.text.pySplit
import com.mailhub.core.text.pyStrip
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * `html.parser.HTMLParser`（`convert_charrefs=True`）的最小移植，加上
 * `security_scan._LinkCollector`：从 HTML 正文里收集 `(href, 链接文字)`，纯解析，不发请求。
 *
 * 移植的是 CPython 3.13 `html/parser.py` 的分词循环与各 `parse_*` 分支、以及
 * `_markupbase.ParserBase` 里注释 / 声明 / 标记段的定位规则。与 Python 的对应关系：
 * - `feed` / `close` / `goahead`：逐段扫描，把标签外的文本交给 [handleData]；
 * - `parse_starttag` + `check_for_whole_start_tag`：标签名、属性名都转小写，属性值支持
 *   单引号、双引号、无引号三种写法，重复属性以最后一个为准，属性值走 [PyHtml.unescape]；
 * - `parse_endtag`：区分正常结束标签、垃圾结束标签与 CDATA 模式内的结束标签；
 * - `parse_comment` / `parse_pi` / `parse_html_declaration` / `parse_marked_section`：
 *   只计算结束位置，内容对收集链接没有用处。
 *
 * Python 的 `_LinkCollector` 遇到畸形输入时可能抛异常，`extract_html_links` 用 `except: pass`
 * 吞掉并保留已经解析出的链接。这里同样抛 [IllegalStateException]，由调用方按那个语义处理。
 */
internal class HtmlLinkCollector {

    /** 已解析出的 `(href.strip(), " ".join(文字.split()))`，按出现顺序。 */
    val links = mutableListOf<Pair<String, String>>()

    private var href: String? = null
    private val text = StringBuilder()

    private var rawdata = ""
    /** 处于 `<script>` / `<style>` 的内容里时为该标签名，对应 `HTMLParser.cdata_elem`。 */
    private var cdataElem: String? = null
    /** CDATA 模式的结束标签模式，对应 `HTMLParser.interesting`。 */
    private var cdataEnd: Pattern? = null

    /** 对应 `HTMLParser.feed`：数据缓冲后按「还没结束」处理。 */
    fun feed(data: String) {
        rawdata += data
        goahead(end = false)
    }

    /** 对应 `HTMLParser.close`：强制处理剩余的缓冲数据。 */
    fun close() {
        goahead(end = true)
    }

    /** 对应 `HTMLParser.goahead`（`convert_charrefs=True`，因此 `&` 一律由 unescape 处理）。 */
    private fun goahead(end: Boolean) {
        val rawdata = this.rawdata
        var i = 0
        val n = rawdata.length
        while (i < n) {
            val j: Int
            if (cdataElem == null) {
                val found = rawdata.indexOf('<', i)
                if (found >= 0) {
                    j = found
                } else {
                    // 结尾可能有一个被截断的字符引用，等更多数据再处理
                    val ampPos = rawdata.lastIndexOf('&', maxOf(i, n - 34))
                    if (ampPos >= 0 && !hasSpaceOrSemicolon(rawdata, ampPos)) break
                    j = n
                }
            } else {
                val match = searchFrom(cdataEnd!!, rawdata, i)
                if (match == null) break // CDATA 里没找到结束标签：等内容，不吐数据
                j = match.start()
            }
            if (i < j) {
                handleData(if (cdataElem == null) PyHtml.unescape(rawdata.substring(i, j)) else rawdata.substring(i, j))
            }
            i = j
            if (i == n) break
            val k = when {
                isStartTagOpen(rawdata, i) -> parseStarttag(i)
                rawdata.startsWith("</", i) -> parseEndtag(i)
                rawdata.startsWith("<!--", i) -> parseComment(i)
                rawdata.startsWith("<?", i) -> parsePi(i)
                rawdata.startsWith("<!", i) -> parseHtmlDeclaration(i)
                i + 1 < n -> {
                    handleData("<")
                    i + 1
                }
                else -> break
            }
            if (k < 0) {
                if (!end) break
                var fallback = rawdata.indexOf('>', i + 1)
                if (fallback < 0) {
                    fallback = rawdata.indexOf('<', i + 1)
                    if (fallback < 0) fallback = i + 1
                } else {
                    fallback += 1
                }
                handleData(
                    if (cdataElem == null) PyHtml.unescape(rawdata.substring(i, fallback))
                    else rawdata.substring(i, fallback))
                if (fallback <= i) break // 兜底：正常输入不会走到这里，避免死循环
                i = fallback
                continue
            }
            if (k <= i) break // 兜底，同上
            i = k
        }
        if (end && i < n && cdataElem == null) {
            handleData(PyHtml.unescape(rawdata.substring(i, n)))
            i = n
        }
        this.rawdata = rawdata.substring(i)
    }

    /** `[^\s;]` 的否定：从 [from] 起是否存在 `\s` 或 `;`。 */
    private fun hasSpaceOrSemicolon(s: String, from: Int): Boolean {
        var i = from
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp == ';'.code || isPySpace(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** 对应 `starttagopen.match(rawdata, i)`：`<` 加一个 ASCII 字母。 */
    private fun isStartTagOpen(s: String, i: Int): Boolean {
        if (i + 1 >= s.length) return false
        val c = s[i + 1]
        return c in 'a'..'z' || c in 'A'..'Z'
    }

    // ---------------- 收集器回调 ----------------

    private fun handleData(data: String) {
        if (href != null) text.append(data)
    }

    /** 对应 `_LinkCollector.handle_starttag`：`dict(attrs).get("href") or ""`，重复属性取最后一个。 */
    private fun handleStartTag(tag: String, attrs: List<Pair<String, String?>>) {
        if (tag != "a") return
        var value: String? = null
        for (attr in attrs) {
            if (attr.first == "href") value = attr.second
        }
        href = value ?: ""
        text.setLength(0)
    }

    /** 对应 `_LinkCollector.handle_endtag`：结束 `<a>` 时把 `(href, 文字)` 记下来。 */
    private fun handleEndTag(tag: String) {
        if (tag != "a") return
        val current = href ?: return
        links += current.pyStrip() to text.toString().pySplit().joinToString(" ")
        href = null
    }

    // ---------------- 各 parse_* 分支 ----------------

    /** 对应 `parse_starttag`，返回标签结束后的下标；负数表示还没结束。 */
    private fun parseStarttag(i: Int): Int {
        val endpos = checkForWholeStartTag(i)
        if (endpos < 0) return endpos
        val rawdata = this.rawdata
        val tagMatch = matchAt(TAGFIND, rawdata, i + 1)
            ?: throw IllegalStateException("unexpected call to parse_starttag()")
        var k = tagMatch.end()
        val tag = tagMatch.group(1).lowercase(Locale.ROOT)

        val attrs = mutableListOf<Pair<String, String?>>()
        while (k < endpos) {
            val m = matchAt(ATTRFIND, rawdata, k) ?: break
            val name = m.group(1)
            val rest = m.group(2)
            val raw = m.group(3)
            var value: String? = when {
                rest == null -> null
                raw != null && raw.length >= 2 &&
                    ((raw[0] == '\'' && raw[raw.length - 1] == '\'') ||
                        (raw[0] == '"' && raw[raw.length - 1] == '"')) -> raw.substring(1, raw.length - 1)
                else -> raw
            }
            if (value != null && value.isNotEmpty()) value = PyHtml.unescape(value)
            attrs += name.lowercase(Locale.ROOT) to value
            k = m.end()
        }

        val tail = rawdata.substring(minOf(k, endpos), endpos).pyStrip()
        if (tail != ">" && tail != "/>") {
            // 不是完整标签：整段当文本
            handleData(rawdata.substring(i, endpos))
            return endpos
        }
        if (tail.endsWith("/>")) {
            // XHTML 风格的空标签：先 starttag 再 endtag
            handleStartTag(tag, attrs)
            handleEndTag(tag)
        } else {
            handleStartTag(tag, attrs)
            if (tag in CDATA_CONTENT_ELEMENTS) setCdataMode(tag)
        }
        return endpos
    }

    /** 对应 `check_for_whole_start_tag`：标签是否已经完整，返回结束下标或 -1。 */
    private fun checkForWholeStartTag(i: Int): Int {
        val rawdata = this.rawdata
        val m = matchAt(LOCATE_START_TAG_END, rawdata, i)
            ?: throw IllegalStateException("we should not get here!")
        val j = m.end()
        val next: Char? = if (j < rawdata.length) rawdata[j] else null
        if (next == null) return -1
        return when {
            next == '>' -> j + 1
            next == '/' -> when {
                rawdata.startsWith("/>", j) -> j + 2
                rawdata.startsWith("/", j) -> -1
                j > i -> j
                else -> i + 1
            }
            next in START_TAG_TAIL_CHARS -> -1
            j > i -> j
            else -> i + 1
        }
    }

    /** 对应 `parse_endtag`。 */
    private fun parseEndtag(i: Int): Int {
        val rawdata = this.rawdata
        val gt = rawdata.indexOf('>', i + 1)
        if (gt < 0) return -1
        var gtpos = gt + 1
        val match = matchAt(ENDTAGFIND, rawdata, i)
        if (match == null) {
            if (cdataElem != null) {
                handleData(rawdata.substring(i, gtpos))
                return gtpos
            }
            val nameMatch = matchAt(TAGFIND, rawdata, i + 2)
            if (nameMatch == null) {
                return if (rawdata.startsWith("</>", i)) i + 3 else parseBogusComment(i)
            }
            val tag = nameMatch.group(1).lowercase(Locale.ROOT)
            // 名称之后到 `>` 之间的内容一律忽略
            gtpos = rawdata.indexOf('>', nameMatch.end()) + 1
            handleEndTag(tag)
            return gtpos
        }
        val elem = match.group(1).lowercase(Locale.ROOT)
        if (cdataElem != null && elem != cdataElem) {
            handleData(rawdata.substring(i, gtpos))
            return gtpos
        }
        handleEndTag(elem)
        clearCdataMode()
        return gtpos
    }

    /** 对应 `parse_comment`（`_commentclose = --\s*>`）。 */
    private fun parseComment(i: Int): Int {
        val match = searchFrom(COMMENT_CLOSE, rawdata, i + 4) ?: return -1
        return match.end()
    }

    /** 对应 `parse_pi`：`<?...>` 到最近的 `>`。 */
    private fun parsePi(i: Int): Int {
        val match = searchFrom(PI_CLOSE, rawdata, i + 2) ?: return -1
        return match.end()
    }

    /** 对应 `parse_bogus_comment`：`<!` / `</` 开头到最近的 `>`。 */
    private fun parseBogusComment(i: Int): Int {
        val pos = rawdata.indexOf('>', i + 2)
        return if (pos < 0) -1 else pos + 1
    }

    /** 对应 `parse_html_declaration`：区分注释、标记段、DOCTYPE 与其余垃圾声明。 */
    private fun parseHtmlDeclaration(i: Int): Int {
        val rawdata = this.rawdata
        if (rawdata.startsWith("<!--", i)) return parseComment(i)
        if (rawdata.startsWith("<![", i)) return parseMarkedSection(i)
        if (rawdata.length >= i + 9 &&
            rawdata.substring(i, i + 9).lowercase(Locale.ROOT) == "<!doctype") {
            val gt = rawdata.indexOf('>', i + 9)
            return if (gt < 0) -1 else gt + 1
        }
        return parseBogusComment(i)
    }

    /** 对应 `parse_marked_section`：`<![CDATA[…]]>` 与 MS Office 的 `<![if …]>`。 */
    private fun parseMarkedSection(i: Int): Int {
        val (sectName, after) = scanName(i + 3)
        if (after < 0) return after
        val match = when (sectName) {
            "temp", "cdata", "ignore", "include", "rcdata" ->
                searchFrom(MARKED_SECTION_CLOSE, rawdata, i + 3)
            "if", "else", "endif" -> searchFrom(MS_MARKED_SECTION_CLOSE, rawdata, i + 3)
            else -> throw IllegalStateException("unknown status keyword in marked section")
        } ?: return -1
        return match.end()
    }

    /**
     * 对应 `_markupbase.ParserBase._scan_name`：读一个声明里的名字，返回（小写名字，名字之后的下标）。
     * 名字取到缓冲末尾（没有后续字符）时返回 -1，表示数据还不完整。
     */
    private fun scanName(start: Int): Pair<String, Int> {
        val rawdata = this.rawdata
        if (start == rawdata.length) return "" to -1
        val m = matchAt(DECL_NAME, rawdata, start)
            ?: throw IllegalStateException("expected name token")
        val token = m.group()
        val name = token.pyStrip()
        if (m.end() == rawdata.length) return "" to -1
        return name.lowercase(Locale.ROOT) to m.end()
    }

    private fun setCdataMode(tag: String) {
        cdataElem = tag.lowercase(Locale.ROOT)
        cdataEnd = PyRegex.compile("</\\s*$cdataElem\\s*>", ignoreCase = true)
    }

    private fun clearCdataMode() {
        cdataElem = null
        cdataEnd = null
    }

    // ---------------- 正则工具 ----------------

    /** 从 [start] 起做 `re.match`（锚定在 [start]），后顾可以越过 [start] 往前看。 */
    private fun matchAt(pattern: Pattern, s: String, start: Int): Matcher? {
        if (start < 0 || start > s.length) return null
        val m = pattern.matcher(s)
        m.region(start, s.length)
        m.useTransparentBounds(true)
        m.useAnchoringBounds(false)
        return if (m.lookingAt()) m else null
    }

    /** 从 [start] 起做 `re.search`。 */
    private fun searchFrom(pattern: Pattern, s: String, start: Int): Matcher? {
        if (start > s.length) return null
        val m = pattern.matcher(s)
        m.region(start, s.length)
        return if (m.find()) m else null
    }

    private companion object {
        /** 对应 `HTMLParser.CDATA_CONTENT_ELEMENTS`。 */
        val CDATA_CONTENT_ELEMENTS = setOf("script", "style")

        /** 对应 `check_for_whole_start_tag` 里那串「说明还没结束」的字符。 */
        val START_TAG_TAIL_CHARS =
            ("abcdefghijklmnopqrstuvwxyz" + "=/ABCDEFGHIJKLMNOPQRSTUVWXYZ").toSet()

        /** `tagfind_tolerant`。 */
        val TAGFIND: Pattern = PyRegex.compile("([a-zA-Z][^\\t\\n\\r\\f />\\x00]*)(?:\\s|/(?!>))*")

        /** `attrfind_tolerant`。 */
        val ATTRFIND: Pattern = PyRegex.compile(
            "((?<=['\"\\s/])[^\\s/>][^\\s/=>]*)" +
                "(\\s*=+\\s*('[^']*'|\"[^\"]*\"|(?!['\"])[^>\\s]*))?(?:\\s|/(?!>))*")

        /** `locatestarttagend_tolerant`（VERBOSE 展开成一行）。 */
        val LOCATE_START_TAG_END: Pattern = PyRegex.compile(
            "<[a-zA-Z][^\\t\\n\\r\\f />\\x00]*(?:[\\s/]*(?:(?<=['\"\\s/])[^\\s/>][^\\s/=>]*" +
                "(?:\\s*=+\\s*(?:'[^']*'|\"[^\"]*\"|(?!['\"])[^>\\s]*)\\s*)?" +
                "(?:\\s|/(?!>))*)*)?\\s*")

        /** `endtagfind`。 */
        val ENDTAGFIND: Pattern = PyRegex.compile("</\\s*([a-zA-Z][-.a-zA-Z0-9:_]*)\\s*>")

        /** `_commentclose`。 */
        val COMMENT_CLOSE: Pattern = PyRegex.compile("--\\s*>")

        /** `piclose`。 */
        val PI_CLOSE: Pattern = PyRegex.compile(">")

        /** `_markedsectionclose`。 */
        val MARKED_SECTION_CLOSE: Pattern = PyRegex.compile("]\\s*]\\s*>")

        /** `_msmarkedsectionclose`。 */
        val MS_MARKED_SECTION_CLOSE: Pattern = PyRegex.compile("]\\s*>")

        /** `_markupbase` 的 `_declname_match`。 */
        val DECL_NAME: Pattern = PyRegex.compile("[a-zA-Z][-_.a-zA-Z0-9]*\\s*")
    }
}
