package com.mailhub.client.ui.screens.shell

/**
 * 手写导航的路由。一级页面四个；[Detail] 是列表打开的邮件详情；
 * [Accounts]、[AddAccount]、[Settings] 是索引层压栈打开的二级页面。
 */
sealed interface Route {

    data object Overview : Route

    data object Inbox : Route

    data object Otp : Route

    data object Security : Route

    /** 邮件详情：压栈打开，返回时弹栈。[id] 是邮件 id。 */
    data class Detail(val id: Long) : Route

    /** 账户列表：索引层「05 账户」压栈打开。 */
    data object Accounts : Route

    /** 添加账户：账户页的「添加账户」压栈打开。 */
    data object AddAccount : Route

    /** 设置：索引层「06 设置」压栈打开。 */
    data object Settings : Route
}

/** 底部标签栏的 4 项：01 总览 / 02 收件 / 03 验证码 / 04 安全。 */
val AppTabs: List<Pair<String, String>> = listOf(
    "01" to "总览",
    "02" to "收件",
    "03" to "验证码",
    "04" to "安全",
)

/**
 * 单个路由对应哪个标签。[Detail] 自己不知道是从哪一页打开的，这里返回 -1；
 * 详情页要按栈判断，用 [tabIndexFor] 的列表重载。
 */
fun tabIndexFor(route: Route): Int = when (route) {
    Route.Overview -> 0
    Route.Inbox -> 1
    Route.Otp -> 2
    Route.Security -> 3
    is Route.Detail -> -1
    Route.Accounts, Route.AddAccount, Route.Settings -> -1
}

/**
 * 页面栈顶上那一页归属哪个标签：详情页归属打开它的那一页——从安全中心打开就是
 * 「04 安全」，从收件箱打开就是「02 收件」；二级页面不对应任何标签，返回 -1。
 */
fun tabIndexFor(stack: List<Route>): Int {
    val current = stack.lastOrNull() ?: return -1
    if (current is Route.Detail) {
        return stack.dropLast(1).lastOrNull()?.let { tabIndexFor(it) } ?: -1
    }
    return tabIndexFor(current)
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
    Route.Accounts -> PageHeader("05", "账户", "账户")
    Route.AddAccount -> PageHeader("05", "添加账户", "添加账户")
    Route.Settings -> PageHeader("06", "设置", "设置")
}

/**
 * 二级页面页头操作入口的文字；一级页面（含详情）返回 null 表示用「索引」。
 * 详情页写「← 」加上打开它的那一页的栏目名，例如从安全中心打开写「← 安全」。
 */
fun backLabelFor(stack: List<Route>): String? {
    val current = stack.lastOrNull() ?: return null
    return when (current) {
        is Route.Detail -> stack.dropLast(1).lastOrNull()?.let { "← ${headerFor(it).section}" }
        Route.Accounts, Route.Settings -> "← 返回"
        Route.AddAccount -> "← 账户"
        else -> null
    }
}

/** 页头操作入口：一级页面打开全屏索引层，二级页面弹栈返回。 */
sealed interface HeaderAction {

    data object Index : HeaderAction

    data class Back(val label: String) : HeaderAction
}

fun headerActionFor(stack: List<Route>): HeaderAction =
    backLabelFor(stack)?.let { HeaderAction.Back(it) } ?: HeaderAction.Index

/** 点通知后要落到的页面栈：切到「02 收件」标签，再压栈详情。 */
fun stackForOpenMessage(id: Long): List<Route> = listOf(Route.Inbox, Route.Detail(id))
