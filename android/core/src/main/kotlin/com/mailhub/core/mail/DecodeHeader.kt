package com.mailhub.core.mail

import com.mailhub.core.text.PyRegex
import com.mailhub.core.text.isPySpace
import com.mailhub.core.text.pyLstrip
import com.mailhub.core.text.pySplitLines
import com.mailhub.core.text.pyStrip
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

/** Python 抛 `email.errors.HeaderParseError` 的场合（例如编码字的 base64 解不出来）。 */
internal class HeaderParseError(message: String) : Exception(message)

/**
 * `mail_client._decode_header` 的移植：
 *
 * ```python
 * parts = email.header.decode_header(str(raw))
 * out = [data.decode(charset or "utf-8", errors="replace") if isinstance(data, bytes) else data ...]
 * return "".join(out).strip()
 * ```
 *
 * 任何异常（未知 charset 的 `LookupError`、base64 解的 `HeaderParseError`）都退回 `str(raw)`。
 *
 * @param raw 头部的原始值（已经过 compat32 的取值语义，见 [MimeHeaders]）；null 对应 Python 的 None。
 */
fun decodeHeader(raw: String?): String {
    if (raw == null) return ""
    return try {
        val out = StringBuilder()
        for ((data, charset) in decodeHeaderParts(raw)) {
            if (data is ByteArray) {
                // charset 为 None 时按 UTF-8 解；名字不认识时 Python 抛 LookupError，走下面的 except
                val text = PyCodecs.decode(data, charset ?: "utf-8") ?: return raw
                out.append(text)
            } else {
                out.append(data as String)
            }
        }
        out.toString().pyStrip()
    } catch (_: Exception) {
        raw
    }
}

/**
 * `email.header.decode_header` 的移植。返回值里 [Any] 是 `String`（普通文本）或 `ByteArray`
 * （编码字解出来的字节），第二项是编码字里写的小写 charset 名（普通文本为 null）。
 *
 * 与 CPython 逐条对应的部分：
 * - 只认出 `=?charset?q?…?=` / `=?charset?b?…?=` 两种编码（[ENCODED_WORD]）；
 * - 按行处理（`header.splitlines()`），每行的第一段做 `lstrip()`；
 * - 两个编码字之间只有空白的片段会被删掉；
 * - charset 相同的相邻片段合并：都在编码字里就首尾相接，普通文本之间用空格连接；
 * - 没有编码字时整串原样返回。
 */
