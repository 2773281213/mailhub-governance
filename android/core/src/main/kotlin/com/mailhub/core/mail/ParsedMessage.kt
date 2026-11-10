package com.mailhub.core.mail

import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import com.mailhub.core.text.cpLen
import com.mailhub.core.text.cpSlice
import java.util.TimeZone

/**
 * `mail_client.parse_message(raw_bytes)` 的完整结果，字段与 Python 返回的字典键一一对应。
 *
 * 头部字段的语义见 [ParsedHeaders]（L5a）；本任务补上正文与附件相关的字段：
 *
 * @property attachNames 附件文件名（`Content-Disposition` 含 `attachment` 的叶子 part），
 *   RFC 2231 解码后再走 `_decode_header` 的 RFC 2047 解码，截断到 120 码点
 * @property dateTs `parsedate_to_datetime(...).timestamp()`；解析不出日期时是当前时间
 * @property snippet [makeSnippet] 的结果：正文空白折成一个空格后取前 200 码点
 * @property bodyText `tidy_body(readable_body(拼起来的 text/plain, 拼起来的 text/html))`
 * @property bodyHtml 拼起来的 text/html 正文，最后截断到 300000 码点
 * @property hasAttach 是否出现过 `Content-Disposition` 含 `attachment` 的 part，1 / 0
 */
data class ParsedMessage(
    /** `(msg.get("Message-ID") or "").strip()`；头部含 8 位字节时整封不可解析 */
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
    /** 附件文件名列表，逐条截断到 120 码点 */
    val attachNames: List<String>,
    /** `parsedate_to_datetime(...).timestamp()`，解析不出时取当前时间 */
    val dateTs: Double,
    /** 正文摘要，最多 200 码点 */
    val snippet: String,
    /** 供分类使用的正文 */
    val bodyText: String,
    /** 原始 HTML 正文，最多 300000 码点 */
    val bodyHtml: String,
    /** 1 表示有附件，0 表示没有 */
    val hasAttach: Int,
    /** 是否有 List-Unsubscribe 头，1 / 0 */
    val unsubscribe: Int,
    /** [headerSignals] 的结果 */
    val headerSignals: Map<String, Boolean>,
    /** 第一条 Authentication-Results（收件服务器写的），截断到 2000 码点 */
    val authResults: String,
)

/**
 * `mail_client.parse_message(raw_bytes)` 的移植。
 *
 * 头部字段复用 L5a 的 [parseHeaders]，正文与附件按 [MimeStructureParser] 解析出的 MIME 树
 * 深度优先遍历（与 Python 的 `msg.walk()` 相同：先序、跳过 multipart / message 容器本身）：
 *
 * - `Content-Disposition` 的**原始头字符串**里含 `attachment`（大小写敏感的 `in` 判断）就是附件，
 *   只取文件名，不读取附件内容；
 * - 其余 part 按 `Content-Transfer-Encoding` 解出字节（base64 / quoted-printable / 原样），
 *   再用 [PyCodecs] 按 charset（缺省 utf-8）解码，`errors="replace"`；charset 不认识时跳过这一部分
 *   （对应 Python 的 `LookupError` 被 `except: continue` 吃掉）；
 * - 累加条件是「追加**之前**长度小于上限」（纯文本 60000、HTML 300000 码点），所以结果可能超限，
 *   照搬 Python，不做修正。
 *
 * **异常处理**：Python 的 `parse_message` 在 `Message-ID` 含 8 位字节（[UnparsableMessageException]）、
 * 地址解析嵌套过深（[AddressRecursionException]）、MIME 嵌套超过上限（[MimeLimitException]）等情况下
 * 会抛异常，调用方 `ImapSession._collect` 用 `unparsable_message()` 兜底。这里直接在同一层兜底：
 * 捕获 [Exception]（**不捕获 `Throwable`**，栈溢出之类的 `Error` 不会被悄悄吞掉），返回
 * [unparsableMessage]。这样 `parseMessage` 永远不抛异常。
 *
 * @param raw 邮件的原始字节（整封）
 * @param clock 缺失或非法日期时的回退时间，对应 Python 的 `time.time()`；null 表示用系统时钟
 * @param zone 没有时区的日期按哪个时区换算；默认 [TimeZone.getDefault]，测试里显式指定
 */
