package com.mailhub.core.mail.codec

/** 二进制表的大端读取游标（`tables.bin` 用）。 */
internal class ByteCursor(private val data: ByteArray) {
    var position = 0
        private set

    fun u8(): Int = data[position++].toInt() and 0xFF

    fun u16(): Int = (u8() shl 8) or u8()

    /** 有符号 16 位：父表下标 -1 表示没有父表。 */
    fun i16(): Int = u16().let { if (it >= 0x8000) it - 0x10000 else it }

    fun u32(): Int {
        var value = 0
        repeat(4) { value = (value shl 8) or u8() }
        return value
    }

    fun bytes(count: Int): ByteArray {
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    /** 无符号 varint（LEB128）。 */
    fun varint(): Int {
        var value = 0
        var shift = 0
        while (true) {
            val byte = u8()
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
    }

    /** 双差分里的码点差用 zigzag 编码。 */
    fun zigzag(): Int {
        val raw = varint()
        return (raw ushr 1) xor -(raw and 1)
    }
}

/**
 * 一张解码表：16 位索引 → 码点，可能带一张父表（差分）。
 *
 * - 子表里出现的索引覆盖父表；子表里值为 0 表示「父表的这一项被屏蔽」；
 * - 子表里没有的索引继续查父表；
 * - [literals] 是解出多个字符的码位（big5hkscs 的 0x8862 / 0x8864 / 0x88A3 / 0x88A5）。
 *
 * 用两个 IntArray 加二分查找，不展开成 65536 项的数组：gbk 的两万多个码位只占约 190 KB。
 */
internal class CodecTable private constructor(
    private val starts: IntArray,
    private val values: IntArray,
    private val literals: Map<Int, IntArray>,
    private val parent: CodecTable?,
) {

    /** [index] 处的码点；-1 表示没有映射（对应 Python 的 `MAP_UNMAPPABLE`）。 */
    fun get(index: Int): Int {
        val own = own(index)
        if (own >= 0) {
            // 0 只在差分表（有父表）里表示「父表的这一项被屏蔽」；根表的 0 是真正的 U+0000
            return if (own == 0 && parent != null) -1 else own
        }
        return parent?.get(index) ?: -1
    }

    /** [index] 解出的多个字符的码点；没有这种码位时返回 null。 */
    fun literal(index: Int): IntArray? {
        literals[index]?.let { return it }
        return parent?.literal(index)
    }

    /** 本表里 [index] 的码点；-1 表示本表没有这一项（交给父表）。 */
    private fun own(index: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = starts[mid]
            when {
                index < start -> high = mid - 1
                index > start -> low = mid + 1
                else -> return values[mid]
            }
        }
        return -1
    }

    companion object {
        /** 解析 `tables.bin` 里的表载荷。 */
        fun parse(payload: ByteArray, parent: CodecTable?): CodecTable {
            val cursor = ByteCursor(payload)
            val count = cursor.u32()
            val starts = IntArray(count)
            val values = IntArray(count)
            var index = 0
            var value = 0
            for (run in 0 until count) {
                index += cursor.varint()
                value += cursor.zigzag()
                starts[run] = index
                values[run] = value
            }
            val literalCount = cursor.u32()
            val literals = HashMap<Int, IntArray>(literalCount * 2)
            repeat(literalCount) {
                val literalIndex = cursor.u16()
                val length = cursor.u8()
                literals[literalIndex] = IntArray(length) { cursor.u32() }
            }
            return CodecTable(starts, values, literals, parent)
        }
    }
}
