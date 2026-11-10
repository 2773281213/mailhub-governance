package com.mailhub.core.security

import com.mailhub.core.text.PyHtml
import com.mailhub.core.text.pySplit
import com.mailhub.core.text.pyStrip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * `html.parser.HTMLParser`（`convert_charrefs=True`、`scripting=False`）的移植，加上
 * `security_scan._LinkCollector`：从 HTML 正文里收集 `(href, 链接文字)`，纯解析，不发请求。
 *
 * **按 CPython 3.14.3 的 `Lib/html/parser.py` 重写**（旧版 3.13 的实现在 `<![x]>`、`--!>`、
 * `</script y>`、`</style/>` 上会丢掉后面的全部链接，见 LOCAL_MODE.md 第四节第 5 条）。
 * 与源码的对应关系：
 * - `feed` / `close` / `goahead`：逐段扫描；`convert_charrefs=True` 时文本侧只看 `<`，
 *   找不到 `<` 时按 `rawdata.rfind('&', max(i, n-34))` 与 `[\t\n\r\f ;]` 判断是否要等更多数据；
 * - `parse_starttag` / `check_for_whole_start_tag` / `locatetagend`：标签名、属性名转小写，
 *   属性值支持单引号、双引号、无引号三种写法，重复属性以最后一个为准；
 * - `_unescape_attrvalue` / `attr_charref` / `_replace_attr_charref`：属性值里只转换
 *   **完整**的实体名与数字引用（3.13 会把 `&notit;` 拆成 `&not;` + `it;`，3.14 不再拆）；
 * - `parse_comment`：`--!?>` 优先，找不到时用 `commentabruptclose`（`-?>`）在 `i+4` 处兜底；
 * - `parse_html_declaration`：`<!--`、大小写敏感的 `<![CDATA[` + `]]>`、`<!doctype`，其余按
 *   `parse_bogus_comment` 到最近的 `>`（3.14 不再调用 `_markupbase.parse_marked_section`，
 *   所以 `<![x]>` 不会再抛断言）；
 * - `parse_endtag`：`</>` 忽略，`</` 后不是字母按 bogus comment，名字之后到 `>` 的内容忽略；
 * - `CDATA_CONTENT_ELEMENTS`（`script` / `style` / `xmp` / `iframe` / `noembed` / `noframes`）
 *   与 `plaintext` 走 RAWTEXT，`RCDATA_CONTENT_ELEMENTS`（`title` / `textarea`）走可转义模式；
 *   结束标签只要求 `</name` 后面是 `[\t\n\r\f />]`，与 3.14 的 `set_cdata_mode` 一致；
 * - 文件尾：`goahead(end=True)` 在 `i<n` 时把剩余内容按数据交出（RAWTEXT 不转换字符引用），
 *   并对 `</`、`<!--`、`<![CDATA[`、`<!doctype`、`<!`、`<?` 分别做源码里的收尾处理。
 *
 * 与 Python 的实现差异只有一处：源码里的断言（`assert`）在这里写成
 * [IllegalStateException]，便于调用方把「解析不完整」当作异常处理。
 *
 * 性能：跳过空白、斜杠与解析属性的循环全部手写，没有嵌套量词或分支循环的正则；
 * 每个输入字符最多被扫描常数次，整体对输入长度线性。查找 `--!?>` / `]]>` / `>` 时
 * 从当前位置向后推进，不会重复扫描已经看过的区间。
 */
internal class HtmlLinkCollector {

    /** 已解析出的 `(href.strip(), " ".join(文字.split()))`，按出现顺序。 */
    val links = mutableListOf<Pair<String, String>>()

    private var href: String? = null
    private val text = StringBuilder()

    private var rawdata = ""

    /** 处于内容元素里时为该标签名，对应 `HTMLParser.cdata_elem`。 */
    private var cdataElem: String? = null

    /** 对应 `HTMLParser._escapable`：RAWTEXT 为 false，RCDATA 为 true。 */
    private var escapable = true

