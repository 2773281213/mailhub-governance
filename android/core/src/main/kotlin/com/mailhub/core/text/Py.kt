package com.mailhub.core.text

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 移植 Python 代码时用的语义工具：正则、按码点切片、真值判断、浮点格式化都走这里，
 * 保证与 Python 3.13 的行为一致。为什么需要这些，见仓库根目录 LOCAL_MODE.md 第四节。
 *
 * 本文件里的不可见字符一律用码点数值或 `\x{…}` 写，不要直接粘贴字符。
 */
object PyRegex {

    /**
     * 只用桌面 JDK 和 Android 都支持的标志位。
     *
     * - Android 的 `java.util.regex` 基于 ICU，**不支持 UNICODE_CHARACTER_CLASS**，传了会直接抛
     *   IllegalArgumentException；
     * - 桌面 JDK 不带这个标志时又按 ASCII 理解 `\d \w \s \b`。
     *
     * 所以这些简写一律由 [translate] 改写成显式的 Unicode 集合，两边结果相同。
     * UNIX_LINES：Python 只把 `\n` 当换行，`.` 能匹配 `\r` 和 U+2028。
     */
    private const val BASE_FLAGS = Pattern.UNIX_LINES

    /**
     * 编译从 Python 源码照抄过来的正则。参数对应 Python 的 `re.I`、`re.M`、`re.S`。
     * 忽略大小写时按 Unicode 折叠（`ſ` 能匹配 `s`、开尔文符号能匹配 `k`），与 Python 3.13 实测一致。
     * 模式先经过 [translate]；Python 会拒绝的模式在这里同样抛 [PatternSyntaxException]。
     */
    fun compile(
        pattern: String,
        ignoreCase: Boolean = false,
        multiline: Boolean = false,
        dotAll: Boolean = false,
    ): Pattern {
        var flags = BASE_FLAGS
        if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if (multiline) flags = flags or Pattern.MULTILINE
        if (dotAll) flags = flags or Pattern.DOTALL
        return Pattern.compile(translate(pattern), flags)
    }

