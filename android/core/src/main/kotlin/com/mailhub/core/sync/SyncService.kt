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

/**
 * 增量轮次单轮最多处理多少个 UID。
 *
 * Python 那边 `search_uids` 没有上限，因为服务器 7×24 在线、一轮跑完就结束了。手机的后台
 * 任务随时会被系统停掉，积压几千封时「每轮只处理前 N 封、水位却只在整轮结束时写」会让
 * 后面的邮件永远收不到；所以这里按块推进水位（见 [SyncService.syncAccount]），并给单轮
 * 加一个上限，多轮追上。首次导入仍是 30 天、最多 200 封（[ImapSession.searchUids]）。
 */
const val MAX_UIDS_PER_ROUND: Int = 500

/** 服务器重建邮箱（UIDVALIDITY 变了）时拒绝回放服务器动作的说明，见 [SyncService.applyServerAction]。 */
const val MAILBOX_REBUILT_TEXT: String = "邮箱已重建，请同步后重试"

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
 * **手机上的中断（见任务 L7-fix-b）**：进程和后台任务随时会被杀，所以这里多了三件事——
 * - 会话在**登录之前**就登记到 [activeSessions]（靠 [sessionFactory] 的 `onCreated` 回调），
 *   登录卡住时 [abort] 也能从别的线程把连接关掉；
 * - [abort] 一律经由 [SyncRound] 的取消标记 + 会话中断两路生效，[syncAccount] 在开始前与
 *   登录后各检查一次标记，已经取消就不再开这一轮；
 * - 每块入库时就在**同一个事务**里推进水位（增量轮）或抬高首次导入标记（首轮），
 *   轮次中途被杀也不会让下一轮从同一个水位重来、或让已导入的历史邮件弹通知。
 *
 * 时间一律来自注入的 [Clock]（对应 `time.time()`）；凭据解密走注入的 [SecretCipher]。
 *
 * @param db 唯一的数据库实例
 * @param cipher 凭据加解密（对应 `security.decrypt`）
 * @param clock 当前时间
 * @param status 内存同步状态（对应 `STATUS` / `_set_status` / `get_status`）
 * @param sessionFactory 建立 IMAP 会话；测试用它注入信任自签名证书的会话。第三个参数是
 *   「会话刚建好、还没登录」的回调，[syncAccount] 用它登记 [activeSessions]
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
    private val sessionFactory: (SyncAccount, String, (ImapSession) -> Unit) -> ImapSession =
        { account, secret, onCreated ->
            openSession(account.toImapAccount(), secret, clock, onCreated)
        },
) {

    private val log: Logger = Logger.getLogger("com.mailhub.core.sync.SyncService")

    /**
     * 正在同步的会话：协程取消时靠它把阻塞的 socket 关掉。
     *
     * 会话在**登录之前**就登记进来（[sessionFactory] 的 `onCreated` 回调），登录卡住时
     * [abort] 照样够得到这条连接。
     */
    private val activeSessions = ConcurrentHashMap<Int, ImapSession>()

    /** 账户 id -> 正在进行的同步轮次；[SyncCoordinator] 在提交工作线程之前登记。 */
    private val rounds = ConcurrentHashMap<Int, SyncRound>()

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

    // ---------------- 轮次与取消标记 ----------------

    /**
     * 登记一轮同步，返回它的取消标记；应在把工作提交给同步线程**之前**调用。
     *
     * 标记跟着轮次对象走，轮次结束就注销（[endSyncRound]），所以迟到的 [abort] 不会把标记
     * 留给下一轮——否则下一轮同步会莫名其妙地直接退出。
     */
    fun beginSyncRound(accountId: Int): SyncRound = SyncRound().also { rounds[accountId] = it }

    /** 一轮同步结束：注销它的取消标记。 */
    fun endSyncRound(accountId: Int, round: SyncRound) {
        rounds.remove(accountId, round)
    }

    /** 本轮是否已被取消：没有登记轮次时（例如直接调用 [syncAccount]）视为未取消。 */
    private fun isCancelled(accountId: Int): Boolean = rounds[accountId]?.isCancelled == true

    // ---------------- 同步 ----------------

    /**
     * 同步单个账户，返回新邮件数，对应 `sync.sync_account`。
     *
     * 调用方必须先拿到这个账户的互斥（[SyncCoordinator] 负责）。账户不存在或已被禁用时
     * 直接返回 0；同步期间账户被删掉时不写回状态（与 Python 相同）。
     *
     * **取消**：本轮如果已经被取消（见 [SyncRound]），开始前就直接返回 0、不去连接服务器；
     * 登录之后被取消则中断会话、状态回到空闲。取消造成的中断**不算失败**：不写 `last_error`、
     * 不写 `last_sync`（对应任务 L7-fix-b 的第 9 条）。
     *
     * **单账户边界接 [Throwable]**：一个账户的 `OutOfMemoryError` / `StackOverflowError`
     * 不能让其他账户跟着挂掉，所以这里连 `Error` 也接住（`CancellationException` 除外）。
     */
    open fun syncAccount(accountId: Int): Int {
        val account = db.queryOne("SELECT * FROM accounts WHERE id=? AND enabled=1", accountId)
            ?.let(SyncAccount::fromRow)
            ?: return 0
        if (isCancelled(accountId)) {
            // 线程开始前就被取消：不开始，也不去连服务器
            return 0
        }
        status.set(accountId, SYNC_STATE_SYNCING, "连接中")
        var session: ImapSession? = null
        try {
            val secret = resolveSecret(account, cipher)
            // 会话在登录之前就登记：登录卡住时 abort 也能从这个回调登记的会话上关掉连接
            val opened = sessionFactory(account, secret) { created ->
                activeSessions[accountId] = created
            }
            session = opened
            if (isCancelled(accountId)) {
                // 登录期间被取消：不继续这一轮
                opened.abort()
                finishRound(accountId, 0)
                return 0
            }
            return syncWithSession(account, opened)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 状态里的说明只给用户看中文；原始异常不落库（可能含凭据，也会暴露服务器平台）
            log.warning("账户$accountId 同步失败：${e::class.java.simpleName}")
            if (sessionAborted(session) || isCancelled(accountId)) {
                // 取消造成的异常不是真的失败：不写 last_error、不动 last_sync，状态回到空闲。
                // 登录还没建好会话时（session 仍是 null）靠轮次的取消标记判断，否则登录中途
                // 被取消会留下一条假的「连接中断」。
                finishRound(accountId, 0)
                return 0
            }
            val err = connectionErrorText(e).cpSlice(0, MAX_ERROR_CHARS)
            try {
                db.execute(
                    "UPDATE accounts SET last_error=?, last_sync=? WHERE id=?",
                    err,
                    clock.now(),
                    accountId,
                )
            } catch (_: Throwable) {
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

    /** 会话是否已被中断（取消）；没有会话时不算。 */
    private fun sessionAborted(session: ImapSession?): Boolean = session?.isAborted() == true

    /**
     * 一轮同步的主体：选中收件箱、已读同步、搜索、分块抓取与分块入库。
     *
     * **水位按块写**（任务 L7-fix-b 第 5 条）：增量轮次在每块入库的**同一个事务**里把
     * `last_uid` 推进到本块（沿用 [advanceWatermark]，失败的 UID 不越过），所以积压几千封时
     * 后台任务被停掉也不会让下一轮从同一个水位重来。首次导入轮**不提前推进**水位，否则
     * 剩下的邮件会失去首次导入判定（[initialImport]）。
     */
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

        val uids = session.searchUids(lastUid).take(MAX_UIDS_PER_ROUND)
        // 本轮开始时 last_uid == 0，且这个账户还没有首次导入标记（或 UIDVALIDITY 刚变）
        // 才算「首次导入」：首轮搜索为空时也会写下 `local_initial_import_<id> = 0`，
        // 之后的第一封真实新信就不会再被当成历史邮件
        val initialImport = lastUid == 0L && (initialImportMarker(accountId) == null || uidRebuilt)
        if (uids.isEmpty()) {
            db.transaction {
                db.execute(
                    "UPDATE accounts SET uidvalidity=?, last_sync=?, last_error='' WHERE id=?",
                    uidvalidity,
                    clock.now(),
                    accountId,
                )
                if (initialImport) {
                    // 首轮一封都没有也要记下「首轮已完成」，否则下一轮 last_uid 仍是 0，
                    // 第一封真实新信会被当成首次导入而被通知屏障跳过
                    markInitialImportFinished(accountId)
                }
            }
            finishRound(accountId, 0)
            return 0
        }

        status.set(accountId, SYNC_STATE_SYNCING, "拉取 ${uids.size} 封".cpSlice(0, MAX_STATUS_CHARS))
        val custom = loadCustomRules(db)
        val failed = LinkedHashSet<Long>()
        val outcome = InsertOutcome(lastUid)
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
                insertBlock(accountId, block, failed, outcome, initialImport)
                block = ArrayList(FETCH_CHUNK_UIDS)
            }
        }
        insertBlock(accountId, block, failed, outcome, initialImport)

        if (sessionAborted(session)) {
            // 取消造成的中断：已经提交的块（含按块推进的水位）留着，但这一轮没跑完，
            // 所以不写 last_error、不写 last_sync，状态回到空闲
            finishRound(accountId, outcome.newCount)
            return outcome.newCount
        }

        var lastError = ""
        if (failed.isNotEmpty()) {
            lastError = "${failed.size} 封邮件拉取或入库失败，下次同步重试"
            if (outcome.insertError.isNotEmpty()) {
                lastError += "（${outcome.insertError}）"
            }
        }
        // 按块推进过的水位不能回退，两者取大
        val watermark = maxOf(outcome.watermark, advanceWatermark(lastUid, uids, failed))
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
                // 兜底：所有块都被 IGNORE 时块里的「新插入最大 id」是 0，
                // 这里按本账户实际的 MAX(id) 再补写一次
                raiseInitialImportMarker(accountId, maxMessageId(accountId))
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
     * 把一块邮件写进库（短事务，只做 SQL），并在同一个事务里写水位或首次导入标记。
     *
     * 「是否新插入」靠 [com.mailhub.core.db.ExecResult.changes] 判断，不用
     * `lastInsertRowid`：`INSERT OR IGNORE` 被忽略时它还是上一次插入留下的值。
     * 入库失败记进 [failed]，下一轮重试，水位不会越过这些 UID。
     *
     * - 增量轮（`initialImport=false`）：把 `accounts.last_uid` 推进到本块，失败的 UID
     *   不越过；这样一轮没跑完（进程被杀、后台任务被停）也不会把整轮丢掉；
     * - 首次导入轮：把 `local_initial_import_<id>` 抬到本块新插入的最大 id，让已经导入的
     *   历史邮件即使后面的块没跑完也不再弹通知；水位不在这里动。
     */
    private fun insertBlock(
        accountId: Int,
        block: List<PendingInsert>,
        failed: MutableSet<Long>,
        outcome: InsertOutcome,
        initialImport: Boolean,
    ) {
        if (block.isEmpty()) {
            return
        }
        var blockMaxId = 0L
        db.transaction {
            for (item in block) {
                try {
                    val result = db.execute(INSERT_MESSAGE_SQL, *item.values)
                    if (result.changes > 0) {
                        outcome.newCount += 1
                        if (result.lastInsertRowid > blockMaxId) {
                            blockMaxId = result.lastInsertRowid
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 入库失败不能被水位跳过，记为失败，下次同步重试
                    failed += item.uid
                    if (outcome.insertError.isEmpty()) {
                        outcome.insertError = (e.message ?: "").cpSlice(0, 120)
                    }
                }
            }
            if (initialImport) {
                raiseInitialImportMarker(accountId, blockMaxId)
            } else {
                val next = advanceWatermark(
                    outcome.watermark,
                    block.map { it.uid } + failed,
                    failed,
                )
                if (next > outcome.watermark) {
                    outcome.watermark = next
                    db.execute("UPDATE accounts SET last_uid=? WHERE id=?", next, accountId)
                }
            }
        }
    }

    /**
     * 读首次导入标记（键是 `local_initial_import_<账户 id>`，见 LOCAL_MODE.md 第五节）：
     * 键不存在时返回 null。值为 0 表示「首轮已经跑过、但没有需要跳过的邮件」，
     * 与「从来没有写过」是两件事。
     */
    private fun initialImportMarker(accountId: Int): Long? =
        db.getSetting("$INITIAL_IMPORT_KEY_PREFIX$accountId", "").trim().toLongOrNull()

    /**
     * 把首次导入标记抬到 [maxId]，对应 LOCAL_MODE.md 第五节。
     *
     * `/api/changes` 靠它跳过「加账户时导入的历史邮件」，所以：
     * - 已经有更大的值时不改小（重建后导入的邮件 id 一定更大，但旧值也许更高）；
     * - 本块一封都没新插入（[maxId] <= 0）时不动：这种情况下标记由整轮结束时的
     *   [maxMessageId] 补写兜底。
     */
    private fun raiseInitialImportMarker(accountId: Int, maxId: Long) {
        if (maxId <= 0) {
            return
        }
        val current = initialImportMarker(accountId)
        if (current != null && current >= maxId) {
            return
        }
        db.setSetting("$INITIAL_IMPORT_KEY_PREFIX$accountId", maxId.toString())
    }

    /**
     * 首轮搜索为空时记下「首轮已完成」：键不存在时写 0。
     *
     * 不写的话下一轮 `last_uid` 仍是 0，第一封真实新信会被当成首次导入的历史邮件，
     * 被通知屏障跳过（永远不会通知）。值 0 在 `/api/changes` 里不跳过任何邮件。
     */
    private fun markInitialImportFinished(accountId: Int) {
        if (initialImportMarker(accountId) != null) {
            return
        }
        db.setSetting("$INITIAL_IMPORT_KEY_PREFIX$accountId", "0")
    }

    /** 本账户当前最大的邮件 id，没有邮件时为 0（首次导入标记的兜底值）。 */
    private fun maxMessageId(accountId: Int): Long =
        db.queryOne("SELECT MAX(id) AS m FROM messages WHERE account_id=?", accountId)?.long("m") ?: 0L

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
     *
     * **校验 UIDVALIDITY（与 Python 的有意偏差，任务 L7-fix-b 第 7 条）**：Python 丢弃了
     * `select_inbox()` 的返回值。服务器重建邮箱后、本地还没同步时，用户删除旧 UID 会删掉
     * 服务器上的**另一封**邮件——手机上这个窗口比服务器大得多（后台任务 15 分钟才跑一次）。
     * 所以这里比较 `select_inbox()` 返回的 UIDVALIDITY 与账户里保存的值，不一致时**不执行
     * 任何写操作**，该账户的每个 id 都进错误列表。账户保存的值是 0（还没同步过）时不比较。
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
                session = sessionFactory(account, resolveSecret(account, cipher)) { }
                val serverUidvalidity = session.selectInbox()
                if (account.uidvalidity != 0L && serverUidvalidity != 0L &&
                    serverUidvalidity != account.uidvalidity
                ) {
                    // 邮箱已经重建：本地这些 UID 指向的可能是服务器上完全不同的邮件，绝不能写
                    for (item in items) {
                        errors += "邮件${item.first}: $MAILBOX_REBUILT_TEXT"
                    }
                    continue
                }
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
                // Python 是 str(exc)[:100]；手机上那是英文平台原文，用户看不懂，换成中文说明
                errors += "账户$accountId: ${connectionErrorText(e).cpSlice(0, 100)}"
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
            session = sessionFactory(account, credential) { }
            session.selectInbox()
        } finally {
            session?.close()
        }
    }

    // ---------------- 取消 ----------------

    /**
     * 从另一个线程中断该账户正在进行的会话：关闭底层 socket，让阻塞的读写立刻抛异常。
     *
     * [SyncCoordinator] 在协程被取消时调用它（投递到同步线程上执行，见它的 `runSync`）。
     * 除了关 socket，还会置位本轮次的取消标记：会话还没建好、正在登录时，
     * [syncAccount] 里的检查同样能看到取消并退出，不会跑完一整轮。
     */
    open fun abort(accountId: Int) {
        rounds[accountId]?.cancel()
        activeSessions[accountId]?.abort()
    }

    /** 一条待插入的邮件：UID（入库失败时记进失败集合）与 24 个绑定参数。 */
    private class PendingInsert(val uid: Long, val values: Array<Any?>)

    /** 一块内插入的累计结果。 */
    private class InsertOutcome(
        /** 已经按块推进到哪：增量轮的水位，块与块之间要接着往上走。 */
        var watermark: Long,
    ) {
        var newCount: Int = 0
        var insertError: String = ""
    }

    private companion object {
        const val ACTION_DELETE = "delete"
        const val ACTION_READ = "read"
    }
}

/**
 * 一轮同步的取消标记，对应「后台任务被系统停掉」时的那次取消。
 *
 * [SyncCoordinator] 在把工作提交给同步线程**之前**用 [SyncService.beginSyncRound] 登记它，
 * 并在轮次结束时注销；取消时 [SyncService.abort] 把它置位。[SyncService.syncAccount] 在
 * 线程开始前、登录后各看一次：已取消就不开始、不继续。
 *
 * 标记跟着轮次对象走而不是按账户长期保存，这样迟到的 `abort`（投递到同步线程上执行，
 * 可能比轮次结束还晚）不会把标记留给下一轮、让下一次同步莫名其妙地直接退出。
 */
class SyncRound internal constructor() {

    @Volatile
    private var cancelled: Boolean = false

    /** 本轮是否已被取消。 */
    val isCancelled: Boolean get() = cancelled

    internal fun cancel() {
        cancelled = true
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
 * 调用方按块推进水位时不能越过它们。
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
