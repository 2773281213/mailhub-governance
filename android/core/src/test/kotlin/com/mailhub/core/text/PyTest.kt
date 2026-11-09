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

    private fun full(pattern: String, input: String): Boolean = PyRegex.compile(pattern).matcher(input).matches()

    private fun pythonRejects(pattern: String): Boolean = PyRegex.compileUser(pattern) == null

    @Test
    fun noEngineSpecificFlags() {
        // Android 的 ICU 正则不支持 UNICODE_CHARACTER_CLASS；编译结果里不能带它
        val flags = PyRegex.compile("\\w+\\d", ignoreCase = true).flags()
        assertEquals(0, flags and java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)
        assertFalse(PyRegex.translate("\\d\\w\\s\\b").contains("\\d"))
    }

    @Test
    fun patternsPythonAcceptsCompileAndMatchLikePython() {
        assertTrue(full("x{a}", "x{a}"))
        assertTrue(full("{\"code\":\\s*\\d+}", "{\"code\": 42}"))
        assertTrue(full("a{\uff12}", "a{\uff12}"))
        assertTrue(full("\\v", "\u000b"))
        assertFalse(full("\\v", "\n"))
        assertTrue(full("\\0", "\u0000"))
        assertTrue(full("[\\0]", "\u0000"))
        assertTrue(full("[\\7]", "\u0007"))
        assertTrue(full("\\141", "a"))
        assertTrue(full("[\\141]", "a"))
        assertTrue(full("\\U0001f600", "\uD83D\uDE00"))
        assertTrue(full("a(?#[comment])b", "ab"))
        assertTrue(full("(a)\\1", "aa"))
        assertTrue(full("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)\\12", "abcdefghijkll"))
        assertTrue(full("[ a]", " "))
        assertTrue(full("[a b]", " "))
        assertTrue(full("[a&b]", "&"))
        val named = PyRegex.compile("(?P<pyg2>a)(?P<x_1>b)").matcher("ab")
        assertTrue(named.matches())
        assertEquals("a", named.group("pyg2"))
        assertTrue(full("(?P<名字>a)(?P=名字)", "aa"))
    }

    @Test
    fun patternsPythonRejectsAreRejected() {
        listOf(
            "\\b+", "\\B*", "[\\w-~]", "[\\x00-\\s]", "\\p{L}", "\\e", "[\\A]", "(?P<123>a)", "(?P<>a)",
            "(?P<a-b>a)", "(?<foo>a)", "(?P<x>a)(?P<x>b)", "a(?#unterminated", "[\\8]", "\\x4", "\\u12", "a\\",
        ).forEach { assertTrue(it, pythonRejects(it)) }
    }

    @Test
    fun backReferencesMustPointToClosedGroups() {
        listOf("(a)\\2", "(a\\1)", "(?P<x>a(?P=x))", "(a)\\12", "a)").forEach { assertTrue(it, pythonRejects(it)) }
        assertTrue(full("(a)\\1", "aa"))
        assertTrue(full("(?P<x>a)(?P=x)", "aa"))
        assertFalse(pythonRejects("(?u)a"))
    }

    @Test
    fun inlineIgnoreCaseIsUnicode() {
        assertTrue(full("(?i)abc", "ABC"))
        assertTrue(full("(?i)s", "ſ"))
        assertTrue(full("(?s)a.b", "a\nb"))
    }

    @Test
    fun boundariesMatchPython313() {
        assertFalse(find("\\B", "\uD840\uDC00"))
        assertEquals("-\uD83D\uDE00-", PyRegex.compile("\\B").matcher("\uD83D\uDE00").replaceAll("-"))
        assertFalse(find("\\B", ""))
        assertFalse(find("\\b", ""))
    }

    @Test
    fun negatedShorthandsInsideClassesMatchPython() {
        assertFalse(full("[\\W]", "\u2460"))
        assertTrue(full("[\\W]", "\ufe0f"))
        assertFalse(full("[\\S]", "\u001c"))
        assertTrue(full("[^a\\W]", "b"))
        assertFalse(full("[^a\\W]", "a"))
        assertFalse(full("[^a\\W]", "-"))
        assertFalse(full("[\\D]", "\uff13"))
        assertTrue(full("[\\d]", "\uff13"))
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
    fun turkishIExpandsWhereverIgnoreCaseApplies() {
        fun match(pattern: String, text: String, ignoreCase: Boolean = false) =
            PyRegex.compile(pattern, ignoreCase = ignoreCase).matcher(text).matches()

        val dotted = 0x130.toChar().toString()
        val dotless = 0x131.toChar().toString()
        for (letter in listOf(dotted, dotless, "i", "I")) {
            // 字面量、转义、字符类、范围、否定字符类
            assertTrue(match("i", letter, ignoreCase = true))
            assertTrue(match("\\x69", letter, ignoreCase = true))
            assertTrue(match("[i]", letter, ignoreCase = true))
            assertTrue(match("[a-z]", letter, ignoreCase = true))
            assertTrue(match("[\\x60-\\x70]", letter, ignoreCase = true))
            assertFalse(match("[^i]", letter, ignoreCase = true))
            assertFalse(match("[^a-z]", letter, ignoreCase = true))
            // 内联标志与作用域
            assertTrue(match("(?i)i", letter))
            assertTrue(match("(?i:i)", letter))
            // 量词作用在展开后的整体上
            assertTrue(match("i{2}", letter + letter, ignoreCase = true))
        }
        // 没有忽略大小写时不展开
        assertFalse(match("i", dotted))
        assertFalse(match("[a-z]", dotted))
        assertEquals("i", PyRegex.translate("i"))
        assertEquals("[a-z]", PyRegex.translate("[a-z]"))
        // 范围没覆盖到 i 时不追加
        assertEquals("[a-c]", PyRegex.translate("[a-c]", ignoreCase = true))
        // 忽略大小写时不走多字符折叠（ICU 的默认行为），结果与 Python 实测相同
        val sharpS = 0xDF.toChar().toString()
        val capitalSharpS = 0x1E9E.toChar().toString()
        val ligatureFi = 0xFB01.toChar().toString()
        val ligatureSt = 0xFB06.toChar().toString()
        val longSt = 0xFB05.toChar().toString()
        assertFalse(match("ss", sharpS, ignoreCase = true))
        assertFalse(match(sharpS, "ss", ignoreCase = true))
        // 这两对 Python 和手机上的 ICU 都认，桌面 JDK 的折叠不认。手机才是运行环境，所以只在设备上断言
        if (System.getProperty("java.vm.name") == "Dalvik") {
            assertTrue(match(sharpS, capitalSharpS, ignoreCase = true))
            assertTrue(match(longSt, ligatureSt, ignoreCase = true))
        }
        assertFalse(match("fi", ligatureFi, ignoreCase = true))
        assertFalse(match("FI", ligatureFi, ignoreCase = true))
        assertFalse(match("st", ligatureSt, ignoreCase = true))
        assertFalse(match("\\x73\\x73", sharpS, ignoreCase = true))
        assertTrue(match("ss", "SS", ignoreCase = true))
        assertTrue(match("ss", "ſſ", ignoreCase = true))
        // 作用域：(?-i:…) 里不展开，(?i:…) 结束后恢复
        assertFalse(match("(?-i:i)", dotted, ignoreCase = true))
        assertTrue(match("(?i:a)b", "Ab"))
        assertFalse(match("(?i:a)b", "AB"))
        assertFalse(match("(?i:a)i", "A$dotted"))
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
        assertTrue(find("a.b", "a\u2028b"))
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
        assertEquals(listOf("a", "b", "c", "d"), " a\u00a0b\u3000c\u001cd ".pySplit())
        assertEquals("x", "\u00a0 x \u3000".pyStrip())
        assertEquals("", " \u2007 ".pyStrip())
        assertEquals(emptyList<String>(), "".pySplit())
        assertFalse(isPySpace('\u200b'.code))
        assertEquals(">q", "\u00a0>q".pyLstrip())
    }

    @Test
    fun splitLinesFollowsPython() {
        assertEquals(listOf("a", "b", "", "c"), "a\r\nb\n\nc\n".pySplitLines())
        assertEquals(emptyList<String>(), "".pySplitLines())
        assertEquals(listOf("x", "y"), "x\u000by".pySplitLines())
        assertEquals(listOf(""), "\n".pySplitLines())
        assertEquals(listOf("a", "b"), "a\u2028b".pySplitLines())
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
            "1\u20093", "\u3000 42\u00a0", "١٢٣", "", ".", "1.2.3", "0x10", "12e",
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
    fun reprFloatMatchesPython() {
        // 期望值来自 Python 3.13 的 repr(x)
        val cases = listOf(
            0.0 to "0.0", -0.0 to "-0.0", 1.0 to "1.0", -1.5 to "-1.5", 0.1 to "0.1", 0.0001 to "0.0001",
            0.00001 to "1e-05", 0.000123 to "0.000123", 1e15 to "1000000000000000.0", 1e16 to "1e+16",
            1.5e16 to "1.5e+16", 123456789012345680.0 to "1.2345678901234568e+17",
            1234567890123456.0 to "1234567890123456.0", 1700000000.5 to "1700000000.5",
            1730773800.123456 to "1730773800.123456", 1e22 to "1e+22", 1e-7 to "1e-07",
            1.7976931348623157e308 to "1.7976931348623157e+308", 2.5 to "2.5", 100.0 to "100.0",
            9999999999999998.0 to "9999999999999998.0", Double.POSITIVE_INFINITY to "inf",
            Double.NEGATIVE_INFINITY to "-inf", Double.NaN to "nan",
        )
        cases.forEach { (value, python) -> assertEquals(python, Py.reprFloat(value)) }
    }

    @Test
    fun formatGMatchesPython() {
        // 期望值来自 Python 3.13 的 format(x, "g")
        val cases = listOf(
            100000.0 to "100000", 180000.0 to "180000", 99999.99 to "100000", 123450.5 to "123450",
            1234565.0 to "1.23456e+06", 1e-05 to "1e-05", 0.0000125 to "1.25e-05", 1e16 to "1e+16",
            12345678.0 to "1.23457e+07", 0.0001 to "0.0001", 0.00001234 to "1.234e-05", -0.0 to "-0",
            0.0 to "0", 1.5 to "1.5", 9216.0 to "9216", 250.5 to "250.5", 200.0 to "200", 1299.5 to "1299.5",
            7.2 to "7.2", 0.1 to "0.1", 1e100 to "1e+100", 123456.5 to "123456", 999999.5 to "1e+06",
            -1234.5 to "-1234.5", 5e-324 to "4.94066e-324", 1.7976931348623157e308 to "1.79769e+308",
            Double.NaN to "nan", Double.POSITIVE_INFINITY to "inf", Double.NEGATIVE_INFINITY to "-inf",
        )
        cases.forEach { (value, python) -> assertEquals("$value", python, Py.formatG(value)) }
    }

    @Test
    fun formatPercentMatchesPython() {
        val cases = listOf(
            0.755 to "76%", 0.745 to "74%", 0.5 to "50%", 0.85 to "85%", 0.75 to "75%", 0.0 to "0%",
            1.0 to "100%", 0.123 to "12%", 0.005 to "0%", 0.015 to "2%", 0.025 to "2%",
        )
        cases.forEach { (value, python) -> assertEquals("$value", python, Py.formatPercent(value)) }
        assertEquals("75.5%", Py.formatPercent(0.755, 1))
        assertEquals("74.5%", Py.formatPercent(0.745, 1))
    }

    @Test
    fun floatAcceptsAtMostOneSign() {
        listOf("+-inf", "+-nan", "-+inf", "--1").forEach { assertNull(it, Py.float(it)) }
        assertEquals(1.0, Py.float("+1")!!, 0.0)
    }

    @Test
    fun truthyHandlesBigNumbers() {
        assertTrue(Py.truthy(Py.int("18446744073709551616")))
        assertTrue(Py.truthy(java.math.BigDecimal("0.5")))
        assertFalse(Py.truthy(java.math.BigInteger.ZERO))
    }

    @Test
    fun roundKeepsNegativeZero() {
        assertEquals("-0.0", Py.round(-0.001, 2).toString())
        assertEquals("-0.0", Py.round(-0.0, 2).toString())
        assertEquals("0.0", Py.round(0.0, 2).toString())
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
