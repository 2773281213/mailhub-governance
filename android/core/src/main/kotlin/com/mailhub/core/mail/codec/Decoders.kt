package com.mailhub.core.mail.codec

import com.mailhub.core.mail.PyTextCodec

/**
 * 各个编解码器的解码器，逐个照 CPython 3.13 的实现移植（`Modules/cjkcodecs` 下的 C 源文件、
 * `Objects/unicodeobject.c` 的 UTF-7 / UTF-16 / UTF-32）。映射表全部来自 [CodecTables]。
 *
 * `errors="replace"` 的错误切分由 CPython 的 `multibytecodec_decerror` 决定，这里逐条对应：
 *
 * - 解码器返回正数 `n`（`return 1` / `return dsg->width`）→ **非法序列**，吃掉 n 个字节、写一个 `\ufffd`；
 * - `REQUIRE_INBUF(k)` 不满足 → **截断**，吃掉**剩余全部**字节、写一个 `\ufffd`。
 *
 * 这两条决定了输入长短不同时替换符的个数（例如 gb18030 的 `FF 31` 只有一个 `\ufffd`，
 * 因为四字节分支先 `REQUIRE_INBUF(4)` 再检查前导字节范围）。
 */

private const val REPLACEMENT = '\uFFFD'

private fun StringBuilder.replacement() {
    append(REPLACEMENT)
}

/** 把若干个码点写进结果（big5hkscs 有解出两个字符的码位）。 */
private fun StringBuilder.appendCodePoints(codePoints: IntArray) {
    for (codePoint in codePoints) appendCodePoint(codePoint)
}

/**
 * 单字节编码（charset 模块的 256 项表）：每个字节独立解码，非法字节各写一个 `\ufffd`。
 */
internal class SingleByteDecoder(
    override val name: String,
    private val table: CodecTable,
) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val codePoint = table.get(byte.toInt() and 0xFF)
            if (codePoint < 0) out.replacement() else out.appendCodePoint(codePoint)
        }
        return out.toString()
    }
}

/**
 * 无状态双字节编码（gb2312、gbk、big5、big5hkscs、cp950、shift_jis、cp932、cp949）：
 *
 * ```c
 * if (c < 0x80) 原样输出;
 * 单字节区命中（Shift_JIS 的 0xA1–0xDF 半角片假名等）→ 输出、吃 1 字节;
 * REQUIRE_INBUF(2);
 * 双字节表命中 → 输出、吃 2 字节;
 * 否则 return 1（非法，吃 1 字节）。
 * ```
 *
 * 码位表用 [CodecTables] 导出的合并结果：同源编码的差异（cp932 相对 shift_jis 等）已经在导出时
 * 折进差分表，这里只查一张表。
 */
internal class DoubleByteDecoder(
    override val name: String,
    private val pairs: CodecTable,
    private val singles: CodecTable,
) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        val n = bytes.size
        var i = 0
        while (i < n) {
            val c = bytes[i].toInt() and 0xFF
            if (c < 0x80) {
                out.append(c.toChar())
                i++
                continue
            }
            val single = singles.get(c)
            if (single >= 0) {
                out.appendCodePoint(single)
                i++
                continue
            }
            if (i + 1 >= n) {
                // REQUIRE_INBUF(2) 不满足：截断，吃掉剩余全部字节
                out.replacement()
                break
            }
            val index = (c shl 8) or (bytes[i + 1].toInt() and 0xFF)
            val literal = pairs.literal(index)
            if (literal != null) {
                out.appendCodePoints(literal)
                i += 2
                continue
            }
            val codePoint = pairs.get(index)
            if (codePoint >= 0) {
                out.appendCodePoint(codePoint)
                i += 2
            } else {
                out.replacement()
                i++
            }
        }
        return out.toString()
    }
}

