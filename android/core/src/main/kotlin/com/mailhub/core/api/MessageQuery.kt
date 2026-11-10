package com.mailhub.core.api

import com.mailhub.core.billing.Billing
import com.mailhub.core.text.Py
import com.mailhub.core.text.cpLen
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * 邮件的筛选与游标编码，逐函数对应 `app.py` 的 `_escape_like` / `_fts_phrase` /
 * `_fts_searchable` / `_message_filter` / `_encode_cursor` / `_decode_cursor`。
 */

/**
 * trigram 分词器至少要 3 个字符才能做子串匹配，更短的关键词仍用 LIKE。
 * 对应 `app.py` 的 `FTS_MIN_QUERY_LEN`。
 */
private const val FTS_MIN_QUERY_LEN = 3

/** SQLite INTEGER 是带符号 64 位；比它大的游标 id 一律当作无效游标。 */
private const val MAX_ID_BITS = 63

/**
 * 把 LIKE 的通配符转义成普通字符，对应 `_escape_like`：搜索 `%` 时不会匹配到所有邮件。
 *
 * 反斜杠必须**先**转义，否则后面补的转义符会被二次转义。
 */
internal fun escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * 把关键词包成 FTS5 短语，对应 `_fts_phrase`：两端加双引号、内部的双引号写成两个。
 *
 * 用户输入的 `*` `%` `_` `(` `OR` 之类不会被当成 FTS 查询语法。
 */
internal fun ftsPhrase(query: String): String = "\"" + query.replace("\"", "\"\"") + "\""

/**
 * 关键词能否交给三元组索引，对应 `_fts_searchable`：够长、索引可用、且不含 FTS5 短语
 * 解析不了的 NUL。长度按**码点**算（Python 的 `len` 也按码点）。
 */
internal fun ftsSearchable(query: String, ftsAvailable: Boolean): Boolean =
    query.cpLen() >= FTS_MIN_QUERY_LEN && !query.contains('\u0000') && ftsAvailable

/** `_message_filter` 的结果：`WHERE` 片段与它的占位符参数。 */
internal class MessageFilter(val sql: String, val args: List<Any?>)

/**
 * 收件箱筛选条件，对应 `_message_filter`；列表和「选择全部」共用，保证两者选中的是同一批邮件。
 *
 * `bill_tier` 不在 [Billing.TIERS] 里时忽略（相当于没有这个筛选）；`unread` 只有 0 / 1 才算
 * 筛选，其余值（默认 -1）表示不限。搜索在 [ftsAvailable] 为假时回落到 LIKE，与服务器一致。
 */
internal fun messageFilter(
    category: String,
    account: Long,
    unread: Long,
    query: String,
    billTier: String,
    ftsAvailable: Boolean,
): MessageFilter {
    val where = mutableListOf("1=1")
    val args = mutableListOf<Any?>()
    if (category.isNotEmpty()) {
        where.add("m.category=?")
        args.add(category)
    }
    if (billTier in Billing.TIERS) {
        where.add("m.bill_tier=?")
        args.add(billTier)
    }
    if (account != 0L) {
        where.add("m.account_id=?")
        args.add(account)
    }
    if (unread == 0L || unread == 1L) {
        where.add("m.unread=?")
        args.add(unread)
    }
    if (query.isNotEmpty()) {
        if (ftsSearchable(query, ftsAvailable)) {
            // 三元组索引做子串匹配，等价于下面 4 个字段的 LIKE，但用得上索引；
            // trigram 对 ASCII 字母不区分大小写，和 LIKE 的行为一致
            where.add("m.id IN (SELECT rowid FROM messages_fts WHERE messages_fts MATCH ?)")
            args.add(ftsPhrase(query))
        } else {
            where.add(
                "(m.subject LIKE ? ESCAPE '\\' OR m.sender_addr LIKE ? ESCAPE '\\'" +
                    " OR m.sender_name LIKE ? ESCAPE '\\' OR m.snippet LIKE ? ESCAPE '\\')",
            )
            val like = "%${escapeLike(query)}%"
            args.addAll(listOf(like, like, like, like))
        }
    }
    // 回收站内容不出现在收件箱
    where.add("m.deleted_ts=0")
    return MessageFilter(where.joinToString(" AND "), args)
}

/**
 * 把 (date_ts, id) 编成不透明游标，对应 `_encode_cursor`。
 *
 * 用 Python 的 `repr` 写 float（[Py.reprFloat]）才能精确往返；客户端把游标当十进制字符串
 * 转一手会丢精度，所以这里不让它们碰数字。编码是 urlsafe base64、去掉尾部填充。
 */
internal fun encodeCursor(dateTs: Double, msgId: Long): String {
    val raw = Py.reprFloat(dateTs) + "|" + msgId.toString()
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
}

/**
 * 解开 [encodeCursor] 生成的游标，对应 `_decode_cursor`；不合法时抛 400。
 *
 * 与 Python 的差别只有一处：超出 64 位整数范围的 id 在这里直接判为无效游标，服务器会
 * 在绑定 SQLite 参数时抛 `OverflowError` 变成 500。两者都是错误，客户端不该发出这种游标。
 */
internal fun decodeCursor(token: String): Pair<Double, Long> {
    val padded = token + "=".repeat((4 - token.length % 4) % 4)
    val bytes = try {
        Base64.getUrlDecoder().decode(padded)
    } catch (e: IllegalArgumentException) {
        throw invalidCursor()
    }
    val raw = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw invalidCursor()
    }
    // Python 的 str.partition("|")：没有分隔符时整串都是第一段，后两段是空串
    val separator = raw.indexOf('|')
    val tsText = if (separator < 0) raw else raw.substring(0, separator)
    val idText = if (separator < 0) "" else raw.substring(separator + 1)
    val dateTs = Py.float(tsText) ?: throw invalidCursor()
    val msgId = Py.int(idText) ?: throw invalidCursor()
    // id 必须为正；NaN 会让比较全部失效，一并拒绝
    if (msgId.signum() <= 0 || dateTs.isNaN() || msgId.bitLength() > MAX_ID_BITS) {
        throw invalidCursor()
    }
    return dateTs to msgId.toLong()
}

private fun invalidCursor(): ApiException = ApiException(400, "无效的分页游标")
