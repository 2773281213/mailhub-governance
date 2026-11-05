package com.mailhub.client.ui.screens.common

import com.mailhub.client.data.net.ApiError

/**
 * 网络层错误转成给用户看的一句话。后端带了 `detail`（例如 400 的中文校验说明）时原样显示。
 *
 * 新页面直接用这个；已有页面各自的同名映射之后统一迁过来。
 */
fun apiErrorText(error: Throwable): String = when (error) {
    is ApiError.Network -> "无法连接服务器，请检查网络后重试"
    is ApiError.Unauthorized -> "登录已过期，请重新登录"
    is ApiError.Decode -> "服务器返回的数据格式不正确"
    is ApiError.TooManyRequests -> "请求过于频繁，请稍后再试"
    is ApiError.Forbidden -> error.detail ?: "操作被拒绝"
    is ApiError.Conflict -> error.detail ?: "服务器状态冲突，请稍后再试"
    is ApiError.Server -> error.detail ?: "服务器错误 ${error.code}"
    else -> "操作失败，请重试"
}
