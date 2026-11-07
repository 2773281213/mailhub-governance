package com.mailhub.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 期望值都来自 Python 3.13 的实际输出（见 LOCAL_MODE.md 第四节），不是按 Java 的习惯写的。 */
class PyTest {

    private fun find(pattern: String, input: String, ignoreCase: Boolean = false): Boolean =
        PyRegex.compile(pattern, ignoreCase = ignoreCase).matcher(input).find()

    private fun findAll(pattern: String, input: String): List<String> {
        val m = PyRegex.compile(pattern).matcher(input)
        val out = mutableListOf<String>()
        while (m.find()) out += m.group()
        return out
    }

    @Test
    fun digitsAndWordsAreUnicode() {
        assertTrue(find("\\d", "３"))
        assertTrue(PyRegex.compile("\\w+").matcher("验证码").matches())
        assertEquals(listOf("验证码abc", "123"), findAll("\\b\\w+\\b", "验证码abc 123"))
    }

    @Test
    fun wordAndSpaceClassesMatchPythonNotJava() {
        assertTrue(find("\\boffer\\b", "\uD83D\uDD25\uFE0Foffer"))
        assertTrue(PyRegex.compile("\\w").matcher("\u00bd").matches())
        assertTrue(PyRegex.compile("\\w").matcher("\u2460").matches())
        assertFalse(PyRegex.compile("\\w").matcher("\u0301").matches())
        assertFalse(PyRegex.compile("\\w").matcher("\u203f").matches())
        assertFalse(PyRegex.compile("\\w").matcher("\u200d").matches())
        assertTrue(PyRegex.compile("\\s").matcher("\u001c").matches())
        assertTrue(PyRegex.compile("\\s").matcher("\u00a0").matches())
        assertFalse(PyRegex.compile("\\s").matcher("\u200b").matches())
        assertTrue(PyRegex.compile("[\\b]").matcher("\u0008").matches())
        assertEquals("a b c", PyRegex.compile("\\s+").matcher("a\u001c\u001db\u00a0c").replaceAll(" "))
        assertTrue(PyRegex.compile("[^\\s/]+").matcher("ab").matches())
        assertFalse(PyRegex.compile("[^\\s/]+").matcher("a\u001cb").matches())
    }

    @Test
    fun turkishAndGreekFoldingMatchesPython313() {
        // Python 3.13 re.I 实测：以下全部为 True（ß 不等于 SS）。Java 的结果相同，这里固定下来防止回归。
        val pairs = listOf(
            "i" to "İ", "i" to "ı", "I" to "ı", "I" to "İ", "İ" to "i", "ı" to "i",
            "ẞ" to "ß", "σ" to "ς", "Σ" to "ς", "Ω" to "ω", "µ" to "μ",
        )
        pairs.forEach { (p, s) ->
            assertTrue("$p ~ $s", PyRegex.compile(PyRegex.escape(p), ignoreCase = true).matcher(s).matches())
        }
        assertFalse(PyRegex.compile("ß", ignoreCase = true).matcher("SS").matches())
        assertTrue(PyRegex.compile("[a-z]", ignoreCase = true).matcher("İ").matches())
    }

    @Test
    fun ignoreCaseFoldsUnicode() {
        assertTrue(find("s", "ſ", ignoreCase = true))
        assertTrue(find("k", "K", ignoreCase = true))
        assertTrue(find("verification", "VERIFICATION", ignoreCase = true))
        assertFalse(find("verification", "VERIFICATION"))
    }

    @Test
    fun onlyNewlineEndsALine() {
        assertTrue(find("a.b", "a\rb"))
        assertTrue(find("a.b", "a b"))
        assertFalse(find("a.b", "a\nb"))
        assertTrue(find("abc$", "abc\n"))
    }

    @Test
    fun translatesPythonOnlySyntax() {
        assertFalse(find("abc\\Z", "abc\n"))
        assertTrue(find("abc\\Z", "xabc"))
        assertEquals(listOf("aa", "a", ""), findAll("a{,2}", "aaa"))
        assertEquals(listOf("x{"), findAll("x{", "x{"))
        assertEquals(listOf("["), findAll("[[]", "[a"))
        assertEquals(listOf("&"), findAll("[a&&b]", "&"))
        assertEquals(listOf("]"), findAll("[]]", "a]"))
        val m = PyRegex.compile("(?P<code_1>\\d+)-(?P=code_1)").matcher("x12-12")
        assertTrue(m.find())
        assertEquals("12", m.group("pyg1"))
        val amount = PyRegex.compile("(?P<pre>\\$)(?P<n1>\\d+)").matcher("x$12")
        assertTrue(amount.find())
        assertEquals("12", amount.group("n1"))
    }

