package com.mailhub.client.data.net

import com.mailhub.client.BuildConfig
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 服务器地址校验。release 只接受 https；debug 额外接受模拟器和本机的 http 地址，
 * 与 debug 的 network_security_config 保持一致。
 * 带路径的地址（例如反向代理挂在 /mailhub）保留路径，API 路径拼在它后面。
 *
 * 不接受带用户信息、查询串或片段（#）的地址：这些内容无法拼进接口路径，
 * 也容易把凭据写进日志。
 *
 * **不接受保留域名**：以 `.invalid` 结尾（以及单独的 `invalid`）的主机名是保留顶级域，
 * 本机会话的 `https://local.mailhub.invalid/` 也在这个范围内，见 `LOCAL_MODE.md` 3.1。
 * 手填进来只会得到一个永远连不上的地址，所以直接拒绝。比较主机名时去掉末尾的点、
 * 不区分大小写（`Example.INVALID.` 同样拒绝）。
 */
object ServerUrl {

    private val debugCleartextHosts = setOf("10.0.2.2", "127.0.0.1", "localhost")

    fun parse(input: String): Result<HttpUrl> =
        parse(input, allowCleartextDebugHosts = BuildConfig.DEBUG)

    fun parse(input: String, allowCleartextDebugHosts: Boolean): Result<HttpUrl> {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("服务器地址不能为空"))
        }
        // scheme 不区分大小写（HTTPS:// 等同于 https://）
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("服务器地址格式不正确"))
        if (url.host.isReservedHost()) {
            return Result.failure(IllegalArgumentException("服务器地址不能使用保留域名"))
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Result.failure(IllegalArgumentException("服务器地址不能带用户名或密码"))
        }
        if (url.query != null || url.fragment != null) {
            return Result.failure(IllegalArgumentException("服务器地址不能带查询参数或 # 片段"))
        }
        if (url.isHttps) {
            return Result.success(url)
        }
        if (!allowCleartextDebugHosts) {
            return Result.failure(IllegalArgumentException("正式版只支持 https 地址"))
        }
        if (url.host !in debugCleartextHosts) {
            return Result.failure(
                IllegalArgumentException("debug 版只允许 10.0.2.2、127.0.0.1、localhost 使用 http"),
            )
        }
        return Result.success(url)
    }
}

/**
 * 保留顶级域 `.invalid`（RFC 2606）以及它本身：这些名字永远解析不到真实服务器。
 *
 * 主机名末尾的点是 DNS 的绝对域名写法，先去掉；比较不区分大小写。
 */
private fun String.isReservedHost(): Boolean {
    val host = removeSuffix(".").lowercase(Locale.ROOT)
    return host == "invalid" || host.endsWith(".invalid")
}