/**
 * Python 的 `gb18030` 编码器（`_codecs_cn.c` 的 `DECODER(gb18030)`）：
 * 两字节部分与 gbk 一样，另外有二 / 四字节的混合形式。
 *
 * 四字节分支的顺序与 CPython 一致：先看第二个字节是不是 0x30–0x39，再 `REQUIRE_INBUF(4)`，
 * **之后**才检查前导字节范围。所以 `FF 31` 这种输入只有一个 `\ufffd`（截断吃掉两个字节），
 * 而不是「非法前导字节 + ASCII」。
 *
 * [bmpRanges] 是 gb18030 四字节 BMP 区的映射表，索引是线性序号 lseq（0–39419），不是 `(b1 << 8) | b2`；
 * 表外的四字节序列由公式算到辅助平面（`lead >= 15`），其余是非法。
 */
internal class Gb18030Decoder(
    override val name: String,
    private val pairs: CodecTable,
    private val bmpRanges: CodecTable,
) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        val n = bytes.size
        var i = 0
        while (i < n) {
            val c = bytes[i].toInt() and 0xFF
            if (c < 0x80) {
                out.append(c.toChar())
                i++
                continue
            }
            if (i + 1 >= n) {
                out.replacement()
                break
            }
            val c2 = bytes[i + 1].toInt() and 0xFF
            if (c2 in 0x30..0x39) {
                if (n - i < 4) {
                    out.replacement()
                    break
                }
                val c3 = bytes[i + 2].toInt() and 0xFF
                val c4 = bytes[i + 3].toInt() and 0xFF
                if (c !in 0x81..0xFE || c3 !in 0x81..0xFE || c4 !in 0x30..0x39) {
                    out.replacement()
                    i++
                    continue
                }
                val lead = c - 0x81
                val second = c2 - 0x30
                val third = c3 - 0x81
                val fourth = c4 - 0x30
                if (lead < 4) {
                    val linear = (lead * 10 + second) * 1260 + third * 10 + fourth
                    if (linear < 39420) {
                        val codePoint = bmpRanges.get(linear)
                        if (codePoint >= 0) {
                            out.appendCodePoint(codePoint)
                            i += 4
                            continue
                        }
                    }
                } else if (lead >= 15) {
                    val codePoint = 0x10000 + ((lead - 15) * 10 + second) * 1260 + third * 10 + fourth
                    if (codePoint <= 0x10FFFF) {
                        out.appendCodePoint(codePoint)
                        i += 4
                        continue
                    }
                }
                out.replacement()
                i++
                continue
            }
            val codePoint = pairs.get((c shl 8) or c2)
            if (codePoint >= 0) {
                out.appendCodePoint(codePoint)
                i += 2
            } else {
                out.replacement()
                i++
            }
        }
        return out.toString()
    }
}

/**
 * Python 的 `euc_kr`（`_codecs_kr.c` 的 `DECODER(euc_kr)`）。
 *
 * 除了 KS X 1001 的双字节表，它还有 KS X 1001:1998 的**补写序列**：以 `A4 D4`（初声填充符）
 * 开头的 8 个字节组成一个音节（`A4 D4 A4 D4` 是「初声填充 + 中声 + 终声」的占位写法）。
 * 字节不够 8 个是截断（吃掉剩余全部）；够 8 个但第 3 / 5 / 7 个字节不是 `A4`，或者初声 / 中声 /
 * 终声查不到，就是非法（只吃 1 字节）——CPython 就是这么写的，半个音节不会把后面一起吃掉。
 */