    @Test
    fun escapeKeepsSpecialCharactersLiteral() {
        val brand = "a.b[c]{2} & #x"
        assertTrue(find("(?<![a-z0-9])" + PyRegex.escape(brand) + "(?![a-z0-9])", "-a.b[c]{2} & #x-"))
        assertFalse(find(PyRegex.escape("a.b"), "axb"))
    }

    @Test
    fun userPatternsThatPythonRejectsReturnNull() {
        assertNull(PyRegex.compileUser("(unclosed"))
        assertNull(PyRegex.compileUser("a**"))
        assertTrue(PyRegex.compileUser("(?P<code>\\d+)", ignoreCase = true)!!.matcher("x42").find())
    }

    @Test
    fun slicesByCodePoint() {
        val s = "ab😀cd"
        assertEquals(5, s.cpLen())
        assertEquals("b😀", s.cpSlice(1, 3))
        assertEquals("cd", s.cpSlice(-2))
        assertEquals("", s.cpSlice(4, 2))
        assertEquals(s, s.cpSlice(-100, 100))
        val m = PyRegex.compile("c").matcher(s)
        assertTrue(m.find())
        assertEquals(3, s.cpIndex(m.start()))
    }

    @Test
    fun whitespaceFollowsPythonIsspace() {
        assertEquals(listOf("a", "b", "c", "d"), " a b　c\u001cd ".pySplit())
        assertEquals("x", "  x 　".pyStrip())
        assertEquals("", "   ".pyStrip())
        assertEquals(emptyList<String>(), "".pySplit())
        assertFalse(isPySpace('​'.code))
        assertEquals(">q", " >q".pyLstrip())
    }

    @Test
    fun splitLinesFollowsPython() {
        assertEquals(listOf("a", "b", "", "c"), "a\r\nb\n\nc\n".pySplitLines())
        assertEquals(emptyList<String>(), "".pySplitLines())
        assertEquals(listOf("x", "y"), "x\u000by".pySplitLines())
        assertEquals(listOf(""), "\n".pySplitLines())
        assertEquals(listOf("a", "b"), "a b".pySplitLines())
    }

    @Test
    fun truthyFollowsPython() {
        listOf(null, false, 0, 0L, 0.0, "", emptyList<Int>(), emptyMap<String, Int>()).forEach {
            assertFalse("$it", Py.truthy(it))
        }
        listOf(true, 1, -1, 0.5, Double.NaN, "0", listOf(0), mapOf("a" to 0)).forEach {
            assertTrue("$it", Py.truthy(it))
        }
    }

    @Test
    fun floatAndIntParseLikePython() {
        val inputs = listOf(
            "１２３", "12.50", " 7 ", "1_000", "1__0", "_1", "1e3", "-.5", "+3.", "inf", "-Infinity", "nan",
            "1 3", "　 42 ", "١٢٣", "", ".", "1.2.3", "0x10", "12e",
        )
        val python = listOf(
            "123.0", "12.5", "7.0", "1000.0", "ERR", "ERR", "1000.0", "-0.5", "3.0", "inf", "-inf", "nan",
            "ERR", "42.0", "123.0", "ERR", "ERR", "ERR", "ERR", "ERR",
        )
        val actual = inputs.map { s ->
            when (val v = Py.float(s)) {
                null -> "ERR"
                Double.POSITIVE_INFINITY -> "inf"
                Double.NEGATIVE_INFINITY -> "-inf"
                else -> if (v.isNaN()) "nan" else v.toString()
            }
        }
        assertEquals(python, actual)
        val ints = listOf("１２３", " 7 ", "1_000", "-5", "+0", "3.0", "", "٣").map { Py.int(it)?.toString() ?: "ERR" }
        assertEquals(listOf("123", "7", "1000", "-5", "0", "ERR", "ERR", "3"), ints)
    }

    @Test
    fun roundIsHalfEvenOnTheBinaryValue() {
        assertEquals(2.67, Py.round(2.675, 2), 0.0)
        assertEquals(0.12, Py.round(0.125, 2), 0.0)
        assertEquals(1440.0, Py.round(200.0 * 7.2, 2), 0.0)
    }

    @Test
    fun formatFixedRoundsHalfEvenOnTheBinaryValue() {
        val inputs = listOf(0.125, 0.375, -0.001, 0.745, 2.675, -0.0, 0.749, 0.75)
        val python = listOf("0.12", "0.38", "-0.00", "0.74", "2.67", "-0.00", "0.75", "0.75")
        assertEquals(python, inputs.map { Py.formatFixed(it, 2) })
    }
}
