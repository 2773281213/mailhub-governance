package com.mailhub.client.data.local

import com.mailhub.client.data.session.Session
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 本机会话的保留地址，见 `LOCAL_MODE.md` 3.1。
 *
 * 与 `:core` 的 `LocalApi.LOCAL_BASE_URL` 指同一台主机（那边是常量且不可见，这里照抄主机名）；
 * `.invalid` 是保留顶级域，永远解析不到真实服务器，所以这个地址只可能被
 * [LocalApiInterceptor] 在进程内接住。
 */
const val LOCAL_SERVER_URL: String = "https://local.mailhub.invalid/"

/** 本机模式的主机名；只比较主机名，scheme 与端口都不参与判断。 */
const val LOCAL_MODE_HOST: String = "local.mailhub.invalid"

/** 本机会话的并发上限：请求在拦截器里阻塞执行，几十秒的探测和治理不能把界面请求堵住。 */
const val LOCAL_MAX_REQUESTS_PER_HOST: Int = 32

/**
 * 这个地址是不是本机会话的保留地址。
 *
 * 只比较主机名：本机模式不监听端口，写成 `http://`、带不带端口都还是同一个进程内地址。
 * 界面分支（L10b）也用它判断要不要隐藏「已登录设备」这类只在服务器模式出现的块。
 */
fun HttpUrl.isLocalMode(): Boolean = host.equals(LOCAL_MODE_HOST, ignoreCase = true)

/** 这个会话是不是本机会话；存储里的地址损坏时按服务器模式处理。 */
val Session.isLocal: Boolean
    get() = serverUrl.toHttpUrlOrNull()?.isLocalMode() == true
