package com.mailhub.client.data.model

/** 当前会话有哪些功能。页面只读这个值决定显示哪些区块，不再自己判断「是不是本机模式」。 */
data class Capabilities(
    /** 安全中心「已登录设备」，以及 `/api/sessions` 系列接口。 */
    val deviceSessions: Boolean = true,
    /** 设置页「治理与 AI」。 */
    val aiSettings: Boolean = true,
    /** 设置页「推送」。 */
    val pushSettings: Boolean = true,
    /** 设置页「清理与晨报」。 */
    val cleanupSettings: Boolean = true,
    /** 设置页「管理密码」。 */
    val adminPassword: Boolean = true,
) {
    companion object {
        /** 连接服务器：全部可用。 */
        val Server = Capabilities()

        /** 本机模式（`LOCAL_MODE.md` 第二节）：P1 之前这些都不可用。以后开放功能只改这张表。 */
        val Local = Capabilities(
            deviceSessions = false,
            aiSettings = false,
            pushSettings = false,
            cleanupSettings = false,
            adminPassword = false,
        )
    }
}

/**
 * 由会话模式与服务器下发的 [Meta] 算出这次会话的能力表。
 *
 * 本机会话直接返回 [Capabilities.Local]，不看 [meta]：本机引擎随 APK 发布，有哪些功能以这张表
 * 为准；而且本机的 `/api/meta` 目前把 `sessions` 报成 `true`，不能信。
 *
 * 服务器会话从 [Capabilities.Server] 出发，只按 `features` 收窄「已登录设备」：`meta` 为 null、
 * 或 `features` 里没有 `sessions` 这个键（旧服务器）都视为支持。其余四项服务器目前没有对应的键，
 * 保持可用。
 */
fun capabilitiesOf(isLocal: Boolean, meta: Meta?): Capabilities = if (isLocal) {
    Capabilities.Local
} else {
    Capabilities.Server.copy(deviceSessions = meta?.features?.get("sessions") ?: true)
}
