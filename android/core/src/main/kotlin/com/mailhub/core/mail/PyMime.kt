package com.mailhub.core.mail

import com.mailhub.core.text.pyStrip
import java.io.ByteArrayOutputStream

/**
 * MIME 结构解析：对应 compat32 策略下 `email.message_from_bytes(raw)` 得到的 `Message` 树，
 * 只保留 `mail_client.parse_message` 用到的那部分行为（`walk()`、`get_content_type()`、
 * `get_payload(decode=True)`、`get_filename()`、`is_multipart()`）。
 *
 * 为什么不用 Angus Mail 的 `MimeMessage`：Python 是规格。`email` 的 compat32 策略对不合规邮件
 * 的处理（缺 boundary、boundary 没出现、连续分隔行、`Content-Disposition` 原文判断大小写）
 * 与 Angus 不同，而分类规则吃的就是这里产出的正文，必须与 Python 逐字符一致。
 *
 * **资源上限（有意偏差）**：邮件内容由攻击者控制，Python 靠递归深度上限在约一千层嵌套时抛
 * `RecursionError`。这里显式限制嵌套层数与 part 数量，超过就抛普通 [MimeLimitException]；
 * [parseMessage] 会把它当成解析失败，返回 `unparsable_message()`（与 Python 抛 RecursionError
 * 后由 `_collect` 兜底的结果相同）。真实邮件的嵌套深度远小于 100 层。
 */

/** 超过 MIME 嵌套层数或 part 数量上限。 */
internal class MimeLimitException(message: String) : Exception(message)

/** MIME 嵌套层数上限（Python 实测在 1000 层左右抛 RecursionError）。 */
internal const val MAX_MIME_DEPTH = 100

/** 单封邮件的 MIME 节点数量上限。 */
internal const val MAX_MIME_PARTS = 2000

private val EMPTY_BYTES = ByteArray(0)

/**
 * 一个 MIME 节点。
 *
 * @param headers 这一段的头部（[MimeHeaders] 的 compat32 取值语义）
 * @param defaultType 没有 Content-Type 时的默认类型；`multipart/digest` 的子节点是 `message/rfc822`，
 *   其余是 `text/plain`（对应 Python 的 `Message.set_default_type`）
 * @param children 非 null 表示 Python 的 `is_multipart()` 为真（multipart 容器或 message 容器）
 * @param payload 叶子节点的原始正文（未按 Content-Transfer-Encoding 解码）
 */
internal class MimePart(
    val headers: MimeHeaders,
    val defaultType: String,
    val children: List<MimePart>?,
    val payload: ByteArray,
) {
    /** `Message.get_content_type()`。 */
    val contentType: String get() = MimeParams.contentType(headers, defaultType)
}

/**
 * 把原始字节解析成 MIME 树，对应 `email.message_from_bytes` + `FeedParser`。
 *
 * 与 `_parsegen` 的对应关系：
 * - 头部/正文分隔行的判断见 [MimeHeaders.parsePart]；
 * - `multipart` 主类型且有 boundary 时按分隔行切开，逐行照抄 `_parsegen` 的分隔行状态机
 *   （前导文字、连续分隔行、紧跟分隔行的结束分隔行、结尾文字都按 RFC 2046 处理）；
 * - 「分隔行前面的那个换行属于分隔行」这条规则只在叶子 part 的正文上生效（`trimPayload`），
 *   容器 part（multipart / message）不裁自己的正文——Python 对容器裁的是 epilogue，
 *   而容器的子 part 已经各自裁过一次，多裁一次会让未闭合的内层 multipart 少一个换行；
 * - `message` 主类型递归解析成一个子节点，子消息同样不裁行尾（Python 里它是 list，裁不到）；
 *   `message/delivery-status` 按空行切成多个只含头部的块，每块都是一个 `text/plain` 子节点；
 * - 声称 multipart 但没有 boundary、或 boundary 在正文里没出现（含 boundary 含非 ASCII 字符，
 *   此时 Python 的 `startswith` 永不匹配）时，Python 把它当成叶子，这里同样返回叶子
 *   （它的类型不是 text/plain，所以不产生正文）。
 */
