package com.mailhub.client.data.local

import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.work.ChangesCursorStore
import com.mailhub.client.work.ChangesNotifier
import com.mailhub.client.work.ChangesSync
import com.mailhub.core.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台节流与通知串行（`LOCAL_MODE.md` 第五节）。
 *
 * 用真实的 [ChangesSync] 配一个可控的会话来源：它一被调用就记下当前并发数并挂起一会儿，
 * 所以「两轮通知同时跑」会被直接观察到，不需要真的发通知或连服务器。
 */
class LocalSyncTest {

    /** 记录 `/api/changes` 被调用的次数与最大并发数。 */
    private class Counter {
        var calls = 0
        var active = 0
        var maxActive = 0
    }

    private fun changesSyncOf(counter: Counter, holdMillis: Long): ChangesSync = ChangesSync(
        session = {
            counter.calls += 1
            counter.active += 1
            counter.maxActive = maxOf(counter.maxActive, counter.active)
            delay(holdMillis)
            counter.active -= 1
            // 没有会话：runOnce 立刻返回 NoSession，不会碰游标、不会发通知
            null
        },
        clientFor = { null },
        cursors = ChangesCursorStore(InMemoryDataStore()),
        notifier = ChangesNotifier { _, _ -> },
    )

    @Test
    fun notifyChangesRunsOneRoundAtATime() = runTest {
        val counter = Counter()
        val sync = LocalSync(
            changesSync = changesSyncOf(counter, holdMillis = 100),
            foregroundSync = {},
            jobSync = {},
            scope = this,
        )

        // 两个账户同时收到新邮件：钩子会并发调用 notifyChanges
        val first = launch { sync.notifyChanges() }
        val second = launch { sync.notifyChanges() }
        advanceUntilIdle()
        first.join()
        second.join()

        assertEquals("两轮都要跑", 2, counter.calls)
        assertEquals("Mutex 保证同一时刻只有一轮", 1, counter.maxActive)
    }

    @Test
    fun foregroundSyncIsThrottledToSixtySeconds() = runTest {
        var now = 1_700_000_000.0
        var calls = 0
        val sync = LocalSync(
            changesSync = changesSyncOf(Counter(), holdMillis = 0),
            foregroundSync = { calls += 1 },
            jobSync = {},
            scope = this,
            clock = Clock { now },
        )

        sync.onForeground()
        advanceUntilIdle()
        assertEquals("第一次回到前台要同步", 1, calls)

        now += 30.0
        sync.onForeground()
        advanceUntilIdle()
        assertEquals("不足 60 秒什么都不做", 1, calls)

        now += 30.0
        sync.onForeground()
        advanceUntilIdle()
        assertEquals("满 60 秒可以再同步", 2, calls)

        now += 59.9
        sync.onForeground()
        advanceUntilIdle()
        assertEquals("59.9 秒仍然不足 60 秒", 2, calls)
    }

    @Test
    fun foregroundFailureDoesNotBreakTheNextTrigger() = runTest {
        var now = 1_700_000_000.0
        var calls = 0
        val sync = LocalSync(
            changesSync = changesSyncOf(Counter(), holdMillis = 0),
            foregroundSync = {
                calls += 1
                error("同步失败")
            },
            jobSync = {},
            scope = this,
            clock = Clock { now },
        )

        sync.onForeground()
        advanceUntilIdle()
        now += 60.0
        sync.onForeground()
        advanceUntilIdle()

        assertEquals("失败不能让下一次触发被吞掉", 2, calls)
    }

    @Test
    fun jobSyncIsCancelledAfterEightMinutes() = runTest {
        var finished = false
        var cancelled = false
        val sync = LocalSync(
            changesSync = changesSyncOf(Counter(), holdMillis = 0),
            foregroundSync = {},
            jobSync = {
                try {
                    // 比 8 分钟上限还长：后台任务不能一直占着系统给的时间
                    delay(JOB_SYNC_TIMEOUT_MILLIS + 60_000L)
                    finished = true
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            },
            scope = this,
        )

        sync.syncForJob()

        assertFalse("超时后不应再继续同步", finished)
        assertTrue("超时必须真的取消协程（SyncCoordinator 靠它关 IMAP）", cancelled)
        assertEquals("上限是 8 分钟", JOB_SYNC_TIMEOUT_MILLIS, currentTime)
    }
}
