package com.mailhub.core.sync

import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 一个账户的同步状态，对应 Python `sync.STATUS[acc_id]` 里那个 dict
 * `{"state": ..., "msg": ..., "ts": ..., "new": ...}`。
 *
 * @property state `idle` / `syncing` / `error`
 * @property msg 给用户看的中文说明（连接中、拉取 N 封、错误原因）
 * @property ts 最近一次状态变更的时间戳（`time.time()`）
 * @property new 最近一次同步新增的邮件数
 */
data class AccountSyncStatus(
    val state: String,
    val msg: String = "",
    val ts: Double = 0.0,
    val new: Int = 0,
)

/** 状态取值，与 Python 里字面量相同。 */
const val SYNC_STATE_IDLE: String = "idle"
const val SYNC_STATE_SYNCING: String = "syncing"
const val SYNC_STATE_ERROR: String = "error"

/**
 * 内存里的同步状态表，对应 `sync.STATUS` + `_status_lock` + `_set_status` / `get_status` /
 * `forget_account`。
 *
 * Python 把它放在模块级全局变量里；本机模式只有进程内一个实例，所以由 [SyncService] 持有，
 * 测试可以各自新建一个，不会互相干扰。**线程安全**：所有读写都在同一把锁里。
 */
class SyncStatus(private val clock: Clock = SystemClock) {

    private val lock = ReentrantLock()
    private val statuses = LinkedHashMap<Int, AccountSyncStatus>()

    /** 对应 `_set_status`：写入（或覆盖）一个账户的状态。 */
    fun set(accountId: Int, state: String, msg: String = "", new: Int = 0) {
        lock.withLock {
            statuses[accountId] = AccountSyncStatus(state = state, msg = msg, ts = clock.now(), new = new)
        }
    }

    /**
     * 对应 `get_status`：返回一份快照，键是账户 id 的字符串形式（服务器端接口就是这么
     * 序列化的）。返回的是拷贝，调用方改它不会影响内部状态。
     */
    fun snapshot(): Map<String, AccountSyncStatus> = lock.withLock {
        val out = LinkedHashMap<String, AccountSyncStatus>(statuses.size * 2)
        for ((accountId, status) in statuses) {
            out[accountId.toString()] = status
        }
        out
    }

    /** 读取单个账户的状态，没有时返回 null。 */
    fun of(accountId: Int): AccountSyncStatus? = lock.withLock { statuses[accountId] }

    /** 对应 `forget_account` 里的那一半：账户删除后不再保留它的状态。 */
    fun forget(accountId: Int) {
        lock.withLock { statuses.remove(accountId) }
    }
}
