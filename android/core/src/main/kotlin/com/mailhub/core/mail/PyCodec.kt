package com.mailhub.core.mail

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

/**
 * Python `bytes.decode(name, errors="replace")` 的移植。
 *
 * `mail_client._decode_header` 用 `data.decode(charset or "utf-8", errors="replace")` 解编码字，
 * 所以这里要对同样的字节得到同样的字符串。JDK 自带的 charset 与 CPython 有三类差异，都在本文件兜住：
 *
 * 1. **名字**：Python 先把编码名按 `encodings.normalize_encoding` 归一化（小写、非字母数字折成 `_`），
 *    再查别名表，查不到抛 `LookupError`。Java 认的名字集合不同（典型：Java 把 `GB2312` 当成 `GBK` 的别名）。
 * 2. **映射表**：`gbk` / `gb18030` / `big5` / `shift_jis` / `euc-kr` 有少量码位与 Python 不同
 *    （Java 用了 MS 的 PUA 扩展、个别符号的 Unicode 码点不同），GB2312 更是整体比 GBK 严格。
 * 3. **错误切分**：`errors="replace"` 时每种编解码器吃掉多少字节、吐几个 `\ufffd` 各不相同，
 *    这里按 CPython 3.13 实测的结果逐个对齐。
 */
internal interface PyTextCodec {
    /** 对应 `bytes.decode(name, errors="replace")`：永不抛异常。 */
    fun decode(bytes: ByteArray): String
}

/** Python 编码名字到编解码器的映射，对应 `codecs.lookup(name)`。 */
internal object PyCodecs {

    /** 查表；`null` 表示 Python 会抛 `LookupError`（未知编码）。 */
    fun lookup(name: String): PyTextCodec? {
        val key = normalizeEncoding(name)
        ALIASES[key]?.let { return cache.computeIfAbsent(it) { canonical -> build(canonical) } }
        if (key.isEmpty()) return null
        // 别名表里没有的名字再交给 Java 试一次：Python 认得、这里没列出的编码（koi8-r、cp1251…）
        // 仍然解得出来，只是映射表和错误切分用 JDK 的。
        val charset = try {
            Charset.forName(name.trim())
        } catch (_: Exception) {
            return null
        }
        return cache.computeIfAbsent("java:" + charset.name()) { JavaCodec(charset) }
    }

    /** `codecs.lookup(name)` 成功之后的 `data.decode(name, errors="replace")`；名字不认识时返回 null。 */
    fun decode(bytes: ByteArray, name: String): String? = lookup(name)?.decode(bytes)

    private val cache = ConcurrentHashMap<String, PyTextCodec>()

    private fun build(canonical: String): PyTextCodec = when (canonical) {
        "ascii" -> AsciiCodec
        "latin-1" -> Latin1Codec
        "cp1252" -> Cp1252Codec
        "utf-8" -> Utf8Codec
        "gb2312" -> Gb2312Codec()
        "gbk" -> DoubleByteCodec(
            javaName = "GBK",
            leads = ints(0x81..0xFE),
            trails = ints(0x40..0x7E, 0x80..0xFE),
            rejectPua = true,
            // a2e3 是欧元符号、a892 是 JDK 的另一个码点，Python 的 gbk 表里这两处不是 Java 给的字符。
            overrides = "a2e3=-,a892=2295",
        )
        "gb18030" -> Gb18030Codec("GB18030", GB18030_OVERRIDES)
        "big5" -> DoubleByteCodec(
            javaName = "Big5",
            leads = ints(0xA1..0xFE),
            trails = ints(0x40..0x7E, 0xA1..0xFE),
            overrides = "a15a=2574,a1c3=ffe3,a1c5=02cd,a1fe=ff0f,a240=ff3c",
        )
        "shift_jis" -> DoubleByteCodec(
            javaName = "Shift_JIS",
            leads = ints(0x81..0x9F, 0xE0..0xFC),
            trails = ints(0x40..0x7E, 0x80..0xFC),
            katakana = 0xA1..0xDF,
            overrides = "815c=2015",
        )
        "euc-kr" -> DoubleByteCodec(
            javaName = "EUC-KR",
            leads = ints(0xA1..0xFE),
            trails = ints(0xA1..0xFE),
            overrides = "a2e8=-,a4d4=-",
        )
        // iso-2022-jp 是有状态的转义序列编码，直接用 JDK 的实现：合法输入与 Python 一致，
        // 只有 JIS X 0208 的 0x213D 一处两个实现给了不同的 Unicode 码点（Java 是 U+2014，Python 是 U+2015）。
        "iso-2022-jp" -> JavaCodec(Charset.forName("ISO-2022-JP"))
        else -> error("未知的规范名 $canonical")
    }

