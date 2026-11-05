package com.mailhub.client.ui.screens.overview

import com.mailhub.client.data.model.GovernanceStatus
import com.mailhub.client.data.model.Overview
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.ui.screens.common.apiErrorText

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

/**
 * 取数据和局部操作的失败文案，都用黑色正文说明，不用红色小字。
 * 除兜底一句外都与 [apiErrorText] 相同，这里只保留不同的那一支。
 */
fun overviewErrorText(error: Throwable): String = when (error) {
    is ApiError.Network,
    is ApiError.Unauthorized,
    is ApiError.Decode,
    is ApiError.TooManyRequests,
    is ApiError.Forbidden,
    is ApiError.Conflict,
    is ApiError.Server -> apiErrorText(error)

    else -> "加载失败，请重试"
}
