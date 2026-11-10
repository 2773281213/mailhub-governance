package com.mailhub.core.security

import com.mailhub.core.text.PyRegex
import java.text.Normalizer
import java.util.Locale

/** 对应 Python 的 `ValueError`：URL 不合法时由 [PyUrlSplit] 抛出，调用方按需转成空串。 */
internal class PyValueError(message: String) : Exception(message)

/** `urlsplit` 里与主机名相关的两个字段（路径、查询、片段对安全扫描没有用处，不保留）。 */
internal class PyUrlParts(val scheme: String, val netloc: String) {
    /**
     * 对应 `SplitResult.hostname`：取 netloc 里 `@` 之后的部分，方括号优先，冒号之后是端口；
     * 主机名为空时返回 null。返回的是未改大小写的原始主机名（Python 的 `hostname` 只把
     * `%` 之前的部分转小写，`_link_host` 随后又整体转一次小写，两者等价）。
     */
    val hostname: String?
        get() {
            val at = netloc.lastIndexOf('@')
            val hostinfo = netloc.substring(at + 1)
            val host: String
            val open = hostinfo.indexOf('[')
            if (open >= 0) {
                val bracketed = hostinfo.substring(open + 1)
                val close = bracketed.indexOf(']')
                host = if (close >= 0) bracketed.substring(0, close) else bracketed
            } else {
                val colon = hostinfo.indexOf(':')
                host = if (colon >= 0) hostinfo.substring(0, colon) else hostinfo
            }
            return if (host.isEmpty()) null else host
        }
}

/**
 * Python `urllib.parse.urlsplit` 的移植（只保留 scheme 与 netloc）。
 *
 * 不能用 `java.net.URI`：它对不合法字符直接抛异常，也不做 Python 那套
 * 「去掉 C0 控制符、NFKC 校验、IP 字面量校验」的处理。这里照 CPython 3.13
 * 的 `urlsplit`、`_splitnetloc`、`_checknetloc`、`_check_bracketed_netloc`、
 * `_check_bracketed_host` 逐段改写，遇到 Python 会抛 `ValueError` 的情况同样抛 [PyValueError]。
 */
internal object PyUrlSplit {

    /** `urlsplit` 里 scheme 允许的字符。 */
    private const val SCHEME_CHARS =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-."

    /** RFC 3986 的 IPvFuture 写法：`v` + 十六进制版本号 + `.` + 其余任意内容。 */
    private val IPV_FUTURE: java.util.regex.Pattern = PyRegex.compile("\\Av[a-fA-F0-9]+\\..+\\Z")

    /** 对应 `_UNSAFE_URL_BYTES_TO_REMOVE`：WHATWG 规定要去掉的空白。 */
    private val UNSAFE = charArrayOf('\t', '\r', '\n')

    fun split(url: String): PyUrlParts {
        // url.lstrip(_WHATWG_C0_CONTROL_OR_SPACE)：去掉开头的 0x00–0x20
        var rest = url.trimStart { it.code <= 0x20 }
        rest = rest.filter { it !in UNSAFE }

        var scheme = ""
        val colon = rest.indexOf(':')
        if (colon > 0 && rest[0].code < 128 && isAsciiLetter(rest[0])) {
            var valid = true
            for (i in 0 until colon) {
                if (rest[i] !in SCHEME_CHARS) {
                    valid = false
                    break
                }
            }
            if (valid) {
                scheme = rest.substring(0, colon).lowercase(Locale.ROOT)
                rest = rest.substring(colon + 1)
            }
        }

        var netloc = ""
        if (rest.startsWith("//")) {
            // _splitnetloc(url, 2)：域名到最早的 '/?#'
            var delim = rest.length
            for (c in charArrayOf('/', '?', '#')) {
                val found = rest.indexOf(c, 2)
                if (found >= 0 && found < delim) delim = found
            }
            netloc = rest.substring(2, delim)
            if (('[' in netloc && ']' !in netloc) || (']' in netloc && '[' !in netloc)) {
                throw PyValueError("Invalid IPv6 URL")
            }
            if ('[' in netloc && ']' in netloc) checkBracketedNetloc(netloc)
        }
        checkNetloc(netloc)
        return PyUrlParts(scheme, netloc)
    }

    /** 对应 `_checknetloc`：非 ASCII 的 netloc 在 NFKC 归一化后不能多出 `/?#@:`。 */
    private fun checkNetloc(netloc: String) {
        if (netloc.isEmpty()) return
        if (netloc.all { it.code < 128 }) return
        val stripped = netloc.replace("@", "").replace(":", "").replace("#", "").replace("?", "")
        val normalized = Normalizer.normalize(stripped, Normalizer.Form.NFKC)
        if (stripped == normalized) return
        for (c in "/?#@:") {
            if (c in normalized) throw PyValueError("netloc '$netloc' contains invalid characters")
        }
    }

