package com.mailhub.core.mail

import com.mailhub.core.text.isPySpace
import com.mailhub.core.text.pyStrip
import java.io.ByteArrayOutputStream

/**
 * 邮件头参数（`Content-Type` / `Content-Disposition` 里 `;` 分隔的那些）的解析，
 * 逐条对应 Python `email` 标准库的 `Message.get_param` 及其依赖：
 *
 * - [parseParams] = `email.message._parseparam`；
 * - [decodeParams] = `email.utils.decode_params`（RFC 2231 的 `name*0*=` 续行与 `name*=` 扩展写法）；
 * - [pyUnquote] / [pyQuote] = `email.utils.unquote` / `quote`；
 * - [collapseRfc2231] = `email.utils.collapse_rfc2231_value`（`errors="replace"`）；
 * - [getParam] = `Message.get_param`（`unquote=True`）；
 * - [contentType] = `Message.get_content_type`；[contentCharset] = `Message.get_content_charset`；
 * - [boundary] = `Message.get_boundary`（`rstrip()`）；[filename] = `Message.get_filename`（`strip()`）。
 *
 * 参数值的字节语义沿用 compat32：头部里的 8 位字节已经被换成 U+FFFD（见 [MimeHeaders]），
 * 所以这里的 `raw-unicode-escape` 编码与 Python 对同一封邮件的处理一致。
 *
 * 已知偏差：`decode_params` 在「同一个参数既有 `filename*` 又有 `filename*0*`」时，
 * Python 的排序比较 `None` 和 `int` 会抛 `TypeError`；[decodeParams] 同样抛 [MimeParamOrderException]，
 * 调用方按「附件名解析失败」处理，结果与 Python 的 `except Exception: fn = ""` 相同。
 */
internal object MimeParams {

    /** `^(\w+)\*(([0-9]+)\*?)?$`（`re.ASCII`）：一个 RFC 2231 续行参数的解析结果。 */
    private class Continuation(val baseName: String, val number: Long?)

    private class ContinuationValue(val number: Long?, val value: String, val encoded: Boolean)

    /** Python 在 `decode_params` 里排序 `(None, ...)` 与 `(数字, ...)` 时抛 `TypeError`。 */
    class MimeParamOrderException(name: String) : Exception("参数 $name 的续行编号混用了空值与数字")

    /** `Message.get_param` 的返回值：普通参数，或 RFC 2231 的 (charset, language, value) 三元组。 */
    sealed class ParamValue {
        class Plain(val value: String) : ParamValue()
        class Extended(val charset: String?, val language: String?, val value: String) : ParamValue()
    }

    private class MimeParam(val name: String, val extended: Rfc2231?, val plain: String) {
        fun toValue(): ParamValue = if (extended != null) {
            ParamValue.Extended(extended.charset, extended.language, pyUnquote(extended.text))
        } else {
            ParamValue.Plain(pyUnquote(plain))
        }
    }

    private class Rfc2231(val charset: String?, val language: String?, val text: String)

    /** `Message.get_param`：没有这个头、或头里没有这个参数时返回 null。 */
    fun getParam(headers: MimeHeaders, name: String, header: String): ParamValue? {
        val raw = headers.get(header) ?: return null
        for (param in decodeParams(parseParams(raw))) {
            if (param.name.equals(name, ignoreCase = true)) return param.toValue()
        }
        return null
    }

    /** `Message.get_content_type`：没有 Content-Type 时用 [defaultType]，格式非法时退回 `text/plain`。 */
    fun contentType(headers: MimeHeaders, defaultType: String): String {
        val value = headers.get("Content-Type") ?: return defaultType
        val ctype = splitParam(value).lowercase()
        // RFC 2045 §5.2：不合法就用 text/plain
        return if (ctype.count { it == '/' } != 1) "text/plain" else ctype
    }

    /** `Message.get_content_charset`：没有 charset 参数或参数不是 ASCII 时返回 null。 */
    fun contentCharset(headers: MimeHeaders): String? {
        val value = getParam(headers, "charset", "Content-Type") ?: return null
        val charset = when (value) {
            is ParamValue.Plain -> value.value
            is ParamValue.Extended -> {
                // RFC 2231 编码的 charset：按 charset 自己的名字解出 ASCII 文本
                val pcharset = value.charset ?: "us-ascii"
                val bytes = rawUnicodeEscapeEncode(value.value)
                val codec = PyCodecs.lookup(pcharset)
                val decoded = codec?.decode(bytes)
                // Python 这里用严格模式：解不出来（LookupError / UnicodeError）就退回原始文本。
                // 本移植的编解码器只有 replace 语义，出现替换符就当成解码失败。
                if (decoded == null || decoded.contains('\uFFFD')) value.value else decoded
            }
        }
        // charset 名必须在 ASCII 范围内
        if (!charset.all { it.code < 0x80 }) return null
        return charset.lowercase()
    }

