package com.mailhub.client.work

import com.mailhub.client.data.model.MessageSummary
import com.mailhub.client.data.net.ApiClient
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.session.Session

/** 每页取多少条。后端上限是 200，15 分钟一轮，50 条通常一页就够。 */
const val ChangesPageSize: Int = 50

/** 一轮最多翻几页：长时间离线后追进度用，翻满就留到下一轮。 */
const val ChangesMaxPages: Int = 10

/** 一轮最多发几条通知，和后端推送的上限一致，防止离线很久后一口气弹一大串。 */
const val ChangesMaxNotifications: Int = 5

/** 一轮执行的结论，[ChangesJobService] 据此决定要不要让系统重试。 */
sealed interface ChangesOutcome {

    /** 没有已保存的会话，或会话里的服务器地址无效。 */
    data object NoSession : ChangesOutcome

    /** 令牌已失效：什么都不做，下次登录会重新调度。 */
    data object Unauthorized : ChangesOutcome

    /** 网络错误、限流、服务器 5xx：交给系统按退避策略重试。 */
    data object Retry : ChangesOutcome

    /** 其他错误（比如返回格式不对）：重试也没用，等下一个周期。 */
    data object Failed : ChangesOutcome

    data class Done(val notified: Int) : ChangesOutcome
}

/**
 * 后台增量同步的一轮：读会话 → 按游标拉 `/api/changes` → 挑出要通知的邮件 → 推进游标。
 *
 * - 第一次执行（这台服务器还没有游标）只记下返回的游标，不发通知；
 * - `reset = true`（服务器恢复了旧备份）：把游标设为返回值，不发通知；
 * - 其余情况按 [channelFor] 挑出要通知的邮件，最多发 [ChangesMaxNotifications] 条（取最新的）；
 * - 游标按服务器地址分开存，见 [ChangesCursorStore]。
 *
 * 不依赖 Android 类型，可以在 JVM 测试里直接跑。不写日志：条目里有主题和验证码。
 */
class ChangesSync(
    private val session: suspend () -> Session?,
    private val clientFor: (Session) -> ApiClient?,
    private val cursors: ChangesCursorStore,
    private val notifier: ChangesNotifier,
) {

    suspend fun runOnce(): ChangesOutcome {
        val current = session() ?: return ChangesOutcome.NoSession
        val origin = originOf(current.serverUrl) ?: return ChangesOutcome.NoSession
        val client = clientFor(current) ?: return ChangesOutcome.NoSession
        val start = cursors.get(origin) ?: return initialize(client, origin)

        var cursor = start
        val eligible = mutableListOf<Pair<MessageSummary, NotifyChannel>>()
        var stop: ChangesOutcome? = null
        for (pageIndex in 0 until ChangesMaxPages) {
            val result = fetch(client, cursor)
            val failure = result.exceptionOrNull()
            if (failure != null) {
                stop = outcomeOf(failure)
                break
            }
            val page = result.getOrThrow()
            if (page.reset) {
                // 服务器的数据倒退了，之前收集到的也不可信，只重置游标
                cursors.set(origin, page.cursor)
                return ChangesOutcome.Done(notified = 0)
            }
            page.items.forEach { item ->
                channelFor(item)?.let { eligible += item to it }
            }
            val advanced = page.cursor > cursor
            cursor = maxOf(cursor, page.cursor)
            if (page.items.size < ChangesPageSize || !advanced) {
                break
            }
        }
        val toNotify = eligible.takeLast(ChangesMaxNotifications)
        toNotify.forEach { (item, channel) -> notifier.notify(item, channel) }
        if (cursor != start) {
            cursors.set(origin, cursor)
        }
        return stop ?: ChangesOutcome.Done(notified = toNotify.size)
    }

    /**
     * 第一次执行：after_id < 0 只返回当前水位，记下它，不补发历史邮件。
     * 不能用 0：服务器对 `<= 0` 处理相同，但本机模式的 0 表示「从头列出」（`LOCAL_MODE.md` 第五节）。
     */
    private suspend fun initialize(client: ApiClient, origin: String): ChangesOutcome {
        val result = fetch(client, -1)
        val failure = result.exceptionOrNull()
        if (failure != null) {
            return outcomeOf(failure)
        }
        cursors.set(origin, result.getOrThrow().cursor)
        return ChangesOutcome.Done(notified = 0)
    }

    private suspend fun fetch(client: ApiClient, afterId: Long): Result<ChangesPage> =
        client.callService(ChangesApi::class.java) { it.changes(afterId = afterId, limit = ChangesPageSize) }

    private fun outcomeOf(error: Throwable): ChangesOutcome = when (error) {
        is ApiError.Unauthorized -> ChangesOutcome.Unauthorized
        is ApiError.Network, is ApiError.TooManyRequests -> ChangesOutcome.Retry
        is ApiError.Server -> if (error.code >= 500) ChangesOutcome.Retry else ChangesOutcome.Failed
        else -> ChangesOutcome.Failed
    }
}
