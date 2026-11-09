package com.mailhub.core.sync

import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.FakeSecretCipher
import com.mailhub.core.db.withTempDb
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyncCoordinator] 的调度语义：并发上限、每账户互斥、到期判断、治理补跑、取消与容错。
 *
 * 用 [FakeSyncService] 替换真正的 IMAP 同步（IMAP 是阻塞 IO，没法用可控时钟调时序），
 * 阻塞点全部用 `CountDownLatch` 同步，**不依赖真实时间**：测试推进到哪一步完全由事件决定，
 * 不靠 `sleep` 猜。数据库是临时目录里的真实 SQLite 文件（到期判断要查 `accounts` 表）。
 */
class SyncCoordinatorTest {

    private val clock = FakeClock(1_768_492_800.0) // 2026-01-15T00:00:00Z

    // ---------------- 并发 ----------------

    /** 全部账户一起点名时最多 3 个同时跑（与服务器端的 3 个 worker 一致）。 */
    @Test
    fun concurrencyIsCappedAtThree() = withCoordinator { db, fake, coordinator ->
        runBlocking {
            val ids = (1..6).map { insertAccount(db, name = "账户$it") }
            val gate = CountDownLatch(1)
            fake.gate = gate
            fake.gateAccounts = ids.toSet()
            fake.started = CountDownLatch(SYNC_CONCURRENCY)

            val job = async(Dispatchers.Default) { coordinator.syncAccounts(ids) }

            assertTrue("应有 3 个账户进入同步", fake.started.await(30, TimeUnit.SECONDS))
            assertEquals(
                "同时进行的同步不能超过 $SYNC_CONCURRENCY 个，实际 ${fake.maxRunning}",
                SYNC_CONCURRENCY,
                fake.maxRunning,
            )
            assertEquals(
                "第四个账户应还在排队，实际已进入 ${fake.calls}",
                SYNC_CONCURRENCY,
                fake.calls.size,
            )

            gate.countDown()
            val result = job.await()
            assertEquals("每个账户都要同步一次", ids.toSet(), result.keys)
            assertEquals("并发上限不能被突破，实际 ${fake.maxRunning}", SYNC_CONCURRENCY, fake.maxRunning)
        }
    }

    /** 同一账户互斥：正在同步时再次请求不会丢，而是在它结束之后再跑一次。 */
    @Test
    fun sameAccountRequestsAreSerializedAndNotLost() = withCoordinator { db, fake, coordinator ->
        runBlocking {
            val id = insertAccount(db, name = "账户A")
            val gate = CountDownLatch(1)
            fake.gate = gate
            fake.gateAccounts = setOf(id)
            fake.started = CountDownLatch(1)

            val first = async(Dispatchers.Default) { coordinator.syncAccounts(listOf(id)) }
            assertTrue("第一次同步应已开始", fake.started.await(30, TimeUnit.SECONDS))
            assertTrue("同步期间 isSyncing 应为真", coordinator.isSyncing(id))

            val second = async(Dispatchers.Default) { coordinator.syncAccounts(listOf(id)) }
            assertEquals("第二次请求要等互斥，实际 ${fake.calls}", listOf(id), fake.calls.toList())

            gate.countDown()
            first.await()
            second.await()

            assertEquals("同一个账户要跑两次，请求不能丢", listOf(id, id), fake.calls.toList())
            assertEquals("同一账户不能并发同步", 1, fake.maxPerAccount)
        }
    }

    // ---------------- 到期判断 ----------------