internal class MimeStructureParser(
    private val maxDepth: Int = MAX_MIME_DEPTH,
    private val maxParts: Int = MAX_MIME_PARTS,
) {

    private var parsedParts = 0

    fun parse(raw: ByteArray): MimePart = parsePart(raw, 0, raw.size, 0, "text/plain", trimPayload = false)

    /**
     * @param trimPayload 叶子正文末尾是否去掉一个行尾。只有 multipart 的直接子 part 为真
     *   （RFC 2046：分隔行前面的换行属于分隔行）；顶层正文、message 的子消息、
     *   `message/delivery-status` 的块都保留原样。
     */
    private fun parsePart(
        raw: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        defaultType: String,
        trimPayload: Boolean,
    ): MimePart {
        if (depth > maxDepth) throw MimeLimitException("MIME 嵌套超过 $maxDepth 层")
        parsedParts++
        if (parsedParts > maxParts) throw MimeLimitException("MIME part 数量超过 $maxParts")

        val block = MimeHeaders.parsePart(raw, start, end)
        val headers = block.headers
        // 头部末行的 `From ` 被推回正文时，它和后面的正文之间还有一个已被丢掉的空行（Python 的
        // 分隔行）：正文要把这个空行抠掉，所以这里拼一份新的字节再继续解析。
        var bodyRaw = raw
        var bodyStart = block.bodyStart
        var bodyEnd = end
        if (block.skippedLineStart >= 0) {
            val merged = ByteArray((block.skippedLineStart - block.bodyStart) + (end - block.skippedLineEnd))
            raw.copyInto(merged, 0, block.bodyStart, block.skippedLineStart)
            raw.copyInto(merged, block.skippedLineStart - block.bodyStart, block.skippedLineEnd, end)
            bodyRaw = merged
            bodyStart = 0
            bodyEnd = merged.size
        }
        val contentType = MimeParams.contentType(headers, defaultType)
        val mainType = contentType.substringBefore('/')

        if (mainType == "multipart") {
            val boundary = MimeParams.boundary(headers)
            if (boundary != null) {
                val children = splitMultipart(bodyRaw, bodyStart, bodyEnd, boundary, contentType, depth)
                if (children != null) return MimePart(headers, defaultType, children, EMPTY_BYTES)
            }
            return leaf(headers, defaultType, bodyRaw, bodyStart, bodyEnd, trimPayload)
        }
        if (mainType == "message") {
            if (contentType == "message/delivery-status") {
                // 每个块是独立的子消息（没有正文的容器），一样计入 part 上限
                val blocks = deliveryStatusBlocks(bodyRaw, bodyStart, bodyEnd, depth, trimPayload)
                return MimePart(headers, defaultType, blocks, EMPTY_BYTES)
            }
            // Python 裁的是「最后创建的那个子消息」的正文：message 容器自己不裁自己的正文，
            // 但内层若还是叶子就裁一次，若内层是 multipart 就由那一层自己裁（所以标志继续往下传）。
            val child = parsePart(bodyRaw, bodyStart, bodyEnd, depth + 1, "text/plain", trimPayload)
            return MimePart(headers, defaultType, listOf(child), EMPTY_BYTES)
        }
        return leaf(headers, defaultType, bodyRaw, bodyStart, bodyEnd, trimPayload)
    }

    private fun leaf(
        headers: MimeHeaders,
        defaultType: String,
        raw: ByteArray,
        start: Int,
        end: Int,
        trimPayload: Boolean,
    ): MimePart {
        val payloadEnd = if (trimPayload) trimLineEnding(raw, start, end) else end
        return MimePart(headers, defaultType, null, raw.copyOfRange(start, payloadEnd))
    }

    /**
     * `FeedParser._parsegen` 里 multipart 那一段的逐行状态机。返回 null 表示「没有起始分隔行」，
     * Python 在这种情况下把整个正文当叶子。
     */
    private fun splitMultipart(
        raw: ByteArray,
        bodyStart: Int,
        end: Int,
        boundary: String,
        parentType: String,
        depth: Int,
    ): List<MimePart>? {
        // 分隔行是按字节比较的：boundary 里出现 >=0x80 的字符时，Python 的 `line.startswith('--'+boundary)`
        // 永远不成立（原始行里那些字节是代理字符），按「没有起始分隔行」处理。
        if (boundary.any { it.code >= 0x80 }) return null
        val separator = separatorBytes(boundary)
        val childDefault = if (parentType == "multipart/digest") "message/rfc822" else "text/plain"
        val children = ArrayList<MimePart>()
        var position = bodyStart
        var capturePreamble = true
        while (true) {
            if (position >= end) break
            val lineEnd = MimeHeaders.lineEnd(raw, position, end)
            val match = boundaryMatch(raw, position, lineEnd, separator)
            if (match == null) {
                // 前导文字：Python 收进 preamble，不影响正文
                position = lineEnd
                continue
            }
            if (match.isEnd) {
                // 只看到结束分隔行：Python 把之前的内容当正文（叶子），不是 multipart
                return if (capturePreamble) null else children
            }
            if (capturePreamble) {
                capturePreamble = false
                // Python 的 unreadline：不推进，重新处理这一行
                continue
            }
            // 吃掉紧跟其后的连续分隔行。Python 这里不区分结束分隔行，所以
            // `--b` 后面紧跟 `--b--` 时，结束行也被当成普通分隔行吃掉，后面的内容仍是子 part。
            while (position < end) {
                val innerEnd = MimeHeaders.lineEnd(raw, position, end)
                if (boundaryMatch(raw, position, innerEnd, separator) == null) break
                position = innerEnd
            }
            if (position >= end) return children
            // 解析一个子 part：从当前位置直到下一个分隔行
            val partStart = position
            var childEnd = end
            var scan = position
            while (scan < end) {
                val scanEnd = MimeHeaders.lineEnd(raw, scan, end)
                if (boundaryMatch(raw, scan, scanEnd, separator) != null) {
                    childEnd = scan
                    break
                }
                scan = scanEnd
            }
            children += parsePart(raw, partStart, childEnd, depth + 1, childDefault, trimPayload = true)
            position = childEnd
        }
        return if (capturePreamble) null else children
    }

    /**
     * `message/delivery-status` 的分块：Python 逐个 `_parsegen` 解析以空行分隔的块，
     * 每块是一个只含头部的子消息（块内不是头部行的部分落到该子消息的正文）。
     * 块与块之间的空行属于分隔符，块本身不含这个空行。
     *
     * @param trimPayload 只有**最后一块**可能是 Python 收尾时裁正文的那一个子消息
     *   （`_last` 指向最后创建的消息），前面的块一律不裁。
     */
    private fun deliveryStatusBlocks(
        raw: ByteArray,
        bodyStart: Int,
        end: Int,
        depth: Int,
        trimPayload: Boolean,
    ): List<MimePart> {
        val blocks = ArrayList<MimePart>()
        var position = bodyStart
        while (true) {
            var scan = position
            var blockEnd = end
            var nextStart = end
            var hasNext = false
            while (scan < end) {
                val scanEnd = MimeHeaders.lineEnd(raw, scan, end)
                val first = raw[scan].toInt() and 0xFF
                if (first == 0x0D || first == 0x0A) {
                    blockEnd = scan
                    nextStart = scanEnd
                    hasNext = nextStart < end
                    break
                }
                scan = scanEnd
            }
            blocks += parsePart(raw, position, blockEnd, depth + 1, "text/plain", trimPayload && !hasNext)
            if (!hasNext) break
            position = nextStart
        }
        return blocks
    }

    private class BoundaryMatch(val isEnd: Boolean)

    /**
     * `boundarymatch`：行以 `--boundary` 开头，后面只能是可选的 `--`、空格 / 制表符和行尾。
     */
    private fun boundaryMatch(raw: ByteArray, start: Int, end: Int, separator: ByteArray): BoundaryMatch? {
        if (end - start < separator.size) return null
        for (index in separator.indices) {
            if (raw[start + index] != separator[index]) return null
        }
        var index = start + separator.size
        var isEnd = false
        if (index + 1 < end && raw[index] == '-'.code.toByte() && raw[index + 1] == '-'.code.toByte()) {
            isEnd = true
            index += 2
        }
        while (index < end && (raw[index] == ' '.code.toByte() || raw[index] == '\t'.code.toByte())) index++
        if (index < end) {
            when (raw[index].toInt() and 0xFF) {
                0x0D -> index += if (index + 1 < end && (raw[index + 1].toInt() and 0xFF) == 0x0A) 2 else 1
                0x0A -> index++
                else -> return null
            }
        }
        return if (index == end) BoundaryMatch(isEnd) else null
    }

    /** `"--" + boundary` 的字节形式（boundary 一定是纯 ASCII，见 [splitMultipart] 的首行判断）。 */
    private fun separatorBytes(boundary: String): ByteArray {
        val bytes = ByteArray(boundary.length + 2)
        bytes[0] = '-'.code.toByte()
        bytes[1] = '-'.code.toByte()
        for (index in boundary.indices) bytes[index + 2] = boundary[index].code.toByte()
        return bytes
    }

    /** RFC 2046：分隔行前面的那个换行属于分隔行，不算 part 的正文。 */
    private fun trimLineEnding(raw: ByteArray, start: Int, end: Int): Int {
        if (end <= start) return end
        val last = raw[end - 1].toInt() and 0xFF
        if (last == 0x0A) {
            return if (end - 2 >= start && (raw[end - 2].toInt() and 0xFF) == 0x0D) end - 2 else end - 1
        }
        if (last == 0x0D) return end - 1
        return end
    }
}

