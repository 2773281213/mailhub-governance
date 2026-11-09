package com.mailhub.core.mail.codec

import java.util.concurrent.ConcurrentHashMap

/**
 * Python 编解码器的解码表：资源 `py/codecs/tables.bin` 与 `py/codecs/aliases.txt`，
 * 由 `scripts/golden/codecs.py` 从 CPython 3.13.3 实测导出。
 *
 * 表是**数据**而不是平台能力：桌面 JDK 与 Android ICU 的 `Charset` 映射不同（Android 的 Shift_JIS、
 * Big5 带厂商扩展，不同 Android 版本之间也不一样），所以解码一律查这里，不调用 `Charset.forName(...)`。
 * `scripts/golden/codecs.py --check` 保证资源与 Python 逐字节一致。
 *
 * 二进制格式（大端）：
 *
 * ```text
 * "MHCD" 版本号 1
 * u16 表个数，逐张：u8 名字长度 + 名字 + i16 父表下标（-1 无）+ u32 载荷长度 + 载荷
 * u16 编解码器个数，逐个：u8 名字长度 + 名字 + u8 家族 + u8 表个数 + u16 表下标…
 * ```
 *
 * 表载荷是「索引与码点的双差分 varint 流」：u32 条数，然后逐条 varint 索引差、varint 码点差（zigzag）。
 * 末尾是 u32 字面条数 + 逐条 (u16 索引, u8 字符数, u32 码点…)；解出多个字符的码位只有 big5hkscs
 * 的四个组合序列。父表表示差分：子表里没出现的索引继续查父表，子表里出现 0 表示父表的这一项被屏蔽
 * （只有 big5hkscs 会屏蔽父表的码位）。
 *
 * 每张表用二分查找回答单点查询，不展开成 65536 项的数组：gbk 的两万多个码位只占约 190 KB 堆内存
 * （两个 IntArray），而且只有真正用到的编解码器才会被解析。
 */
internal object CodecTables {

    // 家族编号，必须与 scripts/golden/codecs.py 的 FAMILY_* 一致。
    const val SINGLE = 1
    const val DOUBLE = 2
    const val GB18030 = 3
    const val EUC_KR = 4
    const val EUC_JP = 5
    const val ISO2022_JP = 6
    const val HZ = 7
    const val UTF7 = 8
    const val UTF8 = 9
    const val UTF16 = 10
    const val UTF16_LE = 11
    const val UTF16_BE = 12
    const val UTF32 = 13
    const val UTF32_LE = 14
    const val UTF32_BE = 15

    /** 一个编解码器：家族编号 + 表下标（表的含义由家族决定）。 */
    internal class CodecSpec(val name: String, val family: Int, val tables: IntArray)

    private class RawTable(val name: String, val parentIndex: Int, val payload: ByteArray)

    /**
     * `aliases.txt` 的两张表：`a` 行是「归一化后的别名 → 规范名」，`m` 行是「encodings 模块名 → 规范名」。
     * Python 的别名值本来指向模块名，导出脚本已经把它换成 `codecs.lookup(别名).name`，所以两张表的值
     * 都是规范名；[canonicalName] 只比对键。
     */
    private val aliases: Map<String, String> by lazy { readAliases(0) }
    private val modules: Map<String, String> by lazy { readAliases(1) }

    private class Blob(val tables: List<RawTable>, val codecs: Map<String, CodecSpec>)

    private val blob: Blob by lazy { readBlob() }

    private val resolved = ConcurrentHashMap<Int, CodecTable>()

    /** 表下标 → 表；父表（差分基准）按需递归解析。 */
    fun table(index: Int): CodecTable = resolved.computeIfAbsent(index) { current ->
        val raw = blob.tables[current]
        val parent = if (raw.parentIndex >= 0) table(raw.parentIndex) else null
        CodecTable.parse(raw.payload, parent)
    }

    fun codec(name: String): CodecSpec? = blob.codecs[name]