    /** `syncDue` 的条件：`enabled=1` 且 `now - last_sync >= max(60, poll_interval or 300)`。 */
    @Test
    fun syncDueHonoursEnabledIntervalAndDefaultPoll() = withCoordinator { db, fake, coordinator ->
        runBlocking {
            val due = insertAccount(db, name = "到期", lastSync = clock.now() - 400, pollInterval = 300)
            val tooSoon = insertAccount(db, name = "未到期", lastSync = clock.now() - 100, pollInterval = 300)
            val floor = insertAccount(db, name = "下限60秒", lastSync = clock.now() - 61, pollInterval = 10)
            val belowFloor =
                insertAccount(db, name = "不足60秒", lastSync = clock.now() - 59, pollInterval = 10)
            val defaultPoll =
                insertAccount(db, name = "间隔为0", lastSync = clock.now() - 301, pollInterval = 0)
            val defaultPollNotYet =
                insertAccount(db, name = "间隔为0未到", lastSync = clock.now() - 299, pollInterval = 0)
            val disabled = insertAccount(db, name = "停用", lastSync = 0.0, enabled = 0)

            val result = coordinator.syncDue()

            assertEquals(
                "到期账户应是 $due/$floor/$defaultPoll，实际 ${result.keys}",
                setOf(due, floor, defaultPoll),
                result.keys,
            )
            assertEquals("点了名就要同步", setOf(due, floor, defaultPoll), fake.calls.toSet())
            assertFalse("未到期的账户不该同步", tooSoon in fake.calls)
            assertFalse("不足 60 秒的账户不该同步", belowFloor in fake.calls)
            assertFalse("poll_interval=0 时按默认 300 秒，未到不该同步", defaultPollNotYet in fake.calls)
            assertFalse("停用的账户不该同步", disabled in fake.calls)
        }
    }

    /** `syncDue` 跳过正在同步的账户（对应 `_tick` 里的 `_is_syncing` 判断）。 */
    @Test
    fun syncDueSkipsAccountsThatAreSyncing(): Unit = withCoordinator { db, fake, coordinator ->
        runBlocking {
            val busy = insertAccount(db, name = "正在同步", lastSync = 0.0)
            val idle = insertAccount(db, name = "空闲", lastSync = 0.0)
            val gate = CountDownLatch(1)
            fake.gate = gate
            fake.gateAccounts = setOf(busy)
            fake.started = CountDownLatch(1)

            val job = async(Dispatchers.Default) { coordinator.syncAccounts(listOf(busy)) }
            assertTrue("手动同步应已开始", fake.started.await(30, TimeUnit.SECONDS))

            val result = coordinator.syncDue()

            assertFalse("正在同步的账户要跳过，实际 ${result.keys}", busy in result.keys)
            assertTrue("其余到期账户照常同步，实际 ${result.keys}", idle in result.keys)

            gate.countDown()
            job.await()
        }
    }

    // ---------------- 同步后钩子 ----------------

    /** 只有本轮有新邮件（`n > 0`）才调用 `afterNewMail`。 */
    @Test
    fun afterNewMailOnlyRunsForAccountsWithNewMail() {
        val hooks = CopyOnWriteArrayList<Pair<Int, Int>>()
        withCoordinator(afterNewMail = { id, n -> hooks += id to n }) { db, fake, coordinator ->
            runBlocking {
                val quiet = insertAccount(db, name = "没有新邮件")
                val loud = insertAccount(db, name = "有新邮件")
                fake.newCounts = mapOf(quiet to 0, loud to 3)

                coordinator.syncAccounts(listOf(quiet, loud))

                assertEquals("只有 n>0 的账户才跑钩子，实际 $hooks", listOf(loud to 3), hooks.toList())
            }
        }
    }

    /** 钩子在**释放信号量之后**执行：治理可能跑几分钟，不能占着并发名额。 */
    @Test
    fun afterNewMailRunsAfterTheSemaphoreIsReleased() {
        val hookGate = CountDownLatch(1)
        val hookStarted = CountDownLatch(1)
        val firstBox = AtomicInteger()
        withCoordinator(
            concurrency = 1,
            afterNewMail = { id, _ ->
                if (id == firstBox.get()) {
                    hookStarted.countDown()
                    hookGate.await(30, TimeUnit.SECONDS)
                }
            },
        ) { db, fake, coordinator ->
            runBlocking {
                firstBox.set(insertAccount(db, name = "先跑的"))
                val other = insertAccount(db, name = "后跑的")
                fake.newCounts = mapOf(firstBox.get() to 1)
                val otherEntered = CountDownLatch(1)
                fake.onEnter = { if (it == other) otherEntered.countDown() }

                val jobFirst = async(Dispatchers.Default) { coordinator.syncAccounts(listOf(firstBox.get())) }
                assertTrue("钩子应已开始", hookStarted.await(30, TimeUnit.SECONDS))

                val jobOther = async(Dispatchers.Default) { coordinator.syncAccounts(listOf(other)) }
                assertTrue(
                    "钩子还占着时另一个账户应该能同步（信号量已释放）",
                    otherEntered.await(30, TimeUnit.SECONDS),
                )

                hookGate.countDown()
                jobFirst.await()
                jobOther.await()
            }
        }
    }