internal fun decodeHeaderParts(header: String): List<Pair<Any, String?>> {
    if (!ENCODED_WORD.matcher(header).find()) return listOf(header to null)

    // (文本, 编码, charset)：普通文本的编码和 charset 都是 null
    data class Word(val text: String, val encoding: String?, val charset: String?)

    val words = ArrayList<Word>()
    for (line in header.pySplitLines()) {
        val parts = splitWithGroups(ENCODED_WORD, line).toMutableList()
        var first = true
        while (parts.isNotEmpty()) {
            var unencoded = parts.removeAt(0)
            if (first) {
                unencoded = unencoded.pyLstrip()
                first = false
            }
            if (unencoded.isNotEmpty()) words += Word(unencoded, null, null)
            if (parts.isNotEmpty()) {
                val charset = parts.removeAt(0).lowercase()
                val encoding = parts.removeAt(0).lowercase()
                val encoded = parts.removeAt(0)
                words += Word(encoded, encoding, charset)
            }
        }
    }

    // 两个编码字之间只隔空白时，那段空白要丢掉（RFC 2047：相邻编码字之间的线性空白不算内容）
    val droplist = ArrayList<Int>()
    for (index in words.indices) {
        if (index > 1 && words[index].encoding != null && words[index - 2].encoding != null &&
            isPyBlank(words[index - 1].text)
        ) {
            droplist += index - 1
        }
    }
    for (index in droplist.reversed()) words.removeAt(index)

    // 逐个解码成字节；普通文本先按 raw-unicode-escape 变成字节，才能和编码字拼接
    val decoded = ArrayList<Pair<ByteArray, String?>>()
    for (word in words) {
        when (val encoding = word.encoding) {
            null -> decoded += rawUnicodeEscapeEncode(word.text) to word.charset
            "q" -> decoded += rawUnicodeEscapeEncode(quopriHeaderDecode(word.text)) to word.charset
            "b" -> {
                var encoded = word.text
                val remainder = encoded.length % 4
                // Postel 规则：补上缺的 '='
                if (remainder != 0) encoded += "=".repeat(4 - remainder)
                val bytes = try {
                    a2bBase64Lenient(encoded)
                } catch (_: Base64DecodeError) {
                    throw HeaderParseError("Base64 decoding error")
                }
                decoded += bytes to word.charset
            }
            else -> throw IllegalStateException("Unexpected encoding: $encoding")
        }
    }

    // 合并相邻的、charset 相同的片段
    val collapsed = ArrayList<Pair<ByteArray, String?>>()
    var lastWord: ByteArray? = null
    var lastCharset: String? = null
    for ((word, charset) in decoded) {
        if (lastWord == null) {
            lastWord = word
            lastCharset = charset
        } else if (charset != lastCharset) {
            collapsed += lastWord!! to lastCharset
            lastWord = word
            lastCharset = charset
        } else if (lastCharset == null) {
            lastWord = lastWord!! + byteArrayOf(' '.code.toByte()) + word
        } else {
            lastWord = lastWord!! + word
        }
    }
    if (lastWord != null) collapsed += lastWord!! to lastCharset
    return collapsed
}

/** `email.header.ecre`：编译一次复用。 */
private val ENCODED_WORD: Pattern = PyRegex.compile(
    "=\\?(?P<charset>[^?]*?)\\?(?P<encoding>[qQbB])\\?(?P<encoded>.*?)\\?=",
    multiline = true,
)

/**
 * Python 的 `re.split(pattern, text)`（模式带捕获组）：返回「非匹配片段 + 各组」交错的列表。
 */
private fun splitWithGroups(pattern: Pattern, text: String): List<String> {
    val matcher = pattern.matcher(text)
    val out = ArrayList<String>()
    var last = 0
    while (matcher.find()) {
        out += text.substring(last, matcher.start())
        for (group in 1..matcher.groupCount()) out += matcher.group(group) ?: ""
        last = matcher.end()
    }
    out += text.substring(last)
    return out
}

/** Python 的 `s.isspace()`（非空且全是空白）。 */
private fun isPyBlank(text: String): Boolean {
    if (text.isEmpty()) return false
    var index = 0
    while (index < text.length) {
        val cp = text.codePointAt(index)
        if (!com.mailhub.core.text.isPySpace(cp)) return false
        index += Character.charCount(cp)
    }
    return true
}

/**
 * `email.quoprimime.header_decode` 的移植：`_` 换成空格，`=XX`（只认 ASCII 十六进制）换成对应字节。
 * 返回的是「每字符一个字节」的字符串（CPython 里这一步之后才用 raw-unicode-escape 变回字节）。
 */
internal fun quopriHeaderDecode(text: String): String {
    val spaced = text.replace('_', ' ')
    val out = StringBuilder(spaced.length)
    var index = 0
    while (index < spaced.length) {
        val ch = spaced[index]
        if (ch == '=' && index + 2 < spaced.length &&
            isAsciiHex(spaced[index + 1]) && isAsciiHex(spaced[index + 2])
        ) {
            out.append(spaced.substring(index + 1, index + 3).toInt(16).toChar())
            index += 3
        } else {
            out.append(ch)
            index++
        }
    }
    return out.toString()
}

private fun isAsciiHex(ch: Char): Boolean =
    ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

/**
 * Python 的 `bytes(s, 'raw-unicode-escape')`：0x100 以下的字符写成单字节，
 * 其余写成 `\uXXXX`（U+FFFF 以上拆成代理对的两个转义）。
 */
