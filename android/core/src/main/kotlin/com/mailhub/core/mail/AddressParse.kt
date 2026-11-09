package com.mailhub.core.mail

import com.mailhub.core.text.pyStrip

/**
 * 地址解析的嵌套层数超过上限。Python 在同类输入上抛 `RecursionError`（`Exception` 的子类，
 * `parse_message` 的 `except Exception` 能接住并走 `unparsable_message()`）；这里也抛普通的
 * `Exception`，而不是 JVM 的 `StackOverflowError`（`Error`，`catch (Exception)` 拦不住）。
 *
 * 上限见 `AddrlistParser.MAX_DEPTH`（300 层）。
 */
class AddressRecursionException(message: String) : Exception(message)

/**
 * `parseaddr(msg.get(name))` 的移植：`msg.get` 在头部含 8 位字节时返回 `Header` 对象，
 * 而 `parseaddr` 对非 str 直接返回 `("", "")`。对应 [HeaderField.has8Bit]。
 *
 * 用于 `From` / `Reply-To` 等直接喂给 `parseaddr` 的字段；`To` / `Cc` / `Delivered-To`
 * 走的是 `getaddresses`，它在 Python 里先对每个值做 `str(v)`，所以仍用 [getAddresses]。
 */
fun parseAddrHeader(field: HeaderField?): Pair<String, String> {
    if (field == null || field.has8Bit) return "" to ""
    return parseAddr(field.value)
}

/**
 * `email.utils.parseaddr` / `email.utils.getaddresses` 的移植。
 *
 * Python 3.13 里这两个函数仍然走 `email._parseaddr.AddrlistClass`（rfc822 时代的状态机），
 * 不是 `email.headerregistry` 的新解析器。所以这里照抄那套状态机，
 * 连同 `email.utils` 里那层「严格模式」校验（括号配对、域名里的 `[`、地址个数）一起搬过来：
 * 解析失败时 Python 返回 `('', '')`，[parseAddr] 也一样。
 */
fun parseAddr(addr: String?): Pair<String, String> {
    // Python：不是 str（含 None）就返回 ("", "")
    if (addr == null) return "" to ""
    val checked = preParseValidation(listOf(addr))[0]
    val addresses = postParseValidation(parseAddressList(checked))
    if (addresses.isEmpty() || addresses.size > 1) return "" to ""
    return addresses[0]
}

/**
 * `email.utils.getaddresses(fieldvalues)` 的移植（`strict=True`）。
 *
 * 严格模式下解析出来的地址个数必须与「`1 + 逗号个数`」相符（引号里的逗号不算），
 * 否则整组返回 `[('', '')]`，避免把畸形的头拆成两个地址。
 */
fun getAddresses(values: List<String>): List<Pair<String, String>> {
    val checked = preParseValidation(values)
    val result = postParseValidation(parseAddressList(checked.joinToString(", ")))
    var expected = 0
    for (value in checked) {
        // 显示名里的逗号不是分隔符，先按引号切掉
        expected += 1 + stripQuotedRealnames(value).count { it == ',' }
    }
    if (result.size != expected) return listOf("" to "")
    return result
}

/** `email._parseaddr.AddressList(field).addresslist`。 */
internal fun parseAddressList(field: String): List<Pair<String, String>> =
    if (field.isEmpty()) emptyList() else AddrlistParser(field).getAddrList()

/** `email.utils._pre_parse_validation`：括号不配对时整段换成 `('', '')` 让解析结果必然为空。 */
private fun preParseValidation(values: List<String>): List<String> =
    values.map { if (checkParenthesis(it)) it else "('', '')" }

/** `email.utils._post_parse_validation`：域名里出现 `[` 说明域名限制没解析成功，判为失败。 */
private fun postParseValidation(addresses: List<Pair<String, String>>): List<Pair<String, String>> =
    addresses.map { (name, addr) -> if ('[' in addr) "" to "" else name to addr }

/** `email.utils._iter_escaped_chars`：逐个吐字符，反斜杠转义的两个字符算一个单元。 */
private fun iterEscapedChars(addr: String): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    var escape = false
    var pos = 0
    for ((index, ch) in addr.withIndex()) {
        pos = index
        if (escape) {
            out += index to ("\\" + ch)
            escape = false
        } else if (ch == '\\') {
            escape = true
        } else {
            out += index to ch.toString()
        }
    }
    if (escape) out += pos to "\\"
    return out
}

/** `email.utils._strip_quoted_realnames`：去掉双引号里的显示名。 */
private fun stripQuotedRealnames(addr: String): String {
    if ('"' !in addr) return addr
    var start = 0
    var openPos: Int? = null
    val result = StringBuilder()
    for ((pos, ch) in iterEscapedChars(addr)) {
        if (ch == "\"") {
            if (openPos == null) {
                openPos = pos
            } else {
                if (start != openPos) result.append(addr.substring(start, openPos!!))
                start = pos + 1
                openPos = null
            }
        }
    }
    if (start < addr.length) result.append(addr.substring(start))
    return result.toString()
}

