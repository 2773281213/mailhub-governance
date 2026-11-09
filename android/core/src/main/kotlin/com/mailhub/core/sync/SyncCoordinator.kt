package com.mailhub.core.sync

import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import com.mailhub.core.text.cpSlice
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** 并发上限，与服务器端的 3 个 worker 一致（小内存机器）。 */
const val SYNC_CONCURRENCY: Int = 3

/** 到期判定的下限：即使 `poll_interval` 填得更小，也至少隔 60 秒（对应 `max(60, ...)`）。 */
const val MIN_POLL_INTERVAL_SECONDS: Double = 60.0

/** 治理补跑最多几轮（防死循环的硬上限）。 */
const val MAX_AFTER_NEW_MAIL_ROUNDS: Int = 5

/**
 * 同步协调器，取代 `sync.SyncEngine` 的 30 秒心跳线程与 `_force_queue`。
 *
 * 手机进程没有常驻循环，照搬心跳会导致第 4 个以后的账户永远轮不到，所以这里改成
 * 「有人调用就同步」的协程 API：[syncAccounts] 同步点名的账户，[syncDue] 同步到期的账户。
 *
 * 与 `SyncEngine` 的对应关系：
 * - `Semaphore(3)` ↔ `SyncEngine._workers` 的并发上限 3；
 * - 每个账户一把 [Mutex] ↔ `_sync_locks`：同一账户同时只同步一次。Python 抢不到锁时把手动
 *   请求塞回 `_force_queue` 等下一次心跳；本机没有心跳，所以这里改成**等待**——正在同步时
 *   再次请求，会在它结束后再同步一次，请求不会丢；
 * - `_tick` 里的到期判断（`enabled=1` 且 `now - last_sync >= max(60, poll_interval)`）→
 *   [syncDue]，并且同样跳过正在同步的账户；
 * - `sync_account` 之后紧跟着的治理 → 注入的 [afterNewMail]。
 *
 * **治理忙碌时不能丢邮件**：Python 靠 30 秒心跳补跑治理，本机没有心跳。所以 [afterNewMail]
 * 结束后会查一次待治理数量 [pendingGovernance]，只要还有待治理邮件就再跑一轮，直到没有
 * 待治理邮件、或与上一轮相比**确实没有减少**（防死循环），并另加 [MAX_AFTER_NEW_MAIL_ROUNDS]
 * 的硬上限。
 *
 * @param service 单账户同步
 * @param clock 当前时间（到期判断）
 * @param concurrency 并发上限，默认 [SYNC_CONCURRENCY]
 * @param afterNewMail 某账户新增了邮件后的钩子（L8 的治理、L10 的通知挂在这里）；同一账户
 *   串行执行，默认什么都不做
 * @param pendingGovernance 待治理邮件数（对应 `governance.governance_status()["pending"]`），
 *   用来判断治理是否还有积压；默认 0，表示不做补跑
 */