    /** 同一账户的钩子串行：前一个还在跑时，后面那轮的新邮件不会并发调用钩子。 */
    @Test
    fun afterNewMailIsSerializedPerAccount() {
        val hookCalls = AtomicInteger()
        val hookStarted = CountDownLatch(1)
        val hookGate = CountDownLatch(1)
        withCoordinator(afterNewMail = { _, n ->
            if (n > 0) {
                hookCalls.incrementAndGet()
                hookStarted.countDown()
                hookGate.await(30, TimeUnit.SECONDS)
            }
        }) { db, fake, coordinator ->
            runBlocking {
                val id = insertAccount(db, name = "账户A")
                fake.newCounts = mapOf(id to 1)
                val twoSyncs = CountDownLatch(2)
                fake.onEnter = { twoSyncs.countDown() }

                val first = async(Dispatchers.Default) { coordinator.syncAccounts(listOf(id)) }
                assertTrue("钩子应已开始", hookStarted.await(30, TimeUnit.SECONDS))
                val second = async(Dispatchers.Default) { coordinator.syncAccounts(listOf(id)) }
                // 第二轮的同步已经跑完（进了两次 syncAccount），但钩子还被第一轮占着
                assertTrue("第二轮同步应已跑完", waitForSyncs(fake, id, 2, 30_000))

                assertEquals("钩子还在跑时第二轮不能并发调用钩子", 1, hookCalls.get())

                hookGate.countDown()
                first.await()
                second.await()
                assertEquals("两轮各调一次钩子，实际 ${hookCalls.get()}", 2, hookCalls.get())
            }
        }
    }

    // ---------------- 治理积压补跑 ----------------

    /** 待治理数量每轮都在减少时继续补跑，减到 0 就停。 */
    @Test
    fun governanceBacklogRerunsWhilePendingDecreases() {
        val hookCalls = AtomicInteger()
        val pending = CopyOnWriteArrayList(listOf(3, 2, 1, 0))
        val index = AtomicInteger()
        withCoordinator(
            afterNewMail = { _, _ -> hookCalls.incrementAndGet() },
            pendingGovernance = { pending.getOrElse(index.getAndIncrement()) { 0 } },
        ) { db, fake, coordinator ->
            runBlocking {
                val id = insertAccount(db)
                fake.newCounts = mapOf(id to 1)
                coordinator.syncAccounts(listOf(id))
            }
        }
        assertEquals("3→2→1→0：钩子应跑 4 轮，实际 ${hookCalls.get()}", 4, hookCalls.get())
    }

    /** 待治理数量不减少说明治理确实没有进展，立刻停止补跑。 */
    @Test
    fun governanceBacklogStopsWhenPendingDoesNotDecrease() {
        val hookCalls = AtomicInteger()
        withCoordinator(
            afterNewMail = { _, _ -> hookCalls.incrementAndGet() },
            pendingGovernance = { 5 },
        ) { db, fake, coordinator ->
            runBlocking {
                val id = insertAccount(db)
                fake.newCounts = mapOf(id to 1)
                coordinator.syncAccounts(listOf(id))
            }
        }
        assertEquals("一直不减：跑两轮就停，实际 ${hookCalls.get()}", 2, hookCalls.get())
    }