/** `email.utils._check_parenthesis`：忽略引号里的括号后，括号必须配对。 */
private fun checkParenthesis(addr: String): Boolean {
    val stripped = stripQuotedRealnames(addr)
    var opens = 0
    for ((_, ch) in iterEscapedChars(stripped)) {
        if (ch == "(") {
            opens++
        } else if (ch == ")") {
            opens--
            if (opens < 0) return false
        }
    }
    return opens == 0
}

/**
 * `email._parseaddr.AddrlistClass` 的状态机。所有下标与长度按码点计（Python 的 str 索引）。
 */
private class AddrlistParser(field: String) {

    private val chars: IntArray = field.codePoints().toArray()
    private val length: Int get() = chars.size
    private var pos = 0
    private var commentList = ArrayList<String>()

    /** 当前递归层数：`getAddress`（组地址嵌套）与 `getDelimited`（嵌套注释）都算一层。 */
    private var depth = 0

    /**
     * 进入一层递归。超过 [MAX_DEPTH] 时抛 [AddressRecursionException]（普通 `Exception`），
     * 对应 Python 的 `RecursionError`。
     */
    private inline fun <T> withDepth(block: () -> T): T {
        if (depth >= MAX_DEPTH) {
            throw AddressRecursionException("地址解析嵌套超过 $MAX_DEPTH 层")
        }
        depth++
        try {
            return block()
        } finally {
            depth--
        }
    }

    fun getAddrList(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        while (pos < length) {
            val addresses = getAddress()
            if (addresses.isEmpty()) result += "" to "" else result += addresses
        }
        return result
    }

    /** `gotonext`：跳过空白并顺手把注释收进 commentList。 */
    private fun gotonext(): String {
        val whitespace = StringBuilder()
        while (pos < length) {
            val ch = chars[pos]
            if (isLws(ch) || isCr(ch)) {
                if (!isCr(ch)) whitespace.appendCodePoint(ch)
                pos++
            } else if (ch == '('.code) {
                commentList += getComment()
            } else {
                break
            }
        }
        return whitespace.toString()
    }

    private fun getAddress(): List<Pair<String, String>> = withDepth {
        commentList = ArrayList()
        gotonext()
        val oldPos = pos
        // Python 的 `oldcl = self.commentlist` 是别名，不是拷贝：后面解析 addr-spec 时
        // 追加的注释会同时出现在 oldcl 里（`a(c1)@b.example` 因此得到 `c1 c1`）。
        val oldComments = commentList
        val phraseList = getPhraseList()
        gotonext()
        var result: List<Pair<String, String>> = emptyList()

        if (pos >= length) {
            // 没有域名，理论上不是合法地址，但 Python 会把短语当成地址
            if (phraseList.isNotEmpty()) result = listOf(commentList.joinToString(" ") to phraseList[0])
        } else if (chars[pos] == '.'.code || chars[pos] == '@'.code) {
            // 整段就是一个 addr-spec：回到起点按 addr-spec 重新解析
            pos = oldPos
            commentList = oldComments
            val addrspec = getAddrSpec()
            result = listOf(commentList.joinToString(" ") to addrspec)
        } else if (chars[pos] == ':'.code) {
            // 组地址：`组名: 地址, 地址;`
            result = emptyList()
            val fieldLength = length
            pos++
            while (pos < length) {
                gotonext()
                if (pos < fieldLength && chars[pos] == ';'.code) {
                    pos++
                    break
                }
                result = result + getAddress()
            }
        } else if (chars[pos] == '<'.code) {
            val routeAddr = getRouteAddr()
            result = if (commentList.isNotEmpty()) {
                listOf((phraseList.joinToString(" ") + " (" + commentList.joinToString(" ") + ")") to routeAddr)
            } else {
                listOf(phraseList.joinToString(" ") to routeAddr)
            }
        } else {
            if (phraseList.isNotEmpty()) {
                result = listOf(commentList.joinToString(" ") to phraseList[0])
            } else if (isSpecial(chars[pos])) {
                pos++
            }
        }
        gotonext()
        if (pos < length && chars[pos] == ','.code) pos++
        result
    }

    /** `getrouteaddr`：跳过路由信息（`<@a,@b:user@host>`），只要 addr-spec。 */
    private fun getRouteAddr(): String {
        if (pos >= length || chars[pos] != '<'.code) return ""
        var expectRoute = false
        pos++
        gotonext()
        var address = ""
        while (pos < length) {
            if (expectRoute) {
                getDomain()
                expectRoute = false
            } else if (chars[pos] == '>'.code) {
                pos++
                break
            } else if (chars[pos] == '@'.code) {
                pos++
                expectRoute = true
            } else if (chars[pos] == ':'.code) {
                pos++
            } else {
                address = getAddrSpec()
                pos++
                break
            }
            gotonext()
        }
        return address
    }