    /** `Message.get_boundary`：`collapse_rfc2231_value(boundary).rstrip()`。 */
    fun boundary(headers: MimeHeaders): String? {
        val value = getParam(headers, "boundary", "Content-Type") ?: return null
        return collapseRfc2231(value).pyRstrip()
    }

    /** `Message.get_filename`：先找 Content-Disposition 的 filename，再找 Content-Type 的 name。 */
    fun filename(headers: MimeHeaders): String? {
        val value = getParam(headers, "filename", "Content-Disposition")
            ?: getParam(headers, "name", "Content-Type")
            ?: return null
        return collapseRfc2231(value).pyStrip()
    }

    /** `email.utils.collapse_rfc2231_value(value, errors="replace")`。 */
    fun collapseRfc2231(value: ParamValue): String = when (value) {
        is ParamValue.Plain -> value.value
        is ParamValue.Extended -> {
            val charset = value.charset ?: "us-ascii"
            val codec = PyCodecs.lookup(charset)
            if (codec == null) {
                // charset 不认识：Python 退回 unquote(text)（这里已经去引号）
                value.value
            } else {
                codec.decode(rawUnicodeEscapeEncode(value.value))
            }
        }
    }

    /** `email.utils.unquote`。 */
    fun pyUnquote(text: String): String {
        if (text.length > 1) {
            if (text.startsWith("\"") && text.endsWith("\"")) {
                return text.substring(1, text.length - 1).replace("\\\\", "\\").replace("\\\"", "\"")
            }
            if (text.startsWith("<") && text.endsWith(">")) return text.substring(1, text.length - 1)
        }
        return text
    }