/**
 * `part.get_payload(decode=True)` 的字节部分（叶子节点）。
 *
 * 与 `email.message.Message.get_payload` 的对应关系：Content-Transfer-Encoding 先 `strip().lower()`；
 * `quoted-printable` 走 `quopri.decodestring`，`base64` 走 `decode_b`（补齐 padding、忽略非法字符），
 * `x-uuencode` / `uuencode` / `uue` / `x-uue` 走 `_decode_uu`，其余（7bit / 8bit / binary / 未知）原样返回。
 */
internal fun payloadBytes(part: MimePart): ByteArray {
    val encoding = (part.headers.get("Content-Transfer-Encoding") ?: "").pyStrip().lowercase()
    return when (encoding) {
        "quoted-printable" -> quotedPrintableDecode(part.payload)
        "base64" -> base64BodyDecode(part.payload)
        // Python：`except ValueError: return bpayload`（binascii.Error 是 ValueError 的子类）
        "x-uuencode", "uuencode", "uue", "x-uue" -> try {
            uuDecode(part.payload)
        } catch (_: UuDecodeError) {
            part.payload
        }
        else -> part.payload
    }
}

/**
 * `quopri.decodestring`（`binascii.a2b_qp(header=False)`）的移植：
 * - `=XX`（大小写十六进制都认）解码成一个字节；
 * - `=\n` 与 `=\r\n` 是软换行，一起删掉；正文结尾的单个 `=` 也丢掉；
 * - 不是合法转义的 `=`：只写出 `=`，后面的字符如果是 `=` 就丢掉，否则原样写出
 *   （`==41` → `=41`，`=ZZ` → `=ZZ`，`====` → `==`）。
 *
 * 偏差：`=` 后面只跟一个裸 `\r` 时，CPython 的实现会一路跳到下一个 `\n`（实测
 * `b"x=\rAB"` 解出 `b"x"`）；这里照抄这个行为，避免与 Python 分叉。
 */
