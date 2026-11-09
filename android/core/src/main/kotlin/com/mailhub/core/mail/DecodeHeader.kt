package com.mailhub.core.mail

import com.mailhub.core.text.isPySpace
import com.mailhub.core.text.pyLstrip
import com.mailhub.core.text.pySplitLines
import com.mailhub.core.text.pyStrip
import java.io.ByteArrayOutputStream

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
                // Python 的 `bytes.decode`：空字节在查编解码器之前就返回空串，
                // 所以 `=?unknown?q??=` 不会抛 LookupError，结果也是空串
                if (data.isEmpty()) continue
                // Python 是 `charset or "utf-8"`：charset 为空串时也用 UTF-8；
                // 名字不认识时 Python 抛 LookupError，走下面的 except
                val name = if (charset.isNullOrEmpty()) "utf-8" else charset
                val text = PyCodecs.decode(data, name) ?: return raw
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
 * - 只认出 `=?charset?q?…?=` / `=?charset?b?…?=` 两种编码（[ENCODED_WORD] 的语义）；
 * - 按行处理（`header.splitlines()`），每行的第一段做 `lstrip()`；
 * - 两个编码字之间只有空白的片段会被删掉；
 * - charset 相同的相邻片段合并：都在编码字里就首尾相接，普通文本之间用空格连接；
 * - 没有编码字时整串原样返回。
 *
 * **反回溯（有意偏差）**：Python 用的是
 * `ecre = re.compile(r'=\?(?P<charset>[^?]*?)\?(?P<encoding>[qQbB])\?(?P<encoded>.*?)\?=')`
 * 加 `re.split`。`.*?` 在「很多个 `=?x?q?A` 开头、始终没有 `?=`」的输入上是平方级：
 * 一兆字节大约 7.5×10¹⁰ 步，而 `Subject` 与附件名都走这里，攻击者可以控。
 * 这里用单遍扫描实现同一语义（见 [findEncodedWord]），最坏输入线性。
 */
internal fun decodeHeaderParts(header: String): List<Pair<Any, String?>> {
    // Python 的 `if not ecre.search(header): return [(header, None)]`：这个判断在**整串**上做
    // （`.` 只被换行挡住），而接下来的切分是按 `header.splitlines()` 逐行做的。两者不一致时以 Python 为准：
    // 例如 `=?a?q?x\r?=` 在整串上能匹配（`.` 认裸 \r），逐行切分后两行都没有编码字。
    if (findEncodedWord(header, 0, Finders(header)) == null) return listOf(header to null)

    // (文本, 编码, charset)：普通文本的编码和 charset 都是 null
    data class Word(val text: String, val encoding: String?, val charset: String?)

    val words = ArrayList<Word>()
    for (line in header.pySplitLines()) {
        val parts = splitEncodedWords(line).toMutableList()
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
                // Python 的 `len(encoded_string)` 按码点计
                val remainder = encoded.codePointCount(0, encoded.length) % 4
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

/**
 * 一个编码字的匹配：`start`..`end` 是整段，三个捕获组是组文本。
 *
 * 对应 Python 的 `email.header.ecre`：
 * `=\?(?P<charset>[^?]*?)\?(?P<encoding>[qQbB])\?(?P<encoded>.*?)\?=`（`re.MULTILINE`）。
 */
private class EncodedWordMatch(val start: Int, val end: Int, val charset: String, val encoding: String, val encoded: String)

/**
 * Python 的 `ecre.split(line)`（模式带捕获组）：返回「非匹配片段 + 各组」交错的列表。
 *
 * `ENCODED_WORD_PATTERN` 的匹配是**确定的**，所以可以手写扫描而不损失语义：
 * 1. `=?` 之后是 `[^?]*?`，字符集里不能有 `?`，所以 `\?` 只能落在 `=?` 之后的**第一个** `?` 上；
 * 2. 紧接着必须是 `[qQbB]`，再后面必须是 `?`；
 * 3. `.*?`（不含换行）配到 `?=` 的最小匹配，也就是这个位置之后的**第一个** `?=`——
 *    它必须在同一行的下一个换行之前。
 *
 * 「下一个 `?`」「下一个 `?=`」「下一个换行」用单调游标的缓存来查（见 [MonotoneFinder]），
 * 查询位置只增不减，所以整行扫描是线性的；找不到匹配时从候选起点的下一个字符继续。
 */
private fun splitEncodedWords(line: String): List<String> {
    val finders = Finders(line)
    val out = ArrayList<String>()
    var last = 0
    while (true) {
        val match = findEncodedWord(line, last, finders) ?: break
        out += line.substring(last, match.start)
        out += match.charset
        out += match.encoding
        out += match.encoded
        last = match.end
    }
    out += line.substring(last)
    return out
}

/** 在一行里从 [from] 开始找下一个编码字；找不到返回 null。 */
private fun findEncodedWord(line: String, from: Int, finders: Finders): EncodedWordMatch? {
    var index = from
    while (true) {
        val start = line.indexOf("=?", index)
        if (start < 0) return null
        val charsetStart = start + 2
        val question = finders.nextQuestionMark(charsetStart)
        if (question >= 0 && question + 2 < line.length) {
            val encoding = line[question + 1]
            if ((encoding == 'q' || encoding == 'Q' || encoding == 'b' || encoding == 'B') && line[question + 2] == '?') {
                val encodedStart = question + 3
                val encodedEnd = finders.nextEncodedEnd(encodedStart)
                if (encodedEnd >= 0) {
                    return EncodedWordMatch(
                        start,
                        encodedEnd + 2,
                        line.substring(charsetStart, question),
                        encoding.toString(),
                        line.substring(encodedStart, encodedEnd),
                    )
                }
            }
        }
        index = start + 1
    }
}

/**
 * 「下一个 `?`」「下一个 `?=`（且不跨行）」的单调查找器。
 *
 * `decode_header` 里的查询位置只增不减，所以缓存「从上次起点往后找到的位置」即可：
 * 起点变大时如果缓存位置还在后面就直接复用，否则重新扫一遍——每个字符最多被扫常数次，整体线性。
 */
private class Finders(private val line: String) {

    private var questionFrom = -1
    private var questionPosition = -1

    private var encodedFrom = -1
    private var encodedPosition = -1

    private var newlineFrom = -1
    private var newlinePosition = -1

    fun nextQuestionMark(from: Int): Int {
        if (questionFrom >= 0 && from >= questionFrom && (questionPosition < 0 || questionPosition >= from)) {
            return questionPosition
        }
        questionFrom = from
        questionPosition = line.indexOf('?', from)
        return questionPosition
    }

    /** 下一个 `?=` 的位置；如果它跨过了换行（`.*?` 不含换行）就返回 -1。 */
    fun nextEncodedEnd(from: Int): Int {
        if (!(encodedFrom >= 0 && from >= encodedFrom && (encodedPosition < 0 || encodedPosition >= from))) {
            encodedFrom = from
            encodedPosition = line.indexOf("?=", from)
        }
        if (encodedPosition < 0) return -1
        val newline = nextNewline(from)
        return if (newline >= 0 && newline < encodedPosition) -1 else encodedPosition
    }

    private fun nextNewline(from: Int): Int {
        if (newlineFrom >= 0 && from >= newlineFrom && (newlinePosition < 0 || newlinePosition >= from)) {
            return newlinePosition
        }
        newlineFrom = from
        newlinePosition = line.indexOf('\n', from)
        return newlinePosition
    }
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
 * Python 的 `bytes(s, 'raw-unicode-escape')`：0x100 以下的字符写成单字节，BMP 内的其余字符
 * 写成 `\uXXXX`，U+10000 以上的写成 `\UXXXXXXXX`（8 位小写十六进制，不是代理对）。
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
            out.write("\\U%08x".format(cp).toByteArray(Charsets.US_ASCII))
        }
        index += Character.charCount(cp)
    }
    return out.toByteArray()
}