    /** 补跑有硬上限，永远不会空转成死循环。 */
    @Test
    fun governanceBacklogStopsAtTheRoundLimit() {
        val hookCalls = AtomicInteger()
        val pending = AtomicInteger(100)
        withCoordinator(
            afterNewMail = { _, _ -> hookCalls.incrementAndGet() },
            pendingGovernance = { pending.decrementAndGet() },
        ) { db, fake, coordinator ->
            runBlocking {
                val id = insertAccount(db)
                fake.newCounts = mapOf(id to 1)
                coordinator.syncAccounts(listOf(id))
            }
        }
        assertEquals(
            "补跑轮数上限是 $MAX_AFTER_NEW_MAIL_ROUNDS，实际 ${hookCalls.get()}",
            MAX_AFTER_NEW_MAIL_ROUNDS,
            hookCalls.get(),
        )
    }

    // ---------------- 取消与容错 ----------------

    /** 协程被取消时调用 `SyncService.abort`，把阻塞中的 IMAP socket 关掉。 */
    @Test
    fun cancellationAbortsTheRunningSession() = withCoordinator { db, fake, coordinator ->
        runBlocking {
            val id = insertAccount(db)
            val gate = CountDownLatch(1)
            fake.gate = gate
            fake.gateAccounts = setOf(id)
            fake.started = CountDownLatch(1)

            val job = launch(Dispatchers.Default) { coordinator.syncAccounts(listOf(id)) }
            assertTrue("同步应已开始", fake.started.await(30, TimeUnit.SECONDS))

            job.cancelAndJoin()

            assertTrue("取消时必须调用 abort，实际 ${fake.aborted}", fake.aborted.contains(id))
            gate.countDown()
        }
    }

    /** 一个账户抛异常不影响其他账户（对应 Python 每个账户一个线程、异常只杀自己）。 */
    @Test
    fun oneFailingAccountDoesNotAffectTheOthers() = withCoordinator { db, fake, coordinator ->
        runBlocking {
            val first = insertAccount(db, name = "账户1")
            val broken = insertAccount(db, name = "账户2")
            val third = insertAccount(db, name = "账户3")
            fake.newCounts = mapOf(first to 1, third to 2)
            fake.failing = setOf(broken)

            val result = coordinator.syncAccounts(listOf(first, broken, third))

            assertEquals("抛异常的账户按 0 封计", 0, result[broken])
            assertEquals("其他账户照常返回结果", 1, result[first])
            assertEquals(2, result[third])
            assertEquals("三个账户都要跑到", setOf(first, broken, third), fake.calls.toSet())
        }
    }

    /** `forgetAccount`：清掉状态与锁，之后的请求照常工作。 */
    @Test
    fun forgetAccountClearsStateAndLocks() = withCoordinator { db, fake, coordinator ->
        runBlocking {
            val id = insertAccount(db)
            coordinator.syncAccounts(listOf(id))
            assertFalse("同步结束后不该再是同步中", coordinator.isSyncing(id))
            assertTrue("同步后应有状态", fake.getStatus().containsKey("$id"))

            coordinator.forgetAccount(id)

            assertTrue("要调用 SyncService.forgetAccount，实际 ${fake.forgotten}", fake.forgotten.contains(id))
            assertFalse("状态应被清掉", fake.getStatus().containsKey("$id"))
            assertFalse("锁应被清掉", coordinator.isSyncing(id))

            // 忘掉之后仍然能正常同步
            coordinator.syncAccounts(listOf(id))
            assertEquals("同步请求应照常执行", listOf(id, id), fake.calls.toList())
        }
    }

    // ---------------- 替身与辅助 ----------------