class SyncCoordinator(
    private val service: SyncService,
    private val clock: Clock = SystemClock,
    private val concurrency: Int = SYNC_CONCURRENCY,
    private val afterNewMail: suspend (accountId: Int, newCount: Int) -> Unit = { _, _ -> },
    private val pendingGovernance: () -> Int = { 0 },
) {

    private val log: Logger = Logger.getLogger("com.mailhub.core.sync.SyncCoordinator")

    private val semaphore = Semaphore(concurrency)

    /** 账户 id -> 互斥锁；对应 `_sync_locks`。 */
    private val accountLocks = ConcurrentHashMap<Int, Mutex>()

    /** 账户 id -> 钩子的串行锁：同一账户的 [afterNewMail] 不会并发。 */
    private val afterLocks = ConcurrentHashMap<Int, Mutex>()

    /**
     * 同步线程池。IMAP 是阻塞 IO，取消时要能从**另一个线程**关闭 socket，所以工作放在
     * 独立线程上跑，协程只负责等结果与转发取消（见 [runSync]）。
     */
    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mailhub-sync").apply { isDaemon = true }
    }

    /**
     * 同步指定的账户，返回每个账户本轮的新邮件数，对应 `trigger` + `_drain_force_queue`
     * 的语义（手动请求）。
     *
     * 并发上限由 [concurrency] 控制；同一账户重复请求会排队，前一轮结束后再同步一次。
     */
    suspend fun syncAccounts(ids: Collection<Int>): Map<Int, Int> = coroutineScope {
        val distinct = ids.distinct()
        distinct
            .map { accountId -> async { accountId to syncOne(accountId) } }
            .awaitAll()
            .toMap()
    }

    /**
     * 同步到期的账户，条件与 `SyncEngine._tick` 相同：`enabled=1`、
     * `now - last_sync >= max(60, poll_interval)`，并且跳过正在同步的账户。
     */
    suspend fun syncDue(): Map<Int, Int> = coroutineScope {
        val due = dueAccountIds().filter { !isSyncing(it) }
        due
            .map { accountId -> async { accountId to syncOne(accountId) } }
            .awaitAll()
            .toMap()
    }

    /** 账户当前是否有同步在跑：只读锁状态，不创建新锁（对应 `_is_syncing`）。 */
    fun isSyncing(accountId: Int): Boolean = accountLocks[accountId]?.isLocked == true

    /**
     * 账户删除后清掉它的同步状态与两把锁，对应 `forget_account`：正在进行的会话会被中断，
     * 之后的请求不会再为它排队。
     */
    fun forgetAccount(accountId: Int) {
        service.forgetAccount(accountId)
        accountLocks.remove(accountId)
        afterLocks.remove(accountId)
    }

    /** 停止工作线程池；协调器不再使用时调用。 */
    fun close() {
        workers.shutdownNow()
    }

    // ---------------- 内部实现 ----------------

    /**
     * 同步一个账户：先拿该账户的互斥（保证同一账户串行），再用信号量限制全局并发。
     *
     * 钩子在**释放信号量之后**执行（治理可能跑几分钟，不能占着并发名额），但同一账户的
     * 钩子仍然串行（[afterLocks]）。
     */
    private suspend fun syncOne(accountId: Int): Int {
        val lock = accountLocks.computeIfAbsent(accountId) { Mutex() }
        val newCount = try {
            lock.withLock { semaphore.withPermit { runSync(accountId) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 一个账户出问题不能影响别的账户：与 Python 每个账户一个线程、异常只杀自己相同
            log.warning("账户$accountId 同步异常：${e::class.java.simpleName}")
            return 0
        }
        if (newCount > 0) {
            runAfterNewMail(accountId, newCount)
        }
        return newCount
    }

    /**
     * 在独立线程上跑阻塞的同步，并把协程取消转成「关闭 IMAP socket」。
     *
     * 用 [suspendCancellableCoroutine] 而不是 `withContext(Dispatchers.IO)`：后者的取消只在
     * 阻塞代码返回之后才被观察到，而这里需要**取消的那一刻**就从另一个线程关掉 socket，
     * 让阻塞的读写立刻抛出——这样后台任务被系统停止时能在秒级退出。
     */
    private suspend fun runSync(accountId: Int): Int = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { service.abort(accountId) }
        workers.execute {
            // 已经取消时 resumeWith 会被忽略，不会抛异常
            continuation.resumeWith(runCatching { service.syncAccount(accountId) })
        }
    }

    /**
     * 调用注入的钩子，并在治理忙碌 / 还有积压时补跑，对应 Python「靠 30 秒心跳补跑治理」。
     *
     * 判定「确实无进展」：与**上一轮结束后**的待治理数量比较，没有减少就停（有积压但治理
     * 一直忙时会命中这里），另加 [MAX_AFTER_NEW_MAIL_ROUNDS] 上限，双重防止死循环。
     */
    private suspend fun runAfterNewMail(accountId: Int, newCount: Int) {
        val lock = afterLocks.computeIfAbsent(accountId) { Mutex() }
        lock.withLock {
            var round = 0
            var lastPending = Int.MAX_VALUE
            while (round < MAX_AFTER_NEW_MAIL_ROUNDS) {
                try {
                    // 补跑时已经没有「新邮件」了，计数传 0
                    afterNewMail(accountId, if (round == 0) newCount else 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 钩子（治理 / 通知）失败不能影响同步结果，也不再补跑
                    log.warning(
                        "账户$accountId 同步后处理失败：${(e.message ?: "").cpSlice(0, 120)}",
                    )
                    return
                }
                round += 1
                val pending = try {
                    pendingGovernance()
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }
                    0
                }
                if (pending <= 0) {
                    return
                }
                if (pending >= lastPending) {
                    // 与上一轮相比没有减少：治理确实没有进展（例如一直忙），别空转
                    return
                }
                lastPending = pending
            }
        }
    }

    /** 到期账户 id 列表，对应 `_tick` 里那一段查询与判断。 */
    private fun dueAccountIds(): List<Int> {
        val now = clock.now()
        val out = ArrayList<Int>()
        val rows = service.db.query("SELECT id, poll_interval, last_sync FROM accounts WHERE enabled=1")
        for (row in rows) {
            val accountId = Math.toIntExact(row.long("id") ?: continue)
            val rawPoll = row.long("poll_interval") ?: 0L
            // Python 是 `r["poll_interval"] or 300`
            val poll = if (rawPoll == 0L) SyncAccount.DEFAULT_POLL_INTERVAL_SECONDS else rawPoll
            val lastSync = row.double("last_sync") ?: 0.0
            if (now - lastSync >= maxOf(MIN_POLL_INTERVAL_SECONDS, poll.toDouble())) {
                out += accountId
            }
        }
        return out
    }
}