/** `binascii.Error`，对应 Python 里 base64 解的失败。 */
internal class Base64DecodeError(message: String) : Exception(message)

/**
 * `binascii.a2b_base64(data, strict_mode=False)` 的移植（Python 3.11 起的宽松模式），
 * 入口与 `email.base64mime.decode` 一样先做 `data.encode('raw-unicode-escape')`。
 *
 * 逐条对应 CPython 3.13 `Modules/binascii.c` 的状态机：
 * - 字母表外的字节（空白、标点、`\uXXXX` 里的反斜杠等）直接跳过，且不会重置 `pads`；
 * - `=` 只在 `quad_pos >= 2` 且 `quad_pos + pads >= 4` 时终止解码（`pads` 是连续 `=` 的个数，
 *   遇到数据字节清零）。`quad_pos` 为 0 或 1 时 `=` 被忽略，不参与计数；
 * - 结束后 `quad_pos == 1` 说明数据字符数比 4 的倍数多 1，报错；
 *   `quad_pos` 为 2 或 3 说明缺 padding，报错。
 *
 * 需要注意的点：第一个 `=` 之后的数据字符仍会继续解码（例如 `QUJD=REVG` 得到 `ABCDEF`），
 * 这与「`=` 之后全部丢弃」的旧实现不同。
 */
internal fun a2bBase64Lenient(text: String): ByteArray = a2bBase64LenientBytes(rawUnicodeEscapeEncode(text))

/**
 * 同 [a2bBase64Lenient]，输入已经是字节（邮件正文用：正文按 ascii + surrogateescape 变成字符串，
 * 再变回字节仍是原来的字节，所以直接喂字节即可）。
 */
internal fun a2bBase64LenientBytes(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(bytes.size / 4 * 3 + 3)
    var quadPos = 0
    var leftChar = 0
    var pads = 0
    var stoppedByPadding = false
    for (byte in bytes) {
        val ch = byte.toInt() and 0xFF
        if (ch == '='.code) {
            if (quadPos >= 2) {
                pads++
                if (quadPos + pads >= 4) {
                    // 与 C 的 `goto done` 一样：padding 提前结束解码，跳过最后的完整性检查
                    stoppedByPadding = true
                    break
                }
            }
            continue
        }
        val value = base64Value(ch)
        if (value < 0) continue
        pads = 0
        when (quadPos) {
            0 -> {
                quadPos = 1
                leftChar = value
            }
            1 -> {
                quadPos = 2
                out.write((leftChar shl 2) or (value shr 4))
                leftChar = value and 0x0F
            }
            2 -> {
                quadPos = 3
                out.write((leftChar shl 4) or (value shr 2))
                leftChar = value and 0x03
            }
            else -> {
                quadPos = 0
                out.write((leftChar shl 6) or value)
                leftChar = 0
            }
        }
    }
    if (!stoppedByPadding) {
        if (quadPos == 1) throw Base64DecodeError("number of data characters cannot be 1 more than a multiple of 4")
        if (quadPos != 0) throw Base64DecodeError("Incorrect padding")
    }
    return out.toByteArray()
}

/** `table_a2b_base64`：字母表内返回 0–63，其余返回 -1。 */
internal fun base64Value(ch: Int): Int = when (ch) {
    in 'A'.code..'Z'.code -> ch - 'A'.code
    in 'a'.code..'z'.code -> ch - 'a'.code + 26
    in '0'.code..'9'.code -> ch - '0'.code + 52
    '+'.code -> 62
    '/'.code -> 63
    else -> -1
}