internal fun quotedPrintableDecode(payload: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(payload.size)
    var index = 0
    while (index < payload.size) {
        val byte = payload[index].toInt() and 0xFF
        if (byte != '='.code) {
            out.write(byte)
            index++
            continue
        }
        if (index + 1 >= payload.size) {
            // 结尾的单个 '='：丢掉
            index++
            continue
        }
        val first = payload[index + 1].toInt() and 0xFF
        if (first == 0x0A) {
            index += 2
            continue
        }
        if (first == 0x0D) {
            if (index + 2 < payload.size && (payload[index + 2].toInt() and 0xFF) == 0x0A) {
                index += 3
            } else {
                // 裸 CR：与 CPython 一样跳到下一个 '\n'（没有就丢掉剩余内容）
                var next = index + 2
                while (next < payload.size && (payload[next].toInt() and 0xFF) != 0x0A) next++
                index = if (next < payload.size) next + 1 else payload.size
            }
            continue
        }
        if (index + 2 < payload.size && isHexDigit(first) && isHexDigit(payload[index + 2].toInt() and 0xFF)) {
            out.write(hexValue(first) * 16 + hexValue(payload[index + 2].toInt() and 0xFF))
            index += 3
            continue
        }
        // 非法转义：写出 '='，后面的字符是 '=' 就丢掉，否则原样写出
        out.write('='.code)
        if (first != '='.code) out.write(first)
        index += 2
    }
    return out.toByteArray()
}