internal class EucKrDecoder(
    override val name: String,
    private val pairs: CodecTable,
    private val jamo: CodecTable,
) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        val n = bytes.size
        var i = 0
        while (i < n) {
            val c = bytes[i].toInt() and 0xFF
            if (c < 0x80) {
                out.append(c.toChar())
                i++
                continue
            }
            if (i + 1 >= n) {
                out.replacement()
                break
            }
            if (c == 0xA4 && (bytes[i + 1].toInt() and 0xFF) == 0xD4) {
                if (n - i < 8) {
                    out.replacement()
                    break
                }
                val third = bytes[i + 2].toInt() and 0xFF
                val fifth = bytes[i + 4].toInt() and 0xFF
                val seventh = bytes[i + 6].toInt() and 0xFF
                if (third != 0xA4 || fifth != 0xA4 || seventh != 0xA4) {
                    out.replacement()
                    i++
                    continue
                }
                val choseongByte = bytes[i + 3].toInt() and 0xFF
                val jungseongByte = bytes[i + 5].toInt() and 0xFF
                val jongseongByte = bytes[i + 7].toInt() and 0xFF
                // `euc-kr-jamo` 表是「初声 30 项 + 中声 21 项 + 终声 30 项」的顺序拼接，
                // 所以中声从 30 开始、终声从 51 开始。
                val cho = if (choseongByte in 0xA1..0xBE) jamo.get(choseongByte - 0xA1) else -1
                val jung = if (jungseongByte in 0xBF..0xD3) jamo.get(30 + jungseongByte - 0xBF) else -1
                val jong = when {
                    jongseongByte == 0xD4 -> 0
                    jongseongByte in 0xA1..0xBE -> jamo.get(51 + jongseongByte - 0xA1)
                    else -> -1
                }
                if (cho < 0 || jung < 0 || jong < 0) {
                    out.replacement()
                    i++
                    continue
                }
                out.appendCodePoint(0xAC00 + cho * 588 + jung * 28 + jong)
                i += 8
                continue
            }
            val codePoint = pairs.get((c shl 8) or (bytes[i + 1].toInt() and 0xFF))
            if (codePoint >= 0) {
                out.appendCodePoint(codePoint)
                i += 2
            } else {
                out.replacement()
                i++
            }
        }
        return out.toString()
    }
}

/**
 * Python 的 `euc_jp`（`_codecs_jp.c` 的 `DECODER(euc_jp)`）：
 * `0x8E` 后面跟 0xA1–0xDF 是 JIS X 0201 半角片假名（两个字节），`0x8F` 后面是 JIS X 0212 的三字节区，
 * 其余双字节码位是 JIS X 0208。两个表都按 CPython 的写法把字节 `^ 0x80` 折到 0x21–0x7E 再查。
 */
internal class EucJpDecoder(
    override val name: String,
    private val jisx0208: CodecTable,
    private val jisx0212: CodecTable,
) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        val n = bytes.size
        var i = 0
        while (i < n) {
            val c = bytes[i].toInt() and 0xFF
            if (c < 0x80) {
                out.append(c.toChar())
                i++
                continue
            }
            if (c == 0x8E) {
                if (i + 1 >= n) {
                    out.replacement()
                    break
                }
                val c2 = bytes[i + 1].toInt() and 0xFF
                if (c2 in 0xA1..0xDF) {
                    out.appendCodePoint(0xFEC0 + c2)
                    i += 2
                } else {
                    out.replacement()
                    i++
                }
                continue
            }
            if (c == 0x8F) {
                if (n - i < 3) {
                    out.replacement()
                    break
                }
                val index = (((bytes[i + 1].toInt() and 0xFF) xor 0x80) shl 8) or
                    ((bytes[i + 2].toInt() and 0xFF) xor 0x80)
                val codePoint = jisx0212.get(index)
                if (codePoint >= 0) {
                    out.appendCodePoint(codePoint)
                    i += 3
                } else {
                    out.replacement()
                    i++
                }
                continue
            }
            if (i + 1 >= n) {
                out.replacement()
                break
            }
            val index = ((c xor 0x80) shl 8) or ((bytes[i + 1].toInt() and 0xFF) xor 0x80)
            val codePoint = jisx0208.get(index)
            if (codePoint >= 0) {
                out.appendCodePoint(codePoint)
                i += 2
            } else {
                out.replacement()
                i++
            }
        }
        return out.toString()
    }
}

