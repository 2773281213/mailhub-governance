package com.mailhub.client.ui.screens.detail

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.BatchRequest
import com.mailhub.client.data.model.BatchResult
import com.mailhub.client.data.model.MessageDetail
import com.mailhub.client.data.model.Meta
import com.mailhub.client.data.net.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 详情页状态机：打开即标已读、受保护邮件的两步删除、风险邮件的链接禁用。
 * 网关换成假实现，直接断言 batch 请求里的 ids 与 override_protection。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 假网关：记录 batch 与详情请求；第一次删除返回 403 + protected。 */
    private class FakeDetailGateway(private val detail: MessageDetail) : DetailGateway {

        val batches = mutableListOf<BatchRequest>()
        val messageCalls = mutableListOf<Pair<Long, Boolean>>()

        /** 默认：不带 override_protection 的删除被安全策略拦下，带上之后成功。 */
        var batchResult: (BatchRequest) -> Result<BatchResult> = { request ->
            if (request.action == "delete" && !request.overrideProtection) {
                Result.failure(ApiError.Forbidden("这是受保护邮件", protected = true))
            } else {
                Result.success(BatchResult(ok = true, count = request.ids.size))
            }
        }

        override suspend fun message(id: Long, markRead: Boolean): Result<MessageDetail> {
            messageCalls += id to markRead
            return Result.success(detail)
        }

        override suspend fun meta(): Result<Meta> = Result.success(
            Meta(displayCategories = DetailFixtureCategories),
        )

        override suspend fun accounts(): Result<List<Account>> = Result.success(DetailFixtureAccounts)

        override suspend fun batch(request: BatchRequest): Result<BatchResult> {
            batches += request
            return batchResult(request)
        }
    }

    @Test
    fun openingMarksTheMessageRead() = runTest(dispatcher) {
        val gateway = FakeDetailGateway(plainDetail().copy(unread = 1))
        val viewModel = DetailViewModel(gateway)

        viewModel.onEnter(91)
        advanceUntilIdle()

        // 打开详情必须带上 markRead = true
        assertEquals(listOf(91L to true), gateway.messageCalls)
        assertFalse(viewModel.state.value.loading)
        assertEquals(91L, viewModel.state.value.message?.id)
        // 原件未读，读完要通知外壳把收件箱那一行改成已读
        assertTrue(viewModel.state.value.markedRead)
    }

    @Test
    fun protectedDeleteAsksFirstThenRetriesWithOverride() = runTest(dispatcher) {
        val gateway = FakeDetailGateway(plainDetail())
        val viewModel = DetailViewModel(gateway)
        viewModel.onEnter(91)
        advanceUntilIdle()

        viewModel.onEvent(DetailEvent.RequestDelete)
        assertTrue(viewModel.state.value.confirmingDelete)
        viewModel.onEvent(DetailEvent.ConfirmDelete)
        advanceUntilIdle()

        val first = gateway.batches.single()
        assertEquals("第一次只带这一封", listOf(91L), first.ids)
        assertEquals("delete", first.action)
        assertFalse(first.overrideProtection)
        // 403 + protected → 弹受保护邮件的二次确认，还没算删掉
        assertTrue(viewModel.state.value.confirmingProtected)
        assertFalse(viewModel.state.value.deleted)

        viewModel.onEvent(DetailEvent.ConfirmProtectedDelete)
        advanceUntilIdle()

        assertEquals(2, gateway.batches.size)
        val second = gateway.batches[1]
        assertEquals("确认后也只带这一封", listOf(91L), second.ids)
        assertEquals("delete", second.action)
        assertTrue("确认后重发必须带 override_protection", second.overrideProtection)
        assertTrue(viewModel.state.value.deleted)
        assertFalse(viewModel.state.value.deleting)
    }

    @Test
    fun forbiddenWithoutProtectedFlagShowsNoticeInsteadOfConfirming() = runTest(dispatcher) {
        val gateway = FakeDetailGateway(plainDetail())
        gateway.batchResult = { Result.failure(ApiError.Forbidden("没有权限", protected = false)) }
        val viewModel = DetailViewModel(gateway)
        viewModel.onEnter(91)
        advanceUntilIdle()

        viewModel.onEvent(DetailEvent.RequestDelete)
        viewModel.onEvent(DetailEvent.ConfirmDelete)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.confirmingProtected)
        assertFalse(viewModel.state.value.deleted)
        assertEquals("没有权限", viewModel.state.value.notice)
    }

    @Test
    fun markingUnreadSendsOneIdAndFlagsTheShell() = runTest(dispatcher) {
        val gateway = FakeDetailGateway(plainDetail())
        val viewModel = DetailViewModel(gateway)
        viewModel.onEnter(91)
        advanceUntilIdle()

        viewModel.onEvent(DetailEvent.MarkUnread)
        advanceUntilIdle()

        val request = gateway.batches.single()
        assertEquals(listOf(91L), request.ids)
        assertEquals("unread", request.action)
        assertTrue(request.confirmed)
        assertTrue(viewModel.state.value.markedUnread)
        assertEquals(1, viewModel.state.value.message?.unread)
    }

    @Test
    fun riskyMailBlocksLinksWithNotice() = runTest(dispatcher) {
        val gateway = FakeDetailGateway(riskyDetail())
        val viewModel = DetailViewModel(gateway)
        viewModel.onEnter(42)
        advanceUntilIdle()

        viewModel.onEvent(DetailEvent.TapLink("https://paypa1-secure.example/verify"))

        assertEquals(RiskyLinkBlockedText, viewModel.state.value.notice)
        assertNull("风险邮件不弹浏览器确认框", viewModel.state.value.pendingLink)
    }

    @Test
    fun safeMailAsksBeforeOpeningTheLinkInTheBrowser() = runTest(dispatcher) {
        val gateway = FakeDetailGateway(plainDetail())
        val viewModel = DetailViewModel(gateway)
        viewModel.onEnter(91)
        advanceUntilIdle()

        viewModel.onEvent(DetailEvent.TapLink("https://mailhub.dev/changelog"))
        assertEquals("https://mailhub.dev/changelog", viewModel.state.value.pendingLink)

        viewModel.onEvent(DetailEvent.ConfirmOpenLink)
        assertEquals("https://mailhub.dev/changelog", viewModel.state.value.linkToOpen)
        assertNull(viewModel.state.value.pendingLink)

        // 页面层打开浏览器后清掉一次性信号
        viewModel.clearOpenedLink()
        assertNull(viewModel.state.value.linkToOpen)
    }

    @Test
    fun showingExternalImagesOnlyAffectsThisMessage() = runTest(dispatcher) {
        val gateway = FakeDetailGateway(plainDetail())
        val viewModel = DetailViewModel(gateway)
        viewModel.onEnter(91)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.showExternalImages)
        viewModel.onEvent(DetailEvent.ShowExternalImages)
        assertTrue(viewModel.state.value.showExternalImages)
    }
}
