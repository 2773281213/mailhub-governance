package com.mailhub.client.data.local

import android.content.Context
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionState
import com.mailhub.client.work.ChangesCursorStore
import com.mailhub.client.work.ChangesSync
import com.mailhub.client.work.SystemChangesNotifier
import com.mailhub.client.work.originOf
import com.mailhub.core.Clock
import com.mailhub.core.SystemClock
import com.mailhub.core.api.CoordinatorSyncBackend
import com.mailhub.core.api.LocalApi
import com.mailhub.core.api.ServiceSyncStatusSource
import com.mailhub.core.db.Db
import com.mailhub.core.governance.GovernanceService
import com.mailhub.core.sync.SyncCoordinator
import com.mailhub.core.sync.SyncService
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 本机库文件名，放在 App 私有的 databases 目录里，随卸载一起删除。 */
const val LOCAL_DATABASE_NAME: String = "mailhub_local.db"

/**
 * 组装本机后端（`LOCAL_MODE.md` 第三节）：数据库、治理、IMAP 同步、[LocalApi] 与通知入口。
 *
 * **进程内只有一个，而且全部延迟创建。** 服务器模式的用户不能因为装了本机模式就打开数据库、
 * 加载打包的 SQLite 本地库：所有成员都要等到第一次真正用到本机后端时（`LocalApiInterceptor`
 * 的第一次请求、`enterLocalMode` 的准备、本机会话的后台任务）才创建，并且首次打开放在
 * [Dispatchers.IO] 上。
 *
 * [scope] 只跑同步与通知。退出本机模式时调用 [cancelSync]：取消进行中的同步（`SyncCoordinator`
 * 收到取消会关掉 IMAP），**数据库不关、数据不删**，再次进入本机模式看到的是原来的数据。
 *
 * 数据库锁的约束沿用 `:core`：这里只把「打开库、查最大 id、查到期账户」这类短查询放在锁外，
 * 网络请求（IMAP、`/api/changes` 实际是本机库）都在 `Db.transaction` 之外。
 */
