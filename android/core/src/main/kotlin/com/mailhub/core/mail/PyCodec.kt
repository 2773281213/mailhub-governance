package com.mailhub.core.mail

import com.mailhub.core.mail.codec.CodecTables
import com.mailhub.core.mail.codec.DoubleByteDecoder
import com.mailhub.core.mail.codec.EucJpDecoder
import com.mailhub.core.mail.codec.EucKrDecoder
import com.mailhub.core.mail.codec.Gb18030Decoder
import com.mailhub.core.mail.codec.HzDecoder
import com.mailhub.core.mail.codec.Iso2022JpDecoder
import com.mailhub.core.mail.codec.SingleByteDecoder
import com.mailhub.core.mail.codec.Utf16Decoder
import com.mailhub.core.mail.codec.Utf32Decoder
import com.mailhub.core.mail.codec.Utf7Decoder
import com.mailhub.core.mail.codec.Utf8Decoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Python `bytes.decode(name, errors="replace")` 的移植。
 *
 * `mail_client._decode_header` 用 `data.decode(charset or "utf-8", errors="replace")` 解编码字，
 * 所以这里要对同样的字节得到同样的字符串。
 *
 * **解码不碰平台的 `Charset`。** 桌面 JDK 与 Android ICU 的映射不同（Android 的 Shift_JIS、
 * Big5 带厂商扩展，不同 Android 版本之间也不一样），而国内邮件大量是 GBK / GB2312 / GB18030 / Big5，
 * 不能赌平台。映射表由 `scripts/golden/codecs.py` 从 CPython 3.13.3 实测导出，随 `:core` 打包
 * （`py/codecs/tables.bin`），桌面和手机的结果相同；实现见 `com.mailhub.core.mail.codec`。
 *
 * 与 Python 的对应关系：
 *
 * 1. **名字**：Python 先把编码名小写化、把空格和 `-` 折成 `_`，再按 `encodings.normalize_encoding`
 *    折叠，然后查别名表；查不到时只有不含 `.` 的名字才按 `encodings` 模块名直接命中。
 *    名字不认识时 Python 抛 `LookupError`，这里 [lookup] 返回 null，调用方退回原文
 *    （`decodeHeader` 的 `except` 分支）。
 * 2. **映射表**：全部来自 [CodecTables]，与 CPython 逐字节一致（`codecs.py --check` 把关）。
 * 3. **错误切分**：`errors="replace"` 时每种编解码器吃掉多少字节、吐几个 `\ufffd` 各不相同，
 *    见 `codec/Decoders.kt` 里的说明。
 */
internal interface PyTextCodec {

    /** 规范名，对应 `codecs.lookup(name).name`（例如 `latin1` 的规范名是 `iso8859-1`）。 */
    val name: String

    /** 对应 `bytes.decode(name, errors="replace")`：永不抛异常。 */
    fun decode(bytes: ByteArray): String
}

/** Python 编码名字到编解码器的映射，对应 `codecs.lookup(name)`。 */
internal object PyCodecs {

    /** 查表；`null` 表示 Python 会抛 `LookupError`（未知编码），或本移植没有实现该编码。 */
    fun lookup(name: String): PyTextCodec? {
        val canonical = CodecTables.canonicalName(name) ?: return null
        val spec = CodecTables.codec(canonical) ?: return null
        cache[canonical]?.let { return it }
        return cache.computeIfAbsent(canonical) { build(spec) }
    }

    /** `codecs.lookup(name)` 成功之后的 `data.decode(name, errors="replace")`；名字不认识时返回 null。 */
    fun decode(bytes: ByteArray, name: String): String? = lookup(name)?.decode(bytes)

    /**
     * Python `encodings.normalize_encoding` 的移植（大小写折叠与 `-`→`_` 已经包含在内）：
     * ASCII 字母数字和 `.` 保留，其余字符折成单个 `_`，非 ASCII 字母数字丢弃。
     * 所以 `UTF-8`、`utf 8` 归一化后都是 `utf_8`，而 `utf.8` 归一化成 `utf.8`。
     */
    fun normalizeEncoding(name: String): String = CodecTables.normalizeEncoding(name)

    private val cache = ConcurrentHashMap<String, PyTextCodec>()

    private fun build(spec: CodecTables.CodecSpec): PyTextCodec {
        val canonical = spec.name
        val tables = spec.tables
        return when (spec.family) {
            CodecTables.SINGLE -> SingleByteDecoder(canonical, CodecTables.table(tables[0]))
            CodecTables.DOUBLE -> DoubleByteDecoder(
                canonical,
                CodecTables.table(tables[0]),
                CodecTables.table(tables[1]),
            )
            CodecTables.GB18030 -> Gb18030Decoder(
                canonical,
                CodecTables.table(tables[0]),
                CodecTables.table(tables[1]),
            )
            CodecTables.EUC_KR -> EucKrDecoder(
                canonical,
                CodecTables.table(tables[0]),
                CodecTables.table(tables[1]),
            )
            CodecTables.EUC_JP -> EucJpDecoder(
                canonical,
                CodecTables.table(tables[0]),
                CodecTables.table(tables[1]),
            )
            CodecTables.ISO2022_JP -> Iso2022JpDecoder(canonical, CodecTables.table(tables[0]))
            CodecTables.HZ -> HzDecoder(canonical, CodecTables.table(tables[0]))
            CodecTables.UTF7 -> Utf7Decoder(canonical)
            CodecTables.UTF8 -> Utf8Decoder(canonical)
            CodecTables.UTF16 -> Utf16Decoder(canonical, littleEndian = null)
            CodecTables.UTF16_LE -> Utf16Decoder(canonical, littleEndian = true)
            CodecTables.UTF16_BE -> Utf16Decoder(canonical, littleEndian = false)
            CodecTables.UTF32 -> Utf32Decoder(canonical, littleEndian = null)
            CodecTables.UTF32_LE -> Utf32Decoder(canonical, littleEndian = true)
            CodecTables.UTF32_BE -> Utf32Decoder(canonical, littleEndian = false)
            else -> error("未知的编解码器家族 ${spec.family}（$canonical）")
        }
    }
}
