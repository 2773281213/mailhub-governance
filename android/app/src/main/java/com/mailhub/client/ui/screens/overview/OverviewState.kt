package com.mailhub.client.ui.screens.overview

import com.mailhub.client.data.model.GovernanceStatus
import com.mailhub.client.data.model.Overview
import com.mailhub.client.data.net.ApiError

/** 总览页界面状态。失败只描述「整页还没数据」的情况，局部动作失败用 [notice]。 */
data class OverviewUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val overview: Overview? = null,
    val governance: GovernanceStatus? = null,
    val governanceRunning: Boolean = false,
    /** 一次性提示（例如治理已经在跑），显示成底色的 Snackbar。 */
    val notice: String? = null,
)

sealed interface OverviewEvent {

    data object Refresh : OverviewEvent

    data object RunGovernance : OverviewEvent

    data object DismissNotice : OverviewEvent
}

/** 取数据和局部操作的失败文案，都用黑色正文说明，不用红色小字。 */
fun overviewErrorText(error: Throwable): String = when (error) {
    is ApiError.Network -> "无法连接服务器，请检查网络后重试"
    is ApiError.Unauthorized -> "登录已过期，请重新登录"
    is ApiError.Decode -> "服务器返回的数据格式不正确"
    is ApiError.TooManyRequests -> "请求过于频繁，请稍后再试"
    is ApiError.Forbidden -> error.detail ?: "操作被拒绝"
    is ApiError.Conflict -> error.detail ?: "服务器状态冲突，请稍后再试"
    is ApiError.Server -> error.detail ?: "服务器错误 ${error.code}"
    else -> "加载失败，请重试"
}
