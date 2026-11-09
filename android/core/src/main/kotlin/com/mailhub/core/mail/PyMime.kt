package com.mailhub.core.mail

import com.mailhub.core.text.pyStrip

/**
 * MIME 结构解析：对应 compat32 策略下 `email.message_from_bytes(raw)` 得到的 `Message` 树，
 * 只保留 `mail_client.parse_message` 用到的那部分行为（`walk()`、`get_content_type()`、
 * `get_payload(decode=True)`、`get_filename()`、`is_multipart()`）。
 *
 * 为什么不用 Angus Mail 的 `MimeMessage`：Python 是规格。`email` 的 compat32 策略对不合规邮件
 * 的处理（缺 boundary、boundary 没出现、连续分隔行、`Content-Disposition` 原文判断大小写）
 * 与 Angus 不同，而分类规则吃的就是这里产出的正文，必须与 Python 逐字符一致。
 * 头部仍然复用 L5a 的 [MimeHeaders]（8 位字节、裸 CR 断行等语义已经对齐）。
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
 * - `multipart` 主类型且有 boundary 时按分隔行切开（前导文字、连续分隔行、结尾文字都按 RFC 2046 处理），
 *   每个子 part 的正文末尾再按「换行属于分隔行」去掉一个行尾；
 * - `message` 主类型递归解析成一个子节点；`message/delivery-status` 在 Python 里按空行切成
 *   多个只有头部的子消息，这里按「没有子节点」处理（都不产生正文，见任务总结的偏差）；
 * - 声称 multipart 但没有 boundary、或 boundary 在正文里没出现时，Python 把它当成叶子，
 *   这里同样返回叶子（它的类型不是 text/plain，所以不产生正文）。
 */