    private fun getAddrSpec(): String {
        val parts = ArrayList<String>()
        gotonext()
        while (pos < length) {
            var preserveWhitespace = true
            val ch = chars[pos]
            when {
                ch == '.'.code -> {
                    if (parts.isNotEmpty() && parts.last().trimmedEmpty()) parts.removeAt(parts.size - 1)
                    parts += "."
                    pos++
                    preserveWhitespace = false
                }
                ch == '"'.code -> parts += "\"" + quote(getQuote()) + "\""
                isAtomEnd(ch) -> {
                    if (parts.isNotEmpty() && parts.last().trimmedEmpty()) parts.removeAt(parts.size - 1)
                    break
                }
                else -> parts += getAtom(null)
            }
            val whitespace = gotonext()
            if (preserveWhitespace && whitespace.isNotEmpty()) parts += whitespace
        }
        if (pos >= length || chars[pos] != '@'.code) return parts.joinToString("")
        parts += "@"
        pos++
        gotonext()
        val domain = getDomain()
        // 域名解析失败时返回空串，而不是只返回 local part
        if (domain.isEmpty()) return ""
        return parts.joinToString("") + domain
    }

    private fun getDomain(): String {
        val parts = ArrayList<String>()
        while (pos < length) {
            val ch = chars[pos]
            when {
                isLws(ch) -> pos++
                ch == '('.code -> commentList += getComment()
                ch == '['.code -> parts += getDomainLiteral()
                ch == '.'.code -> {
                    pos++
                    parts += "."
                }
                // bpo-34155：`a@malicious.org@important.com` 这种两个 @ 的域名不解析
                ch == '@'.code -> return ""
                isAtomEnd(ch) -> break
                else -> parts += getAtom(null)
            }
        }
        return parts.joinToString("")
    }

    private fun getDelimited(beginChar: Char, endChars: String, allowComments: Boolean): String = withDepth {
        if (pos >= length || chars[pos] != beginChar.code) return@withDepth ""
        val parts = ArrayList<String>()
        var quoted = false
        pos++
        while (pos < length) {
            val ch = chars[pos]
            if (quoted) {
                parts += String(Character.toChars(ch))
                quoted = false
            } else if (ch < 0x10000 && endChars.indexOf(ch.toChar()) >= 0) {
                pos++
                break
            } else if (allowComments && ch == '('.code) {
                parts += getComment()
                continue // getcomment 已经推进过 pos
            } else if (ch == '\\'.code) {
                quoted = true
            } else {
                parts += String(Character.toChars(ch))
            }
            pos++
        }
        parts.joinToString("")
    }

    private fun getQuote(): String = getDelimited('"', "\"\r", allowComments = false)

    private fun getComment(): String = getDelimited('(', ")\r", allowComments = true)

    private fun getDomainLiteral(): String = "[" + getDelimited('[', "]\r", allowComments = false) + "]"

    private fun getAtom(atomEnds: ((Int) -> Boolean)?): String {
        val builder = StringBuilder()
        val isEnd = atomEnds ?: { ch: Int -> isAtomEnd(ch) }
        while (pos < length) {
            if (isEnd(chars[pos])) break
            builder.appendCodePoint(chars[pos])
            pos++
        }
        return builder.toString()
    }

    private fun getPhraseList(): List<String> {
        val parts = ArrayList<String>()
        while (pos < length) {
            val ch = chars[pos]
            when {
                isLws(ch) || isCr(ch) -> pos++
                ch == '"'.code -> parts += getQuote()
                ch == '('.code -> commentList += getComment()
                isPhraseEnd(ch) -> break
                else -> parts += getAtom { candidate -> isPhraseEnd(candidate) }
            }
        }
        return parts
    }

    /** Python `aslist[-1].strip()` 为假，即这段只含空白。 */
    private fun String.trimmedEmpty(): Boolean = this.pyStrip().isEmpty()

    private companion object {
        /**
         * 递归层数上限。Python 的默认递归上限是 1000 帧，实测约 450 层嵌套注释、
         * 500–1000 层嵌套组地址就会抛 `RecursionError`；精确值取决于进程状态，无法也不必逐帧对齐。
         * 这里取 300：正常邮件的嵌套层数远小于它，而几万层的攻击输入会立即变成普通异常，
         * 不会触发 JVM 的 `StackOverflowError`。
         */
        const val MAX_DEPTH = 300

        /** `self.specials`。 */
        const val SPECIALS = "()<>@,:;.\"[]"

        /** `self.LWS`。 */
        fun isLws(ch: Int): Boolean = ch == ' '.code || ch == '\t'.code

        /** `self.CR`：`\r\n`。 */
        fun isCr(ch: Int): Boolean = ch == '\r'.code || ch == '\n'.code

        fun isSpecial(ch: Int): Boolean = ch < 0x80 && SPECIALS.indexOf(ch.toChar()) >= 0

        /** `self.atomends` = specials + LWS + CR。 */
        fun isAtomEnd(ch: Int): Boolean = isSpecial(ch) || isLws(ch) || isCr(ch)

        /** `self.phraseends` = atomends 去掉 `.`（RFC 2822 允许句点出现在短语里）。 */
        fun isPhraseEnd(ch: Int): Boolean = isAtomEnd(ch) && ch != '.'.code

        /** `email._parseaddr.quote`：只在带引号的字符串里需要转义 `\` 和 `"`。 */
        fun quote(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
