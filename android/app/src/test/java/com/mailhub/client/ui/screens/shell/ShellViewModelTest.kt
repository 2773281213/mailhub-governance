package com.mailhub.client.ui.screens.shell

import com.mailhub.client.data.FakeCipher
import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.data.repo.AuthRepository
import com.mailhub.client.data.repo.MailRepository
import com.mailhub.client.data.session.SessionManager
import com.mailhub.client.data.session.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外壳页面栈：索引层压栈打开账户与设置、账户页 → 添加账户 → 弹回、
 * 通知点击后切到收件标签并打开详情。只调用同步方法，不发请求。
 */
class ShellViewModelTest {

    private fun viewModel(): ShellViewModel {
        val manager = SessionManager(SessionStore(InMemoryDataStore(), FakeCipher()))
        return ShellViewModel(
            mailRepository = MailRepository(manager),
            authRepository = AuthRepository(manager),
        )
    }

    @Test
    fun indexOpensAccountsAndClosesTheIndexLayer() {
        val viewModel = viewModel()

        viewModel.openIndex()
        viewModel.openAccounts()

        assertEquals(listOf<Route>(Route.Overview, Route.Accounts), viewModel.stack.value)
        assertFalse(viewModel.indexOpen.value)
    }

    @Test
    fun indexOpensSettingsAndClosesTheIndexLayer() {
        val viewModel = viewModel()

        viewModel.openIndex()
        viewModel.openSettings()

        assertEquals(listOf<Route>(Route.Overview, Route.Settings), viewModel.stack.value)
        assertFalse(viewModel.indexOpen.value)
    }

    @Test
    fun accountsOpenAddAccountAndPopBack() {
        val viewModel = viewModel()

        viewModel.push(Route.Accounts)
        viewModel.openAddAccount()
        assertEquals(Route.AddAccount, viewModel.stack.value.last())

        assertTrue(viewModel.pop())
        assertEquals(Route.Accounts, viewModel.stack.value.last())
        assertEquals(listOf<Route>(Route.Overview, Route.Accounts), viewModel.stack.value)
    }

    @Test
    fun detailOpenedFromSecurityKeepsTheSecurityTab() {
        val viewModel = viewModel()

        viewModel.selectTab(3)
        viewModel.push(Route.Detail(11))

        assertEquals(Route.Detail(11), viewModel.stack.value.last())
        assertEquals(3, tabIndexFor(viewModel.stack.value))
    }

    @Test
    fun notificationSwitchToInboxAndOpensTheDetail() {
        val viewModel = viewModel()

        viewModel.selectTab(3)
        viewModel.openMessage(42)

        assertEquals(listOf<Route>(Route.Inbox, Route.Detail(42)), viewModel.stack.value)
        assertEquals(1, tabIndexFor(viewModel.stack.value))
        assertEquals("← 收件", backLabelFor(viewModel.stack.value))
    }

    /**
     * 旋转、切深色、改字号都会重建 Activity，重建后组合里会再调一次 start()。
     * start() 只对一个实例生效一次：页面栈必须留在用户原来那一页，不能被重置回总览。
     */
    @Test
    fun startOnlyResetsTheStackOncePerInstance() {
        val viewModel = viewModel()

        viewModel.start()
        viewModel.openIndex()
        viewModel.openSettings()
        assertEquals(Route.Settings, viewModel.stack.value.last())

        viewModel.start()

        assertEquals(listOf<Route>(Route.Overview, Route.Settings), viewModel.stack.value)
    }
}