    /**
     * Python `encodings.normalize_encoding` 的移植：ASCII 字母数字和 `.` 保留，其余字符折成单个 `_`，
     * 非 ASCII 字母数字丢弃。所以 `UTF-8`、`utf 8`、`utf.8` 归一化后都是 `utf_8`。
     */
    fun normalizeEncoding(name: String): String {
        val out = StringBuilder(name.length)
        var pendingSeparator = false
        for (ch in name) {
            val keep = (ch.isLetterOrDigit() && ch.code < 0x80) || ch == '.'
            if (keep) {
                if (pendingSeparator && out.isNotEmpty()) out.append('_')
                out.append(ch.lowercaseChar())
                pendingSeparator = false
            } else {
                pendingSeparator = true
            }
        }
        return out.toString()
    }

    /** Python `codecs` 别名表里与邮件头有关的条目；键是 [normalizeEncoding] 之后的写法。 */
    private val ALIASES: Map<String, String> = buildMap {
        fun alias(canonical: String, vararg names: String) {
            for (name in names) put(normalizeEncoding(name), canonical)
        }
        alias(
            "ascii", "ascii", "us_ascii", "us-ascii", "646", "ansi_x3.4_1968", "ansi_x3_4_1968",
            "ansi_x3.4_1986", "cp367", "csascii", "ibm367", "iso646_us", "iso_646.irv_1991",
            "iso_ir_6", "us",
        )
        alias(
            "latin-1", "latin_1", "latin-1", "latin1", "iso_8859_1", "iso8859_1", "iso-8859-1",
            "8859", "cp819", "csisolatin1", "ibm819", "iso_8859_1_1987", "iso_ir_100", "l1",
        )
        alias("cp1252", "cp1252", "windows_1252", "1252")
        alias("utf-8", "utf_8", "utf-8", "utf8", "u8", "utf", "cp65001")
        alias(
            "gb2312", "gb2312", "euc_cn", "euc-cn", "euccn", "gb2312_80", "gb_2312", "gb_2312_80",
            "chinese", "csiso58gb231280", "iso_ir_58",
        )
        alias("gbk", "gbk", "936", "ms936", "cp936")
        alias("gb18030", "gb18030", "gb_18030")
        alias("big5", "big5", "big5_tw", "csbig5", "cn_big5")
        alias(
            "shift_jis", "shift_jis", "shift-jis", "shiftjis", "sjis", "csshiftjis", "ms_kanji",
            "x_sjis",
        )
        alias(
            "euc-kr", "euc_kr", "euc-kr", "euckr", "ksc5601", "ks_c_5601", "ks_c_5601_1987",
            "korean", "csksc56011987", "iso_ir_149", "cp949", "uhc", "windows_949",
        )
        alias("iso-2022-jp", "iso_2022_jp", "iso-2022-jp", "csiso2022jp", "iso2022jp")
    }

    /** 把若干区间摊平成数组，用于前导 / 尾字节的集合。 */
    private fun ints(vararg ranges: IntRange): IntArray =
        ranges.flatMap { it.toList() }.toIntArray()

    /** GB18030 两字节码位与 Python 的差异（其余码位、以及四字节部分，JDK 与 CPython 一致）。 */
    private const val GB18030_OVERRIDES =
        "a6d9=e78d,a6da=e78e,a6db=e78f,a6dc=e790,a6dd=e791,a6de=e792,a6df=e793,a6ec=e794," +
            "a6ed=e795,a6f3=e796,a8bc=e7c7,fe59=e81e,fe61=e826,fe66=e82b,fe67=e82c,fe6d=e832," +
            "fe7e=e843,fe90=e854,fea0=e864"
}

/** 解析 `码位=字符`（`-` 表示该码位在 Python 里非法）形式的差异表。 */
private fun parseCellOverrides(text: String): Map<Int, Int> {
    if (text.isEmpty()) return emptyMap()
    return text.split(',').associate { item ->
        val (cellText, charText) = item.split('=')
        cellText.trim().toInt(16) to if (charText.trim() == "-") -1 else charText.trim().toInt(16)
    }
}

/** 单字节编解码器的公共部件。 */
private abstract class SingleByteCodec : PyTextCodec {
    /** 第 b 个字节对应的码点，-1 表示该字节非法（`errors="replace"` 下换成 `\ufffd`）。 */
    protected abstract fun codeOf(b: Int): Int

    override fun decode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = codeOf(byte.toInt() and 0xFF)
            out.appendCodePoint(if (code < 0) 0xFFFD else code)
        }
        return out.toString()
    }
}

/** Python 的 `ascii` 编解码器：0x80 以上一律是错误。 */
private object AsciiCodec : SingleByteCodec() {
    override fun codeOf(b: Int): Int = if (b < 0x80) b else -1
}

/** Python 的 `iso-8859-1`：每个字节都解出同码点字符。 */
private object Latin1Codec : SingleByteCodec() {
    override fun codeOf(b: Int): Int = b
}

