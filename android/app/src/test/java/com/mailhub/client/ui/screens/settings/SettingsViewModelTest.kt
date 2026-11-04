package com.mailhub.client.ui.screens.settings

import com.mailhub.client.data.model.LoginResponse
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.data.settings.AiScopeAll
import com.mailhub.client.data.settings.PushTestResult
import com.mailhub.client.data.settings.SettingsDto
import com.mailhub.client.data.settings.SettingsUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
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
 * 设置页状态机：逐块只提交改动字段、clean_server 打开前必须确认、
 * 改密码的本地校验与换令牌、400 的 detail、同步块的隐藏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

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

    private class FakeSettingsGateway(var settings: SettingsDto = SettingsFixture) : SettingsGateway {

        var saveResult: Result<LoginResponse> = Result.success(LoginResponse(ok = true))
        var pushResult: Result<PushTestResult> = Result.success(PushTestResult(ok = true, msg = "已发送到 Bark"))
        var replaceResult = true

        val saves = mutableListOf<SettingsUpdate>()
        val replaced = mutableListOf<Triple<String, String, Double>>()
        var pushCalls = 0

        override suspend fun load(): Result<SettingsDto> = Result.success(settings)

        override suspend fun save(update: SettingsUpdate): Result<LoginResponse> {
            saves += update
            return saveResult
        }

        override suspend fun testPush(): Result<PushTestResult> {
            pushCalls++
            return pushResult
        }

        override suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean {
            replaced += Triple(token, sessionId, expiresTs)
            return replaceResult
        }
    }

    private fun TestScope.loaded(gateway: FakeSettingsGateway): SettingsViewModel {
        val viewModel = SettingsViewModel(gateway)
        viewModel.onEnter()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun savingASectionSubmitsOnlyItsChangedFields() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway()
        val viewModel = loaded(gateway)

        // 01 改了一项，03 也改了一项；保存 01 时不能把 03 的改动带上
        viewModel.onEvent(SettingsEvent.SwitchChanged(SwitchField.AiSendBody, false))
        viewModel.onEvent(SettingsEvent.NumberChanged(NumberField.DigestHour, "6"))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Ai))
        advanceUntilIdle()

        assertEquals(listOf(SettingsUpdate(aiSendBody = false)), gateway.saves)
        val state = viewModel.state.value
        assertEquals(SavedText, state.feedback[SettingsSection.Ai])
        assertEquals(false, state.saved?.aiSendBody)
        // 03 的编辑还在，没被冲掉
        assertEquals("6", state.draft.number(NumberField.DigestHour))

        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Cleanup))
        advanceUntilIdle()
        assertEquals(SettingsUpdate(digestHour = 6), gateway.saves.last())
    }

    @Test
    fun scopeChangeIsPartOfTheAiSection() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.ScopeChanged(AiScopeAll))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Ai))
        advanceUntilIdle()

        assertEquals(listOf(SettingsUpdate(aiScope = AiScopeAll)), gateway.saves)
    }

    @Test
    fun savingWithoutChangesSendsNothing() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway()
        val viewModel = loaded(gateway)

        // 改了又改回来，等于没改
        viewModel.onEvent(SettingsEvent.SwitchChanged(SwitchField.NotifyOtp, true))
        viewModel.onEvent(SettingsEvent.SwitchChanged(SwitchField.NotifyOtp, false))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Push))
        advanceUntilIdle()

        assertTrue(gateway.saves.isEmpty())
        assertEquals(UnchangedText, viewModel.state.value.feedback[SettingsSection.Push])
    }

    @Test
    fun invalidNumbersBlockTheRequest() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.NumberChanged(NumberField.DigestHour, "24"))
        viewModel.onEvent(SettingsEvent.NumberChanged(NumberField.CleanOtpDays, ""))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Cleanup))
        advanceUntilIdle()

        assertTrue(gateway.saves.isEmpty())
        val errors = viewModel.state.value.numberErrors
        assertEquals(NumberField.DigestHour.errorText, errors[NumberField.DigestHour])
        assertEquals(NumberField.CleanOtpDays.errorText, errors[NumberField.CleanOtpDays])

        // 改正之后错误消失，保存只带改动的那一项
        viewModel.onEvent(SettingsEvent.NumberChanged(NumberField.DigestHour, "23"))
        viewModel.onEvent(SettingsEvent.NumberChanged(NumberField.CleanOtpDays, "7"))
        assertTrue(viewModel.state.value.numberErrors.isEmpty())
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Cleanup))
        advanceUntilIdle()
        assertEquals(listOf(SettingsUpdate(digestHour = 23)), gateway.saves)
    }

    @Test
    fun numberInputKeepsOnlyAsciiDigits() = runTest(dispatcher) {
        val viewModel = loaded(FakeSettingsGateway())

        viewModel.onEvent(SettingsEvent.NumberChanged(NumberField.BodyKeepDays, "1２a3-4567"))

        assertEquals("13456", viewModel.state.value.draft.number(NumberField.BodyKeepDays))
    }

    @Test
    fun turningCleanServerOnNeedsConfirmation() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.SwitchChanged(SwitchField.CleanServer, true))

        // 只弹确认面板，开关还是关的
        assertTrue(viewModel.state.value.confirmingCleanServer)
        assertFalse(viewModel.state.value.draft.switch(SwitchField.CleanServer))

        viewModel.onEvent(SettingsEvent.DismissCleanServer)
        assertFalse(viewModel.state.value.confirmingCleanServer)
        assertFalse(viewModel.state.value.draft.switch(SwitchField.CleanServer))

        viewModel.onEvent(SettingsEvent.SwitchChanged(SwitchField.CleanServer, true))
        viewModel.onEvent(SettingsEvent.ConfirmCleanServer)
        assertFalse(viewModel.state.value.confirmingCleanServer)
        assertTrue(viewModel.state.value.draft.switch(SwitchField.CleanServer))

        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Cleanup))
        advanceUntilIdle()
        assertEquals(listOf(SettingsUpdate(cleanServer = true)), gateway.saves)
    }

    @Test
    fun turningCleanServerOffNeedsNoConfirmation() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway(SettingsFixture.copy(cleanServer = true))
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.SwitchChanged(SwitchField.CleanServer, false))

        assertFalse(viewModel.state.value.confirmingCleanServer)
        assertFalse(viewModel.state.value.draft.switch(SwitchField.CleanServer))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Cleanup))
        advanceUntilIdle()
        assertEquals(listOf(SettingsUpdate(cleanServer = false)), gateway.saves)
    }

    @Test
    fun mismatchedOrShortPasswordsSendNothing() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.Old, "old-pass-123"))
        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.New, "short"))
        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.Confirm, "short"))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Password))
        advanceUntilIdle()

        assertTrue(gateway.saves.isEmpty())
        assertEquals(
            mapOf(PasswordField.New to "新密码至少 8 位"),
            viewModel.state.value.passwordErrors,
        )

        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.New, "new-pass-123"))
        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.Confirm, "new-pass-124"))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Password))
        advanceUntilIdle()

        assertTrue(gateway.saves.isEmpty())
        assertEquals(
            mapOf(PasswordField.Confirm to "两次输入的新密码不一致"),
            viewModel.state.value.passwordErrors,
        )
    }

    @Test
    fun passwordLengthCountsCodePointsLikeTheBackend() {
        // 4 个表情符号是 8 个 UTF-16 单元，但只有 4 个字符，后端会拒绝
        val emoji = "😀".repeat(4)
        val errors = passwordErrors(PasswordDraft(old = "x", new = emoji, confirm = emoji))
        assertEquals(setOf(PasswordField.New), errors.keys)
    }

    @Test
    fun passwordChangeReplacesTheTokenAndClearsTheFields() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway().apply {
            saveResult = Result.success(
                LoginResponse(ok = true, token = "tok-new", sessionId = "sid-new", expiresTs = 200.0),
            )
        }
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.Old, "old-pass-123"))
        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.New, "new-pass-123"))
        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.Confirm, "new-pass-123"))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Password))
        advanceUntilIdle()

        assertEquals(
            listOf(SettingsUpdate(oldPassword = "old-pass-123", newPassword = "new-pass-123")),
            gateway.saves,
        )
        assertEquals(listOf(Triple("tok-new", "sid-new", 200.0)), gateway.replaced)
        val state = viewModel.state.value
        assertEquals(PasswordChangedText, state.feedback[SettingsSection.Password])
        assertEquals(PasswordDraft(), state.password)
        assertNull(state.pending)
    }

    @Test
    fun passwordChangeWithoutANewTokenAsksToLogInAgain() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway()
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.Old, "old-pass-123"))
        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.New, "new-pass-123"))
        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.Confirm, "new-pass-123"))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Password))
        advanceUntilIdle()

        assertTrue(gateway.replaced.isEmpty())
        assertEquals(
            PasswordChangedReloginText,
            viewModel.state.value.feedback[SettingsSection.Password],
        )
    }

    @Test
    fun badRequestShowsTheBackendDetail() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway().apply {
            saveResult = Result.failure(ApiError.Server(400, "原密码错误"))
        }
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.Old, "wrong-pass"))
        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.New, "new-pass-123"))
        viewModel.onEvent(SettingsEvent.PasswordChanged(PasswordField.Confirm, "new-pass-123"))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Password))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("原密码错误", state.feedback[SettingsSection.Password])
        assertTrue(gateway.replaced.isEmpty())
        // 输入保留，方便改正后重试
        assertEquals("wrong-pass", state.password.old)
        assertNull(state.pending)
    }

    @Test
    fun sectionSaveFailureShowsDetailAndKeepsTheEdit() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway().apply {
            saveResult = Result.failure(ApiError.Server(400, "AI 判定范围必须是 all / uncertain / other"))
        }
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.SwitchChanged(SwitchField.AiEnabled, false))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Ai))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("AI 判定范围必须是 all / uncertain / other", state.feedback[SettingsSection.Ai])
        assertEquals(true, state.saved?.aiEnabled)
        assertFalse(state.draft.switch(SwitchField.AiEnabled))
    }

    @Test
    fun testPushShowsTheBackendMessage() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway().apply {
            pushResult = Result.success(PushTestResult(ok = false, msg = "未配置任何推送通道"))
        }
        val viewModel = loaded(gateway)

        viewModel.onEvent(SettingsEvent.TestPush)
        advanceUntilIdle()

        assertEquals(1, gateway.pushCalls)
        assertEquals("未配置任何推送通道", viewModel.state.value.feedback[SettingsSection.Push])
    }

    @Test
    fun syncSectionIsHiddenWhenTheBackendHasNoField() = runTest(dispatcher) {
        val viewModel = loaded(FakeSettingsGateway(SettingsFixture.copy(syncReadState = null)))

        val state = viewModel.state.value
        assertFalse(SettingsSection.Sync in state.sections)
        // 后面的区块顺延编号，不留空号
        assertEquals("04", state.indexOf(SettingsSection.Password))
    }

    @Test
    fun syncSectionIsShownWhenTheBackendHasTheField() = runTest(dispatcher) {
        val viewModel = loaded(FakeSettingsGateway())

        val state = viewModel.state.value
        assertEquals("04", state.indexOf(SettingsSection.Sync))
        assertEquals("05", state.indexOf(SettingsSection.Password))
    }

    @Test
    fun reenteringKeepsUnsavedEdits() = runTest(dispatcher) {
        val viewModel = loaded(FakeSettingsGateway())

        viewModel.onEvent(SettingsEvent.NumberChanged(NumberField.AiBackfillDays, "60"))
        viewModel.onEnter()
        advanceUntilIdle()

        assertEquals("60", viewModel.state.value.draft.number(NumberField.AiBackfillDays))
    }
}
