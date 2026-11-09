package com.mailhub.core.sync

import com.mailhub.core.Clock
import com.mailhub.core.SecretCipher
import com.mailhub.core.SystemClock
import com.mailhub.core.billing.Billing
import com.mailhub.core.db.Db
import com.mailhub.core.db.toJsonText
import com.mailhub.core.imap.FetchedMessage
import com.mailhub.core.imap.ImapException
import com.mailhub.core.imap.ImapSession
import com.mailhub.core.imap.connectionErrorText
import com.mailhub.core.imap.openSession
import com.mailhub.core.rules.Rules
import com.mailhub.core.rules.loadCustomRules
import com.mailhub.core.security.SecurityScan
import com.mailhub.core.text.cpSlice
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException

/** 每块抓取的邮件数，对应 `sync.FETCH_CHUNK_UIDS`：抓完一块就立刻入库，再去抓下一块。 */
const val FETCH_CHUNK_UIDS: Int = 10

/** 允许回放到服务器上的动作，对应 `sync.SERVER_ACTIONS`。 */
val SERVER_ACTIONS: List<String> = listOf("delete", "read", "unread")

/** `last_error` / 状态说明里最多保留多少字，对应 Python 的 `[:200]`。 */
private const val MAX_ERROR_CHARS = 200

/** 状态里「拉取 N 封」的最大说明长度，与 Python 一致（不截断，这里只做防御）。 */
private const val MAX_STATUS_CHARS = 200

/** 首次导入标记在 settings 里的键前缀，见 LOCAL_MODE.md 第五节。 */
const val INITIAL_IMPORT_KEY_PREFIX: String = "local_initial_import_"

/** 邮件入库语句：列与顺序与 `sync.sync_account` 里的 INSERT OR IGNORE 完全相同。 */
private val INSERT_MESSAGE_SQL = """
    INSERT OR IGNORE INTO messages
       (account_id, uid, msg_id, subject, sender_name, sender_addr, to_addr,
        date_ts, snippet, body_text, body_html, category, otp_code, unread,
        has_attach, unsubscribe, created_ts, risk_level, risk_reasons,
        needs_review, confidence, header_signals, template_key,
        body_truncated)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""".trimIndent()

/** [SyncService.applyServerAction] 的结果：服务器确认成功的本地 id 与错误说明。 */
data class ServerActionResult(
    val succeeded: List<Long>,
    val errors: List<String>,
)

/**
 * 单个账户的同步，逐函数对照 `sync.py`：`sync_account` 与它的辅助函数
 * （`_is_duplicate`、`_rebind_by_message_id`、`_unbind_stale_uids`、`_advance_watermark`、
 * `_iter_fetched`、`_push_read_state`、`_pull_read_state`、`_sync_read_state`），
 * 以及 `apply_server_action`、`resolve_secret`、状态查询与 `oauth_auth.probe_connection`。
 *
 * **数据库锁的约束（硬性，见 `Db.transaction` 的 KDoc）**：`Db` 只有一个连接和一把锁，
 * 所以 IMAP 请求、MIME 解析、安全扫描、规则分类全部在锁外完成；锁里只做「这一块邮件」的
 * 落库、状态更新与少量设置写入。对应 Python「抓完一块，写一堆语句，再 `commit()`」。
 *
 * **与 Python 的三处结构性差别：**
 * 1. 同一账户的互斥从本类移到了 [SyncCoordinator]（Python 是 `_sync_locks` +
 *    `sync_account` 里的 `acquire(blocking=False)`）。所以 [syncAccount] 假定调用方已经
 *    持有该账户的锁，不会自己抢锁、也不会把「正在同步」的请求塞回队列。
 * 2. [syncAccount] 是阻塞函数（跑在协程的 IO 线程上），取消时由 [abort] 从另一个线程关闭
 *    socket；Python 那边是线程，没有取消这一说。
 * 3. 本机模式的通知由 App 在同步后统一处理，所以**没有** `notify.notify_new_messages`。
 *
 * 时间一律来自注入的 [Clock]（对应 `time.time()`）；凭据解密走注入的 [SecretCipher]。
 *
 * @param db 唯一的数据库实例
 * @param cipher 凭据加解密（对应 `security.decrypt`）
 * @param clock 当前时间
 * @param status 内存同步状态（对应 `STATUS` / `_set_status` / `get_status`）
 * @param sessionFactory 建立 IMAP 会话；测试用它注入信任自签名证书的会话
 *
 * **为什么是 `open`**：[SyncCoordinator] 的调度逻辑（并发上限、每账户互斥、治理补跑、取消）
 * 需要能注入一个可控的「同步一个账户」实现才能确定性地测时序，而 IMAP 会话本身就是阻塞 IO。
 * 测试用 [syncAccount] / [abort] / [forgetAccount] 的替身满足这个需要；正式代码不继承本类。
 */