/**
 * Python 的 `cp1252`：0x81、0x8D、0x8F、0x90、0x9D 五个字节在标准里没有定义（解出 `\ufffd`），
 * 其余 251 个字节与 JDK 的 windows-1252 完全一致（256 个字节逐个比对过）。
 */
private object Cp1252Codec : SingleByteCodec() {

    private val undefined = setOf(0x81, 0x8D, 0x8F, 0x90, 0x9D)

    private val table: IntArray by lazy {
        val charset = Charset.forName("windows-1252")
        IntArray(256) { b ->
            if (b in undefined) -1 else strictDecode(charset, byteArrayOf(b.toByte()))?.codePointAt(0) ?: -1
        }
    }

    override fun codeOf(b: Int): Int = table[b]
}

/**
 * Python 的 `utf-8` 编解码器在 `errors="replace"` 下的错误切分：按 Unicode 的「最长合法子序列」
 * 规则吐 `\ufffd`，再从出错的那个字节接着解。JDK 的 `CharsetDecoder` 把一个非法序列整体算一个错误
 * （例如 `ED A0 80`：Python 是三个 `\ufffd`，Java 是一个），所以这里自己走一遍。
 */
private object Utf8Codec : PyTextCodec {

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
                out.appendCodePoint(0xFFFD)
                i++
                continue
            }
            // 次字节还有额外的上下界：E0 不能是 80–9F（过长编码）、ED 不能是 A0–BF（代理区）、
            // F0 不能是 80–8F、F4 不能是 90–BF（超出 U+10FFFF）。
            val secondLow = when (b) {
                0xE0 -> 0xA0
                0xF0 -> 0x90
                0xF4 -> 0x80
                else -> 0x80
            }
            val secondHigh = if (b == 0xED) 0x9F else 0xBF
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
                // 出错时只吃掉「已确认合法的那几个字节」，接着从出错的字节开始重新判断。
                out.appendCodePoint(0xFFFD)
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
 * Python 的 `gb2312` 编解码器：**比 GBK 严格**，只认 GB2312-80 的 7445 个字符，
 * GBK 独有的字解不出来（`errors="replace"` 下是 `\ufffd`）。JDK 把 `GB2312` 当作 `GBK` 的别名，
 * 不能直接用，所以这里用 [Gb2312Table] 判定合法码位，再用 JDK 的 GBK 取字符
 * （两者对这些码位只有 2 处不同，见 [OVERRIDES]）。
 *
 * 错误切分与 CPython 一致：一次只吃掉一个字节、吐一个 `\ufffd`（例如 `D7 FA` 是两个 `\ufffd`）。
 */
private class Gb2312Codec : PyTextCodec {

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
            if (i + 1 < n) {
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (Gb2312Table.isValid(b, b2)) {
                    val code = cellCode(b, b2)
                    if (code >= 0) {
                        out.appendCodePoint(code)
                        i += 2
                        continue
                    }
                }
            }
            out.appendCodePoint(0xFFFD)
            i++
        }
        return out.toString()
    }

    private fun cellCode(b1: Int, b2: Int): Int {
        overrides[(b1 shl 8) or b2]?.let { return it }
        return strictDecode(GBK, byteArrayOf(b1.toByte(), b2.toByte()))?.codePointAt(0) ?: -1
    }

    private companion object {
        val GBK: Charset = Charset.forName("GBK")

        /** `a1a4`（Python 是片假名中点 U+30FB）、`a1aa`（U+2015）在 JDK 的 GBK 里是别的字符。 */
        val overrides: Map<Int, Int> = parseCellOverrides("a1a4=30fb,a1aa=2015")
    }
}

/**
 * 双字节编解码器（gbk / big5 / shift_jis / euc-kr）在 `errors="replace"` 下的移植。
 *
 * 结构照 CPython 的 mbcs 解码器：ASCII 区原样、合法的双字节码位查表、其余一次吃掉一个字节吐一个
 * `\ufffd`。映射表用 JDK 的 charset 解两字节得到，再用 [overrides] 修正 Python 与 JDK 不同的码位；
 * GBK 还要把 JDK 独有的 PUA 区（U+E000–U+F8FF）算作非法，因为 Python 的 gbk 表里没有这些码位。
 */
