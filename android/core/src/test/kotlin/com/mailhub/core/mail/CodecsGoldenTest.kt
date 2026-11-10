package com.mailhub.core.mail

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.GoldenCase
import com.mailhub.core.golden.str
import com.mailhub.core.mail.codec.CodecTables
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import java.util.zip.Inflater

/**
 * 编解码器的金标回放：期望值由 `scripts/golden/codecs.py` 用 CPython 3.13.3 算出来。
 *
 * 四类用例：
 *
 * - `decode`：`bytes.decode(codec, errors="replace")` 的结果；
 * - `lookup`：`codecs.lookup(name).name`，未知编码的期望值是 null（对应 `LookupError`）；
 * - `unsupported`：Python 认得、本移植没实现的规范名，必须都返回 null；
 * - `pairs`：全码位比对。每个 (b1, b2) 一个分类：
 *   `0` 命中的码位（期望值从同一份资源里取）、`1` 一个 `\ufffd`（两个字节一起被吃掉）、
 *   `2` 「前一个字节单独解 + 后一个字节单独解」（各吃一个字节）、`3` 解出多个字符的例外。
 *
 * 每个用例失败时都会写明 id、期望值与实际值，全部跑完再统一失败（见 [Golden.replay]）。
 */
class CodecsGoldenTest {

    @Test
    fun decodesMatchPython() = Golden.replay("codecs") { case ->
        when (case.fn) {
            "decode" -> checkDecode(case)
            "lookup" -> Golden.diff(
                (case.expected as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                PyCodecs.lookup(case.args.str("name"))?.name,
            )
            "unsupported" -> checkUnsupported(case)
            "pairs" -> checkPairs(case)
            else -> "未知的 fn：${case.fn}"
        }
    }

    private fun checkDecode(case: GoldenCase): String? {
        val codec = case.args.str("codec")
        val bytes = hexToBytes(case.args.str("hex"))
        val actual = PyCodecs.decode(bytes, codec) ?: return "未知编码 $codec"
        return Golden.diff(case.expected.jsonPrimitive.content, actual)
    }

    private fun checkUnsupported(case: GoldenCase): String? {
        val names = (case.expected as JsonArray).map { it.jsonPrimitive.content }
        val known = names.filter { PyCodecs.lookup(it) != null }
        return if (known.isEmpty()) null else "这些编码 Python 有、本移植不该认识：$known"
    }

    /** 全码位比对：逐 (b1, b2) 断言解码结果与 Python 一致。 */
    private fun checkPairs(case: GoldenCase): String? {
        val codec = case.args.str("codec")
        val expected = case.expected.jsonObject
        val classes = inflate(Base64.getDecoder().decode(expected.getValue("classes").jsonPrimitive.content))
        if (classes.size != 0x10000) return "分类表长度是 ${classes.size}，应为 65536"
        val literals = HashMap<Int, String>()
        for (entry in expected.getValue("literals").jsonArray) {
            val pair = (entry as JsonArray)
            literals[pair[0].jsonPrimitive.content.toInt(16)] = pair[1].jsonPrimitive.content
        }
        val problems = ArrayList<String>()
        for (index in 0 until 0x10000) {
            val b1 = index shr 8
            val b2 = index and 0xFF
            val actual = PyCodecs.decode(byteArrayOf(b1.toByte(), b2.toByte()), codec)
                ?: return "未知编码 $codec"
            val want = when (classes[index].toInt()) {
                0 -> {
                    val codePoint = CodecTables.tableValue(codec, 0, index)
                    if (codePoint < 0) return "${hex(index)} 在分类表里是命中，但资源表里没有"
                    String(Character.toChars(codePoint))
                }
                1 -> "\uFFFD"
                2 -> unit(codec, b1) + unit(codec, b2)
                else -> literals[index] ?: return "${hex(index)} 缺少字面量"
            }
            if (actual != want) {
                problems.add("${hex(index)} 期望 ${escape(want)} 实际 ${escape(actual)}")
            }
        }
        return if (problems.isEmpty()) {
            null
        } else {
            "${problems.size} 个码位不一致：${problems.take(8).joinToString("；")}"
        }
    }

    /**
     * 单个字节在「已经至少有下一个字节可看」时的解码结果：ASCII 原样、双字节编码的单字节区
     * （Shift_JIS 的半角片假名等）查资源里的单字节表，其余都是 `\ufffd`。
     */
    private fun unit(codec: String, byte: Int): String {
        if (byte < 0x80) return byte.toChar().toString()
        if (CodecTables.family(codec) == CodecTables.DOUBLE) {
            val codePoint = CodecTables.tableValue(codec, 1, byte)
            if (codePoint >= 0) return String(Character.toChars(codePoint))
        }
        return "\uFFFD"
    }

    /**
     * CPython 的 utf-7 解码器在「高代理后面没有低代理」时会把孤立代理码点原样写进字符串，
     * 这种字符串无法写进 UTF-8 的 JSON，所以导出脚本跳过了这类用例。这里单独钉住它：
     * `+2D3mZw-` 是 UTF-16 的 `D83D E667`，CPython 输出 `\uD83D\uE667`。
     */
    @Test
    fun utf7KeepsLoneSurrogate() {
        val decoded = PyCodecs.decode(hexToBytes("2B3244336D5A772D"), "utf-7")
        assertEquals("\uD83D\uE667", decoded)
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val buffer = ByteArray(0x20000)
        var size = 0
        while (!inflater.finished() && size < buffer.size) {
            val read = inflater.inflate(buffer, size, buffer.size - size)
            if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            size += read
        }
        inflater.end()
        return buffer.copyOf(size)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(index: Int): String = "%04x".format(index)

    private fun escape(text: String): String = buildString {
        for (ch in text) {
            if (ch.code in 0x20..0x7E && ch != '\\') {
                append(ch)
            } else {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            }
        }
    }
}