private fun isHexDigit(value: Int): Boolean =
    value in '0'.code..'9'.code || value in 'a'.code..'f'.code || value in 'A'.code..'F'.code

private fun hexValue(value: Int): Int = when (value) {
    in '0'.code..'9'.code -> value - '0'.code
    in 'a'.code..'f'.code -> value - 'a'.code + 10
    else -> value - 'A'.code + 10
}

/**
 * `email._encoded_words.decode_b`（base64 正文）的移植，逐条对应 Python 3.13 的实现：
 *
 * 1. 先去掉所有行尾（`b"".join(payload.splitlines())`，`bytes.splitlines` 只认 CR / LF）；
 * 2. 补齐缺少的 padding，用**严格模式**解码（只允许字母表内字符、padding 正确、末尾没有多余数据）；
 * 3. 严格模式失败时按宽松模式解码（忽略字母表外的字符）；
 * 4. 再失败就多补两个 `=` 再宽松解码；
 * 5. 仍然失败（数据字符数比 4 的倍数多 1）时，把**编码后的字节**原样返回。
 */
internal fun base64BodyDecode(payload: ByteArray): ByteArray {
    val encoded = stripLineBreaks(payload)
    if (encoded.isEmpty()) return EMPTY_BYTES
    val remainder = encoded.size % 4
    val padded = if (remainder == 0) encoded else encoded + ByteArray(4 - remainder) { '='.code.toByte() }
    strictBase64DecodeOrNull(padded)?.let { return it }
    lenientOrNull(encoded)?.let { return it }
    lenientOrNull(encoded + byteArrayOf('='.code.toByte(), '='.code.toByte()))?.let { return it }
    return encoded
}

private fun lenientOrNull(bytes: ByteArray): ByteArray? = try {
    a2bBase64LenientBytes(bytes)
} catch (_: Base64DecodeError) {
    null
}

/** `b"".join(payload.splitlines())`：`bytes.splitlines` 只把 CR、LF、CRLF 当行尾（不含 VT / FF / FS）。 */
private fun stripLineBreaks(payload: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(payload.size)
    for (byte in payload) {
        when (byte.toInt() and 0xFF) {
            0x0A, 0x0D -> Unit
            else -> out.write(byte.toInt() and 0xFF)
        }
    }
    return out.toByteArray()
}

/**
 * `base64.b64decode(..., validate=True)` 的严格校验 + 解码；不合法时返回 null。
 *
 * 规则（与 `binascii.a2b_base64(strict_mode=True)` 一致，用金标 `mail_body.json` 里的
 * `QUJDRA=A`、`AB\x0bC` 等用例钉住）：长度是 4 的倍数、`=` 只能出现在最后两个位置且最多两个、
 * 其余字符必须在字母表内。
 */
