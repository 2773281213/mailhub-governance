package com.mailhub.client.work

import com.mailhub.client.data.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 会话状态变化后要对后台周期任务做的事。 */
enum class ChangesAction {

    /** 进入已登录：调度后台拉取。 */
    Schedule,

    /** 进入未登录（任何原因）：取消后台拉取。 */
    Cancel,

    /** 恢复中，或状态没有跨过登录边界：什么都不做。 */
    None,
}

/**
 * 「前一状态 → 当前状态 → 动作」：进入已登录时调度、进入未登录时取消，恢复中不动作。
 * 同一侧（已登录 → 已登录、未登录 → 未登录）不重复调用，避免周期计时被清零。
 *
 * [previous] 为 null 表示还没有看到过任何状态（刚启动）：这时按进入当前一侧处理。
 */
fun changesActionFor(previous: SessionState?, current: SessionState): ChangesAction = when (current) {
    is SessionState.LoggedIn -> if (previous is SessionState.LoggedIn) ChangesAction.None else ChangesAction.Schedule
    is SessionState.LoggedOut -> if (previous is SessionState.LoggedOut) ChangesAction.None else ChangesAction.Cancel
    SessionState.Restoring -> ChangesAction.None
}

/**
 * 把会话状态绑到后台周期任务上，不依赖 Android，也不依赖界面是否在组合：
 * 调度只跟着会话走，按返回键关掉 App、系统回收 Activity 都不会取消已经排好的任务。
 *
 * 每次状态变化都交给 [changesActionFor] 判断，动作不是 [ChangesAction.None] 时回调一次。
 * 启动时先按「没有前一状态」处理当前值：一开始就是 [SessionState.LoggedOut] 时会取消一次，
 * 属于无害的清理（例如进程重启后系统里还留着上一轮排好的任务）。
 */
class ChangesScheduleBinding(
    private val states: StateFlow<SessionState>,
    private val scope: CoroutineScope,
    private val onAction: (ChangesAction) -> Unit,
) {

    /** 开始跟随会话状态；返回的 Job 随 [scope] 结束，调用方不需要自己取消。 */
    fun start(): Job = scope.launch {
        var previous: SessionState? = null
        states.collect { current ->
            val action = changesActionFor(previous, current)
            previous = current
            if (action != ChangesAction.None) {
                onAction(action)
            }
        }
    }
}