/**
 * Python 的 `iso2022_jp`（`_codecs_iso2022.c`）。
 *
 * iso2022_jp 的配置是 `NO_SHIFT | USE_JISX0208_EXT`：SO / SI 原样输出（不切换字符集），
 * 认 `ESC ( B`（ASCII）、`ESC ( J`（JIS X 0201 Roman）、`ESC $ B` / `ESC $ @`（JIS X 0208）、
 * `ESC $ ( B`，以及 `ESC & @ ESC $ B` 这种六字节写法。其它转义序列按 CPython 的长度报错：
 * 错误长度是 1 / 3 / 4 / 6 或整个转义序列的长度，不是一个字节。
 *
 * JIS X 0208 的 0x213D 在 CPython 里是 U+2015（Java 的 ISO-2022-JP 给的是 U+2014），
 * 这个差异在导出的表里，不在代码里。
 */
internal class Iso2022JpDecoder(
    override val name: String,
    private val jisx0208: CodecTable,
) : PyTextCodec {

    private class EscResult(
        val length: Int,
        val charset: Int,
        val errorSize: Int,
        val g0: Boolean,
    ) {
        val incomplete: Boolean get() = errorSize == INCOMPLETE

        companion object {
            const val INCOMPLETE = -1
            fun error(size: Int) = EscResult(0, ASCII, size, false)
            val truncated = EscResult(0, ASCII, INCOMPLETE, false)
            fun ok(length: Int, charset: Int) = EscResult(length, charset, 0, true)
            fun designation1(length: Int) = EscResult(length, ASCII, 0, false)
        }
    }

    override fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        val n = bytes.size
        var g0 = ASCII
        var escapeThroughout = false
        var i = 0
        while (i < n) {
            val c = bytes[i].toInt() and 0xFF
            if (escapeThroughout) {
                // 非 ISO-2022 的转义序列：原样当 ISO-8859-1 输出，直到遇到字母或 '@'
                out.append(c.toChar())
                i++
                if (isEscapeEnd(c)) escapeThroughout = false
                continue
            }
            when (c) {
                ESC -> {
                    if (i + 1 >= n) {
                        out.replacement()
                        break
                    }
                    if (isIso2022Escape(bytes[i + 1].toInt() and 0xFF)) {
                        val result = processEscape(bytes, i)
                        if (result.incomplete) {
                            out.replacement()
                            break
                        }
                        if (result.errorSize > 0) {
                            out.replacement()
                            i += result.errorSize
                            continue
                        }
                        if (result.g0) g0 = result.charset
                        i += result.length
                    } else {
                        out.append(ESC.toChar())
                        escapeThroughout = true
                        i++
                    }
                }
                // NO_SHIFT：SO / SI 不切换字符集，按普通字节输出
                SO, SI -> {
                    out.append(c.toChar())
                    i++
                }
                LF -> {
                    out.append('\n')
                    i++
                }
                else -> {
                    when {
                        c < 0x20 -> {
                            out.append(c.toChar())
                            i++
                        }
                        c >= 0x80 -> {
                            out.replacement()
                            i++
                        }
                        g0 == ASCII || g0 == JISX0201_R -> {
                            // JIS X 0201 Roman 的字节本来就都在 0x00–0x7F
                            out.append(c.toChar())
                            i++
                        }
                        else -> {
                            if (n - i < 2) {
                                out.replacement()
                                break
                            }
                            val index = (c shl 8) or (bytes[i + 1].toInt() and 0xFF)
                            val codePoint = jisx0208.get(index)
                            if (codePoint >= 0) {
                                out.appendCodePoint(codePoint)
                                i += 2
                            } else {
                                // return dsg->width：JIS X 0208 的宽度是 2
                                out.replacement()
                                i += 2
                            }
                        }
                    }
                }
            }
        }
        return out.toString()
    }

    /** 对应 `iso2022processesc`。 */
    private fun processEscape(bytes: ByteArray, start: Int): EscResult {
        val n = bytes.size
        var escapeLength = 0
        var probe = 1
        while (probe < MAX_ESCAPE_SEQUENCE) {
            if (start + probe >= n) return EscResult.truncated
            if (isEscapeEnd(bytes[start + probe].toInt() and 0xFF)) {
                escapeLength = probe + 1
                break
            }
            if (probe + 1 < n - start &&
                bytes[start + probe] == '&'.code.toByte() &&
                bytes[start + probe + 1] == '@'.code.toByte()
            ) {
                probe += 2
            }
            probe++
        }
        var charset = ASCII
        var designation = 0
        when (escapeLength) {
            0 -> return EscResult.error(1)
            3 -> {
                if (bytes[start + 1] == '$'.code.toByte()) {
                    charset = (bytes[start + 2].toInt() and 0xFF) or DBCS
                } else {
                    charset = bytes[start + 2].toInt() and 0xFF
                    when (bytes[start + 1].toInt()) {
                        '('.code -> designation = 0
                        ')'.code -> designation = 1
                        else -> return EscResult.error(3)
                    }
                }
            }
            4 -> {
                if (bytes[start + 1] != '$'.code.toByte()) return EscResult.error(4)
                charset = (bytes[start + 3].toInt() and 0xFF) or DBCS
                designation = when (bytes[start + 2].toInt()) {
                    '('.code -> 0
                    ')'.code -> 1
                    else -> return EscResult.error(4)
                }
            }
            6 -> {
                // USE_JISX0208_EXT：ESC & @ ESC $ B
                if (bytes[start + 3] == ESC.toByte() && bytes[start + 4] == '$'.code.toByte() &&
                    bytes[start + 5] == 'B'.code.toByte()
                ) {
                    charset = 'B'.code or DBCS
                } else {
                    return EscResult.error(6)
                }
            }
            else -> return EscResult.error(escapeLength)
        }
        // 只认 iso2022_jp 声明过的字符集；designation 1 只可能来自 ESC ) X，
        // 而 NO_SHIFT 让 G1 没有作用，所以只要字符集在名单里就接受（G0 不变）。
        if (charset != ASCII && charset !in DESIGNATED_CHARSETS) {
            return EscResult.error(escapeLength)
        }
        if (designation != 0) return EscResult.designation1(escapeLength)
        return EscResult.ok(escapeLength, charset)
    }

    private companion object {
        const val ESC = 0x1B
        const val SO = 0x0E
        const val SI = 0x0F
        const val LF = 0x0A
        const val MAX_ESCAPE_SEQUENCE = 16
        const val DBCS = 0x80
        const val ASCII = 'B'.code
        const val JISX0201_R = 'J'.code
        const val JISX0208 = 'B'.code or DBCS
        const val JISX0208_O = '@'.code or DBCS

        /** iso2022_jp 允许的字符集（`iso2022_jp_designations`）。 */
        val DESIGNATED_CHARSETS = setOf(JISX0208, JISX0201_R, JISX0208_O)

        fun isEscapeEnd(c: Int): Boolean = c in 'A'.code..'Z'.code || c == '@'.code

        fun isIso2022Escape(c: Int): Boolean =
            c == '('.code || c == ')'.code || c == '$'.code || c == '.'.code || c == '&'.code
    }
}

