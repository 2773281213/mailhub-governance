package com.mailhub.client.ui.screens.shell

import com.mailhub.client.data.session.SessionState

/**
 * 登录代际：只有「非已登录 → 已登录」才算新的一代。
 * 已登录内部换令牌（改密码、退出其他所有设备）沿用同一代，
 * 设置页正在显示的「密码已修改」不会被冲掉。
 */
fun isNewLoginGeneration(previous: SessionState?, current: SessionState): Boolean =
    current is SessionState.LoggedIn && previous !is SessionState.LoggedIn

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
 */
fun changesActionFor(previous: SessionState, current: SessionState): ChangesAction = when (current) {
    is SessionState.LoggedIn -> if (previous is SessionState.LoggedIn) ChangesAction.None else ChangesAction.Schedule
    is SessionState.LoggedOut -> if (previous is SessionState.LoggedOut) ChangesAction.None else ChangesAction.Cancel
    SessionState.Restoring -> ChangesAction.None
}