    /** `plaintext` 的 `interesting` 是永远不匹配的 `\z`。 */
    private var cdataNeverEnds = false

    /** 对应 `HTMLParser.feed`：数据缓冲后按「还没结束」处理。 */
    fun feed(data: String) {
        rawdata += data
        goahead(end = false)
    }

    /** 对应 `HTMLParser.close`：强制处理剩余的缓冲数据。 */
    fun close() {
        goahead(end = true)
    }

    /** 对应 `HTMLParser.goahead`（`convert_charrefs=True`、`scripting=False`）。 */
    private fun goahead(end: Boolean) {
        val rawdata = this.rawdata
        var i = 0
        val n = rawdata.length
        while (i < n) {
            val j: Int
            if (cdataElem == null) {
                val found = rawdata.indexOf('<', i)
                if (found < 0) {
                    // 结尾可能有一个被截断的字符引用：等更多数据再处理
                    val ampPos = lastIndexOfFrom(rawdata, '&', maxOf(i, n - 34))
                    if (ampPos >= 0 && !hasSpaceOrSemicolon(rawdata, ampPos)) break
                    j = n
                } else {
                    j = found
                }
            } else {
                val found = findCdataEnd(rawdata, i)
                if (found < 0) break // CDATA 里没找到结束标签：等内容，不吐数据
                j = found
            }
            if (i < j) {
                val chunk = rawdata.substring(i, j)
                handleData(if (cdataElem == null || escapable) PyHtml.unescape(chunk) else chunk)
            }
            i = j
            if (i == n) break
            if (rawdata[i] != '<') throw IllegalStateException("interesting.search() lied")

            val k: Int
            if (isStartTagOpen(rawdata, i)) {
                k = parseStarttag(i)
            } else if (rawdata.startsWith("</", i)) {
                k = parseEndtag(i)
            } else if (rawdata.startsWith("<!--", i)) {
                k = parseComment(i)
            } else if (rawdata.startsWith("<?", i)) {
                k = parsePi(i)
            } else if (rawdata.startsWith("<!", i)) {
                k = parseHtmlDeclaration(i)
            } else if (i + 1 < n || end) {
                handleData("<")
                k = i + 1
            } else {
                break
            }
            if (k < 0) {
                if (!end) break
                // 对应 goahead 里 `k < 0` 的文件尾处理；没有链接影响的分支直接跳过
                when {
                    isStartTagOpen(rawdata, i) -> Unit
                    rawdata.startsWith("</", i) -> {
                        if (i + 2 == n) {
                            handleData("</")
                        } else if (!isEndTagOpen(rawdata, i)) {
                            // bogus comment：内容对收集链接没有用处
                        }
                    }
                    rawdata.startsWith("<!--", i) -> Unit
                    rawdata.startsWith("<![CDATA[", i) -> Unit
                    rawdata.regionMatches(i, "<!doctype", 0, DOCTYPE_PREFIX.length, ignoreCase = true) -> Unit
                    rawdata.startsWith("<!", i) -> Unit
                    rawdata.startsWith("<?", i) -> Unit
                    else -> throw IllegalStateException("we should not get here!")
                }
                i = n
            } else {
                i = k
            }
        }
        if (end && i < n) {
            val rest = rawdata.substring(i, n)
            handleData(if (cdataElem == null || escapable) PyHtml.unescape(rest) else rest)
            i = n
        }
        this.rawdata = rawdata.substring(i)
    }

    /** Python 的 `rawdata.rfind(ch, start)`：返回下标 >= [from] 的最后一个 [ch]。 */
    private fun lastIndexOfFrom(s: String, ch: Char, from: Int): Int {
        var p = s.length - 1
        while (p >= from) {
            if (s[p] == ch) return p
            p--
        }
        return -1
    }

    /** `[\t\n\r\f ;]` 的否定：从 [from] 起是否存在这些字符之一。 */
    private fun hasSpaceOrSemicolon(s: String, from: Int): Boolean {
        var i = from
        while (i < s.length) {
            if (s[i] in SPACE_OR_SEMICOLON) return true
            i++
        }
        return false
    }

