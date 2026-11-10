package com.mailhub.client.ui.screens.login

import com.mailhub.client.data.net.ApiError
import kotlin.math.ceil

/** 出错的是哪个输入框；null 表示不是某一个字段的问题。 */
enum class LoginField { Server, Password }

/** 一次登录失败的展示方式：哪个输入框画红色下划线 + 黑色正文说明。 */
data class LoginFailure(val field: LoginField?, val message: String)

/** 登录页界面状态。 */
data class LoginUiState(
    val serverUrl: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val failure: LoginFailure? = null,
    val expiredNotice: Boolean = false,
)

sealed interface LoginEvent {

    data class ServerChanged(val value: String) : LoginEvent

    data class PasswordChanged(val value: String) : LoginEvent

    data object Submit : LoginEvent

    /** 「本机独立使用」：不连服务器，直接进入本机会话。 */
    data object EnterLocal : LoginEvent
}

/** 进入本机模式失败时的说明：不指向任何一个输入框，也不透露底层异常。 */
const val LocalModeFailureText: String = "无法进入本机模式，请稍后再试"

/** AuthRepository 用这个前缀说明地址不合法。 */
private const val ServerUrlPrefix = "服务器地址不正确"

/**
 * 把登录失败映射成界面文案。
 * 地址不合法沿用 ServerUrl 给出的原因；其余按后端状态码给固定文案，版本过旧沿用 ApiError 的说明。
 */
fun loginFailureOf(error: Throwable): LoginFailure = when (error) {
    is ApiError.Network ->
        if (error.detail.startsWith(ServerUrlPrefix)) {
            LoginFailure(field = LoginField.Server, message = error.detail)
        } else {
            LoginFailure(field = null, message = "无法连接服务器，请检查地址和网络")
        }

    is ApiError.Unauthorized -> LoginFailure(field = LoginField.Password, message = "密码错误")

    is ApiError.TooManyRequests -> LoginFailure(
        field = null,
        message = "尝试过多，请 ${retryMinutes(error.retryAfterSec)} 分钟后再试",
    )

    is ApiError.Decode -> LoginFailure(field = null, message = "服务器返回的数据格式不正确")

    is ApiError.Server -> LoginFailure(field = null, message = error.detail ?: "服务器错误")

    is ApiError.Forbidden -> LoginFailure(field = null, message = error.detail ?: "操作被拒绝")

    is ApiError.Conflict -> LoginFailure(field = null, message = error.detail ?: "服务器状态冲突，请稍后再试")

    else -> LoginFailure(field = null, message = "无法连接服务器，请检查地址和网络")
}

/** retryAfterSec 向上取整到分钟，至少 1 分钟（后端可能给 0）。 */
fun retryMinutes(retryAfterSec: Int): Int = maxOf(1, ceil(retryAfterSec / 60.0).toInt())
