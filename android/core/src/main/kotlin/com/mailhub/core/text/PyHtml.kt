package com.mailhub.core.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger

/**
 * Python 的 `html.unescape`，照 CPython 3.13 `html/__init__.py` 逐行移植。
 * 表（HTML5 命名实体、非法数字引用替换表、要删除的码点）由 scripts/golden/html_unescape.py 从 Python 导出，
 * 放在资源 `py/html_unescape.json` 里。
 */
object PyHtml {

    private class Tables(
        val entities: Map<String, String>,
        val invalidCharrefs: Map<Int, String>,
        val invalidCodepoints: Set<Int>,
    )

    private val tables: Tables by lazy {
        val stream = PyHtml::class.java.getResourceAsStream("/py/html_unescape.json")
            ?: error("缺少资源 py/html_unescape.json")
        val root = Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        Tables(
            entities = root.getValue("entities").jsonObject.mapValues { it.value.jsonPrimitive.content },
            invalidCharrefs = root.getValue("invalid_charrefs").jsonObject
                .entries.associate { it.key.toInt() to it.value.jsonPrimitive.content },
            invalidCodepoints = root.getValue("invalid_codepoints").jsonArray.map { it.jsonPrimitive.int }.toSet(),
        )
    }

    /** Python `_charref` 第三个分支排除的字符：`[^\t\n\f <&#;]`。 */
    private fun isNameChar(cp: Int): Boolean = when (cp) {
        '\t'.code, '\n'.code, '\u000c'.code, ' '.code, '<'.code, '&'.code, '#'.code, ';'.code -> false
        else -> true
    }

    /** 对应 `unescape(s)`：没有 `&` 原样返回，否则逐个替换字符引用。 */
    fun unescape(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] != '&') {
                out.append(s[i])
                i++
                continue
            }
            val end = matchCharref(s, i + 1)
            if (end < 0) {
                out.append('&')
                i++
                continue
            }
            out.append(replaceCharref(s.substring(i + 1, end)))
            i = end
        }
        return out.toString()
    }

    /**
     * 在 [start]（`&` 之后）匹配 `#[0-9]+;?|#[xX][0-9a-fA-F]+;?|[^\t\n\f <&#;]{1,32};?`，
     * 返回分组 1 的结束下标（UTF-16），不匹配返回 -1。三个分支按 Python 的先后顺序尝试，
     * 第三个分支的 {1,32} 按码点计。
     */
    private fun matchCharref(s: String, start: Int): Int {
        fun optionalSemicolon(i: Int) = if (i < s.length && s[i] == ';') i + 1 else i
        if (start < s.length && s[start] == '#') {
            var i = start + 1
            while (i < s.length && s[i] in '0'..'9') i++
            if (i > start + 1) return optionalSemicolon(i)
            if (start + 1 < s.length && (s[start + 1] == 'x' || s[start + 1] == 'X')) {
                i = start + 2
                while (i < s.length && (s[i] in '0'..'9' || s[i] in 'a'..'f' || s[i] in 'A'..'F')) i++
                if (i > start + 2) return optionalSemicolon(i)
            }
            return -1 // `#` 不属于第三个分支的字符集
        }
        var i = start
        var count = 0
        while (i < s.length && count < 32) {
            val cp = s.codePointAt(i)
            if (!isNameChar(cp)) break
            i += Character.charCount(cp)
            count++
        }
        if (count == 0) return -1
        return optionalSemicolon(i)
    }

    /** 对应 `_replace_charref`。 */
    private fun replaceCharref(group: String): String {
        val t = tables
        if (group[0] == '#') {
            val num = if (group[1] == 'x' || group[1] == 'X') {
                BigInteger(group.substring(2).trimEnd(';'), 16)
            } else {
                BigInteger(group.substring(1).trimEnd(';'))
            }
            if (num.bitLength() <= 31) {
                val n = num.toInt()
                t.invalidCharrefs[n]?.let { return it }
                if (n in 0xD800..0xDFFF || n > 0x10FFFF) return "�"
                if (n in t.invalidCodepoints) return ""
                return String(Character.toChars(n))
            }
            return "�"
        }
        t.entities[group]?.let { return it }
        // 找最长的前缀实体名（HTML5 标准的规定）：x 从 len(s)-1 递减到 2，按码点计
        val len = group.cpLen()
        for (x in len - 1 downTo 2) {
            val prefix = group.cpSlice(0, x)
            t.entities[prefix]?.let { return it + group.cpSlice(x) }
        }
        return "&$group"
    }
}