private class DoubleByteCodec(
    javaName: String,
    leads: IntArray,
    trails: IntArray,
    private val katakana: IntRange? = null,
    private val rejectPua: Boolean = false,
    overrides: String = "",
) : PyTextCodec {

    private val charset: Charset = Charset.forName(javaName)
    private val leadSet = BooleanArray(256).also { flags -> leads.forEach { flags[it] = true } }
    private val trailSet = BooleanArray(256).also { flags -> trails.forEach { flags[it] = true } }
    private val overrides: Map<Int, Int> = parseCellOverrides(overrides)

    /** 码位表：-2 表示还没算过，-1 表示 Python 认为该码位非法。 */
    private val table = IntArray(1 shl 16) { UNKNOWN }

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
            if (katakana != null && b in katakana) {
                // Shift_JIS 的 0xA1–0xDF 是单字节半角片假名，优先于双字节解析。
                val code = strictDecode(charset, byteArrayOf(b.toByte()))?.codePointAt(0) ?: -1
                out.appendCodePoint(if (code < 0) 0xFFFD else code)
                i++
                continue
            }
            if (leadSet[b] && i + 1 < n && trailSet[bytes[i + 1].toInt() and 0xFF]) {
                val code = cell(b, bytes[i + 1].toInt() and 0xFF)
                if (code >= 0) {
                    out.appendCodePoint(code)
                    i += 2
                    continue
                }
            }
            out.appendCodePoint(0xFFFD)
            i++
        }
        return out.toString()
    }

    private fun cell(b1: Int, b2: Int): Int {
        val index = (b1 shl 8) or b2
        overrides[index]?.let { return it }
        val cached = table[index]
        if (cached != UNKNOWN) return cached
        var code = strictDecode(charset, byteArrayOf(b1.toByte(), b2.toByte()))?.codePointAt(0) ?: -1
        if (rejectPua && code in 0xE000..0xF8FF) code = -1
        table[index] = code
        return code
    }

    private companion object {
        const val UNKNOWN = -2
    }
}

/**
 * Python 的 `gb18030` 编解码器。两字节部分与 JDK 只有 19 个码位不同（见 [PyCodecs] 里的差异表），
 * 四字节部分完全一致。错误切分按 CPython 实测的结果：
 * 两字节形式一次吃掉一个字节；四字节形式在剩余字节不足 4 个时把剩下的全吃掉，
 * 够 4 个但内容非法时只吃 1 个字节。
 */
private class Gb18030Codec(javaName: String, overrides: String) : PyTextCodec {

    private val charset: Charset = Charset.forName(javaName)
    private val overrides: Map<Int, Int> = parseCellOverrides(overrides)

    /** 码位表：-2 表示还没算过，-1 表示 Python 认为该码位非法。 */
    private val table = IntArray(1 shl 16) { UNKNOWN }

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
            if (b in 0x81..0xFE && i + 1 < n) {
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (b2 in 0x40..0xFE && b2 != 0x7F) {
                    val code = cell(b, b2)
                    if (code >= 0) {
                        out.appendCodePoint(code)
                        i += 2
                        continue
                    }
                } else if (b2 in 0x30..0x39) {
                    if (n - i < 4) {
                        out.appendCodePoint(0xFFFD)
                        i = n
                        continue
                    }
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    val b4 = bytes[i + 3].toInt() and 0xFF
                    if (b3 in 0x81..0xFE && b4 in 0x30..0x39) {
                        val text = strictDecode(charset, bytes.copyOfRange(i, i + 4))
                        if (text != null && text.isNotEmpty()) {
                            out.append(text)
                            i += 4
                            continue
                        }
                    }
                }
            }
            out.appendCodePoint(0xFFFD)
            i++
        }
        return out.toString()
    }

    private fun cell(b1: Int, b2: Int): Int {
        val index = (b1 shl 8) or b2
        overrides[index]?.let { return it }
        val cached = table[index]
        if (cached != UNKNOWN) return cached
        val code = strictDecode(charset, byteArrayOf(b1.toByte(), b2.toByte()))?.codePointAt(0) ?: -1
        table[index] = code
        return code
    }

    private companion object {
        const val UNKNOWN = -2
    }
}

/**
 * 兜底：名字在 Java 里有、别名表里没有时用它。合法输入与 Python 一致；非法输入这里按 JDK 的
 * `REPLACE` 策略（一个非法序列一个 `\ufffd`），可能与 CPython 的切分不同（金标未覆盖这类名字）。
 */
private class JavaCodec(private val charset: Charset) : PyTextCodec {

    override fun decode(bytes: ByteArray): String {
        strictDecode(charset, bytes)?.let { return it }
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val result = CharBuffer.allocate(bytes.size * 4 + 16)
        decoder.decode(ByteBuffer.wrap(bytes), result, true)
        decoder.flush(result)
        result.flip()
        return result.toString()
    }
}

/** 严格解码一次；失败返回 `null`（对应 Python 抛 `UnicodeDecodeError`）。 */
internal fun strictDecode(charset: Charset, bytes: ByteArray): String? {
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        val result = CharBuffer.allocate(bytes.size * 4 + 16)
        decoder.decode(ByteBuffer.wrap(bytes), result, true)
        decoder.flush(result)
        result.flip()
        result.toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