/**
 * Python 的 `hz`（`_codecs_cn.c` 的 `DECODER(hz)`）：`~{` 进 GB 模式、`~}` 回 ASCII 模式、
 * `~~` 是 `~`、`~\n` 是续行。
 *
 * GB 模式查的是 gb2312 的表，但 CPython 传的是**原始字节**（`TRYMAP_DEC(gb2312, decoded, c, INBYTE2)`），
 * 而这张表按 `^ 0x80` 索引，所以真实的 GB2312 文本在 CPython 里解不出来（这是 CPython 的既有行为，
 * 金标照它生成）。这里同样把字节 `^ 0x80` 再查表，结果与 CPython 一致。
 */
internal class HzDecoder(
    override val name: String,
    private val gb2312: CodecTable,
) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        val n = bytes.size
        var gbMode = false
        var i = 0
        while (i < n) {
            val c = bytes[i].toInt() and 0xFF
            if (c == '~'.code) {
                if (i + 1 >= n) {
                    out.replacement()
                    break
                }
                val c2 = bytes[i + 1].toInt() and 0xFF
                when {
                    c2 == '~'.code && !gbMode -> out.append('~')
                    c2 == '{'.code && !gbMode -> gbMode = true
                    c2 == '\n'.code && !gbMode -> Unit // 续行
                    c2 == '}'.code && gbMode -> gbMode = false
                    else -> {
                        out.replacement()
                        i++
                        continue
                    }
                }
                i += 2
                continue
            }
            if ((c and 0x80) != 0) {
                out.replacement()
                i++
                continue
            }
            if (!gbMode) {
                out.append(c.toChar())
                i++
                continue
            }
            if (i + 1 >= n) {
                out.replacement()
                break
            }
            val index = ((c xor 0x80) shl 8) or ((bytes[i + 1].toInt() and 0xFF) xor 0x80)
            val codePoint = gb2312.get(index)
            if (codePoint >= 0) {
                out.appendCodePoint(codePoint)
                i += 2
            } else {
                out.replacement()
                i++
            }
        }
        return out.toString()
    }
}

