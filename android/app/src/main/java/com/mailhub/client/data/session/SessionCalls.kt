package com.mailhub.client.data.session

import com.mailhub.client.data.net.ApiError

/** 未登录时各 Repository 统一返回的错误说明。 */
private const val NotLoggedIn: String = "尚未登录"

/**
 * 用当前会话的客户端调用功能自有接口；未登录时直接返回 [ApiError.Network]，不发请求。
 * 各功能的 Repository 都走这里，不再各写一份「取客户端 → 判空 → 调用」。
 */
suspend fun <S : Any, T> SessionManager.callService(type: Class<S>, block: suspend (S) -> T): Result<T> {
    val client = currentClient() ?: return Result.failure(ApiError.Network(NotLoggedIn))
    return client.callService(type, block)
}
