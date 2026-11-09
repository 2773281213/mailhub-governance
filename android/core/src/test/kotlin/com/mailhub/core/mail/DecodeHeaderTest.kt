package com.mailhub.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `_decode_header` / `decode_header` 的反回溯测试。
 *
 * Python 用 `ecre = re.compile(r'=\?(?P<charset>[^?]*?)\?(?P<encoding>[qQbB])\?(?P<encoded>.*?)\?=')`
 * 加 `re.split` 解编码字，`.*?` 在「很多个 `=?x?q?A` 开头、始终没有 `?=`」的输入上是平方级：
 * 一兆字节大约 7.5×10¹⁰ 步。`Subject` 和附件名都走这里，而这两个字段完全由攻击者控制，
 * 所以必须在线性时间内返回。期望值对着 Python 3.13.3 算：没有 `?=` 时原样返回整串。
 */
class DecodeHeaderTest {

    @Test(timeout = 2_000)
    fun encodedWordWorstCaseReturnsQuickly() {
        // 1 MB 的「=?x?q?A」重复：Python 的 ecre 在这里会退化成平方级
        val input = "=?x?q?A".repeat(1_000_000 / 7)
        val started = System.nanoTime()
        val text = decodeHeader(input)
        val parts = decodeHeaderParts(input)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("decode_header 花了 ${elapsedMs}ms", elapsedMs < 2_000)
        assertEquals(input, text)
        assertEquals(1, parts.size)
        assertEquals(input, parts[0].first)
    }

    @Test(timeout = 2_000)
    fun unmatchableQuestionMarksReturnQuickly() {
        // 只有 `=?` 与 `?`、没有编码标记：每 7 个字符一个候选起点，且后面再没有 `?=`
        val input = "=?" + "?".repeat(1_000_000)
        val started = System.nanoTime()
        val parts = decodeHeaderParts(input)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("decode_header 花了 ${elapsedMs}ms", elapsedMs < 2_000)
        assertEquals(listOf<Pair<Any, String?>>(input to null), parts)
    }

    @Test(timeout = 2_000)
    fun longEncodedWordsStillDecode() {
        // 正常用法不能被防回溯改坏：编码字要照常解出来
        val parts = decodeHeaderParts("=?utf-8?B?5L2g5aW9?=")
        assertEquals(1, parts.size)
        assertEquals("你好", PyCodecs.decode(parts[0].first as ByteArray, parts[0].second ?: "utf-8"))
    }
}
