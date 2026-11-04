package com.mailhub.client.ui.screens.shell

/**
 * 手写导航的路由。一级页面四个；[Detail] 是收件箱打开的邮件详情，
 * [Secondary] 是索引层里的二级页面占位。
 */
sealed interface Route {

    data object Overview : Route

    data object Inbox : Route

    data object Otp : Route

    data object Security : Route

    /** 邮件详情：压栈打开，返回时弹栈。[id] 是邮件 id。 */
    data class Detail(val id: Long) : Route

    /** 索引层里的二级页面占位：[index] 是编号（05、06），[title] 既是栏目名也是标题。 */
    data class Secondary(val index: String, val title: String) : Route
}

/** 底部标签栏的 4 项：01 总览 / 02 收件 / 03 验证码 / 04 安全。 */
val AppTabs: List<Pair<String, String>> = listOf(
    "01" to "总览",
    "02" to "收件",
    "03" to "验证码",
    "04" to "安全",
)

/** 路由对应哪个标签；详情页属于「收件」，二级页面不对应任何标签，返回 -1。 */
fun tabIndexFor(route: Route): Int = when (route) {
    Route.Overview -> 0
    Route.Inbox -> 1
    is Route.Detail -> 1
    Route.Otp -> 2
    Route.Security -> 3
    is Route.Secondary -> -1
}

/** 标签序号换成路由。 */
fun routeForTab(index: Int): Route = when (index) {
    1 -> Route.Inbox
    2 -> Route.Otp
    3 -> Route.Security
    else -> Route.Overview
}

/** 一级页面的页头元信息：编号、栏目名、标题。 */
data class PageHeader(val index: String, val section: String, val title: String)

fun headerFor(route: Route): PageHeader = when (route) {
    Route.Overview -> PageHeader("01", "总览", "总览")
    Route.Inbox -> PageHeader("02", "收件", "收件")
    Route.Otp -> PageHeader("03", "验证码", "验证码")
    Route.Security -> PageHeader("04", "安全", "安全")
    is Route.Detail -> PageHeader("02", "邮件", "邮件")
    is Route.Secondary -> PageHeader(route.index, route.title, route.title)
}
