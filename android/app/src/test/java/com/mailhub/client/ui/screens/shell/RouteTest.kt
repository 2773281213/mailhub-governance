package com.mailhub.client.ui.screens.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 手写导航的纯规则：二级页面不归属标签、详情归属打开它的那一页、
 * 页头编号与栏目名、页头返回入口的文字。
 */
class RouteTest {

    @Test
    fun secondaryPagesBelongToNoTab() {
        assertEquals(-1, tabIndexFor(Route.Accounts))
        assertEquals(-1, tabIndexFor(Route.AddAccount))
        assertEquals(-1, tabIndexFor(Route.Settings))
        assertEquals(-1, tabIndexFor(listOf(Route.Overview, Route.Accounts)))
        assertEquals(-1, tabIndexFor(listOf(Route.Overview, Route.Settings)))
    }

    @Test
    fun tabPagesKeepTheirIndex() {
        assertEquals(0, tabIndexFor(Route.Overview))
        assertEquals(1, tabIndexFor(Route.Inbox))
        assertEquals(2, tabIndexFor(Route.Otp))
        assertEquals(3, tabIndexFor(Route.Security))
    }

    @Test
    fun detailBelongsToThePageThatOpenedIt() {
        // 从安全中心打开：标签停在「04 安全」
        assertEquals(3, tabIndexFor(listOf(Route.Security, Route.Detail(11))))
        // 从收件箱打开：标签停在「02 收件」
        assertEquals(1, tabIndexFor(listOf(Route.Inbox, Route.Detail(7))))
        // 单独一个详情没有出处
        assertEquals(-1, tabIndexFor(listOf(Route.Detail(7))))
    }

    @Test
    fun headersUseTheNumberedSections() {
        assertEquals(PageHeader("04", "安全", "安全"), headerFor(Route.Security))
        assertEquals(PageHeader("05", "账户", "账户"), headerFor(Route.Accounts))
        assertEquals(PageHeader("05", "添加账户", "添加账户"), headerFor(Route.AddAccount))
        assertEquals(PageHeader("06", "设置", "设置"), headerFor(Route.Settings))
    }

    @Test
    fun topLevelPagesHaveNoBackLabel() {
        assertNull(backLabelFor(listOf(Route.Overview)))
        assertNull(backLabelFor(listOf(Route.Security)))
        assertNull(backLabelFor(emptyList()))
    }

    @Test
    fun secondaryPagesUseTheBackEntry() {
        assertEquals("← 返回", backLabelFor(listOf(Route.Overview, Route.Accounts)))
        assertEquals("← 返回", backLabelFor(listOf(Route.Overview, Route.Settings)))
        // 添加账户是从账户页压栈的，入口写「← 账户」
        assertEquals("← 账户", backLabelFor(listOf(Route.Overview, Route.Accounts, Route.AddAccount)))
    }

    @Test
    fun detailBackEntryNamesThePreviousSection() {
        assertEquals("← 安全", backLabelFor(listOf(Route.Security, Route.Detail(11))))
        assertEquals("← 收件", backLabelFor(listOf(Route.Inbox, Route.Detail(7))))
    }

    @Test
    fun headerActionSwitchesBetweenIndexAndBack() {
        assertEquals(HeaderAction.Index, headerActionFor(listOf(Route.Security)))
        assertEquals(HeaderAction.Back("← 返回"), headerActionFor(listOf(Route.Overview, Route.Accounts)))
        assertEquals(HeaderAction.Back("← 安全"), headerActionFor(listOf(Route.Security, Route.Detail(11))))
    }

    @Test
    fun notificationOpensInboxThenDetail() {
        assertEquals(listOf<Route>(Route.Inbox, Route.Detail(42)), stackForOpenMessage(42))
    }
}
