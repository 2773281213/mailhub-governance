package com.mailhub.client.data.net

/** 网络层的统一错误类型。后端出错时返回 {"detail": "..."}，detail 原样带过来。 */
sealed class ApiError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {

    class Network(val detail: String, cause: Throwable? = null) : ApiError(detail, cause)

    class Unauthorized(val detail: String? = null) : ApiError(detail ?: "未授权")

    class Forbidden(val detail: String?, val protected: Boolean) : ApiError(detail ?: "操作被拒绝")

    class Conflict(val detail: String?) : ApiError(detail ?: "冲突")

    class TooManyRequests(val retryAfterSec: Int) : ApiError("请求过于频繁")

    class Server(val code: Int, val detail: String?) : ApiError(detail ?: "服务器错误 $code")
}