/**
 * Python 的 `utf-8` 在 `errors="replace"` 下的错误切分：按 Unicode 的「最长合法子序列」规则吐
 * `\ufffd`，再从出错的字节接着解。JDK 的 `CharsetDecoder` 把一个非法序列整体算一个错误
 * （例如 `ED A0 80`：Python 是三个 `\ufffd`，Java 是一个），所以这里自己走一遍。
 *
 * 次字节的额外上下界：`E0` 不能是 80–9F（过长编码）、`ED` 不能是 A0–BF（代理区）、
 * `F0` 不能是 80–8F、`F4` 不能超过 8F（超出 U+10FFFF，所以 `F4 90 80 80` 是四个 `\ufffd`）。
 */
internal class Utf8Decoder(override val name: String) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        val n = bytes.size
        var i = 0
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                out.append(b.toChar())
                i++
                continue
            }
            val length = when {
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> 0
            }
            if (length == 0) {
                out.replacement()
                i++
                continue
            }
            val secondLow = when (b) {
                0xE0 -> 0xA0
                0xF0 -> 0x90
                else -> 0x80
            }
            val secondHigh = when (b) {
                0xED -> 0x9F
                0xF4 -> 0x8F
                else -> 0xBF
            }
            var j = i + 1
            var complete = true
            while (j < i + length) {
                if (j >= n) {
                    complete = false
                    break
                }
                val c = bytes[j].toInt() and 0xFF
                val low = if (j == i + 1) secondLow else 0x80
                val high = if (j == i + 1) secondHigh else 0xBF
                if (c < low || c > high) {
                    complete = false
                    break
                }
                j++
            }
            if (complete) {
                out.appendCodePoint(codePointAt(bytes, i, length))
                i = j
            } else {
                // 只吃掉「已确认合法的那几个字节」，接着从出错的字节开始重新判断
                out.replacement()
                i = j
            }
        }
        return out.toString()
    }

    private fun codePointAt(bytes: ByteArray, start: Int, length: Int): Int = when (length) {
        2 -> ((bytes[start].toInt() and 0x1F) shl 6) or (bytes[start + 1].toInt() and 0x3F)
        3 -> ((bytes[start].toInt() and 0x0F) shl 12) or
            ((bytes[start + 1].toInt() and 0x3F) shl 6) or
            (bytes[start + 2].toInt() and 0x3F)
        else -> ((bytes[start].toInt() and 0x07) shl 18) or
            ((bytes[start + 1].toInt() and 0x3F) shl 12) or
            ((bytes[start + 2].toInt() and 0x3F) shl 6) or
            (bytes[start + 3].toInt() and 0x3F)
    }
}

