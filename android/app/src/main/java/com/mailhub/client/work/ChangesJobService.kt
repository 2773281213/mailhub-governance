package com.mailhub.client.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.mailhub.client.MailHubApp
import com.mailhub.client.data.session.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

/**
 * 定期拉取 `/api/changes` 的后台任务。用平台自带的 JobScheduler，不引入 WorkManager。
 *
 * 会话从 App 自己的 [com.mailhub.client.data.session.SessionManager] 读：同一个存储文件在进程里
 * 只能有一个 DataStore，后台任务不能另开一份。请求用单独建的客户端，它收到 401 时
 * 不会改动会话（它不是当前会话的客户端），符合「401 什么都不做」。
 */
class ChangesJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            val outcome = try {
                runOneRound()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 不记录异常内容：里面可能带着服务器返回的邮件数据
                ChangesOutcome.Retry
            }
            jobFinished(params, outcome == ChangesOutcome.Retry)
        }
        return true
    }

    /**
     * 一轮后台任务。
     *
     * 本机会话：先同步到期的账户（最多 8 分钟，[com.mailhub.client.data.local.LocalSync.syncForJob]），
     * 再走下面原有的 `/api/changes` 拉取；服务器模式 `localSyncOrNull()` 返回 null，
     * 路径与原来一行都不差。
     */
    private suspend fun runOneRound(): ChangesOutcome {
        val container = (applicationContext as MailHubApp).container
        container.localSyncOrNull()?.syncForJob()
        return changesSyncOf(this).runOnce()
    }

    /** 系统中途叫停（比如断网）：取消这一轮，返回 true 让系统稍后重试。 */
    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** 用 App 的容器组装一轮同步。 */
internal fun changesSyncOf(context: Context): ChangesSync {
    val sessionManager = (context.applicationContext as MailHubApp).container.sessionManager
    return ChangesSync(
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
}

/**
 * 调度入口。登录后调用 [schedule]、退出后调用 [cancel]；接线由集成任务完成。
 */
object ChangesScheduler {

    /** 固定的任务 id，App 里只有这一个周期任务。 */
    const val JobId: Int = 4201

    /** 系统允许的最小周期。 */
    val PeriodMillis: Long = TimeUnit.MINUTES.toMillis(15)

    /** 已经调度过就不再重排：重复 schedule 会把周期计时清零。 */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JobId) != null) {
            return
        }
        scheduler.schedule(jobInfo(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JobId)
    }

    /** 需要网络、开机后保留（需要 RECEIVE_BOOT_COMPLETED 权限）。 */
    internal fun jobInfo(context: Context): JobInfo =
        JobInfo.Builder(JobId, ComponentName(context, ChangesJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PeriodMillis)
            .setPersisted(true)
            .build()
}