private fun strictBase64DecodeOrNull(bytes: ByteArray): ByteArray? {
    if (bytes.size % 4 != 0) return null
    var end = bytes.size
    while (end > 0 && (bytes[end - 1].toInt() and 0xFF) == '='.code) end--
    if (bytes.size - end > 2) return null
    for (index in 0 until end) {
        if (base64Value(bytes[index].toInt() and 0xFF) < 0) return null
    }
    return a2bBase64LenientBytes(bytes)
}

/** `binascii.Error` 的移植：uuencode 解码失败（`binascii.Error` 是 `ValueError` 的子类）。 */
internal class UuDecodeError(message: String) : Exception(message)

/**
 * `email.message._decode_uu` 的移植：跳过 `begin <mode> <path>` 行，逐行解码到 `end` 行为止。
 *
 * - 找不到 `begin` 行、或遇到空行（`Truncated input`）时抛 [UuDecodeError]，
 *   调用方按 Python 的 `except ValueError: return bpayload` 原样返回正文；
 * - 单行解码失败（`binascii.Error`）时按 Python 的兜底做法，用「长度字节推出来的字节数」
 *   截断该行再解一次；这次再失败就抛出去，同样原样返回正文。
 */
internal fun uuDecode(encoded: ByteArray): ByteArray {
    val lines = byteSplitLines(encoded)
    var index = 0
    var found = false
    while (index < lines.size) {
        val line = lines[index]
        index++
        if (!startsWithAscii(line, "begin ")) continue
        val rest = line.copyOfRange(6, line.size)
        val space = rest.indexOf(' '.code.toByte())
        val mode = if (space < 0) rest else rest.copyOfRange(0, space)
        if (!isOctal(mode)) continue
        found = true
        break
    }
    if (!found) throw UuDecodeError("`begin` line not found")
    val out = ByteArrayOutputStream()
    while (index < lines.size) {
        val line = lines[index]
        index++
        if (line.isEmpty()) throw UuDecodeError("Truncated input")
        if (line.trimBytes(END_STRIP).contentEquals("end".toByteArray(Charsets.US_ASCII))) break
        val decoded = try {
            a2bUu(line)
        } catch (_: UuDecodeError) {
            // Python 的兜底：按长度字节推算这一行应该有多少字节，截断后再解
            val nbytes = ((((line[0].toInt() and 0xFF) - 0x20) and 0x3F) * 4 + 5) / 3
            a2bUu(line.copyOfRange(0, minOf(nbytes, line.size)))
        }
        out.write(decoded)
    }
    return out.toByteArray()
}

/** `line.strip(b' \t\r\n\f')` 用到的字节集合。 */
private val END_STRIP = byteArrayOf(0x20, 0x09, 0x0D, 0x0A, 0x0C)

/**
 * `binascii.a2b_uu(line)` 的移植。逐条对应 CPython 3.13 的行为（用 8 万条随机行与
 * `binascii.a2b_uu` 逐条比对过）：
 *
 * - 第一个字符给出这一行的字节数：`(line[0] - 0x20) & 0o77`；
 * - 数据字符必须落在 `0x20..0x60`（空格到反引号），`\n` / `\r` 当作值为 0 的字符参与拼接；
 * - 每 4 个字符解出 3 个字节，行尾不足 4 个字符时把剩余位按一个字节写出；
 * - 缓冲区写满以后还有非零的字符 → `Trailing garbage`；末尾不足一个字节的部分直接丢弃。
 */