    /**
     * 编译用户自定义规则里的正则（Python 端是运行时 `re.search(pattern, target, re.I)`）。
     * Python 会抛 `re.error` 的模式，或转换后 Java 仍不认的模式，返回 null，由调用方跳过这条规则。
     */
    fun compileUser(pattern: String, ignoreCase: Boolean = false): Pattern? = try {
        compile(pattern, ignoreCase = ignoreCase)
    } catch (_: PatternSyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * 把 Python 3.13 的正则语法改写成在桌面 JDK 和 Android ICU 上语义都相同的写法：
     * - `\d \D \w \W \s \S \b \B` 展开成 Python 定义的显式集合（见 [WORD]、[SPACE]）；
     * - `\Z` 改成 `\z`；`\v` 是 U+000B；`\a`、`\0`、八进制、`\x`、`\u`、`\U` 统一改成 `\x{…}`；
     * - `(?P<name>…)` 改成 `(?<name>…)`，`(?P=name)` 改成 `\k<name>`。组名本身是合法 Java 组名
     *   （ASCII 字母开头、只含 ASCII 字母数字）时原样保留，移植代码可以照旧写 `group("pre")`；否则换成
     *   不与其他组名冲突的 `pyg<序号>`；
     * - `(?#…)` 注释删掉；
     * - `{,n}` 改成 `{0,n}`；不构成量词的 `{`、字符类外的 `}` 和 `]` 按字面量转义；
     * - 字符类里的 `[`、`&`、空格按字面量转义（Java 和 ICU 把它们当成嵌套、交集或忽略的空白）；
     * - 字符类里的 `\W \S \D` 用嵌套集合表达。
     *
     * Python 会报错的写法同样抛 [PatternSyntaxException]：
     * - 不认识的字母转义，如 `\p`、`\e`；
     * - `\x` / `\u` / `\U` 位数不够；
     * - 结尾的单个 `\`；
     * - `\b+` 这类给零宽断言加量词；
     * - 字符类里以简写为端点的范围，如 `[\w-~]`；
     * - 非法或重复的组名、裸 `(?<name>…)`；
     * - 没结束的注释。
     *
     * 不支持 `\N{名字}`（直接报错，规则会被跳过）。
     */
    fun translate(pattern: String): String = Translator(pattern).run()

    /**
     * Python 的 `re.escape(s)`：只给正则特殊字符加反斜杠，结果可以安全拼进 [compile] 的模式里。
     * 不要用 `Pattern.quote`：`\Q…\E` 里的内容会被 [translate] 当成普通模式改写。
     */
    fun escape(text: String): String {
        val out = StringBuilder(text.length * 2)
        for (ch in text) {
            if (ch in SPECIAL || ch.code == 0x0b || ch.code == 0x0c) out.append('\\')
            out.append(ch)
        }
        return out.toString()
    }

    /** Python 3.7+ `re.escape` 会转义的字符（另加 \v 和 \f，见 [escape]）。 */
    private const val SPECIAL = "()[]{}?*+-|^$\\.&~# \t\n\r"

    /** Python 认可的量词：`{m}`、`{m,}`、`{,n}`、`{m,n}`，只认 ASCII 数字，至少要有一个数字。 */
    private val QUANTIFIER: Pattern = Pattern.compile("\\{([0-9]*)(,([0-9]*))?\\}")

    private val JAVA_GROUP_NAME: Pattern = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*")

    private val PY_GROUP_DEF: Pattern = Pattern.compile("\\(\\?P<([^>]*)>")

    /**
     * Python 的 `\w`：`str.isalnum()` 或下划线，即字母（L*）和带数值的字符（N*，含 ½、①）。
     * Java 和 ICU 的 `\w` 还把组合附加符（Mn/Mc/Me，例如 emoji 后面的 U+FE0F）、连接标点和 ZWJ 算进去，
     * 会让 `\boffer\b` 在「🔥\ufe0foffer」里失配，所以 `\w`、`\b` 都按这个集合重写。
     * 下划线放在最前面，避免和后面的 `-` 拼成范围。
     */
    private const val WORD = "_\\p{L}\\p{N}"

    /** Python 的 `\s`：与 [isPySpace] 相同的集合（比 Java 多出 \x1c–\x1f）。 */
    private const val SPACE =
        "\\x{9}-\\x{d}\\x{1c}-\\x{20}\\x{85}\\x{a0}\\x{1680}\\x{2000}-\\x{200a}\\x{2028}\\x{2029}\\x{202f}\\x{205f}\\x{3000}"

    /** Python 的 `\d`：Unicode 十进制数字（Nd）。 */
    private const val DIGIT = "\\p{Nd}"
    private const val NOT_DIGIT = "\\P{Nd}"

    /**
     * 位于码点边界：开头，或前面紧挨着一个完整码点。
     * 桌面 JDK 处理空匹配时会停在高低代理之间，Python 按码点处理，不存在这种位置。实测在这个位置上，
     * 针对孤立代理的字符类前后查看都不会命中，只有 `.` 的后顾能区分（它从代理对中间往回读到的是整对，
     * 结束位置对不上），所以用它判断。ICU 本来就按码点处理，这一条在 Android 上恒为真。
     */
    private const val AT_CODE_POINT = "(?:\\A|(?<=(?s:.)))"

    /**
     * 「前一个码点是单词字符」。桌面 JDK 的单字符后顾只往回看一个 UTF-16 单元，看不到辅助平面字符
     * （例如 CJK 扩展 B 的汉字），写成 `{1,2}` 才能把代理对当成一个码点匹配上。语义仍然是
     * 「前一个码点是单词字符」：前一个码点不是单词字符时，一个和两个都匹配不到当前位置。
     */
    private const val PREV_WORD = "(?<=[$WORD]{1,2})"
    private const val PREV_NOT_WORD = "(?<![$WORD]{1,2})"

    private const val BOUNDARY =
        "$AT_CODE_POINT(?:$PREV_WORD(?![$WORD])|$PREV_NOT_WORD(?=[$WORD]))"

    /** Python 3.13 的 `\B` 在空字符串上不匹配（3.14 才改），所以排除「既是开头又是结尾」的位置。 */
    private const val NOT_BOUNDARY =
        "$AT_CODE_POINT(?!\\A\\z)(?:$PREV_WORD(?=[$WORD])|$PREV_NOT_WORD(?![$WORD]))"

    private class Translator(private val p: String) {
        private val out = StringBuilder(p.length + 16)
        private var i = 0
        private var inClass = false
        private var classStart = -1
        /** 字符类里上一个成员是简写集合（\w \s \d 及其否定）。 */
        private var prevShorthand = false
        /** 字符类里刚读到一个范围符 `-`，下一个成员是范围的终点。 */
        private var rangePending = false
        private val groupNames = HashMap<String, String>()
        private val usedJavaNames = HashSet<String>()
        private var nextPyg = 1

        init {
            // 先收集所有合法 Java 组名，自动取名时避开它们
            val m = PY_GROUP_DEF.matcher(p)
            while (m.find()) {
                val name = m.group(1)
                if (JAVA_GROUP_NAME.matcher(name).matches()) usedJavaNames += name
            }
        }

        fun run(): String {
            while (i < p.length) {
                if (inClass) classChar() else outsideChar()
            }
            if (inClass) fail("字符类没有结束")
            return out.toString()
        }

        private fun fail(reason: String): Nothing = throw PatternSyntaxException(reason, p, i)

        private fun outsideChar() {
            val c = p[i]
            when {
                c == '\\' -> outsideEscape()
                c == '[' -> {
                    inClass = true
                    classStart = i + 1
                    prevShorthand = false
                    rangePending = false
                    out.append('[')
                    i++
                }
                c == '(' && p.startsWith("(?#", i) -> {
                    val close = p.indexOf(')', i + 3)
                    if (close < 0) fail("注释没有结束")
                    i = close + 1
                }
                c == '(' && p.startsWith("(?P<", i) -> groupDefinition()
                c == '(' && p.startsWith("(?P=", i) -> groupReference()
                c == '(' && p.startsWith("(?<", i) && !p.startsWith("(?<=", i) && !p.startsWith("(?<!", i) ->
                    fail("Python 不支持 (?<name>…)")
                c == '{' -> brace()
                c == '}' -> { out.append("\\}"); i++ }
                c == ']' -> { out.append("\\]"); i++ }
                else -> { out.append(c); i++ }
            }
        }

        private fun brace() {
            val m = QUANTIFIER.matcher(p).region(i, p.length)
            if (m.lookingAt() && (m.group(1).isNotEmpty() || m.group(3)?.isNotEmpty() == true)) {
                out.append('{').append(m.group(1).ifEmpty { "0" })
                if (m.group(2) != null) out.append(',').append(m.group(3))
                out.append('}')
                i = m.end()
            } else {
                out.append("\\{")
                i++
            }
        }

        /** 当前位置（零宽断言之后）是否紧跟量词。 */
        private fun quantifierFollows(at: Int): Boolean {
            if (at >= p.length) return false
            val c = p[at]
            if (c == '*' || c == '+' || c == '?') return true
            if (c != '{') return false
            val m = QUANTIFIER.matcher(p).region(at, p.length)
            return m.lookingAt() && (m.group(1).isNotEmpty() || m.group(3)?.isNotEmpty() == true)
        }

        private fun outsideEscape() {
            if (i + 1 >= p.length) fail("模式以单个反斜杠结尾")
            val n = p[i + 1]
            i += 2
            when (n) {
                'A' -> out.append("\\A")
                'Z' -> out.append("\\z")
                'b', 'B' -> {
                    if (quantifierFollows(i)) fail("零宽断言后面不能接量词")
                    out.append(if (n == 'b') BOUNDARY else NOT_BOUNDARY)
                }
                'd' -> out.append(DIGIT)
                'D' -> out.append(NOT_DIGIT)
                'w' -> out.append("[$WORD]")
                'W' -> out.append("[^$WORD]")
                's' -> out.append("[$SPACE]")
                'S' -> out.append("[^$SPACE]")
                in '0'..'9' -> outsideNumericEscape(n)
                else -> simpleEscape(n)
            }
        }

        /** `\0` 与八进制是字符；`\1`–`\99` 是反向引用（加括号，防止后面的数字被 Java 吞进组号）。 */
        private fun outsideNumericEscape(first: Char) {
            if (first == '0') {
                var value = 0
                var count = 0
                while (count < 2 && i < p.length && p[i] in '0'..'7') {
                    value = value * 8 + (p[i] - '0')
                    i++
                    count++
                }
                appendCodePoint(value)
                return
            }
            // 三位八进制（首位 1–3 时可能）按字符处理
            if (i + 1 < p.length && first in '0'..'7' && p[i] in '0'..'7' && p[i + 1] in '0'..'7') {
                val value = (first - '0') * 64 + (p[i] - '0') * 8 + (p[i + 1] - '0')
                if (value > 0xff) fail("八进制转义超出范围")
                appendCodePoint(value)
                i += 2
                return
            }
            val digits = StringBuilder().append(first)
            if (i < p.length && p[i] in '0'..'9') {
                digits.append(p[i])
                i++
            }
            out.append("(?:\\").append(digits).append(')')
        }

        /** 字母和其他字符的单字符转义（字符类内外通用）。 */
        private fun simpleEscape(n: Char, inClassContext: Boolean = false) {
            when (n) {
                'a' -> appendCodePoint(0x07)
                'f' -> appendCodePoint(0x0c)
                'n' -> appendCodePoint(0x0a)
                'r' -> appendCodePoint(0x0d)
                't' -> appendCodePoint(0x09)
                'v' -> appendCodePoint(0x0b)
                'x' -> hexEscape(2)
                'u' -> hexEscape(4)
                'U' -> hexEscape(8)
                'N' -> fail("不支持 \\N{名字}")
                else -> {
                    if (n in 'a'..'z' || n in 'A'..'Z') fail("Python 不认识的转义 \\$n")
                    if (inClassContext && n in '0'..'9') fail("Python 不认识的转义 \\$n")
                    // 非字母数字字符：按字面量
                    out.append('\\').append(n)
                }
            }
        }

        private fun hexEscape(width: Int) {
            if (i + width > p.length) fail("十六进制转义位数不够")
            val hex = p.substring(i, i + width)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("十六进制转义位数不够")
            val value = hex.toLong(16)
            if (value > 0x10FFFF) fail("码点超出范围")
            appendCodePoint(value.toInt())
            i += width
        }

        private fun appendCodePoint(cp: Int) {
            out.append("\\x{").append(Integer.toHexString(cp)).append('}')
        }

        private fun groupDefinition() {
            val close = p.indexOf('>', i + 4)
            if (close < 0) fail("组名没有结束")
            val name = p.substring(i + 4, close)
            if (!isPythonIdentifier(name)) fail("组名不是合法标识符")
            if (name in groupNames) fail("组名重复定义")
            val javaName = if (JAVA_GROUP_NAME.matcher(name).matches()) {
                name
            } else {
                var candidate: String
                do {
                    candidate = "pyg${nextPyg++}"
                } while (candidate in usedJavaNames)
                usedJavaNames += candidate
                candidate
            }
            groupNames[name] = javaName
            out.append("(?<").append(javaName).append('>')
            i = close + 1
        }

        private fun groupReference() {
            val close = p.indexOf(')', i + 4)
            if (close < 0) fail("组引用没有结束")
            val name = p.substring(i + 4, close)
            val javaName = groupNames[name] ?: fail("引用了未定义的组名")
            out.append("\\k<").append(javaName).append('>')
            i = close + 1
        }

        private fun classChar() {
            val c = p[i]
            val atHead = i == classStart || (i == classStart + 1 && p[classStart] == '^')
            when {
                c == '\\' -> {
                    if (i + 1 >= p.length) fail("模式以单个反斜杠结尾")
                    val n = p[i + 1]
                    i += 2
                    classEscape(n)
                    return
                }
                c == ']' && atHead -> { out.append("\\]"); memberDone(false); i++ }
                c == ']' -> {
                    if (rangePending) fail("字符类范围不完整")
                    inClass = false
                    out.append(']')
                    i++
                }
                c == '^' && i == classStart -> { out.append('^'); i++ }
                c == '-' && !atHead && i + 1 < p.length && p[i + 1] != ']' && !rangePending -> {
                    if (prevShorthand) fail("字符类范围的端点不能是简写集合")
                    rangePending = true
                    out.append('-')
                    i++
                }
                c == '-' -> { out.append("\\-"); memberDone(false); i++ }
                c == '[' -> { out.append("\\["); memberDone(false); i++ }
                c == '&' -> { out.append("\\&"); memberDone(false); i++ }
                c == ' ' -> { out.append("\\x{20}"); memberDone(false); i++ }
                else -> { out.append(c); memberDone(false); i++ }
            }
        }

        private fun memberDone(shorthand: Boolean) {
            if (shorthand && rangePending) fail("字符类范围的端点不能是简写集合")
            rangePending = false
            prevShorthand = shorthand
        }

        private fun classEscape(n: Char) {
            when (n) {
                'd' -> { out.append(DIGIT); memberDone(true) }
                'D' -> { out.append(NOT_DIGIT); memberDone(true) }
                'w' -> { out.append(WORD); memberDone(true) }
                'W' -> { out.append("[^$WORD]"); memberDone(true) }
                's' -> { out.append(SPACE); memberDone(true) }
                'S' -> { out.append("[^$SPACE]"); memberDone(true) }
                'b' -> { appendCodePoint(0x08); memberDone(false) }
                in '0'..'7' -> {
                    // 字符类里的数字转义都是八进制，最多三位
                    var value = n - '0'
                    var count = 1
                    while (count < 3 && i < p.length && p[i] in '0'..'7') {
                        value = value * 8 + (p[i] - '0')
                        i++
                        count++
                    }
                    if (value > 0xff) fail("八进制转义超出范围")
                    appendCodePoint(value)
                    memberDone(false)
                }
                else -> { simpleEscape(n, inClassContext = true); memberDone(false) }
            }
        }

        /** Python 的 `str.isidentifier()`：首字符是字母或下划线，其余是字母、数字、下划线或组合符。 */
        private fun isPythonIdentifier(name: String): Boolean {
            if (name.isEmpty()) return false
            var idx = 0
            var first = true
            while (idx < name.length) {
                val cp = name.codePointAt(idx)
                val ok = if (first) {
                    cp == '_'.code || Character.isUnicodeIdentifierStart(cp)
                } else {
                    cp == '_'.code || Character.isUnicodeIdentifierPart(cp) && !Character.isIdentifierIgnorable(cp)
                }
                if (!ok) return false
                first = false
                idx += Character.charCount(cp)
            }
            return true
        }
    }
}

/** 按码点计的长度，对应 Python 的 `len(s)`。 */
fun String.cpLen(): Int = codePointCount(0, length)

/** 把 UTF-16 下标（例如 `Matcher.start()`）换成码点下标，对应 Python 的 `m.start()`。 */
fun String.cpIndex(utf16Index: Int): Int = codePointCount(0, utf16Index)

/** 把码点下标换成 UTF-16 下标。 */
fun String.utf16Index(cpIndex: Int): Int = offsetByCodePoints(0, cpIndex)

/**
 * Python 的 `s[start:end]`，按码点计。null 表示省略；负数从末尾数；越界自动截断；end ≤ start 时返回空串。
 */
fun String.cpSlice(start: Int? = null, end: Int? = null): String {
    val n = cpLen()
    fun norm(i: Int?, default: Int): Int = when {
        i == null -> default
        i < 0 -> maxOf(0, i + n)
        else -> minOf(i, n)
    }
    val from = norm(start, 0)
    val to = norm(end, n)
    if (to <= from) return ""
    return substring(utf16Index(from), utf16Index(to))
}

/**
 * Python 的 `str.isspace()` 认的空白（Python 3.13 / Unicode 15.1 实测）。比 Java 的 `isWhitespace` 多出
 * U+00A0（&nbsp;）、U+2007、U+202F、U+0085 和 U+001C–U+001F。邮件 HTML 里 &nbsp; 很常见，不能混用。
 */
fun isPySpace(codePoint: Int): Boolean = when (codePoint) {
    0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x85, 0xa0, 0x1680,
    in 0x2000..0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000 -> true
    else -> false
}

/** Python 的 `s.split()`（不带参数）：按 [isPySpace] 切分，丢掉空段。 */
fun String.pySplit(): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        if (isPySpace(cp)) {
            if (current.isNotEmpty()) { out += current.toString(); current.setLength(0) }
        } else {
            current.appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

/** Python `str.splitlines()` 认的换行符（实测）：\n \v \f \r \x1c \x1d \x1e \x85 U+2028 U+2029。 */
private fun isPyLineBreak(code: Int): Boolean = when (code) {
    0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x85, 0x2028, 0x2029 -> true
    else -> false
}

/**
 * Python 的 `s.splitlines()`：换行符见 [isPyLineBreak]，`\r\n` 算一个；不保留换行符，末尾的换行不产生空行，
 * 空串得到空列表。Kotlin 的 `lines()` 只认 \n \r \r\n，不能混用。
 */
fun String.pySplitLines(): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (isPyLineBreak(c.code)) {
            out += substring(start, i)
            i += if (c == '\r' && i + 1 < length && this[i + 1] == '\n') 2 else 1
            start = i
        } else {
            i++
        }
    }
    if (start < length) out += substring(start)
    return out
}

/** Python 的 `s.lstrip()`（不带参数）。 */
fun String.pyLstrip(): String {
    var start = 0
    while (start < length) {
        val cp = codePointAt(start)
        if (!isPySpace(cp)) break
        start += Character.charCount(cp)
    }
    return substring(start)
}

/** Python 的 `s.strip()`（不带参数）：两端去掉 [isPySpace] 空白。 */
fun String.pyStrip(): String {
    var start = 0
    var end = length
    while (start < end) {
        val cp = codePointAt(start)
        if (!isPySpace(cp)) break
        start += Character.charCount(cp)
    }
    while (end > start) {
        val cp = codePointBefore(end)
        if (!isPySpace(cp)) break
        end -= Character.charCount(cp)
    }
    return substring(start, end)
}

object Py {

    /** Python 的真值判断：None、False、0、0.0、空串、空集合为假，其余为真（NaN 为真）。 */
    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Double -> value != 0.0
        is Float -> value != 0.0f
        is BigInteger -> value.signum() != 0
        is BigDecimal -> value.signum() != 0
        is Number -> value.toLong() != 0L
        is CharSequence -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        is Array<*> -> value.isNotEmpty()
        else -> true
    }

    /**
     * Python 的 `float(s)`，失败返回 null（对应 ValueError）。与 Kotlin `toDouble()` 的差别：
     * 认任何 Unicode 十进制数字（全角、阿拉伯-印度数字），两端按 [isPySpace] 去空白，数字之间允许单个下划线，
     * 认 inf / infinity / nan（不分大小写，最多一个正负号）；不认十六进制和中间的空白。
     */
    fun float(s: String): Double? {
        val t = asciiNumber(s.pyStrip(), allowFraction = true) ?: return null
        val lower = t.lowercase()
        val negative = lower.startsWith("-")
        val body = if (lower.startsWith("+") || negative) lower.substring(1) else lower
        if (body == "inf" || body == "infinity") return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        if (body == "nan") return Double.NaN
        if (!FLOAT_SYNTAX.matcher(t).matches()) return null
        return t.toDouble()
    }

    /** Python 的 `int(s)`（十进制），失败返回 null。规则同 [float]，但不认小数点和指数。 */
    fun int(s: String): BigInteger? {
        val t = asciiNumber(s.pyStrip(), allowFraction = false) ?: return null
        if (!INT_SYNTAX.matcher(t).matches()) return null
        return BigInteger(t.removePrefix("+"))
    }

    /**
     * 把 Unicode 十进制数字换成 ASCII，并去掉数字之间的单个下划线；下划线不在两个数字之间时返回 null。
     * 其余字符原样保留，交给语法检查。
     */
    private fun asciiNumber(s: String, allowFraction: Boolean): String? {
        val out = StringBuilder(s.length)
        var i = 0
        var prevDigit = false
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val step = Character.charCount(cp)
            when {
                Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() -> {
                    out.append(('0' + Character.digit(cp, 10)))
                    prevDigit = true
                }
                cp == '_'.code -> {
                    val nextIsDigit = i + step < s.length &&
                        Character.getType(s.codePointAt(i + step)) == Character.DECIMAL_DIGIT_NUMBER.toInt()
                    if (!prevDigit || !nextIsDigit) return null
                    prevDigit = false
                }
                else -> {
                    if (!allowFraction && (cp == '.'.code)) return null
                    out.appendCodePoint(cp)
                    prevDigit = false
                }
            }
            i += step
        }
        return out.toString()
    }

    // 只认 ASCII 数字：Android ICU 的 \d 默认包含全角数字
    private val FLOAT_SYNTAX: Pattern = Pattern.compile("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?")
    private val INT_SYNTAX: Pattern = Pattern.compile("[+-]?[0-9]+")

    /**
     * Python 的 `round(value, digits)`（digits ≥ 0）：对二进制精确值四舍六入五成双，再取最接近的 double。
     * 负数舍入成零时保留负零（`round(-0.001, 2)` 是 -0.0）。
     */
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN() || value.isInfinite()) return value
        val rounded = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
        val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
        return if (rounded == 0.0 && negative) -0.0 else rounded
    }

    /**
     * Python 的 `f"{value:.{digits}f}"`：对二进制精确值做四舍六入五成双，所以 0.125 是 "0.12"、0.375 是 "0.38"；
     * 负数舍入成零时保留负号（-0.001 是 "-0.00"）。Java 的 `String.format("%.2f")` 用的是 HALF_UP，不能直接用。
     */
    fun formatFixed(value: Double, digits: Int): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
        val text = BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).abs().toPlainString()
        return if (negative) "-$text" else text
    }
}