class LocalRuntime(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val clock: Clock = SystemClock,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initMutex = Mutex()

    @Volatile
    private var backend: LocalBackend? = null

    /** 本机后端与它派生出来的入口，第一次打开数据库时一起创建。 */
    private class LocalBackend(
        val api: LocalApi,
        val sync: LocalSync,
        val db: Db,
    )

    /**
     * 本机接口，供 [LocalApiInterceptor] 使用。
     *
     * 拦截器跑在 OkHttp 的工作线程上、方法签名是阻塞的；首次调用时用 [runBlocking] 把「打开
     * 数据库」放到 IO 线程上完成，之后直接返回已建好的实例。
     */
    fun api(): LocalApi = backend?.api ?: runBlocking { ensureBackend().api }

    /** 本机通知入口，供前台与后台任务使用；首次调用时在 IO 线程上打开本机库。 */
    suspend fun sync(): LocalSync = ensureBackend().sync

    /** 本机库里当前最大的邮件 id，空库为 0；进入本机模式时用它设通知游标（`LOCAL_MODE.md` 3.1）。 */
    suspend fun maxMessageId(): Long = withContext(Dispatchers.IO) {
        ensureBackend().db.queryOne("SELECT COALESCE(MAX(id), 0) AS m FROM messages")?.long("m") ?: 0L
    }

    /** 退出本机模式：取消进行中的同步，数据库与数据原样保留。 */
    fun cancelSync() {
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun ensureBackend(): LocalBackend = backend ?: initMutex.withLock {
        backend ?: withContext(Dispatchers.IO) { createBackend() }.also { backend = it }
    }

    /**
     * 创建整套本机后端。只在 IO 线程上调用一次（[initMutex] + [backend] 双重检查）。
     *
     * 治理钩子对应 `sync.py` 的 `SyncEngine._spawn`：某个账户同步到新邮件后立刻跑
     * `ai_classify_pending(max(30, n))`（即 `runGovernance(limit = max(30, n), trigger = "sync")`），
     * 然后触发一次通知。钩子不依赖 `accountId`：它可能是 0（不针对某个账户）。
     * 治理忙碌时 `runGovernance` 返回 `busy=true` 而不是抛异常，补跑交给 [SyncCoordinator]。
     *
     * **不要在 `Db.transaction {}` 里做网络请求或长时间工作**：这里没有任何事务包裹。
     */
    private fun createBackend(): LocalBackend {
        val path = context.getDatabasePath(LOCAL_DATABASE_NAME).absolutePath
        // 先建好父目录：Db 自己也会 mkdirs，这里是让「拿不到数据库目录」尽早失败
        File(path).absoluteFile.parentFile?.mkdirs()
        val db = Db(path, clock).also { it.initDb() }
        // AI 关闭：GovernanceService 默认用 GovernanceAi.Disabled
        val governance = GovernanceService(db, clock)
        val cipher = KeystoreSecretCipher()
        val service = SyncService(db, cipher, clock)
        // 协调器与 LocalSync 互相需要：钩子持有稍后赋值的 sync，真正的同步只会在两者都建好之后发生
        lateinit var sync: LocalSync
        val coordinator = SyncCoordinator(
            service,
            clock,
            afterNewMail = { _, newCount ->
                governance.runGovernance(
                    limit = maxOf(GOVERNANCE_MIN_LIMIT, newCount),
                    trigger = "sync",
                )
                sync.notifyChanges()
            },
            pendingGovernance = { governance.governanceStatus().pending },
        )
        val syncBackend = CoordinatorSyncBackend(service, coordinator, scope)
        val api = LocalApi(
            db = db,
            clock = clock,
            zone = ZoneId.systemDefault(),
            status = ServiceSyncStatusSource(service),
            governance = governance,
            cipher = cipher,
            sync = syncBackend,
            // 对应 app.py 的 _trigger_read_state_sync：已读状态要写回服务器的账户各触发一次
            onReadStateChanged = { ids -> ids.distinct().sorted().forEach(syncBackend::triggerAccount) },
        )
        sync = LocalSync(
            changesSync = changesSync(),
            foregroundSync = { coordinator.syncAccounts(foregroundAccountIds(db)) },
            jobSync = { coordinator.syncDue() },
            scope = scope,
            clock = clock,
        )
        return LocalBackend(api, sync, db)
    }

    /** 一轮 `/api/changes`：会话、客户端、游标与通知全部沿用现有的服务器模式实现。 */
    private fun changesSync(): ChangesSync = ChangesSync(
        session = {
            sessionManager.restore()
            (sessionManager.state.value as? SessionState.LoggedIn)?.session
        },
        clientFor = { session ->
            session.serverUrl.toHttpUrlOrNull()?.let { sessionManager.newClient(it, session.token) }
        },
        cursors = ChangesCursorStore.of(context),
        notifier = SystemChangesNotifier(context),
    )

    /**
     * 前台要同步的账户：`enabled=1` 且距上次同步已满 [FOREGROUND_SYNC_MIN_SECONDS] 秒。
     *
     * 与 `SyncCoordinator.syncDue` 的差别只在到期判定：前台不按每个账户的 `poll_interval`，
     * 回到前台时只要是「60 秒没同步过」的启用账户都同步一次（`LOCAL_MODE.md` 第五节）。
     */
    private fun foregroundAccountIds(db: Db): List<Int> {
        val rows = db.query(
            "SELECT id FROM accounts WHERE enabled=1 AND last_sync <= ?",
            clock.now() - FOREGROUND_SYNC_MIN_SECONDS,
        )
        return rows.mapNotNull { row -> row.long("id")?.let(Math::toIntExact) }
    }

    private companion object {
        /** 对应 `sync.py` 的 `ai_classify_pending(limit: int = 30)`：至少治理 30 封。 */
        const val GOVERNANCE_MIN_LIMIT = 30
    }
}

/**
 * 进入本机模式时把通知游标设成本机库当前的 `max(id)`（空库为 0），见 `LOCAL_MODE.md` 3.1。
 *
 * 这一步必须在 `SessionManager.activate` **之前**完成：否则从激活到游标就绪之间到达的
 * `/api/changes` 会按「第一次执行」把游标设成当前水位，首次同步导入的邮件就被当成历史基线吞掉。
 */
suspend fun initializeLocalCursor(cursors: ChangesCursorStore, maxMessageId: suspend () -> Long) {
    val origin = originOf(LOCAL_SERVER_URL) ?: return
    cursors.set(origin, maxMessageId())
}