    /**
     * 可控的同步替身：记录调用、统计并发、在 [gate] 上阻塞，抛异常 / 返回新邮件数都能指定。
     * 只用 `CountDownLatch` 同步，测试不依赖真实时间。
     */
    private class FakeSyncService(db: Db, clock: FakeClock) :
        SyncService(db, FakeSecretCipher(), clock) {

        /** 每次 `syncAccount` 的账户 id，按调用顺序。 */
        val calls = CopyOnWriteArrayList<Int>()

        /** `abort` 收到的账户 id。 */
        val aborted = CopyOnWriteArrayList<Int>()

        /** `forgetAccount` 收到的账户 id。 */
        val forgotten = CopyOnWriteArrayList<Int>()

        /** 同时进入 `syncAccount` 的最大个数。 */
        @Volatile
        var maxRunning: Int = 0
            private set

        /** 单个账户内同时进入 `syncAccount` 的最大个数（互斥是否生效）。 */
        @Volatile
        var maxPerAccount: Int = 0
            private set

        /** 进入 `syncAccount` 时倒计数：测试用来等「已经开始」。 */
        @Volatile
        var started: CountDownLatch = CountDownLatch(0)

        /** 进入 `syncAccount` 时的回调（测试用来等某个具体账户）。 */
        @Volatile
        var onEnter: (Int) -> Unit = {}

        /** [gateAccounts] 里的账户在 `syncAccount` 里等这个门打开。 */
        @Volatile
        var gate: CountDownLatch = CountDownLatch(0)

        /** 需要被 [gate] 拦住的账户；空集合表示谁都不拦。 */
        @Volatile
        var gateAccounts: Set<Int> = emptySet()

        /** 各账户返回的新邮件数，缺省 0。 */
        @Volatile
        var newCounts: Map<Int, Int> = emptyMap()

        /** 这些账户的 `syncAccount` 抛异常。 */
        @Volatile
        var failing: Set<Int> = emptySet()

        private val running = AtomicInteger()
        private val perAccount = ConcurrentHashMap<Int, AtomicInteger>()

        override fun syncAccount(accountId: Int): Int {
            val now = running.incrementAndGet()
            val per = perAccount.computeIfAbsent(accountId) { AtomicInteger() }
            val perNow = per.incrementAndGet()
            synchronized(this) {
                if (now > maxRunning) {
                    maxRunning = now
                }
                if (perNow > maxPerAccount) {
                    maxPerAccount = perNow
                }
            }
            calls += accountId
            onEnter(accountId)
            started.countDown()
            try {
                if (accountId in gateAccounts) {
                    gate.await(30, TimeUnit.SECONDS)
                }
                if (accountId in failing) {
                    throw IllegalStateException("账户 $accountId 的同步失败")
                }
                val newCount = newCounts[accountId] ?: 0
                status.set(accountId, "idle", "", newCount)
                return newCount
            } finally {
                per.decrementAndGet()
                running.decrementAndGet()
            }
        }

        override fun abort(accountId: Int) {
            aborted += accountId
        }

        override fun forgetAccount(accountId: Int) {
            forgotten += accountId
            super.forgetAccount(accountId)
        }
    }

    /** 建临时库、替身与协调器，用完关掉工作线程池。 */
    private fun <T> withCoordinator(
        concurrency: Int = SYNC_CONCURRENCY,
        afterNewMail: suspend (Int, Int) -> Unit = { _, _ -> },
        pendingGovernance: () -> Int = { 0 },
        block: (Db, FakeSyncService, SyncCoordinator) -> T,
    ): T = withTempDb(clock) { db ->
        db.initDb()
        val fake = FakeSyncService(db, clock)
        val coordinator = SyncCoordinator(fake, clock, concurrency, afterNewMail, pendingGovernance)
        try {
            block(db, fake, coordinator)
        } finally {
            coordinator.close()
        }
    }

    /** 插入一个账户（到期判断只看这几列）。 */
    private fun insertAccount(
        db: Db,
        name: String = "测试账户",
        lastSync: Double = 0.0,
        pollInterval: Long = 300,
        enabled: Int = 1,
    ): Int = Math.toIntExact(
        db.execute(
            """INSERT INTO accounts
               (name, provider, email, imap_host, imap_port, auth_type, secret_enc,
                enabled, poll_interval, last_uid, uidvalidity, last_sync, created_ts)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            name,
            "custom",
            "user@example.com",
            "127.0.0.1",
            993,
            "password",
            "",
            enabled,
            pollInterval,
            0,
            0,
            lastSync,
            clock.now(),
        ).lastInsertRowid,
    )

    /** 等到替身进入 `syncAccount` 达到 [times] 次（用轮询等待，不依赖具体耗时）。 */
    private fun waitForSyncs(fake: FakeSyncService, accountId: Int, times: Int, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (fake.calls.count { it == accountId } >= times) {
                return true
            }
            Thread.sleep(5)
        }
        return fake.calls.count { it == accountId } >= times
    }
}
