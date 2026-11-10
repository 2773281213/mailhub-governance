package com.mailhub.client.data.local

import com.mailhub.core.api.LocalApi
import com.mailhub.core.api.LocalRequest
import com.mailhub.core.api.LocalResponse
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** 一个请求处理函数：正式代码用真实 [LocalApi]，测试用可控的假数据。 */
internal fun interface LocalRequestHandler {
    fun handle(request: LocalRequest): LocalResponse
}

/**
 * 把 OkHttp 的请求交给 `:core` 的 [LocalApi]，再把它的响应照抄成 [Response]，见
 * `LOCAL_MODE.md` 3.1。
 *
 * **永远不调用 `chain.proceed`**：本机模式不监听端口，也不发出任何网络请求。挂在
 * `AuthInterceptor` 之后，所以请求头里已经带上了 `Authorization`（`LocalApi` 不校验令牌，
 * 但界面和测试看到的行为与服务器一致）。
 *
 * 映射规则（与服务器返回的形状一一对应）：
 * - 路径用解码后的 [HttpUrl.pathSegments] 拼成 `/a/b`，中文与空格都是解码后的原文；
 * - 查询参数用 [HttpUrl.queryParameterNames] / [HttpUrl.queryParameter]，同样取解码后的值；
 * - 请求头原样传；请求体用 [Buffer] 读成 UTF-8 字符串，没有请求体时是 `null`；
 * - 响应状态码、响应头原样带上（`X-MailHub-Protected` 靠它），响应体是
 *   `LocalResponse.body.toString()`，`Content-Type: application/json`。
 *
 * 只处理 [isLocalMode] 的主机：别的地址一律抛 [IOException]，绝不放到网络上去。
 */
class LocalApiInterceptor internal constructor(
    private val handler: LocalRequestHandler,
) : Interceptor {

    /** 正式用法：延迟取本机后端，第一次真正发请求时才打开本机库。 */
    constructor(api: () -> LocalApi) : this(LocalRequestHandler { request -> api().handle(request) })

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isLocalMode()) {
            // 不是本机会话却挂了这个拦截器：宁可失败也不能把请求发到网络上
            throw IOException("本机拦截器不处理该主机：${request.url.host}")
        }
        // OkHttp 的异步调用遇到非 IOException 会重新抛到工作线程，整个进程崩溃。
        // 打开本机库失败、本地库加载失败都可能走到这里，所以一律转成 IOException，页面显示错误就好。
        val local = try {
            handler.handle(request.toLocalRequest())
        } catch (error: IOException) {
            throw error
        } catch (error: Throwable) {
            // 只留类型：异常文本里可能有邮件内容或数据库路径
            throw IOException("本机后端不可用：${error::class.java.simpleName}", error)
        }
        // LocalApi.handle 是阻塞调用，期间用户可能已经取消了这次请求
        if (chain.call().isCanceled()) {
            throw IOException("Canceled")
        }
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(local.status)
            .message(if (local.status >= HTTP_BAD_REQUEST) "Error" else "OK")
            .header("Content-Type", JSON_CONTENT_TYPE)
            .body(local.body.toString().toResponseBody(JSON_MEDIA_TYPE))
        for ((name, value) in local.headers) {
            builder.header(name, value)
        }
        return builder.build()
    }

    private fun Request.toLocalRequest(): LocalRequest {
        val url = this.url
        val query = LinkedHashMap<String, String>()
        for (name in url.queryParameterNames) {
            query[name] = url.queryParameter(name).orEmpty()
        }
        val headerMap = LinkedHashMap<String, String>()
        for (name in headers.names()) {
            headerMap[name] = headers[name].orEmpty()
        }
        return LocalRequest(
            method = method,
            // pathSegments 已经是解码后的值；根路径 "/" 会得到一个空段，拼回来还是 "/"
            path = "/" + url.pathSegments.joinToString("/"),
            query = query,
            headers = headerMap,
            body = body?.let { requestBody ->
                val buffer = Buffer()
                requestBody.writeTo(buffer)
                buffer.readUtf8()
            },
        )
    }

    private companion object {
        /** 服务器所有接口都返回 JSON。 */
        const val JSON_CONTENT_TYPE = "application/json"

        val JSON_MEDIA_TYPE = JSON_CONTENT_TYPE.toMediaType()

        /** 400 及以上用 "Error"，其余用 "OK"；okhttp 不允许空的原因短语。 */
        const val HTTP_BAD_REQUEST = 400
    }
}