internal fun rawUnicodeEscapeEncode(text: String): ByteArray {
    val out = ByteArrayOutputStream(text.length)
    var index = 0
    while (index < text.length) {
        val cp = text.codePointAt(index)
        if (cp < 0x100) {
            out.write(cp)
        } else if (cp <= 0xFFFF) {
            out.write("\\u%04x".format(cp).toByteArray(Charsets.US_ASCII))
        } else {
            val offset = cp - 0x10000
            val high = 0xD800 + (offset shr 10)
            val low = 0xDC00 + (offset and 0x3FF)
            out.write("\\u%04x\\u%04x".format(high, low).toByteArray(Charsets.US_ASCII))
        }
        index += Character.charCount(cp)
    }
    return out.toByteArray()
}

/** `binascii.Error`，对应 Python 里 base64 解的失败。 */
internal class Base64DecodeError(message: String) : Exception(message)

/**
 * `binascii.a2b_base64(data, strict_mode=False)` 的移植（Python 3.11 起的宽松模式）：
 *
 * - 字母表外的字符（空白、标点）直接丢弃；
 * - 数据字符（含第一个 `=` 之后的那些）总数是 4n+1 时报错；
 * - 第一个 `=` 之前的数据字符数不是 4 的倍数时，要求有足够的 `=` 把它们补满，否则报错；
 * - 分段解码，最后一组不足 4 个字符时只输出完整的字节，多余的位丢掉（`ab==` 得到 1 个字节）。
 */
internal fun a2bBase64Lenient(text: String): ByteArray {
    val data = StringBuilder(text.length)
    var dataChars = 0
    var padding = 0
    var paddingStarted = false
    for (ch in text) {
        when {
            isBase64Char(ch) -> {
                dataChars++
                if (!paddingStarted) data.append(ch)
            }
            ch == '=' -> {
                padding++
                paddingStarted = true
            }
            else -> Unit // 字母表外：忽略
        }
    }
    if (dataChars % 4 == 1) {
        throw Base64DecodeError("number of data characters ($dataChars) cannot be 1 more than a multiple of 4")
    }
    val remainder = data.length % 4
    if (remainder == 1) throw Base64DecodeError("Incorrect padding")
    if (remainder != 0 && padding < 4 - remainder) throw Base64DecodeError("Incorrect padding")

    val out = ByteArrayOutputStream(data.length / 4 * 3 + 3)
    var index = 0
    while (index + 3 < data.length) {
        val chunk = (decodeBase64Char(data[index]) shl 18) or
            (decodeBase64Char(data[index + 1]) shl 12) or
            (decodeBase64Char(data[index + 2]) shl 6) or
            decodeBase64Char(data[index + 3])
        out.write((chunk shr 16) and 0xFF)
        out.write((chunk shr 8) and 0xFF)
        out.write(chunk and 0xFF)
        index += 4
    }
    val rest = data.length - index
    if (rest == 2) {
        val chunk = (decodeBase64Char(data[index]) shl 18) or (decodeBase64Char(data[index + 1]) shl 12)
        out.write((chunk shr 16) and 0xFF)
    } else if (rest == 3) {
        val chunk = (decodeBase64Char(data[index]) shl 18) or
            (decodeBase64Char(data[index + 1]) shl 12) or
            (decodeBase64Char(data[index + 2]) shl 6)
        out.write((chunk shr 16) and 0xFF)
        out.write((chunk shr 8) and 0xFF)
    }
    return out.toByteArray()
}

private fun isBase64Char(ch: Char): Boolean =
    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '+' || ch == '/'

private fun decodeBase64Char(ch: Char): Int = when (ch) {
    in 'A'..'Z' -> ch - 'A'
    in 'a'..'z' -> ch - 'a' + 26
    in '0'..'9' -> ch - '0' + 52
    '+' -> 62
    '/' -> 63
    else -> 0 // 调用方已过滤掉字母表外的字符
}
