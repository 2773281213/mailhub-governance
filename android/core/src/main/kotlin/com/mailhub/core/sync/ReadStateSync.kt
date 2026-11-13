package com.mailhub.core.sync

import com.mailhub.core.db.Db
import com.mailhub.core.db.SettingsSchema
import com.mailhub.core.imap.ImapSession
import com.mailhub.core.text.cpSlice
import java.util.TreeMap
import java.util.logging.Logger

/**
 * 拉取方向每轮只看最近这么多封本地未读邮件，对应 `READ_STATE_PULL_LIMIT`：避免每轮把
 * 整个收件箱的 FLAGS 都取一遍。
 */
const val READ_STATE_PULL_LIMIT: Int = 200

/** 日志里最多带多长的异常说明（不含凭据），对应 Python 的 `str(exc)[:120]`。 */
private const val MAX_LOG_CHARS = 120

/**
 * 「与邮箱服务器同步已读状态」开关是否开启，对应 `sync.read_state_sync_enabled`。
 *
 * 两个方向必须由同一个开关控制：只开拉取时，用户在 MailHub 里标的未读会被服务器的
 * `\Seen` 冲回已读。
 */
internal fun readStateSyncEnabled(db: Db): Boolean =
    SettingsSchema.getBool(db, "sync_read_state")

/**
 * 把本地登记的已读/未读改动写回服务器，对应 `sync._push_read_state`。
 *
 * 成功的邮件只清掉**刚推送的那个**待推送值：推送期间用户若又把同一封邮件改成相反的意图
 * （`flag_push` 从 1 变成 -1），那次新改动必须留着，下一轮再按新意图推送。
 *
 * 只处理 `uid > 0` 的行：UIDVALIDITY 变化后解绑的旧记录在服务器上已经没有 UID。
 * 失败保留 `flag_push`，下一轮同步再试。
 */
internal fun pushReadState(db: Db, session: ImapSession, accountId: Int, log: Logger) {
    val rows = db.query(
        """SELECT id, uid, flag_push FROM messages
           WHERE account_id=? AND flag_push != 0 AND uid > 0 ORDER BY uid""",
        accountId,
    )
    if (rows.isEmpty()) {
        return
    }
    val groups = TreeMap<Int, MutableList<Pair<Long, Long>>>()
    for (row in rows) {
        val flagPush = row.long("flag_push") ?: 0L
        val id = row.long("id") ?: continue
        val uid = row.long("uid") ?: continue
        val key = if (flagPush > 0) 1 else -1
        groups.getOrPut(key) { ArrayList() } += id to uid
    }
    for ((flagPush, items) in groups) {
        try {
            session.markRead(items.map { it.second }, flagPush > 0)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            // 尽力而为：只记日志（不含凭据），标记保留到下一轮重试
            log.warning(
                "账户$accountId 推送已读状态失败：${(e.message ?: "").cpSlice(0, MAX_LOG_CHARS)}",
            )
            continue
        }
        // 对应 Python 的 executemany + commit：只清刚推送的那个值
        db.executeMany(
            "UPDATE messages SET flag_push=0 WHERE id=? AND flag_push=?",
            items.map { arrayOf<Any?>(it.first, flagPush.toLong()) },
        )
    }
}

/**
 * 把服务器上已读的邮件同步为本地已读，对应 `sync._pull_read_state`。
 *
 * 只做「服务器已读 → 本地已读」这一个方向。查询只挑本地未读（`unread=1`）且没有待推送
 * 改动（`flag_push=0`）的邮件，写回时再用 `unread=1 AND flag_push=0` 复核一次，所以查询与
 * 写回之间用户刚标的未读也不会被冲回已读。
 */
internal fun pullReadState(db: Db, session: ImapSession, accountId: Int) {
    val rows = db.query(
        """SELECT id, uid FROM messages
           WHERE account_id=? AND unread=1 AND flag_push=0 AND deleted_ts=0 AND uid > 0
           ORDER BY uid DESC LIMIT ?""",
        accountId,
        READ_STATE_PULL_LIMIT,
    )
    if (rows.isEmpty()) {
        return
    }
    val byUid = LinkedHashMap<Long, Long>(rows.size * 2)
    for (row in rows) {
        val uid = row.long("uid") ?: continue
        val id = row.long("id") ?: continue
        byUid[uid] = id
    }
    val serverFlags = session.fetchFlags(byUid.keys.sorted())
    val seenIds = ArrayList<Long>(serverFlags.size)
    for ((uid, marks) in serverFlags) {
        if (!byUid.containsKey(uid)) {
            continue
        }
        if (hasSeenMark(marks)) {
            byUid[uid]?.let { seenIds += it }
        }
    }
    if (seenIds.isEmpty()) {
        return
    }
    db.executeMany(
        "UPDATE messages SET unread=0 WHERE id=? AND unread=1 AND flag_push=0",
        seenIds.map { arrayOf<Any?>(it) },
    )
}

/**
 * 已读状态双向同步：先推送本地改动，再拉取服务器标记，对应 `sync._sync_read_state`。
 *
 * 两个方向都是尽力而为：任何异常只记日志（不含凭据），不影响本轮新邮件的同步，也不把
 * 账户状态置为 error。
 */
internal fun syncReadState(db: Db, session: ImapSession, accountId: Int, log: Logger) {
    try {
        pushReadState(db, session, accountId, log)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) {
            throw e
        }
        log.warning("账户$accountId 推送已读状态异常：${(e.message ?: "").cpSlice(0, MAX_LOG_CHARS)}")
    }
    try {
        pullReadState(db, session, accountId)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) {
            throw e
        }
        log.warning("账户$accountId 拉取已读状态异常：${(e.message ?: "").cpSlice(0, MAX_LOG_CHARS)}")
    }
}

/**
 * 标记集合里是否有 `\Seen`，对应 `sync._has_seen`：兼容大小写差异。
 *
 * IMAP 的标记名按 RFC 3501 大小写不敏感，而 Angus 与各服务器给的大小写并不统一
 * （Python 那边也会拿到 bytes 或 str 两种），所以这里统一按忽略大小写比较。
 */
internal fun hasSeenMark(marks: Collection<String>?): Boolean =
    marks != null && marks.any { it.trim().equals("\\Seen", ignoreCase = true) }
