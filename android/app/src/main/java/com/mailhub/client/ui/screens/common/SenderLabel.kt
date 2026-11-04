package com.mailhub.client.ui.screens.common

/**
 * 列表行上显示的「发件人」文字。
 *
 * 只显示显示名（display name）等于替攻击者把任意字符串摆在发件人位置：
 * 一封钓鱼邮件的显示名可以写成 `service@paypal.com`，真实地址却是
 * `notice@paypa1-secure.example`，列表上看起来就像官方来信。所以：
 *
 * - 邮件被判为有风险（[isRisky]），或显示名里带 `@` 且与真实地址不同（忽略大小写）时，
 *   一律显示真实地址；
 * - 显示名为空时也退回真实地址；
 * - 其余情况显示显示名。
 *
 * 纯函数，不依赖 Android 或 Compose 运行时，方便直接断言。
 */
fun senderLabel(name: String, addr: String, isRisky: Boolean): String {
    val displayName = name.trim()
    val address = addr.trim()
    // 连真实地址都没有时，只能回到显示名，避免整格空着
    if (address.isEmpty()) return displayName
    val looksLikeAddress = displayName.contains('@')
    val forged = looksLikeAddress && !displayName.equals(address, ignoreCase = true)
    if (isRisky || forged) return address
    return displayName.ifEmpty { address }
}

/**
 * 列表用的风险判定。
 *
 * 后端 `messages.risk_level` 是 TEXT，取值 `none` / `medium` / `high`；
 * `MessageSummary` 只带这个字符串（没有 `MessageDetail.isRisky` 那个便捷属性），
 * 所以列表行在这里判一次。
 */
fun riskLevelIsRisky(riskLevel: String): Boolean =
    riskLevel == "medium" || riskLevel == "high"