    /** `email.utils.quote`：只转义反斜杠和双引号，不加外层引号。 */
    fun pyQuote(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Python 的 `str.rstrip()`（不带参数）。 */
    private fun String.pyRstrip(): String {
        var end = length
        while (end > 0) {
            val codePoint = codePointBefore(end)
            if (!isPySpace(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return substring(0, end)
    }

    /**
     * `email.message._parseparam`：按 `;` 切参数，引号里的 `;` 不算分隔符。
     *
     * 与 Python 逐条对应（包括「引号个数为奇数时继续找下一个 `;`」），但用下标扫描而不是
     * 每次切出剩余串，避免头部很长时退化成平方级。
     */
    fun parseParams(header: String): List<String> {
        val text = ";" + header
        val out = ArrayList<String>()
        var position = 0
        while (position < text.length && text[position] == ';') {
            position++
            var end = text.indexOf(';', position)
            if (end < 0) end = text.length
            // Python 的 s.count('"', 0, end) - s.count('\\"', 0, end)：边扫边累计
            var counted = position
            var quotes = 0
            var escapedQuotes = 0
            fun countTo(limit: Int) {
                val target = minOf(limit, text.length)
                while (counted < target) {
                    if (text[counted] == '"') {
                        if (counted > position && text[counted - 1] == '\\') escapedQuotes++ else quotes++
                    }
                    counted++
                }
            }
            countTo(end)
            while (end > position && (quotes - escapedQuotes) % 2 != 0) {
                val next = text.indexOf(';', end + 1)
                if (next < 0) {
                    end = text.length
                    countTo(end)
                    break
                }
                end = next
                countTo(end)
            }
            var param = text.substring(position, end)
            val eq = param.indexOf('=')
            if (eq >= 0) {
                param = param.substring(0, eq).pyStrip().lowercase() + "=" + param.substring(eq + 1).pyStrip()
            }
            out += param.pyStrip()
            position = end
        }
        return out
    }

    /** `email.utils.decode_params`：把参数列表里的 RFC 2231 续行合并、解码。 */
    private fun decodeParams(params: List<String>): List<MimeParam> {
        if (params.isEmpty()) return emptyList()
        val pairs = params.map { splitParamPair(it) }
        val out = ArrayList<MimeParam>()
        // Python 的第一个参数原样保留（它就是 Content-Type / Content-Disposition 的主值）
        out += MimeParam(pairs[0].first, null, pairs[0].second)
        val continuations = LinkedHashMap<String, MutableList<ContinuationValue>>()
        for (index in 1 until pairs.size) {
            var name = pairs[index].first
            var value = pairs[index].second
            val encoded = name.endsWith("*")
            value = pyUnquote(value)
            val continuation = matchContinuation(name)
            if (continuation != null) {
                continuations.getOrPut(continuation.baseName) { ArrayList() } +=
                    ContinuationValue(continuation.number, value, encoded)
            } else {
                out += MimeParam(name, null, "\"${pyQuote(value)}\"")
            }
        }
        for ((name, values) in continuations) {
            val hasEmpty = values.any { it.number == null }
            val hasNumber = values.any { it.number != null }
            if (hasEmpty && hasNumber) throw MimeParamOrderException(name)
            val sorted = values.sortedBy { it.number ?: 0L }
            val joined = StringBuilder()
            var extended = false
            for (item in sorted) {
                var segment = item.value
                if (item.encoded) {
                    // 按 latin-1 解百分号编码：字符逐个代表一个字节，再交给 collapse_rfc2231_value
                    segment = urlUnquoteLatin1(segment)
                    extended = true
                }
                joined.append(segment)
            }
            val quoted = pyQuote(joined.toString())
            if (extended) {
                val parts = quoted.split("'", limit = 3)
                val charset = if (parts.size <= 2) null else parts[0]
                val language = if (parts.size <= 2) null else parts[1]
                val text = if (parts.size <= 2) quoted else parts[2]
                out += MimeParam(name, Rfc2231(charset, language, "\"$text\""), "")
            } else {
                out += MimeParam(name, null, "\"$quoted\"")
            }
        }
        return out
    }

    /** `p.split('=', 1)` 之后两边都 strip；没有 `=` 时当成裸属性（值取空串）。 */
    private fun splitParamPair(text: String): Pair<String, String> {
        val eq = text.indexOf('=')
        return if (eq < 0) text.pyStrip() to "" else text.substring(0, eq).pyStrip() to text.substring(eq + 1).pyStrip()
    }

    /** `^(\w+)\*(([0-9]+)\*?)?$`（`re.ASCII`）。 */
    private fun matchContinuation(name: String): Continuation? {
        val star = name.indexOf('*')
        if (star <= 0) return null
        for (index in 0 until star) {
            val ch = name[index]
            val word = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'
            if (!word) return null
        }
        val rest = name.substring(star + 1)
        if (rest.isEmpty()) return Continuation(name.substring(0, star), null)
        val digits = if (rest.endsWith("*")) rest.dropLast(1) else rest
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        return Continuation(name.substring(0, star), digits.toLongOrNull() ?: Long.MAX_VALUE)
    }

    /**
     * `urllib.parse.unquote(s, encoding="latin-1")`：先把字符串按 UTF-8 变成字节，
     * `%XX` 解成一个字节（不是合法十六进制就原样保留 `%`），最后按 latin-1 一字节一字符。
     *
     * 这里的 UTF-8 编码与 latin-1 解码都是逐个码点 / 字节换算，**不经过平台的字符集实现**
     * （与 [PyCodecs] 的理由相同：桌面 JDK 与 Android ICU 的映射不一定一致）。
     */
    private fun urlUnquoteLatin1(text: String): String {
        val bytes = utf8Bytes(text)
        val out = ByteArrayOutputStream(bytes.size)
        var index = 0
        while (index < bytes.size) {
            if (bytes[index] == '%'.code.toByte()) {
                if (index + 2 < bytes.size) {
                    val high = hexValue(bytes[index + 1])
                    val low = hexValue(bytes[index + 2])
                    if (high >= 0 && low >= 0) {
                        out.write(high * 16 + low)
                        index += 3
                        continue
                    }
                }
                out.write('%'.code)
                index++
                continue
            }
            out.write(bytes[index].toInt() and 0xFF)
            index++
        }
        val decoded = out.toByteArray()
        val chars = CharArray(decoded.size) { (decoded[it].toInt() and 0xFF).toChar() }
        return String(chars)
    }

    /** Python 的 `s.encode("utf-8")`。 */
    private fun utf8Bytes(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when {
                codePoint < 0x80 -> out.write(codePoint)
                codePoint < 0x800 -> {
                    out.write(0xC0 or (codePoint shr 6))
                    out.write(0x80 or (codePoint and 0x3F))
                }
                codePoint < 0x10000 -> {
                    out.write(0xE0 or (codePoint shr 12))
                    out.write(0x80 or ((codePoint shr 6) and 0x3F))
                    out.write(0x80 or (codePoint and 0x3F))
                }
                else -> {
                    out.write(0xF0 or (codePoint shr 18))
                    out.write(0x80 or ((codePoint shr 12) and 0x3F))
                    out.write(0x80 or ((codePoint shr 6) and 0x3F))
                    out.write(0x80 or (codePoint and 0x3F))
                }
            }
            index += Character.charCount(codePoint)
        }
        return out.toByteArray()
    }

    private fun hexValue(byte: Byte): Int = when (val value = byte.toInt() and 0xFF) {
        in '0'.code..'9'.code -> value - '0'.code
        in 'a'.code..'f'.code -> value - 'a'.code + 10
        in 'A'.code..'F'.code -> value - 'A'.code + 10
        else -> -1
    }

    /** `_splitparam(value)[0]`：第一个 `;` 之前的类型，去掉两端空白。 */
    private fun splitParam(value: String): String {
        val semicolon = value.indexOf(';')
        return (if (semicolon >= 0) value.substring(0, semicolon) else value).pyStrip()
    }
}
