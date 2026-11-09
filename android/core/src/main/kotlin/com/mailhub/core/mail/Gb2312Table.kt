package com.mailhub.core.mail

import java.math.BigInteger

/**
 * GB2312-80 的合法码位表：87 个前导字节（0xA1–0xF7）× 94 个尾字节（0xA1–0xFE），
 * 每个前导字节一行，位图的第 i 位（从低位数）对应尾字节 0xA1 + i。
 *
 * 表是按 CPython 3.13 的 `gb2312` 编解码器逐码位导出的：Python 的 gb2312 严格按
 * GB2312-80 的 7445 个字符（682 个符号 + 6763 个汉字），GBK 独有的码位一律解不出来。
 * 不要改成「等于 GBK」——JDK 的 GB2312 就是 GBK 的别名，那样会放宽语义。
 */
internal object Gb2312Table {

    /** 码位 (b1, b2) 是否是 GB2312 定义的字符。 */
    fun isValid(b1: Int, b2: Int): Boolean {
        if (b1 !in 0xA1..0xF7 || b2 !in 0xA1..0xFE) return false
        return ROWS[b1 - 0xA1].testBit(b2 - 0xA1)
    }

    private val ROWS: List<BigInteger> = listOf(
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("0fff3ff3ffffffffffff0000", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("0007ffffffffffffffffffff", 16), BigInteger("003fffffffffffffffffffff", 16), BigInteger("0000000000ffffff00ffffff", 16),
        BigInteger("0001ffffffff0001ffffffff", 16), BigInteger("000001fffffffff003ffffff", 16), BigInteger("00007ffffffffffffffffff8", 16),
        BigInteger("000000000000000000000000", 16), BigInteger("000000000000000000000000", 16), BigInteger("000000000000000000000000", 16),
        BigInteger("000000000000000000000000", 16), BigInteger("000000000000000000000000", 16), BigInteger("000000000000000000000000", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("01ffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
        BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16), BigInteger("3fffffffffffffffffffffff", 16),
    )
}