internal class MimeStructureParser(
    private val maxDepth: Int = MAX_MIME_DEPTH,
    private val maxParts: Int = MAX_MIME_PARTS,
) {

    private var parsedParts = 0

    fun parse(raw: ByteArray): MimePart = parsePart(raw, 0, raw.size, 0, "text/plain")

    private fun parsePart(
        raw: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        defaultType: String,
    ): MimePart {
        if (depth > maxDepth) throw MimeLimitException("MIME 嵌套超过 $maxDepth 层")
        parsedParts++
        if (parsedParts > maxParts) throw MimeLimitException("MIME part 数量超过 $maxParts")

        val block = MimeHeaders.parsePart(raw, start, end)
        val headers = block.headers
        val contentType = MimeParams.contentType(headers, defaultType)
        val mainType = contentType.substringBefore('/')

        if (mainType == "multipart") {
            val boundary = MimeParams.boundary(headers)
            if (boundary != null) {
                val children = splitMultipart(raw, block.bodyStart, end, boundary, contentType, depth)
                if (children != null) return MimePart(headers, defaultType, children, EMPTY_BYTES)
            }
            return leaf(headers, defaultType, raw, block.bodyStart, end)
        }
        if (mainType == "message") {
            if (contentType == "message/delivery-status") {
                return MimePart(headers, defaultType, emptyList(), EMPTY_BYTES)
            }
            val child = parsePart(raw, block.bodyStart, end, depth + 1, "text/plain")
            return MimePart(headers, defaultType, listOf(child), EMPTY_BYTES)
        }
        return leaf(headers, defaultType, raw, block.bodyStart, end)
    }

    private fun leaf(headers: MimeHeaders, defaultType: String, raw: ByteArray, start: Int, end: Int): MimePart =
        MimePart(headers, defaultType, null, raw.copyOfRange(start, end))

    /**
     * `FeedParser._parsegen` 里 multipart 那一段。返回 null 表示「没有起始分隔行」，
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
        val separator = separatorBytes(boundary)
        val childDefault = if (parentType == "multipart/digest") "message/rfc822" else "text/plain"
        val children = ArrayList<MimePart>()
        var position = bodyStart
        var capturePreamble = true
        var partStart = -1
        while (position < end) {
            val lineEnd = MimeHeaders.lineEnd(raw, position, end)
            val match = boundaryMatch(raw, position, lineEnd, separator)
            if (match != null) {
                if (match.isEnd) {
                    // 只看到结束分隔行：Python 把之前的内容当正文（叶子），不是 multipart
                    if (capturePreamble) return null
                    if (partStart >= 0) {
                        children += parsePart(raw, partStart, trimLineEnding(raw, partStart, position), depth + 1, childDefault)
                        partStart = -1
                    }
                    // 结束分隔行之后是 epilogue，忽略
                    return children
                }
                if (!capturePreamble && partStart >= 0) {
                    children += parsePart(raw, partStart, trimLineEnding(raw, partStart, position), depth + 1, childDefault)
                    partStart = -1
                }
                capturePreamble = false
                position = lineEnd
                continue
            }
            if (!capturePreamble && partStart < 0) partStart = position
            position = lineEnd
        }
        if (capturePreamble) return null
        if (partStart >= 0) {
            children += parsePart(raw, partStart, trimLineEnding(raw, partStart, end), depth + 1, childDefault)
        }
        return children
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

    /** `"--" + boundary` 的字节形式。boundary 里的非 Latin-1 字符（8 位字节被换成 U+FFFD）无法匹配，见 KDoc。 */
    private fun separatorBytes(boundary: String): ByteArray {
        val bytes = ByteArray(boundary.length + 2)
        bytes[0] = '-'.code.toByte()
        bytes[1] = '-'.code.toByte()
        for (index in boundary.indices) {
            val code = boundary[index].code
            bytes[index + 2] = if (code < 0x100) code.toByte() else '?'.code.toByte()
        }
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
 * 其余（7bit / 8bit / binary / 未知）原样返回。
 *
 * 已知偏差：`x-uuencode` / `uuencode` / `uue` / `x-uue` 在 Python 里会做 uudecode，
 * 这里按「原样返回」处理（这类邮件国内几乎见不到，分类只关心纯文本 / HTML 正文）。
 */
internal fun payloadBytes(part: MimePart): ByteArray {
    val encoding = (part.headers.get("Content-Transfer-Encoding") ?: "").pyStrip().lowercase()
    return when (encoding) {
        "quoted-printable" -> quotedPrintableDecode(part.payload)
        "base64" -> base64BodyDecode(part.payload)
        else -> part.payload
    }
}

/**
 * `quopri.decodestring`（`binascii.a2b_qp(header=False)`）的移植：
 * - `=XX`（大小写十六进制都认）解码成一个字节；
 * - `=\n` 与 `=\r\n` 是软换行，一起删掉；正文结尾的单个 `=` 也丢掉；
 * - 不是合法转义的 `=` 连同它后面的一个字符原样保留（例如 `=ZZ`）。
 *
 * 偏差：`=` 后面只跟一个裸 `\r` 时，CPython 的实现会一路跳到下一个 `\n`（实测
 * `b"x=\rAB"` 解出 `b"x"`）；这里照抄这个行为，避免与 Python 分叉。
 */
internal fun quotedPrintableDecode(payload: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(payload.size)
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
        // 非法转义：'=' 和后面的一个字符都原样保留
        out.write('='.code)
        out.write(first)
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
 * `email.message.decode_b`（base64 正文）的移植：
 *
 * 1. 先去掉所有行尾（`b"".join(payload.splitlines())`）；
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
    if (isStrictBase64(padded)) return a2bBase64LenientBytes(padded)
    lenientOrNull(encoded)?.let { return it }
    lenientOrNull(encoded + byteArrayOf('='.code.toByte(), '='.code.toByte()))?.let { return it }
    return encoded
}

private fun lenientOrNull(bytes: ByteArray): ByteArray? = try {
    a2bBase64LenientBytes(bytes)
} catch (_: Base64DecodeError) {
    null
}

/** `payload.splitlines()` 再用空串连接：Python 认的行尾都去掉。 */
private fun stripLineBreaks(payload: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(payload.size)
    for (byte in payload) {
        when (byte.toInt() and 0xFF) {
            0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E -> Unit
            else -> out.write(byte.toInt() and 0xFF)
        }
    }
    return out.toByteArray()
}

/** `base64.b64decode(..., validate=True)` 的校验部分：只有字母表和正确的结尾 padding。 */
private fun isStrictBase64(bytes: ByteArray): Boolean {
    if (bytes.size % 4 != 0) return false
    for (index in bytes.indices) {
        val byte = bytes[index].toInt() and 0xFF
        if (byte == '='.code) {
            // padding 只能出现在最后两个位置，且最多两个
            if (index < bytes.size - 2) return false
        } else if (base64Value(byte) < 0) {
            return false
        }
    }
    return true
}
