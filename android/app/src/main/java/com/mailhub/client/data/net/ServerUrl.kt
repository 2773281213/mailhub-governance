package com.mailhub.client.data.net

import com.mailhub.client.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 服务器地址校验。release 只接受 https；debug 额外接受模拟器和本机的 http 地址，
 * 与 debug 的 network_security_config 保持一致。
 * 带路径的地址（例如反向代理挂在 /mailhub）保留路径，API 路径拼在它后面。
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
        val withScheme = when {
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            else -> "https://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("服务器地址格式不正确"))
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
