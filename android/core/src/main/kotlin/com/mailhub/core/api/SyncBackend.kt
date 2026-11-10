package com.mailhub.core.api

import com.mailhub.core.sync.ServerActionResult
import com.mailhub.core.sync.SyncAccount
import com.mailhub.core.sync.SyncCoordinator
import com.mailhub.core.sync.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 写接口需要从同步子系统拿到的全部能力，逐个对应 `app.py` 里用到的 `sync` 函数。
 *
 * `LocalApi` 因此**不依赖协程**：触发同步是异步接口（立刻返回），探测与服务器动作是阻塞接口，
 * 由 OkHttp 的工作线程直接跑。真实实现在 [CoordinatorSyncBackend]，测试用假实现回放金标。
 *
 * **硬性约定**：`LocalApi` 不在 `Db.transaction {}` 里调用本接口的任何方法——同步会在另一条
 * 线程里去拿同一把数据库锁，事务里等它就会死锁。
 */
interface SyncBackend {

    /**
     * 异步触发单个账户的同步，立刻返回，对应 `sync.engine.trigger`。
     *
     * 与服务器一样**不检查账户是否存在**：队列里的 id 在真正同步时才判断。
     */
    fun triggerAccount(id: Int)

    /**
     * 异步触发全部启用账户的同步，立刻返回，对应 `sync.engine.trigger_all`
     * （条件是 `accounts.enabled=1`）。
     */
    fun triggerAll()

    /**
     * 账户删除后让它忘掉该账户的同步状态与锁，对应 `sync.forget_account`。
     *
     * 必须在数据库事务**之外**调用（可能中断正在跑的 IMAP 会话）。
     */
    fun forgetAccount(id: Int)

    /**
     * 登录、选中收件箱、关闭，对应 `oauth_auth.probe_connection`；失败时抛出原始异常，
     * 由调用方用 `connectionErrorText` 转成中文说明。阻塞。
     */
    fun probe(account: SyncAccount, credential: String)

    /**
     * 把不可逆的服务器动作回放到 IMAP，对应 `sync.apply_server_action`；阻塞。
     *
     * 返回服务器确认成功的本地 id 与错误说明；只有成功的那部分才允许改动本地状态。
     */
    fun applyServerAction(ids: List<Long>, action: String): ServerActionResult

    /**
     * 只用于只读路径的空实现：触发同步、忘记账户什么都不做；**探测与服务器动作直接抛异常**。
     *
     * 这两条故意不返回成功：漏注入真实实现时，`probe` 恒通过会让任意凭据都能入库，
     * `applyServerAction` 恒成功会让 `server=true` 的删除「只删本地、不碰邮箱」还回报 `ok:true`。
     * 失败比假成功安全——调用方会看到 400 / 500，而不是以为邮箱已经同步。
     */
    object NoOp : SyncBackend {
        override fun triggerAccount(id: Int) = Unit
        override fun triggerAll() = Unit
        override fun forgetAccount(id: Int) = Unit
        override fun probe(account: SyncAccount, credential: String): Unit =
            throw UnsupportedOperationException("SyncBackend.NoOp 不能探测连接：必须注入真实实现")

        override fun applyServerAction(ids: List<Long>, action: String): ServerActionResult =
            throw UnsupportedOperationException("SyncBackend.NoOp 不能回放服务器动作：必须注入真实实现")
    }
}

/**
 * [SyncBackend] 的正式实现：把请求转给 L7 的同步协调器与同步服务。
 *
 * 对应关系：
 * - `triggerAccount` → `scope.launch { coordinator.syncAccounts(listOf(id)) }`（`sync.engine.trigger`）；
 * - `triggerAll` → 先查 `accounts` 里 `enabled=1` 的 id，再 `scope.launch { coordinator.syncAccounts(ids) }`
 *   （照 `trigger_all` 的查询条件）；
 * - `forgetAccount` → `coordinator.forgetAccount`（`sync.forget_account`）；
 * - `probe` → `service.probeConnection`（`oauth_auth.probe_connection`）；
 * - `applyServerAction` → `service.applyServerAction`（`sync.apply_server_action`）。
 *
 * 触发的两次调用都只是 `launch`，不会阻塞 [LocalApi] 的调用线程；真正的阻塞 IO 由
 * [SyncCoordinator] 放到自己的工作线程上跑。
 */
class CoordinatorSyncBackend(
    private val service: SyncService,
    private val coordinator: SyncCoordinator,
    private val scope: CoroutineScope,
) : SyncBackend {

    override fun triggerAccount(id: Int) {
        scope.launch { coordinator.syncAccounts(listOf(id)) }
    }

    override fun triggerAll() {
        val ids = service.db
            .query("SELECT id FROM accounts WHERE enabled=1")
            .mapNotNull { it.long("id")?.let(Math::toIntExact) }
        if (ids.isEmpty()) {
            return
        }
        scope.launch { coordinator.syncAccounts(ids) }
    }

    override fun forgetAccount(id: Int) {
        coordinator.forgetAccount(id)
    }

    override fun probe(account: SyncAccount, credential: String) {
        service.probeConnection(account, credential)
    }

    override fun applyServerAction(ids: List<Long>, action: String): ServerActionResult =
        service.applyServerAction(ids, action)
}

/**
 * [SyncStatusSource] 的正式实现：把 `service.getStatus()` 转成 `/api/status` 与账户列表里
 * `sync_state` 需要的形状，对应 Python 的 `sync.get_status()`
 * （`{"账户 id": {"state": ..., "msg": ..., "ts": ..., "new": ...}}`）。
 */
class ServiceSyncStatusSource(private val service: SyncService) : SyncStatusSource {

    override fun status(): Map<String, Map<String, Any?>> =
        service.getStatus().mapValues { (_, value) ->
            linkedMapOf<String, Any?>(
                "state" to value.state,
                "msg" to value.msg,
                "ts" to value.ts,
                "new" to value.new,
            )
        }
}