    /** 对应 `_check_bracketed_netloc`：方括号里只能放主机，方括号外只能接 `:端口`。 */
    private fun checkBracketedNetloc(netloc: String) {
        val at = netloc.lastIndexOf('@')
        val hostAndPort = netloc.substring(at + 1)
        val open = hostAndPort.indexOf('[')
        if (open >= 0) {
            if (hostAndPort.substring(0, open).isNotEmpty()) throw PyValueError("Invalid IPv6 URL")
            val bracketed = hostAndPort.substring(open + 1)
            val close = bracketed.indexOf(']')
            val hostname = if (close >= 0) bracketed.substring(0, close) else bracketed
            val port = if (close >= 0) bracketed.substring(close + 1) else ""
            if (port.isNotEmpty() && !port.startsWith(":")) throw PyValueError("Invalid IPv6 URL")
            checkBracketedHost(hostname)
        } else {
            val colon = hostAndPort.indexOf(':')
            val hostname = if (colon >= 0) hostAndPort.substring(0, colon) else hostAndPort
            checkBracketedHost(hostname)
        }
    }

    /**
     * 对应 `_check_bracketed_host`：要么是 IPvFuture，要么是 `ipaddress.ip_address()` 认的地址。
     * Python 对「方括号里放 IPv4」也抛 `ValueError`，本实现同样返回不合法。
     */
    private fun checkBracketedHost(hostname: String) {
        if (hostname.startsWith("v")) {
            if (!IPV_FUTURE.matcher(hostname).matches()) throw PyValueError("IPvFuture 格式不合法")
            return
        }
        if (!isIpv6Literal(hostname)) throw PyValueError("方括号里不是合法的 IPv6 地址")
    }

    /**
     * 对应 `ipaddress.IPv6Address` 的字符串解析（`_split_scope_id` + `_ip_int_from_string`）：
     * 允许 `%zone` 后缀，允许最后一段写 IPv4，`::` 最多一个，每段 1–4 个 ASCII 十六进制数字。
     */
    private fun isIpv6Literal(address: String): Boolean {
        var addr = address
        val pct = address.indexOf('%')
        if (pct >= 0) {
            addr = address.substring(0, pct)
            val zone = address.substring(pct + 1)
            if (zone.isEmpty() || '%' in zone) return false
        }
        if ('/' in addr || addr.isEmpty()) return false

        val parts = addr.split(":").toMutableList()
        if (parts.size < 3) return false
        if ('.' in parts.last()) {
            if (!isIpv4Literal(parts.removeAt(parts.size - 1))) return false
            parts.add("0")
            parts.add("0")
        }
        if (parts.size > 9) return false

        var skipIndex = -1
        for (i in 1 until parts.size - 1) {
            if (parts[i].isEmpty()) {
                if (skipIndex >= 0) return false
                skipIndex = i
            }
        }

        val partsHi: Int
        val partsLo: Int
        if (skipIndex >= 0) {
            var hi = skipIndex
            var lo = parts.size - skipIndex - 1
            if (parts[0].isEmpty()) {
                hi -= 1
                if (hi != 0) return false
            }
            if (parts[parts.size - 1].isEmpty()) {
                lo -= 1
                if (lo != 0) return false
            }
            if (8 - (hi + lo) < 1) return false
            partsHi = hi
            partsLo = lo
        } else {
            if (parts.size != 8) return false
            if (parts[0].isEmpty() || parts[parts.size - 1].isEmpty()) return false
            partsHi = parts.size
            partsLo = 0
        }

        for (i in 0 until partsHi) {
            if (!isHextet(parts[i])) return false
        }
        for (i in parts.size - partsLo until parts.size) {
            if (!isHextet(parts[i])) return false
        }
        return true
    }

    /** 对应 `_parse_hextet`：只认 ASCII 十六进制，最长 4 个字符（空串不合法）。 */
    private fun isHextet(s: String): Boolean {
        if (s.isEmpty() || s.length > 4) return false
        return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /** 对应 `ipaddress.IPv4Address` 的严格写法：四段、不许前导零、每段 0–255。 */
    private fun isIpv4Literal(s: String): Boolean {
        val octets = s.split(".")
        if (octets.size != 4) return false
        for (octet in octets) {
            if (octet.isEmpty() || octet.length > 3) return false
            if (!octet.all { it in '0'..'9' }) return false
            if (octet.length > 1 && octet[0] == '0') return false
            if (octet.toInt() > 255) return false
        }
        return true
    }

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'
}