    /**
     * Python `codecs.lookup(name)` 成功时的规范名（`CodecInfo.name`）；不认识时返回 null，
     * 对应 Python 的 `LookupError`。
     *
     * 查找顺序照 `encodings.search_function`：先归一化名字，再查别名表（原名，其次把 `.` 换成 `_`）；
     * 别名表里没有时，只有不含 `.` 的名字才按 `encodings` 模块名直接命中——所以 `utf.8` 是未知编码，
     * 而 `iso.8859.1` 因为别名表里有 `iso_8859_1` 而认得。
     *
     * `aliases.txt` 的 `a` 行存的是**规范名**（不是模块名）：Python 的别名值要先 `import encodings.<名字>`
     * 才拿到规范名，导出时已经把这一步做完了，所以这里直接返回它。
     */
    fun canonicalName(name: String): String? {
        val norm = normalizeEncoding(name)
        if (norm.isEmpty()) return null
        aliases[norm]?.let { return it }
        if ('.' !in norm) return modules[norm]
        return aliases[norm.replace('.', '_')]
    }

    /**
     * Python `encodings.normalize_encoding` 的移植：ASCII 字母数字和 `.` 保留，其余字符折成单个 `_`；
     * 非 ASCII 的字母数字**丢弃但不插入下划线**（`aÜb` 归一化成 `ab`）。
     *
     * 调用方在此之前已经小写化（Python 的 `_PyCodec_Lookup` 先做这一步），这里只管折叠。
     * Python 的 `isalnum()` 还认 `²` 这类数字（Unicode 的 No 类），这里用
     * [Character.isLetterOrDigit] 近似，差异只可能出现在含这类字符的编码名上。
     */
    fun normalizeEncoding(name: String): String {
        val out = StringBuilder(name.length)
        var punct = false
        for (ch in name) {
            val ascii = ch.code < 0x80
            val keep = ascii && (ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '.')
            if (keep) {
                if (punct && out.isNotEmpty()) out.append('_')
                out.append(if (ch in 'A'..'Z') ch + 32 else ch)
                punct = false
            } else if (!ascii && Character.isLetterOrDigit(ch)) {
                punct = false
            } else {
                punct = true
            }
        }
        return out.toString()
    }

    /** 测试用：编解码器第 [tableIndex] 张表在 [index] 处的码点，-1 表示没有映射。 */
    fun tableValue(codec: String, tableIndex: Int, index: Int): Int {
        val spec = blob.codecs[codec] ?: return -1
        if (tableIndex >= spec.tables.size) return -1
        return table(spec.tables[tableIndex]).get(index)
    }

    /** 测试用：编解码器的家族编号；不存在时返回 -1。 */
    fun family(codec: String): Int = blob.codecs[codec]?.family ?: -1

    private fun readResource(path: String): ByteArray {
        val stream = CodecTables::class.java.getResourceAsStream(path)
            ?: error("缺少资源 $path，先运行 python scripts/golden/codecs.py")
        return stream.use { it.readBytes() }
    }

    private fun readAliases(kind: Int): Map<String, String> {
        val text = readResource("/py/codecs/aliases.txt").toString(Charsets.UTF_8)
        val map = HashMap<String, String>(512)
        val prefix = if (kind == 0) "a\t" else "m\t"
        for (line in text.lineSequence()) {
            if (!line.startsWith(prefix)) continue
            val end = line.indexOf('\t', prefix.length)
            if (end < 0) continue
            map[line.substring(prefix.length, end)] = line.substring(end + 1)
        }
        return map
    }

    private fun readBlob(): Blob {
        val cursor = ByteCursor(readResource("/py/codecs/tables.bin"))
        val magic = cursor.bytes(4).toString(Charsets.US_ASCII)
        check(magic == "MHCD") { "py/codecs/tables.bin 的魔数不对：$magic" }
        val version = cursor.u8()
        check(version == 1) { "py/codecs/tables.bin 的版本 $version 不认识" }
        val tableCount = cursor.u16()
        val tables = ArrayList<RawTable>(tableCount)
        repeat(tableCount) {
            val name = cursor.bytes(cursor.u8()).toString(Charsets.UTF_8)
            val parent = cursor.i16()
            val payload = cursor.bytes(cursor.u32())
            tables.add(RawTable(name, parent, payload))
        }
        val codecCount = cursor.u16()
        val codecs = HashMap<String, CodecSpec>(codecCount * 2)
        repeat(codecCount) {
            val name = cursor.bytes(cursor.u8()).toString(Charsets.UTF_8)
            val family = cursor.u8()
            val tableIndexes = IntArray(cursor.u8()) { cursor.u16() }
            codecs[name] = CodecSpec(name, family, tableIndexes)
        }
        return Blob(tables, codecs)
    }
}
