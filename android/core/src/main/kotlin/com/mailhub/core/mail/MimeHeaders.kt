package com.mailhub.core.mail

/**
 * 一条邮件头字段。名字保留原文（Python 端是 `line[:i]`，不做 strip、不做大小写归一），
 * 值已经按 compat32 策略的取值语义处理过：折行原样保留，8 位字节换成 `\ufffd`。
 */
data class HeaderField(val name: String, val value: String)

/**
 * 有序的邮件头块，对应 `email.message_from_bytes(raw)`（默认 compat32 策略）的头部视图。
 *
 * 对应关系：
 * - [parse] = `BytesFeedParser.feed` + `FeedParser._parsegen` 收集头部那一段 + [`_parse_headers`]；
 * - [get] = `Message.get(name)`；同名多条时取第一条，名字比较大小写不敏感；
 * - [getAll] = `Message.get_all(name)`。
 *
 * 值的语义见 [headerSourceParse]：折行（含 CRLF 和续行的空白）原样保留；
 * 头部字节不是合法 ASCII 时，Python 先用 `ascii` + `surrogateescape` 解码成代理字符，
 * 再在取值时（`policy._sanitize_header` → `Header.__str__`）把每个 8 位字节换成 `\ufffd`，
 * 这里在解析阶段就做了这一步，所以结果与 `str(msg.get(name))` 相同。
 */
class MimeHeaders(val fields: List<HeaderField>) {

    /** 对应 `Message.get(name)`：没有这个头返回 null。 */
    fun get(name: String): String? {
        val key = name.lowercase()
        return fields.firstOrNull { it.name.lowercase() == key }?.value
    }

    /** 对应 `Message.get_all(name)`：没有这个头返回空列表（Python 返回 None 或 failobj）。 */
    fun getAll(name: String): List<String> {
        val key = name.lowercase()
        return fields.filter { it.name.lowercase() == key }.map { it.value }
    }

    companion object {

        /**
         * 把原始字节解析成头部块。
         *
         * 与 Python 一致的几个细节：
         * - 只有 `\n` 断行（`StringIO.readlines()` 在默认 newline 下不认单独的 `\r`）；
         * - 头部在第一个既不是 `From ` 开头、又不匹配 `^[\041-\071\073-\176]*:`、也不以空格或制表符
         *   开头的行结束（`email.feedparser.headerRE`）；
         * - 首行是续行、缺头名（冒号在第 0 列）、位置异常的 `From ` 行都按缺陷丢弃，后续头部继续解析。
         */
        fun parse(raw: ByteArray): MimeHeaders {
            val text = decodeAsciiDisplay(raw)
            val lines = splitLines(text)
            val headerLines = ArrayList<String>()
            for (line in lines) {
                if (!matchesHeaderLine(line)) break
                headerLines += line
            }
            return MimeHeaders(parseHeaderLines(headerLines))
        }

        /**
         * `email.feedparser.FeedParser._parse_headers` 的移植：把头部行折成 (名字, 值)。
         */
        private fun parseHeaderLines(lines: List<String>): List<HeaderField> {
            val fields = ArrayList<HeaderField>()
            var lastName: String? = null
            var lastLines: MutableList<String>? = null

            fun flush() {
                val name = lastName ?: return
                fields += HeaderField(name, headerSourceParse(lastLines!!))
                lastName = null
                lastLines = null
            }

            for ((index, line) in lines.withIndex()) {
                val first = line[0]
                if (first == ' ' || first == '\t') {
                    // 首行就是续行：Python 记一条缺陷后丢弃这一行
                    if (lastName == null) continue
                    lastLines!!.add(line)
                    continue
                }
                flush()
                if (line.startsWith("From ")) {
                    // 首行的 unix-from 是信件正文前面的信封行；末行更可能是正文的第一行
                    if (index == 0) continue
                    if (index == lines.size - 1) return fields
                    continue
                }
                val colon = line.indexOf(':')
                // headerRE 已经保证 i > 0（i == 0 的情况是缺陷，丢弃）
                if (colon <= 0) continue
                lastName = line.substring(0, colon)
                lastLines = mutableListOf(line)
            }
            // 最后一条头（Python 在循环结束后就地收尾）
            if (lastName != null) fields += HeaderField(lastName!!, headerSourceParse(lastLines!!))
            return fields
        }

        /**
         * `Compat32.header_source_parse` 的移植：名字是第一个冒号之前的全部内容；
         * 值是「第一行冒号之后的内容 + 所有续行」拼起来，先 `lstrip(' \t\r\n')` 再 `rstrip('\r\n')`。
         * 折行内部的 CRLF 与续行空白都保留，所以 `get` 拿到的值与 Python 逐字符相同。
         */
        private fun headerSourceParse(sourceLines: List<String>): String {
            val first = sourceLines[0]
            val colon = first.indexOf(':')
            val builder = StringBuilder(first.substring(colon + 1))
            for (index in 1 until sourceLines.size) builder.append(sourceLines[index])
            return builder.toString().trimStart(' ', '\t', '\r', '\n').trimEnd('\r', '\n')
        }

        /** `email.feedparser.headerRE`：`^(From |[\041-\071\073-\176]*:|[\t ])`。 */
        private fun matchesHeaderLine(line: String): Boolean {
            if (line.isEmpty()) return false
            if (line.startsWith("From ")) return true
            if (line[0] == ' ' || line[0] == '\t') return true
            var index = 0
            while (index < line.length) {
                val ch = line[index]
                val fieldText = ch in '\u0021'..'\u0039' || ch in '\u003B'..'\u007E'
                if (!fieldText) break
                index++
            }
            return index < line.length && line[index] == ':'
        }

        /** 与 `StringIO.readlines()` 相同：只在 `\n` 处断行并保留行尾；末尾没有换行的残行也算一行。 */
        private fun splitLines(text: String): List<String> {
            val lines = ArrayList<String>()
            var start = 0
            var index = 0
            while (index < text.length) {
                if (text[index] == '\n') {
                    lines += text.substring(start, index + 1)
                    start = index + 1
                }
                index++
            }
            if (start < text.length) lines += text.substring(start)
            return lines
        }

        /**
         * Python 的 `data.decode('ascii', 'surrogateescape')` 之后再取值时
         * （`Header.__str__` 的 `encode('ascii', 'surrogateescape').decode('ascii', 'replace')`）
         * 等价于把每个 8 位字节直接换成 `\ufffd`，其余字节原样。
         */
        private fun decodeAsciiDisplay(raw: ByteArray): String {
            val out = StringBuilder(raw.size)
            for (byte in raw) {
                val value = byte.toInt() and 0xFF
                if (value < 0x80) out.append(value.toChar()) else out.append('\uFFFD')
            }
            return out.toString()
        }
    }
}