/**
 * Python 的 `utf-7`（`Objects/unicodeobject.c` 的 `PyUnicode_DecodeUTF7Stateful`）。
 *
 * `+…-` 是 base64 的 UTF-16 段：凑够 16 位就写一个码元，代理对要拼起来；`+-` 是字面的 `+`；
 * 段在结尾没有闭合，或者剩下的位凑不出字符、或者填充位不是 0，都是错误。
 *
 * 与 CPython 的一处已知怪癖保持一致：段内的孤立代理在「后面没有低代理」时会被**原样写进结果**，
 * 所以输出里可能出现孤立的代理码点（Java 的 String 同样允许）。
 */
internal class Utf7Decoder(override val name: String) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val n = bytes.size
        val out = StringBuilder(n)
        var i = 0
        var inShift = false
        var base64Bits = 0
        var base64Buffer = 0L
        var surrogate = 0
        while (i < n) {
            val c = bytes[i].toInt() and 0xFF
            if (inShift) {
                if (isBase64(c)) {
                    base64Buffer = (base64Buffer shl 6) or fromBase64(c).toLong()
                    base64Bits += 6
                    i++
                    if (base64Bits >= 16) {
                        val outChar = ((base64Buffer shr (base64Bits - 16)) and 0xFFFF).toInt()
                        base64Bits -= 16
                        base64Buffer = base64Buffer and ((1L shl base64Bits) - 1)
                        var paired = false
                        if (surrogate != 0) {
                            if (outChar in 0xDC00..0xDFFF) {
                                out.appendCodePoint(
                                    0x10000 + ((surrogate - 0xD800) shl 10) + (outChar - 0xDC00),
                                )
                                surrogate = 0
                                paired = true
                            } else {
                                out.appendCodePoint(surrogate)
                                surrogate = 0
                            }
                        }
                        if (!paired) {
                            if (outChar in 0xD800..0xDBFF) {
                                surrogate = outChar
                            } else {
                                out.appendCodePoint(outChar)
                            }
                        }
                    }
                    continue
                }
                // 离开 base64 段
                inShift = false
                if (base64Bits > 0) {
                    if (base64Bits >= 6) {
                        i++
                        out.replacement()
                        continue
                    }
                    if (base64Buffer != 0L) {
                        i++
                        out.replacement()
                        continue
                    }
                }
                if (surrogate != 0 && c <= 127 && c != '+'.code) {
                    out.appendCodePoint(surrogate)
                }
                surrogate = 0
                if (c == '-'.code) i++
                continue
            }
            when {
                c == '+'.code -> {
                    i++
                    if (i < n && bytes[i] == '-'.code.toByte()) {
                        i++
                        out.append('+')
                    } else if (i < n && !isBase64(bytes[i].toInt() and 0xFF)) {
                        i++
                        out.replacement()
                    } else {
                        inShift = true
                        surrogate = 0
                        base64Bits = 0
                        base64Buffer = 0
                    }
                }
                c <= 127 -> {
                    out.append(c.toChar())
                    i++
                }
                else -> {
                    i++
                    out.replacement()
                }
            }
        }
        if (inShift) {
            // 段没有闭合：还有半个字符、或者填充位不为 0。CPython 的错误区间到结尾，
            // 替换模式下只写一个 \ufffd，已经输出的字符保留。
            if (surrogate != 0 || base64Bits >= 6 || (base64Bits > 0 && base64Buffer != 0L)) {
                out.replacement()
            }
        }
        return out.toString()
    }

    private fun isBase64(c: Int): Boolean =
        c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '+'.code || c == '/'.code

    private fun fromBase64(c: Int): Int = when {
        c in 'A'.code..'Z'.code -> c - 'A'.code
        c in 'a'.code..'z'.code -> c - 'a'.code + 26
        c in '0'.code..'9'.code -> c - '0'.code + 52
        c == '+'.code -> 62
        else -> 63
    }
}