fun parseMessage(
    raw: ByteArray,
    clock: Clock? = null,
    zone: TimeZone = TimeZone.getDefault(),
): ParsedMessage = try {
    parseMessageOrThrow(raw, clock, zone)
} catch (_: Exception) {
    unparsableMessage(clock)
}

/** 解析失败时的占位记录，对应 `mail_client.unparsable_message()`：邮件仍然入库，用户能看到提示。 */
fun unparsableMessage(clock: Clock? = null): ParsedMessage = ParsedMessage(
    msgId = "",
    subject = "（无法解析的邮件）",
    senderName = "",
    senderAddr = "",
    toAddr = "",
    replyTo = "",
    attachNames = emptyList(),
    dateTs = (clock ?: SystemClock).now(),
    snippet = UNPARSABLE_TEXT,
    bodyText = UNPARSABLE_TEXT,
    bodyHtml = "",
    hasAttach = 0,
    unsubscribe = 0,
    headerSignals = emptyMap(),
    authResults = "",
)

private const val UNPARSABLE_TEXT = "MailHub 无法解析这封邮件，请在邮箱网页版查看原文。"

private fun parseMessageOrThrow(raw: ByteArray, clock: Clock?, zone: TimeZone): ParsedMessage {
    val headers = parseHeaders(raw, clock, zone)

    // 用 StringBuilder + 码点计数代替不断拼接的 String：Python 的 len() 是 O(1)、`+=` 也是就地扩展，
    // 照搬成字符串拼接会在 part 很多时退化成平方级。
    val bodyText = StringBuilder()
    val bodyHtml = StringBuilder()
    var bodyTextLength = 0
    var bodyHtmlLength = 0
    var hasAttach = 0
    val attachNames = ArrayList<String>()

    fun visit(part: MimePart) {
        val children = part.children
        if (children != null) {
            // Python：`if part.is_multipart(): continue`，容器本身不贡献正文
            for (child in children) visit(child)
            return
        }
        // 附件判断用原始头字符串做子串判断，大小写敏感（`"attachment" in dispo`）
        val disposition = part.headers.get("Content-Disposition") ?: ""
        val contentType = part.contentType
        if ("attachment" in disposition) {
            hasAttach = 1
            // 只取文件名做静态风险判定，绝不读取或落盘附件内容
            val name = try {
                decodeHeader(MimeParams.filename(part.headers))
            } catch (_: Exception) {
                ""
            }
            if (name.isNotEmpty()) attachNames += name.cpSlice(0, 120)
            return
        }
        val text = try {
            val payload = payloadBytes(part)
            // Python：`part.get_content_charset() or "utf-8"`——charset="" 与没有 charset 一样按 utf-8 解
            val charset = MimeParams.contentCharset(part.headers).takeUnless { it.isNullOrEmpty() } ?: "utf-8"
            // charset 不认识时 Python 抛 LookupError，被下面的 except 吃掉，跳过这一部分
            PyCodecs.decode(payload, charset) ?: return
        } catch (_: Exception) {
            return
        }
        // 累加条件在追加之前判断：结果可能超过上限，与 Python 一致
        if (contentType == "text/plain" && bodyTextLength < 60000) {
            bodyText.append(text)
            bodyTextLength += text.cpLen()
        } else if (contentType == "text/html" && bodyHtmlLength < 300000) {
            bodyHtml.append(text)
            bodyHtmlLength += text.cpLen()
        }
    }

    visit(MimeStructureParser().parse(raw))

    val tidyText = tidyBody(readableBody(bodyText.toString(), bodyHtml.toString()))
    return ParsedMessage(
        msgId = headers.msgId,
        subject = headers.subject,
        senderName = headers.senderName,
        senderAddr = headers.senderAddr,
        toAddr = headers.toAddr,
        replyTo = headers.replyTo,
        attachNames = attachNames,
        dateTs = headers.dateTs,
        snippet = makeSnippet(tidyText),
        bodyText = tidyText,
        bodyHtml = bodyHtml.toString().cpSlice(0, 300000),
        hasAttach = hasAttach,
        unsubscribe = headers.unsubscribe,
        headerSignals = headers.headerSignals,
        authResults = headers.authResults,
    )
}