    /** 对应 3.14 `set_cdata_mode` 的 `</elem(?=[\t\n\r\f />])`（ASCII 大小写不敏感）。 */
    private fun findCdataEnd(s: String, from: Int): Int {
        if (cdataNeverEnds) return -1
        val elem = cdataElem ?: return -1
        var pos = from
        while (true) {
            val p = s.indexOf("</", pos)
            if (p < 0) return -1
            val after = p + 2 + elem.length
            if (after < s.length && s[after] in CDATA_END_TAIL &&
                asciiEqualsIgnoreCase(s, p + 2, elem)) {
                return p
            }
            pos = p + 2
        }
    }

    /** 只按 ASCII 折叠比较：Python 的 `re.IGNORECASE | re.ASCII` 不认 `ſ` 之类的字符。 */
    private fun asciiEqualsIgnoreCase(s: String, offset: Int, lower: String): Boolean {
        if (offset + lower.length > s.length) return false
        for (k in lower.indices) {
            val c = s[offset + k]
            val folded = if (c in 'A'..'Z') c + 32 else c
            if (folded != lower[k]) return false
        }
        return true
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
        val tagMatch = scanTagFind(rawdata, i + 1)
            ?: throw IllegalStateException("unexpected call to parse_starttag()")
        var k = tagMatch.end
        val tag = tagMatch.name.lowercase(Locale.ROOT)

        val attrs = mutableListOf<Pair<String, String?>>()
        while (k < endpos) {
            val attr = matchAttrFind(rawdata, k) ?: break
            var value = attr.value
            if (value != null) {
                // 去掉成对的引号（对应 `attrvalue[:1] == '\'' == attrvalue[-1:]`）
                if (value.length >= 2 &&
                    ((value[0] == '\'' && value[value.length - 1] == '\'') ||
                        (value[0] == '"' && value[value.length - 1] == '"'))) {
                    value = value.substring(1, value.length - 1)
                }
                if (value.isNotEmpty()) value = unescapeAttrValue(value)
            }
            attrs += attr.name.lowercase(Locale.ROOT) to value
            k = attr.end
        }

        // `rawdata[k:endpos].strip()`：k 超出 endpos 时按空串处理
        val tail = if (k < endpos) rawdata.substring(k, endpos).pyStrip() else ""
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
            when {
                tag in CDATA_CONTENT_ELEMENTS -> setCdataMode(tag, escapable = false)
                // scripting=False，noscript 不进入 CDATA 模式
                tag == "plaintext" -> setCdataMode(tag, escapable = false)
                tag in RCDATA_CONTENT_ELEMENTS -> setCdataMode(tag, escapable = true)
            }
        }
        return endpos
    }

    /** 对应 `check_for_whole_start_tag`：返回 `locatetagend` 匹配到 `>` 之后的下标，否则 -1。 */
    private fun checkForWholeStartTag(i: Int): Int = locateTagEnd(this.rawdata, i + 1)

    /**
     * 对应 3.14 的 `locatetagend`（`re.VERBOSE` 那条）：
     * `[a-zA-Z][^\t\n\r\f />]*[\t\n\r\f /]*(?:attr(?:=value)?[\t\n\r\f /]*)*>?`。
     * 手写同一个贪心扫描，返回 `>` 之后的下标；没有完整标签返回 -1。
     * 重复的属性描述最多把每个字符看常数次，不会回溯。
     */
    private fun locateTagEnd(s: String, start: Int): Int {
        val n = s.length
        var pos = start
        if (pos >= n || !isAsciiLetter(s[pos])) {
            throw IllegalStateException("locatetagend 起点不是字母")
        }
        pos++
        while (pos < n && s[pos] !in TAG_NAME_STOP) pos++
        while (true) {
            // [\t\n\r\f /]*
            while (pos < n && (s[pos] in WS || s[pos] == '/')) pos++
            if (pos >= n) break
            // 属性名的后顾与首字符限制
            if (s[pos - 1] !in ATTR_LOOKBEHIND) break
            if (s[pos] in TAG_NAME_STOP) break
            pos++
            while (pos < n && s[pos] !in ATTR_NAME_TAIL_STOP) pos++
            // 可选的 = value：失败时整个可选组回退到属性名之后
            val save = pos
            var q = pos
            while (q < n && s[q] in WS) q++
            if (q < n && s[q] == '=') {
                q++
                while (q < n && s[q] in WS) q++
                if (q < n && s[q] == '\'') {
                    val e = s.indexOf('\'', q + 1)
                    pos = if (e >= 0) e + 1 else save
                } else if (q < n && s[q] == '"') {
                    val e = s.indexOf('"', q + 1)
                    pos = if (e >= 0) e + 1 else save
                } else {
                    while (q < n && s[q] !in BARE_VALUE_STOP) q++
                    pos = q
                }
            } else {
                pos = save
            }
            while (pos < n && (s[pos] in WS || s[pos] == '/')) pos++
        }
        return if (pos < n && s[pos] == '>') pos + 1 else -1
    }

    /** 对应 `parse_endtag`。 */
    private fun parseEndtag(i: Int): Int {
        val rawdata = this.rawdata
        if (rawdata.indexOf('>', i + 2) < 0) return -1 // 快速检查：没有 `>` 说明还没结束
        if (!isEndTagOpen(rawdata, i)) {
            // `</>` 直接忽略（missing-end-tag-name），其余按 bogus comment
            if (i + 2 < rawdata.length && rawdata[i + 2] == '>') return i + 3
            return parseBogusComment(i)
        }
        val j = locateTagEnd(rawdata, i + 2)
        if (j < 0) return -1
        val tag = scanTagFind(rawdata, i + 2)?.name?.lowercase(Locale.ROOT)
            ?: throw IllegalStateException("unexpected call to parse_endtag")
        handleEndTag(tag)
        clearCdataMode()
        return j
    }

    /** 对应 `parse_comment`：`--!?>` 优先，找不到时在 `i+4` 处试 `-?>`。 */
    private fun parseComment(i: Int): Int {
        val rawdata = this.rawdata
        var p = rawdata.indexOf("--", i + 4)
        while (p >= 0) {
            var q = p + 2
            if (q < rawdata.length && rawdata[q] == '!') q++
            if (q < rawdata.length && rawdata[q] == '>') return q + 1
            p = rawdata.indexOf("--", p + 1)
        }
        val abrupt = i + 4
        if (abrupt < rawdata.length) {
            if (rawdata[abrupt] == '>') return abrupt + 1
            if (rawdata[abrupt] == '-' && abrupt + 1 < rawdata.length && rawdata[abrupt + 1] == '>') {
                return abrupt + 2
            }
        }
        return -1
    }

    /** 对应 `parse_pi`：`<?...>` 到最近的 `>`。 */
    private fun parsePi(i: Int): Int {
        val pos = rawdata.indexOf('>', i + 2)
        return if (pos < 0) -1 else pos + 1
    }

    /** 对应 `parse_bogus_comment`：`<!` / `</` 开头到最近的 `>`。 */
    private fun parseBogusComment(i: Int): Int {
        val pos = rawdata.indexOf('>', i + 2)
        return if (pos < 0) -1 else pos + 1
    }

    /** 对应 3.14 的 `parse_html_declaration`（不再经过 `_markupbase.parse_marked_section`）。 */
    private fun parseHtmlDeclaration(i: Int): Int {
        val rawdata = this.rawdata
        if (rawdata.startsWith("<!--", i)) return parseComment(i)
        if (rawdata.startsWith("<![CDATA[", i)) {
            val pos = rawdata.indexOf("]]>", i + 9)
            return if (pos < 0) -1 else pos + 3
        }
        if (i + DOCTYPE_PREFIX.length <= rawdata.length &&
            rawdata.regionMatches(i, DOCTYPE_PREFIX, 0, DOCTYPE_PREFIX.length, ignoreCase = true)) {
            val pos = rawdata.indexOf('>', i + DOCTYPE_PREFIX.length)
            return if (pos < 0) -1 else pos + 1
        }
        return parseBogusComment(i)
    }

    private fun setCdataMode(tag: String, escapable: Boolean) {
        cdataElem = tag.lowercase(Locale.ROOT)
        this.escapable = escapable
        cdataNeverEnds = cdataElem == "plaintext"
    }

    private fun clearCdataMode() {
        cdataElem = null
        escapable = true
        cdataNeverEnds = false
    }

    // ---------------- 标签与属性的手写扫描 ----------------

    private class TagName(val name: String, val end: Int)

    private class AttrMatch(
        val name: String,
        val value: String?,
        val end: Int,
    )

    /** 对应 `tagfind_tolerant`：名字 + 尾部的空白或 `/`（不是 `/>` 的斜杠）。 */
    private fun scanTagFind(s: String, start: Int): TagName? {
        if (start >= s.length || !isAsciiLetter(s[start])) return null
        var p = start + 1
        while (p < s.length && s[p] !in TAG_NAME_STOP) p++
        val name = s.substring(start, p)
        p = skipWhitespaceAndSlash(s, p)
        return TagName(name, p)
    }

    /** 对应 `attrfind_tolerant`：返回名字、可选值（未去引号、未解字符引用）与结束下标。 */
    private fun matchAttrFind(s: String, k: Int): AttrMatch? {
        val n = s.length
        if (k <= 0 || k >= n) return null
        if (s[k - 1] !in ATTR_LOOKBEHIND) return null
        if (s[k] in TAG_NAME_STOP) return null
        var p = k + 1
        while (p < n && s[p] !in ATTR_NAME_TAIL_STOP) p++
        val name = s.substring(k, p)
        val save = p
        var q = p
        while (q < n && s[q] in WS) q++
        if (q < n && s[q] == '=') {
            q++
            while (q < n && s[q] in WS) q++
            if (q < n && s[q] == '\'') {
                val e = s.indexOf('\'', q + 1)
                if (e < 0) return AttrMatch(name, null, skipWhitespaceAndSlash(s, save))
                return AttrMatch(name, s.substring(q, e + 1), skipWhitespaceAndSlash(s, e + 1))
            }
            if (q < n && s[q] == '"') {
                val e = s.indexOf('"', q + 1)
                if (e < 0) return AttrMatch(name, null, skipWhitespaceAndSlash(s, save))
                return AttrMatch(name, s.substring(q, e + 1), skipWhitespaceAndSlash(s, e + 1))
            }
            val valueStart = q
            while (q < n && s[q] !in BARE_VALUE_STOP) q++
            return AttrMatch(name, s.substring(valueStart, q), skipWhitespaceAndSlash(s, q))
        }
        return AttrMatch(name, null, skipWhitespaceAndSlash(s, save))
    }

    /** `(?:[\t\n\r\f ]|/(?!>))*`：空白，或后面不是 `>` 的斜杠。 */
    private fun skipWhitespaceAndSlash(s: String, start: Int): Int {
        var p = start
        while (p < s.length) {
            val c = s[p]
            if (c in WS) {
                p++
            } else if (c == '/' && !(p + 1 < s.length && s[p + 1] == '>')) {
                p++
            } else {
                break
            }
        }
        return p
    }

    private companion object {
        /** 3.14 正则里的 `[\t\n\r\f ]`（注意不含 `\v` 与 Unicode 空白）。 */
        const val WS = "\t\n\r\u000c "

        /** `tagfind_tolerant` 里标签名/属性名首字符的排除集。 */
        const val TAG_NAME_STOP = "\t\n\r\u000c />"

        /** 属性名后续字符的排除集（多一个 `=`）。 */
        const val ATTR_NAME_TAIL_STOP = "\t\n\r\u000c /=>"

        /** 属性名 `(?<=['"\t\n\r\f /])` 的后顾集合。 */
        const val ATTR_LOOKBEHIND = "'\"\t\n\r\u000c /"

        /** 无引号属性值 `[^>\t\n\r\f ]*` 的终止字符。 */
        const val BARE_VALUE_STOP = ">\t\n\r\u000c "

        /** `goahead` 里判断被截断字符引用用的 `[\t\n\r\f ;]`。 */
        const val SPACE_OR_SEMICOLON = "\t\n\r\u000c ;"

        /** `set_cdata_mode` 结束标签的 `(?=[\t\n\r\f />])`。 */
        const val CDATA_END_TAIL = "\t\n\r\u000c />"

        const val DOCTYPE_PREFIX = "<!doctype"

        /** 对应 `HTMLParser.CDATA_CONTENT_ELEMENTS`（RAWTEXT，不转换字符引用）。 */
        val CDATA_CONTENT_ELEMENTS = setOf("script", "style", "xmp", "iframe", "noembed", "noframes")

        /** 对应 `HTMLParser.RCDATA_CONTENT_ELEMENTS`（可转换字符引用）。 */
        val RCDATA_CONTENT_ELEMENTS = setOf("textarea", "title")

        fun isStartTagOpen(s: String, i: Int): Boolean =
            s[i] == '<' && i + 1 < s.length && isAsciiLetter(s[i + 1])

        fun isEndTagOpen(s: String, i: Int): Boolean =
            i + 2 < s.length && s[i] == '<' && s[i + 1] == '/' && isAsciiLetter(s[i + 2])
    }
}

