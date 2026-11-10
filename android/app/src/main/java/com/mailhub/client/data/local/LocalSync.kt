package com.mailhub.client.data.local

import com.mailhub.client.work.ChangesSync
import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 前台同步的最小间隔：回到前台时距上次同步不足这个秒数就什么都不做（`LOCAL_MODE.md` 第五节）。 */
const val FOREGROUND_SYNC_MIN_SECONDS: Double = 60.0

/** 后台任务里一轮同步的总时长上限，见 `LOCAL_MODE.md` 第五节。 */
const val JOB_SYNC_TIMEOUT_MILLIS: Long = 8 * 60 * 1000L

/**
 * 本机模式的收信与通知入口，依赖全部从构造参数传入，可以在 JVM 上直接单测。
 *
 * 三件事：
 * - [notifyChanges] 跑一轮 `/api/changes`（[ChangesSync.runOnce]）：同步结束后发通知并推进游标。
 *   两个账户同时收到新邮件时钩子会并发进来，所以用 [notifyMutex] 串行，避免同一批邮件通知两遍；
 * - [onForeground] 回到前台时同步「到期」的账户，60 秒内重复回到前台只算一次；
 * - [syncForJob] 后台任务里同步到期的账户，最多 8 分钟；超时返回后
 *   [com.mailhub.core.sync.SyncCoordinator] 已经收到取消，会自己关掉 IMAP 连接。
 *
 * 不写日志：条目里有主题和验证码。
 */
class LocalSync(
    /** 一轮增量拉取与通知。 */
    private val changesSync: ChangesSync,
    /** 前台同步：`coordinator.syncAccounts(到期账户)`，由 [LocalRuntime] 组装。 */
    private val foregroundSync: suspend () -> Unit,
    /** 后台同步：`coordinator.syncDue()`，由 [LocalRuntime] 组装。 */
    private val jobSync: suspend () -> Unit,
    /** 同步跑在这个域里；退出登录时由 [LocalRuntime] 取消它的子协程。 */
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
) {

    /** 通知串行锁：同一时刻只有一轮 `/api/changes`，见类注释。 */
    private val notifyMutex = Mutex()

    /** 上一次前台触发的时间；`null` 表示本次进程里还没触发过。 */
    @Volatile
    private var lastForegroundAt: Double? = null

    /** 跑一轮增量拉取并发通知；并发调用按到达顺序串行执行。 */
    suspend fun notifyChanges() {
        notifyMutex.withLock {
            changesSync.runOnce()
        }
    }

    /**
     * 回到前台：距上次触发不足 [FOREGROUND_SYNC_MIN_SECONDS] 秒时什么都不做。
     *
     * 同步放到 [scope] 里异步执行，不阻塞界面；失败不影响下次触发（60 秒的计时照常走）。
     */
    fun onForeground() {
        val now = clock.now()
        val last = lastForegroundAt
        if (last != null && now - last < FOREGROUND_SYNC_MIN_SECONDS) {
            return
        }
        lastForegroundAt = now
        scope.launch {
            runCatching { foregroundSync() }
        }
    }

    /** 后台任务：同步到期的账户，最多 [JOB_SYNC_TIMEOUT_MILLIS] 毫秒。 */
    suspend fun syncForJob() {
        withTimeoutOrNull(JOB_SYNC_TIMEOUT_MILLIS) {
            jobSync()
        }
    }
}
