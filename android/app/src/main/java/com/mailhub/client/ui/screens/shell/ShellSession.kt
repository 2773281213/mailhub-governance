package com.mailhub.client.ui.screens.shell

import com.mailhub.client.data.session.SessionState

/**
 * 登录代际：只有「非已登录 → 已登录」才算新的一代。
 * 已登录内部换令牌（改密码、退出其他所有设备）沿用同一代，
 * 设置页正在显示的「密码已修改」不会被冲掉。
 */
fun isNewLoginGeneration(previous: SessionState?, current: SessionState): Boolean =
    current is SessionState.LoggedIn && previous !is SessionState.LoggedIn