/** ASCII 字母：Python 的标签与属性名只认 `[a-zA-Z]`。 */
private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

private fun isAsciiLetterOrDigit(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

private fun isHexDigit(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/** `html.entities.html5` 的全部键，来自 PyHtml 用的同一份导出表（`/py/html_unescape.json`）。 */
private object HtmlEntityTable {
    val names: Set<String> by lazy {
        val stream = HtmlEntityTable::class.java.getResourceAsStream("/py/html_unescape.json")
            ?: error("缺少资源 py/html_unescape.json")
        Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) })
            .jsonObject.getValue("entities").jsonObject.keys
    }
}

/**
 * 对应 3.14 的 `_unescape_attrvalue`：属性值里只转换完整的实体名与数字引用。
 *
 * `attr_charref` 先匹配一个候选引用，再按 `_replace_attr_charref` 判断：
 * - 数字引用（`&#…`）总是交给 `html.unescape`；
 * - 命名引用只有在**不以 `=` 结尾**且名字是 `html.entities.html5` 的完整键时才转换；
 * - 其余原样保留（3.13 会对整个属性值调用 `unescape`，`&notit;` 会变成 `¬it;`，这是有意的差异）。
 */
private fun unescapeAttrValue(s: String): String {
    if ('&' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    val n = s.length
    while (i < n) {
        if (s[i] != '&') {
            out.append(s[i])
            i++
            continue
        }
        val start = i + 1
        var end = start
        if (end < n && s[end] == '#') {
            end++
            if (end < n && (s[end] == 'x' || s[end] == 'X')) {
                end++
                val digits = end
                while (end < n && isHexDigit(s[end])) end++
                if (end == digits) {
                    out.append('&')
                    i++
                    continue
                }
            } else {
                val digits = end
                while (end < n && s[end] in '0'..'9') end++
                if (end == digits) {
                    out.append('&')
                    i++
                    continue
                }
            }
        } else if (end < n && isAsciiLetter(s[end])) {
            end++
            while (end < n && isAsciiLetterOrDigit(s[end])) end++
        } else {
            out.append('&')
            i++
            continue
        }
        if (end < n && (s[end] == ';' || s[end] == '=')) end++
        val ref = s.substring(i, end)
        val numeric = ref.length > 1 && ref[1] == '#'
        if (numeric) {
            out.append(PyHtml.unescape(ref))
        } else if (!ref.endsWith("=") && ref.substring(1) in HtmlEntityTable.names) {
            out.append(PyHtml.unescape(ref))
        } else {
            out.append(ref)
        }
        i = end
    }
    return out.toString()
}