open class SyncService(
    val db: Db,
    private val cipher: SecretCipher,
    private val clock: Clock = SystemClock,
    val status: SyncStatus = SyncStatus(clock),
    private val sessionFactory: (SyncAccount, String) -> ImapSession = { account, secret ->
        openSession(account.toImapAccount(), secret, clock)
    },
) {

    private val log: Logger = Logger.getLogger("com.mailhub.core.sync.SyncService")

    /** 正在同步的会话：协程取消时靠它把阻塞的 socket 关掉。 */
    private val activeSessions = ConcurrentHashMap<Int, ImapSession>()

    // ---------------- 状态 ----------------

    /** 对应 `get_status`：`{"账户 id": {state, msg, ts, new}}`。 */
    fun getStatus(): Map<String, AccountSyncStatus> = status.snapshot()

    /**
     * 账户删除后清掉它的同步状态（对应 `forget_account`；锁与手动队列在 [SyncCoordinator]
     * 里，它自己会清理）。
     */
    open fun forgetAccount(accountId: Int) {
        status.forget(accountId)
        activeSessions.remove(accountId)?.abort()
    }

    /** 「与邮箱服务器同步已读状态」开关，对应 `read_state_sync_enabled`。 */
    fun readStateSyncEnabled(): Boolean = readStateSyncEnabled(db)

    // ---------------- 同步 ----------------

    /**
     * 同步单个账户，返回新邮件数，对应 `sync.sync_account`。
     *
     * 调用方必须先拿到这个账户的互斥（[SyncCoordinator] 负责）。账户不存在或已被禁用时
     * 直接返回 0；同步期间账户被删掉时不写回状态（与 Python 相同）。
     */
    open fun syncAccount(accountId: Int): Int {
        val account = db.queryOne("SELECT * FROM accounts WHERE id=? AND enabled=1", accountId)
            ?.let(SyncAccount::fromRow)
            ?: return 0
        status.set(accountId, SYNC_STATE_SYNCING, "连接中")
        var session: ImapSession? = null
        try {
            val secret = resolveSecret(account, cipher)
            val opened = sessionFactory(account, secret)
            session = opened
            activeSessions[accountId] = opened
            return syncWithSession(account, opened)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 状态里的说明只给用户看中文；原始异常不落库（可能含凭据，也会暴露服务器平台）
            log.warning("账户$accountId 同步失败：${e::class.java.simpleName}")
            val err = connectionErrorText(e).cpSlice(0, MAX_ERROR_CHARS)
            try {
                db.execute(
                    "UPDATE accounts SET last_error=?, last_sync=? WHERE id=?",
                    err,
                    clock.now(),
                    accountId,
                )
            } catch (_: Exception) {
                // 写状态失败也要把错误告诉调用方
            }
            if (accountExists(accountId)) {
                status.set(accountId, SYNC_STATE_ERROR, err)
            } else {
                status.forget(accountId)
            }
            return 0
        } finally {
            if (session != null) {
                activeSessions.remove(accountId, session)
                session.close()
            }
        }
    }

    /** 一轮同步的主体：选中收件箱、已读同步、搜索、分块抓取与分块入库。 */
    private fun syncWithSession(account: SyncAccount, session: ImapSession): Int {
        val accountId = account.id
        val uidvalidity = session.selectInbox()
        var lastUid = account.lastUid
        var uidRebuilt = false
        if (uidvalidity != 0L && account.uidvalidity != 0L && uidvalidity != account.uidvalidity) {
            // UIDVALIDITY 变化说明服务器重建了邮箱，旧 UID 全部作废
            unbindStaleUids(accountId, uidvalidity)
            lastUid = 0
            uidRebuilt = true
        }
        // 已读状态双向同步放在搜索新邮件之前：两个方向都是尽力而为，失败只记日志。
        // UIDVALIDITY 刚变化的那一轮跳过，因为旧 UID 已作废，按旧 UID 读写只会误改其他邮件。
        if (!uidRebuilt && readStateSyncEnabled()) {
            syncReadState(db, session, accountId, log)
        }

        val uids = session.searchUids(lastUid)
        // 本轮开始时 last_uid == 0（首次同步，或 UIDVALIDITY 重建）才写首次导入标记
        val initialImport = lastUid == 0L
        if (uids.isEmpty()) {
            db.execute(
                "UPDATE accounts SET uidvalidity=?, last_sync=?, last_error='' WHERE id=?",
                uidvalidity,
                clock.now(),
                accountId,
            )
            finishRound(accountId, 0)
            return 0
        }

        status.set(accountId, SYNC_STATE_SYNCING, "拉取 ${uids.size} 封".cpSlice(0, MAX_STATUS_CHARS))
        val custom = loadCustomRules(db)
        val failed = LinkedHashSet<Long>()
        val outcome = InsertOutcome()
        var block = ArrayList<PendingInsert>(FETCH_CHUNK_UIDS)
        for (message in iterFetched(session, uids, failed)) {
            // —— UIDVALIDITY 变化后，同一封信按 Message-ID 认回原来的本地记录 ——
            if (rebindByMessageId(accountId, message)) {
                continue
            }
            // —— 去重：同一封信可能经转发从多个账户到达，用 Message-ID 二次判重 ——
            if (message.parsed.msgId.isNotEmpty() && isDuplicate(accountId, message.parsed.msgId)) {
                continue
            }
            block += planInsert(accountId, message, custom)
            // 一块抓取对应一块落库：块边界与 FETCH_CHUNK_UIDS 对齐
            if (block.size >= FETCH_CHUNK_UIDS) {
                insertBlock(block, failed, outcome)
                block = ArrayList(FETCH_CHUNK_UIDS)
            }
        }
        insertBlock(block, failed, outcome)

        var lastError = ""
        if (failed.isNotEmpty()) {
            lastError = "${failed.size} 封邮件拉取或入库失败，下次同步重试"
            if (outcome.insertError.isNotEmpty()) {
                lastError += "（${outcome.insertError}）"
            }
        }
        val watermark = advanceWatermark(lastUid, uids, failed)
        db.transaction {
            db.execute(
                "UPDATE accounts SET last_uid=?, uidvalidity=?, last_sync=?, last_error=? WHERE id=?",
                watermark,
                uidvalidity,
                clock.now(),
                lastError.cpSlice(0, MAX_ERROR_CHARS),
                accountId,
            )
            if (initialImport) {
                recordInitialImport(accountId, outcome.maxNewId)
            }
        }
        finishRound(accountId, outcome.newCount)
        return outcome.newCount
    }

    /** 收尾：账户还在就写 idle + 新邮件数，同步期间被删除则把状态抹掉（与 Python 相同）。 */
    private fun finishRound(accountId: Int, newCount: Int) {
        if (accountExists(accountId)) {
            status.set(accountId, SYNC_STATE_IDLE, "", newCount)
        } else {
            status.forget(accountId)
        }
    }

    /**
     * 锁外把一封邮件变成一条待插入的行：安全扫描 + 本地规则分类都在这里完成，
     * 只有 [insertBlock] 会短暂进事务。
     */
    private fun planInsert(
        accountId: Int,
        message: FetchedMessage,
        custom: List<com.mailhub.core.rules.CustomRule>,
    ): PendingInsert {
        val parsed = message.parsed
        // —— 安全扫描：静态分析，绝不访问其中的链接或附件 ——
        val scan = SecurityScan.scan(
            subject = parsed.subject,
            body = parsed.bodyText,
            senderName = parsed.senderName,
            senderAddr = parsed.senderAddr,
            replyTo = parsed.replyTo,
            attachments = parsed.attachNames,
            html = parsed.bodyHtml,
            authResults = parsed.authResults,
        )
        val ruled = Rules.classifyDetailed(
            subject = parsed.subject,
            body = parsed.bodyText,
            senderAddr = parsed.senderAddr,
            hasUnsubscribe = parsed.unsubscribe != 0,
            customRules = custom,
            signals = parsed.headerSignals,
        )
        var category = ruled.category
        var otp = ruled.otp
        var confidence = ruled.confidence
        // 中/高风险立即保守标记「可疑」；后续三省六部流水线会补齐证据与审计。
        // 这里只打本地标签，不访问链接、附件，也不执行移动或删除。
        if (SecurityScan.riskAtLeast(scan.risk, SecurityScan.RISK_MEDIUM)) {
            category = "可疑"
            otp = ""
            confidence = 1.0
        }
        val values = arrayOf<Any?>(
            accountId,
            message.uid,
            parsed.msgId,
            parsed.subject,
            parsed.senderName,
            parsed.senderAddr,
            parsed.toAddr,
            parsed.dateTs,
            parsed.snippet,
            parsed.bodyText,
            parsed.bodyHtml,
            category,
            otp,
            message.unread,
            parsed.hasAttach,
            parsed.unsubscribe,
            clock.now(),
            scan.risk,
            scan.reasons.joinToString(" · ").cpSlice(0, 500),
            0,
            confidence,
            toJsonText(parsed.headerSignals),
            Billing.templateKey(parsed.senderAddr, parsed.subject),
            message.bodyTruncated,
        )
        return PendingInsert(message.uid, values)
    }

    /**
     * 把一块邮件写进库（短事务，只做 SQL）。
     *
     * 「是否新插入」靠 [com.mailhub.core.db.ExecResult.changes] 判断，不用
     * `lastInsertRowid`：`INSERT OR IGNORE` 被忽略时它还是上一次插入留下的值。
     * 入库失败记进 [failed]，下一轮重试，水位不会越过这些 UID。
     */
    private fun insertBlock(
        block: List<PendingInsert>,
        failed: MutableSet<Long>,
        outcome: InsertOutcome,
    ) {
        if (block.isEmpty()) {
            return
        }
        db.transaction {
            for (item in block) {
                try {
                    val result = db.execute(INSERT_MESSAGE_SQL, *item.values)
                    if (result.changes > 0) {
                        outcome.newCount += 1
                        if (result.lastInsertRowid > outcome.maxNewId) {
                            outcome.maxNewId = result.lastInsertRowid
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }
                    // 入库失败不能被水位跳过，记为失败，下次同步重试
                    failed += item.uid
                    if (outcome.insertError.isEmpty()) {
                        outcome.insertError = (e.message ?: "").cpSlice(0, 120)
                    }
                }
            }
        }
    }

    /**
     * 首次导入标记：把本轮新插入邮件的最大 id 写进 `settings`，对应 LOCAL_MODE.md 第五节。
     *
     * `/api/changes` 靠它跳过「加账户时导入的历史邮件」，所以只记第一次同步（或 UIDVALIDITY
     * 重建那一轮）的最大 id；已有更大的值时**不改小**（重建后导入的邮件 id 一定更大，
     * 但旧值也许更高，不能被覆盖回去）。
     */
    private fun recordInitialImport(accountId: Int, maxNewId: Long) {
        if (maxNewId <= 0) {
            return
        }
        val key = "$INITIAL_IMPORT_KEY_PREFIX$accountId"
        val current = db.getSetting(key, "").trim().toLongOrNull() ?: 0L
        if (current >= maxNewId) {
            return
        }
        db.setSetting(key, maxNewId.toString())
    }

    /**
     * 跨账户 Message-ID 判重，对应 `_is_duplicate`。
     *
     * 同账户内靠 `UNIQUE(account_id, uid)` 已能拦住重复拉取；这里解决的是「A 邮箱转发到
     * B 邮箱」时同一封信在两个账户各存一份的问题——仅当已存在的那条属于**其他**账户时
     * 才判为重复，避免误伤本账户重同步。
     */
    internal fun isDuplicate(accountId: Int, msgId: String): Boolean {
        if (msgId.isEmpty()) {
            return false
        }
        val row = db.queryOne("SELECT account_id FROM messages WHERE msg_id=? LIMIT 1", msgId)
            ?: return false
        return row.long("account_id") != accountId.toLong()
    }

    /**
     * 把重建邮箱后的新 UID 绑回同一封信原来的本地记录，对应 `_rebind_by_message_id`：
     * 保留分类与回收站状态。
     */
    internal fun rebindByMessageId(accountId: Int, message: FetchedMessage): Boolean {
        val msgId = message.parsed.msgId
        if (msgId.isEmpty()) {
            return false
        }
        return db.transaction {
            val existing = db.queryOne(
                "SELECT 1 FROM messages WHERE account_id=? AND uid=?",
                accountId,
                message.uid,
            )
            if (existing != null) {
                // 这个 UID 已经入库，交给后面的 INSERT OR IGNORE 幂等处理
                return@transaction false
            }
            val row = db.queryOne(
                "SELECT id FROM messages WHERE account_id=? AND uid<0 AND msg_id=? ORDER BY id LIMIT 1",
                accountId,
                msgId,
            ) ?: return@transaction false
            db.execute("UPDATE messages SET uid=? WHERE id=?", message.uid, row.long("id"))
            true
        }
    }

    /**
     * UIDVALIDITY 变化：把旧记录的 UID 改成负数让出唯一键，对应 `_unbind_stale_uids`。
     *
     * 负 UID 不会与服务器上的任何 UID 冲突，服务器动作也会跳过它们。与新的 uidvalidity、
     * 归零的水位放在同一个事务里提交，中途失败也不会重复解绑。
     */
    internal fun unbindStaleUids(accountId: Int, uidvalidity: Long) {
        db.transaction {
            db.execute("UPDATE messages SET uid=-id WHERE account_id=? AND uid>0", accountId)
            db.execute(
                "UPDATE accounts SET last_uid=0, uidvalidity=? WHERE id=?",
                uidvalidity,
                accountId,
            )
        }
    }

    private fun accountExists(accountId: Int): Boolean = try {
        db.queryOne("SELECT 1 FROM accounts WHERE id=?", accountId) != null
    } catch (_: Exception) {
        // 查询本身失败时宁可保留状态，也不要把真实存在的账户从状态表里抹掉
        true
    }

    // ---------------- 服务器动作 ----------------

    /**
     * 把动作按账户分组回放到 IMAP，对应 `apply_server_action`：
     * 返回服务器确认成功的本地 id 与错误说明。
     *
     * UID 为负的记录（UIDVALIDITY 变化后解绑的旧记录）在服务器上已经没有对应邮件，
     * 直接记错误、不连服务器。
     */
    fun applyServerAction(messageIds: List<Long>, action: String): ServerActionResult {
        if (action !in SERVER_ACTIONS) {
            throw IllegalArgumentException("不支持的服务器动作 $action")
        }
        if (messageIds.isEmpty()) {
            return ServerActionResult(emptyList(), emptyList())
        }
        val placeholders = messageIds.joinToString(",") { "?" }
        val rows = db.query(
            "SELECT id, account_id, uid FROM messages WHERE id IN ($placeholders)",
            *messageIds.toTypedArray(),
        )
        val byAccount = LinkedHashMap<Int, MutableList<Pair<Long, Long>>>()
        val errors = ArrayList<String>()
        for (row in rows) {
            val id = row.long("id") ?: continue
            val uid = row.long("uid") ?: 0L
            val accountId = Math.toIntExact(row.long("account_id") ?: 0L)
            if (uid <= 0L) {
                errors += "邮件$id: 服务器上已找不到对应邮件"
                continue
            }
            byAccount.getOrPut(accountId) { ArrayList() } += id to uid
        }

        val succeeded = ArrayList<Long>()
        for ((accountId, items) in byAccount) {
            var session: ImapSession? = null
            try {
                val account = db.queryOne("SELECT * FROM accounts WHERE id=?", accountId)
                    ?.let(SyncAccount::fromRow)
                    ?: throw ImapException("账户不存在")
                session = sessionFactory(account, resolveSecret(account, cipher))
                session.selectInbox()
                val uids = items.map { it.second }
                if (action == ACTION_DELETE) {
                    session.delete(uids)
                } else {
                    session.markRead(uids, action == ACTION_READ)
                }
                succeeded += items.map { it.first }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                errors += "账户$accountId: ${(e.message ?: "").cpSlice(0, 100)}"
            } finally {
                session?.close()
            }
        }
        return ServerActionResult(succeeded, errors)
    }

    // ---------------- 测试连接 ----------------

    /**
     * 登录、选择收件箱、关闭，对应 `oauth_auth.probe_connection`。
     * 添加账户与「测试连接」都调用它；失败时抛出原始异常，由调用方用
     * [connectionErrorText] 转成中文说明。
     */
    fun probeConnection(account: SyncAccount, credential: String) {
        var session: ImapSession? = null
        try {
            session = sessionFactory(account, credential)
            session.selectInbox()
        } finally {
            session?.close()
        }
    }

    // ---------------- 取消 ----------------

    /**
     * 从另一个线程中断该账户正在进行的会话：关闭底层 socket，让阻塞的读写立刻抛异常。
     *
     * [SyncCoordinator] 在协程被取消时调用它；没有正在进行的会话时什么也不做。
     */
    open fun abort(accountId: Int) {
        activeSessions[accountId]?.abort()
    }

    /** 一条待插入的邮件：UID（入库失败时记进失败集合）与 24 个绑定参数。 */
    private class PendingInsert(val uid: Long, val values: Array<Any?>)

    /** 一块内插入的累计结果。 */
    private class InsertOutcome {
        var newCount: Int = 0
        var maxNewId: Long = 0
        var insertError: String = ""
    }

    private companion object {
        const val ACTION_DELETE = "delete"
        const val ACTION_READ = "read"
    }
}

/**
 * 计算新的同步水位：只推进到第一个失败 UID 之前，对应 `_advance_watermark`。
 *
 * 水位之后的 UID 下一轮会重新拉取；已入库的邮件靠 `UNIQUE(account_id, uid)` 幂等跳过，
 * 所以宁可重拉也不能跳过失败的邮件。
 */
internal fun advanceWatermark(lastUid: Long, requested: List<Long>, failed: Set<Long>): Long {
    var watermark = lastUid
    for (uid in requested.sorted()) {
        if (uid in failed) {
            break
        }
        if (uid > watermark) {
            watermark = uid
        }
    }
    return watermark
}

/**
 * 按 [FETCH_CHUNK_UIDS] 逐块抓取，边抓边把该块的邮件交给调用方入库，
 * 对应 `_iter_fetched`。
 *
 * 这是一个惰性 [Sequence]：调用方没有把当前块消费完，它就不会去抓下一块，
 * 所以同一时间只有一块邮件的正文在内存里。抓取失败的 UID 累积到 [failed]，
 * 由调用方在结束后统一推进水位。
 */
internal fun iterFetched(
    session: ImapSession,
    uids: List<Long>,
    failed: MutableSet<Long>,
): Sequence<FetchedMessage> = sequence {
    var start = 0
    while (start < uids.size) {
        val end = minOf(start + FETCH_CHUNK_UIDS, uids.size)
        val result = session.fetchMessages(uids.subList(start, end))
        failed += result.failed
        for (message in result.messages) {
            yield(message)
        }
        start = end
    }
}
