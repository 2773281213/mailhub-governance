package com.mailhub.core.mail

import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import com.mailhub.core.text.cpSlice
import com.mailhub.core.text.pyStrip
import java.util.TimeZone

/**
 * `mail_client.parse_message` 里**只看头部**的那几个字段。
 *
 * 正文、附件（`body_text`、`body_html`、`attach_names`、`has_attach`、`snippet`）属于 L5b，
 * 这里不解析，所以本结构体只包含头部能决定的字段。字段名与 Python 返回的字典键一一对应。
 */
data class ParsedHeaders(
    /** `(msg.get("Message-ID") or "").strip()` */
    val msgId: String,
    /** `_decode_header(msg.get("Subject", ""))` */
    val subject: String,
    /** `_decode_header(parseaddr(msg.get("From", ""))[0])` */
    val senderName: String,
    /** `parseaddr(msg.get("From", ""))[1].lower()` */
    val senderAddr: String,
    /** To + Delivered-To + Cc 合并、小写、去重、按码点排序后用逗号连接，截断到 500 码点 */
    val toAddr: String,
    /** `(parseaddr(msg.get("Reply-To", ""))[1] or "").lower()` */
    val replyTo: String,
    /** `email.utils.parsedate_to_datetime(...).timestamp()`；解析不出日期时取当前时间 */
    val dateTs: Double,
    /** 是否有 List-Unsubscribe 头，1 / 0（对应 Python 的 `1 if msg.get(...) else 0`） */
    val unsubscribe: Int,
    /** 第一条 Authentication-Results（收件服务器写的），截断到 2000 码点 */
    val authResults: String,
    /** [headerSignals] 的结果 */
    val headerSignals: Map<String, Boolean>,
)

/**
 * `mail_client.parse_message(raw_bytes)` 的头部部分。
 *
 * @param raw 邮件的原始字节（整封，含正文；这里只读头部）
 * @param clock 缺失或非法日期时的回退时间，对应 Python 的 `time.time()`；null 表示用系统时钟
 * @param zone 没有时区的日期按哪个时区换算，对应 Python naive datetime 的 `.timestamp()`
 *   （进程本地时区）。默认 [TimeZone.getDefault]，测试里显式指定。
 */
fun parseHeaders(
    raw: ByteArray,
    clock: Clock? = null,
    zone: TimeZone = TimeZone.getDefault(),
): ParsedHeaders {
    val headers = MimeHeaders.parse(raw)

    val subject = decodeHeader(headers.get("Subject") ?: "")

    val (rawName, address) = parseAddr(headers.get("From") ?: "")
    val name = decodeHeader(rawName)

    // 收件地址（含 Delivered-To，用于加号别名匹配）
    val toPairs = getAddresses(
        headers.getAll("To") + headers.getAll("Delivered-To") + headers.getAll("Cc"),
    )
    val toAddr = toPairs.asSequence()
        .map { it.second }
        .filter { it.isNotEmpty() }
        .map { it.lowercase() }
        .distinct()
        .sortedWith(CODE_POINT_ORDER)
        .joinToString(",")
        .cpSlice(0, 500)

    val dateTs = (MailDate.timestamp(headers.get("Date"), zone) ?: 0.0)
        .let { if (it == 0.0) (clock ?: SystemClock).now() else it }

    return ParsedHeaders(
        msgId = (headers.get("Message-ID") ?: "").pyStrip(),
        subject = subject,
        senderName = name,
        senderAddr = address.lowercase(),
        toAddr = toAddr,
        replyTo = parseAddr(headers.get("Reply-To") ?: "").second.lowercase(),
        dateTs = dateTs,
        unsubscribe = if (headers.get("List-Unsubscribe").isNullOrEmpty()) 0 else 1,
        // 只取最上面一条：它由收件服务器写入；更靠下的同名头可能是发件人伪造的
        authResults = (headers.getAll("Authentication-Results").firstOrNull() ?: "").cpSlice(0, 2000),
        headerSignals = headerSignals(headers),
    )
}

/** Python 的字符串排序按码点比较，`String.compareTo` 按 UTF-16 码元，代理区之后会不一致。 */
private val CODE_POINT_ORDER = Comparator<String> { left, right ->
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val a = left.codePointAt(i)
        val b = right.codePointAt(j)
        if (a != b) return@Comparator a.compareTo(b)
        i += Character.charCount(a)
        j += Character.charCount(b)
    }
    (left.length - i).compareTo(right.length - j)
}