/**
 * Python 的 `utf-16` / `utf-16-le` / `utf-16-be`。
 *
 * - `utf-16` 认开头的 BOM（`FE FF` 大端、`FF FE` 小端）；没有 BOM 时用**本机字节序**，
 *   也就是小端（CPython 在 x86 / ARM 上都是这样，见 KDoc 里的偏差说明）。
 * - 孤立的代理码元、超出范围的值各吃掉 2 个字节；末尾不足 2 个字节是截断，吃掉剩余全部。
 */
internal class Utf16Decoder(
    override val name: String,
    private val littleEndian: Boolean?,
) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val n = bytes.size
        val out = StringBuilder(n / 2 + 1)
        var start = 0
        var little = littleEndian ?: true
        if (littleEndian == null && n >= 2) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if (first == 0xFE && second == 0xFF) {
                little = false
                start = 2
            } else if (first == 0xFF && second == 0xFE) {
                little = true
                start = 2
            }
        }
        var i = start
        while (i < n) {
            if (n - i < 2) {
                out.replacement()
                break
            }
            val unit = unitAt(bytes, i, little)
            if (unit in 0xD800..0xDBFF) {
                if (n - i < 4) {
                    out.replacement()
                    break
                }
                val low = unitAt(bytes, i + 2, little)
                if (low in 0xDC00..0xDFFF) {
                    out.appendCodePoint(0x10000 + ((unit - 0xD800) shl 10) + (low - 0xDC00))
                    i += 4
                } else {
                    out.replacement()
                    i += 2
                }
                continue
            }
            if (unit in 0xDC00..0xDFFF) {
                out.replacement()
                i += 2
                continue
            }
            out.append(unit.toChar())
            i += 2
        }
        return out.toString()
    }

    private fun unitAt(bytes: ByteArray, index: Int, little: Boolean): Int {
        val first = bytes[index].toInt() and 0xFF
        val second = bytes[index + 1].toInt() and 0xFF
        return if (little) (second shl 8) or first else (first shl 8) or second
    }
}

/**
 * Python 的 `utf-32` / `utf-32-le` / `utf-32-be`。
 *
 * `utf-32` 认开头的 BOM（`FF FE 00 00` 小端、`00 00 FE FF` 大端），没有 BOM 时按本机字节序（小端）。
 * 代理区、超出 U+10FFFF 的值各吃掉 4 个字节；末尾不足 4 个字节是截断，吃掉剩余全部。
 */
internal class Utf32Decoder(
    override val name: String,
    private val littleEndian: Boolean?,
) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        val n = bytes.size
        val out = StringBuilder(n / 4 + 1)
        var start = 0
        var little = littleEndian ?: true
        if (littleEndian == null && n >= 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00) {
                little = true
                start = 4
            } else if (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF) {
                little = false
                start = 4
            }
        }
        var i = start
        while (i < n) {
            if (n - i < 4) {
                out.replacement()
                break
            }
            val codePoint = if (little) {
                ((bytes[i + 3].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                    (bytes[i].toInt() and 0xFF)
            } else {
                ((bytes[i].toInt() and 0xFF) shl 24) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i + 3].toInt() and 0xFF)
            }
            // Int 里最高位为 1 的值是负数（也就是 > U+10FFFF 的值），要一起判掉，
            // 否则 appendCodePoint 会抛「Not a valid Unicode code point」。
            if (codePoint < 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) {
                out.replacement()
            } else {
                out.appendCodePoint(codePoint)
            }
            i += 4
        }
        return out.toString()
    }
}