internal fun a2bUu(line: ByteArray): ByteArray {
    if (line.size > 1000) throw UuDecodeError("Too many bytes")
    if (line.isEmpty()) throw UuDecodeError("No starting line")
    val binLength = ((line[0].toInt() and 0xFF) - 0x20) and 0x3F
    val out = ByteArrayOutputStream(binLength)
    var leftBits = 0
    var leftChar = 0
    for (index in 1 until line.size) {
        var value = line[index].toInt() and 0xFF
        if (value == 0x0A || value == 0x0D) value = 0x20
        if (value < 0x20 || value > 0x60) throw UuDecodeError("Illegal char")
        val sextet = (value - 0x20) and 0x3F
        if (out.size() >= binLength && sextet != 0) throw UuDecodeError("Trailing garbage")
        when (leftBits) {
            0 -> {
                leftChar = sextet shl 2
                leftBits = 6
            }
            6 -> {
                if (out.size() < binLength) out.write(leftChar or (sextet shr 4))
                leftChar = (sextet shl 4) and 0xFF
                leftBits = 4
            }
            4 -> {
                if (out.size() < binLength) out.write(leftChar or (sextet shr 2))
                leftChar = (sextet shl 6) and 0xFF
                leftBits = 2
            }
            else -> {
                if (out.size() < binLength) out.write(leftChar or sextet)
                leftChar = 0
                leftBits = 0
            }
        }
    }
    if (leftBits != 0 && out.size() < binLength) out.write(leftChar)
    val decoded = out.toByteArray()
    return if (decoded.size == binLength) decoded else decoded + ByteArray(binLength - decoded.size)
}

/** `bytes.splitlines()`：只按 `\r\n`、`\r`、`\n` 切分，行尾不含在结果里。 */
private fun byteSplitLines(bytes: ByteArray): List<ByteArray> {
    val lines = ArrayList<ByteArray>()
    var start = 0
    var index = 0
    while (index < bytes.size) {
        val byte = bytes[index].toInt() and 0xFF
        if (byte == 0x0A) {
            lines += bytes.copyOfRange(start, index)
            index++
            start = index
            continue
        }
        if (byte == 0x0D) {
            lines += bytes.copyOfRange(start, index)
            index++
            if (index < bytes.size && (bytes[index].toInt() and 0xFF) == 0x0A) index++
            start = index
            continue
        }
        index++
    }
    if (start < bytes.size) lines += bytes.copyOfRange(start, bytes.size)
    return lines
}

private fun startsWithAscii(bytes: ByteArray, text: String): Boolean {
    if (bytes.size < text.length) return false
    for (index in text.indices) {
        if ((bytes[index].toInt() and 0xFF) != text[index].code) return false
    }
    return true
}

/** `line.strip(bytes)`：只去掉给定的那几个字节。 */
private fun ByteArray.trimBytes(strip: ByteArray): ByteArray {
    var start = 0
    var end = size
    while (start < end && strip.contains(this[start])) start++
    while (end > start && strip.contains(this[end - 1])) end--
    return copyOfRange(start, end)
}

/**
 * `int(mode, base=8)` 能否成功：允许两侧 ASCII 空白、可选正负号、可选的 `0o` / `0O` 前缀、
 * 数字之间可以夹一个下划线，至少要有一个八进制数字。
 */
private fun isOctal(text: ByteArray): Boolean {
    var index = 0
    fun skipSpace() {
        while (index < text.size) {
            val byte = text[index].toInt() and 0xFF
            if (byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0B || byte == 0x0C || byte == 0x0D) {
                index++
            } else {
                return
            }
        }
    }
    skipSpace()
    if (index < text.size && ((text[index].toInt() and 0xFF) == '-'.code || (text[index].toInt() and 0xFF) == '+'.code)) {
        index++
    }
    if (index + 1 < text.size && (text[index].toInt() and 0xFF) == '0'.code &&
        ((text[index + 1].toInt() and 0xFF) == 'o'.code || (text[index + 1].toInt() and 0xFF) == 'O'.code)
    ) {
        index += 2
    }
    var digits = 0
    var lastWasDigit = false
    while (index < text.size) {
        val byte = text[index].toInt() and 0xFF
        if (byte in '0'.code..'7'.code) {
            digits++
            lastWasDigit = true
            index++
            continue
        }
        if (byte == '_'.code && lastWasDigit) {
            lastWasDigit = false
            index++
            continue
        }
        break
    }
    skipSpace()
    return digits > 0 && lastWasDigit && index == text.size
}
