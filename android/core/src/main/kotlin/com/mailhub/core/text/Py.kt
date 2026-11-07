package com.mailhub.core.text

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 移植 Python 代码时用的语义工具：正则、按码点切片、真值判断、浮点格式化都走这里，
 * 保证与 Python 3 的行为一致。为什么需要这些，见仓库根目录 LOCAL_MODE.md 第四节。
 */
object PyRegex {

    /**
     * Python 3 的 str 正则默认按 Unicode 匹配：`\w` `\d` `\b` `\s` 都认 Unicode（`\d` 连全角数字也算）。
     * Java 要靠 UNICODE_CHARACTER_CLASS 才一样。Python 里只有 `\n` 算换行，`.` 能匹配 `\r` 和 ` `；
     * Java 默认把这些都当换行，要靠 UNIX_LINES 才一样。
     */
    private const val BASE_FLAGS = Pattern.UNICODE_CHARACTER_CLASS or Pattern.UNIX_LINES

    /**
     * 编译从 Python 源码照抄过来的正则。参数对应 Python 的 `re.I`、`re.M`、`re.S`。
     * 忽略大小写时按 Unicode 折叠（UNICODE_CHARACTER_CLASS 已隐含 UNICODE_CASE），
     * 所以 `ſ` 能匹配 `s`、开尔文符号能匹配 `k`，与 Python 相同。
     * 模式先经过 [translate]，把 Python 能写、Java 写法不同的语法换掉。
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
     * 把 Python 正则语法改写成语义相同的 Java 语法：
     * - `(?P<name>…)` 改成 `(?<name>…)`，`(?P=name)` 改成 `\k<name>`。组名本身是合法 Java 组名
     *   （字母开头、只含字母数字）时原样保留，移植代码可以照旧写 `group("pre")`；否则（例如含下划线）
     *   换成 `pyg<序号>` 并保持引用一致；
     * - `\Z`（Python：字符串末尾）改成 Java 的 `\z`；
     * - `{,n}` 改成 `{0,n}`；不构成量词的 `{` 按字面量转义；
     * - 字符类里的 `[` 和 `&&` 在 Python 是字面量，在 Java 是嵌套类和交集，转义掉。
     *
     * 只处理上面这些，其余原样保留。
     */
    fun translate(pattern: String): String {
        val out = StringBuilder(pattern.length + 8)
        val groupNames = HashMap<String, String>()
        var inClass = false
        var classStart = -1
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    out.append(if (inClass) escapeInClass(next) else escape(next))
                    i += 2
                    continue
                }
                inClass -> {
                    // `[]…]` 与 `[^]…]` 开头的 ] 是字面量
                    val atClassHead = i == classStart || (i == classStart + 1 && pattern[classStart] == '^')
                    when {
                        c == ']' && atClassHead -> out.append("\\]")
                        c == ']' -> { inClass = false; out.append(c) }
                        c == '[' -> out.append("\\[")
                        c == '&' && i + 1 < pattern.length && pattern[i + 1] == '&' -> { out.append("&\\&"); i += 2; continue }
                        else -> out.append(c)
                    }
                }
                c == '[' -> { inClass = true; classStart = i + 1; out.append(c) }
                c == '(' && pattern.startsWith("(?P<", i) -> {
                    val close = pattern.indexOf('>', i + 4)
                    if (close < 0) { out.append(c); i++; continue }
                    val name = pattern.substring(i + 4, close)
                    val javaName = groupNames.getOrPut(name) {
                        if (JAVA_GROUP_NAME.matcher(name).matches()) name else "pyg${groupNames.size + 1}"
                    }
                    out.append("(?<").append(javaName).append('>')
                    i = close + 1
                    continue
                }
                c == '(' && pattern.startsWith("(?P=", i) -> {
                    val close = pattern.indexOf(')', i + 4)
                    if (close < 0) { out.append(c); i++; continue }
                    val name = pattern.substring(i + 4, close)
                    // 引用未定义的组名：原样留给 Java 报错，调用方按非法正则处理
                    val javaName = groupNames[name] ?: name
                    out.append("\\k<").append(javaName).append('>')
                    i = close + 1
                    continue
                }
                c == '{' -> {
                    val m = QUANTIFIER.matcher(pattern).region(i, pattern.length)
                    if (m.lookingAt() && (m.group(1).isNotEmpty() || m.group(3)?.isNotEmpty() == true)) {
                        val min = m.group(1).ifEmpty { "0" }
                        out.append('{').append(min)
                        if (m.group(2) != null) out.append(',').append(m.group(3))
                        out.append('}')
                        i = m.end()
                        continue
                    }
                    out.append("\\{")
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * Python 的 `re.escape(s)`：只给正则特殊字符加反斜杠，结果可以安全拼进 [compile] 的模式里。
     * 不要用 `Pattern.quote`：`\Q…\E` 里的内容会被 [translate] 当成普通模式改写。
     */
    fun escape(text: String): String {
        val out = StringBuilder(text.length * 2)
        for (ch in text) {
            if (ch in SPECIAL) out.append('\\')
            out.append(ch)
        }
        return out.toString()
    }

    /** Python 3.7+ `re.escape` 会转义的字符。 */
    private const val SPECIAL = "()[]{}?*+-|^$\\.&~# \t\n\r\u000b\u000c"

    /** Python 认可的量词：`{m}`、`{m,}`、`{,n}`、`{m,n}`；至少要有一个数字。 */
    private val QUANTIFIER: Pattern = Pattern.compile("\\{(\\d*)(,(\\d*))?\\}")

    private val JAVA_GROUP_NAME: Pattern = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*")

    /**
     * Python 的 `\w`：`str.isalnum()` 或下划线，即字母（L*）和带数值的字符（N*，含 ½、①）。
     * Java 的 `\w` 还把组合附加符（Mn/Mc/Me，例如 emoji 后面的 U+FE0F）、连接标点和 ZWJ 算进去，
     * 会让 `\boffer\b` 在「🔥️offer」里失配，所以 `\w`、`\b` 都按这个集合重写。
     */
    private const val WORD = "\\p{L}\\p{N}_"

    /** Python 的 `\s`：与 [isPySpace] 相同的集合（比 Java 多出 \x1c–\x1f）。 */
    private const val SPACE =
        "\\t-\\r\\x1c-\\x20\\x85\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000"

    private const val BOUNDARY = "(?:(?<=[$WORD])(?![$WORD])|(?<![$WORD])(?=[$WORD]))"
    private const val NOT_BOUNDARY = "(?:(?<=[$WORD])(?=[$WORD])|(?<![$WORD])(?![$WORD]))"

    /** 字符类外的转义。 */
    private fun escape(next: Char): String = when (next) {
        'Z' -> "\\z"
        'w' -> "[$WORD]"
        'W' -> "[^$WORD]"
        's' -> "[$SPACE]"
        'S' -> "[^$SPACE]"
        'b' -> BOUNDARY
        'B' -> NOT_BOUNDARY
        else -> "\\$next"
    }

    /**
     * 字符类内的转义。`\w`、`\s` 展开成集合成员；Python 在字符类里的 `\b` 是退格符。
     * 字符类里的 `\W`、`\S` 无法在不改写整个类的情况下精确表达，保留 Java 语义（移植代码里没有用到）。
     */
    private fun escapeInClass(next: Char): String = when (next) {
        'w' -> WORD
        's' -> SPACE
        'b' -> "\\x08"
        else -> "\\$next"
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
 * ` `（&nbsp;）、` `、` `、`\u0085` 和 `\u001c`–`\u001f`，邮件 HTML 里 &nbsp; 很常见，不能混用。
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

/**
 * Python 的 `s.splitlines()`：换行符是 \n \r \r\n \v \f \x1c \x1d \x1e \x85    （实测），
 * 不保留换行符，末尾的换行不产生空行，空串得到空列表。Kotlin 的 `lines()` 只认 \n \r \r\n，不能混用。
 */
fun String.pySplitLines(): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        val isBreak = when (c) {
            '\n', '\u000b', '\u000c', '\r', '\u001c', '\u001d', '\u001e', '\u0085', ' ', ' ' -> true
            else -> false
        }
        if (isBreak) {
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
     * 认 inf / infinity / nan（不分大小写）；不认十六进制和中间的空白。
     */
    fun float(s: String): Double? {
        val t = asciiNumber(s.pyStrip(), allowFraction = true) ?: return null
        val lower = t.lowercase()
        val body = lower.removePrefix("+").removePrefix("-")
        if (body == "inf" || body == "infinity") return if (lower.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        if (body == "nan") return Double.NaN
        if (!FLOAT_SYNTAX.matcher(t).matches()) return null
        return t.toDouble()
    }

    /** Python 的 `int(s)`（十进制），失败返回 null。规则同 [float]，但不认小数点和指数。 */
    fun int(s: String): java.math.BigInteger? {
        val t = asciiNumber(s.pyStrip(), allowFraction = false) ?: return null
        if (!INT_SYNTAX.matcher(t).matches()) return null
        return java.math.BigInteger(t.removePrefix("+"))
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

    private val FLOAT_SYNTAX: Pattern = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    private val INT_SYNTAX: Pattern = Pattern.compile("[+-]?\\d+")

    /**
     * Python 的 `round(value, digits)`（digits ≥ 0）：对二进制精确值四舍六入五成双，再取最接近的 double。
     */
    fun round(value: Double, digits: Int): Double {
        if (value.isNaN() || value.isInfinite()) return value
        return BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
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
