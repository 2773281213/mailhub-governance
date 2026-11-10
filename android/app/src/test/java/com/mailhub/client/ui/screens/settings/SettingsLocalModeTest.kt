package com.mailhub.client.ui.screens.settings

import com.mailhub.client.data.model.LoginResponse
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 设置页按模式取区块：本机模式只剩「同步」（编号从 01 开始），服务器模式仍是五块、
 * 顺序与编号不变；「同步」里的开关在本机模式照常保存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsLocalModeTest {

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

        val saves = mutableListOf<SettingsUpdate>()

        override suspend fun load(): Result<SettingsDto> = Result.success(settings)

        override suspend fun save(update: SettingsUpdate): Result<LoginResponse> {
            saves += update
            return Result.success(LoginResponse(ok = true))
        }

        override suspend fun testPush(): Result<PushTestResult> =
            Result.success(PushTestResult(ok = true, msg = "已发送"))

        override suspend fun replaceToken(token: String, sessionId: String, expiresTs: Double): Boolean = true
    }

    private fun TestScope.loaded(
        gateway: FakeSettingsGateway = FakeSettingsGateway(),
        localMode: Boolean = false,
    ): SettingsViewModel {
        val viewModel = SettingsViewModel(gateway, localMode = localMode)
        viewModel.onEnter()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun localModeShowsOnlyTheSyncSection() = runTest(dispatcher) {
        val state = loaded(localMode = true).state.value

        assertEquals(listOf(SettingsSection.Sync), state.sections)
        // 只剩一块时编号仍是 01
        assertEquals("01", state.indexOf(SettingsSection.Sync))
    }

    @Test
    fun localModeHidesGovernancePushCleanupAndPassword() = runTest(dispatcher) {
        val sections = loaded(localMode = true).state.value.sections

        assertTrue(SettingsSection.Ai !in sections)
        assertTrue(SettingsSection.Push !in sections)
        assertTrue(SettingsSection.Cleanup !in sections)
        assertTrue(SettingsSection.Password !in sections)
    }

    @Test
    fun serverModeKeepsTheSameFiveSectionsAndNumbers() = runTest(dispatcher) {
        val state = loaded(localMode = false).state.value

        assertEquals(SettingsSection.entries.toList(), state.sections)
        assertEquals("01", state.indexOf(SettingsSection.Ai))
        assertEquals("02", state.indexOf(SettingsSection.Push))
        assertEquals("03", state.indexOf(SettingsSection.Cleanup))
        assertEquals("04", state.indexOf(SettingsSection.Sync))
        assertEquals("05", state.indexOf(SettingsSection.Password))
    }

    @Test
    fun localModeStillSavesTheReadStateSwitch() = runTest(dispatcher) {
        val gateway = FakeSettingsGateway()
        val viewModel = loaded(gateway = gateway, localMode = true)

        viewModel.onEvent(SettingsEvent.SwitchChanged(SwitchField.SyncReadState, true))
        viewModel.onEvent(SettingsEvent.Save(SettingsSection.Sync))
        advanceUntilIdle()

        assertEquals(listOf(SettingsUpdate(syncReadState = true)), gateway.saves)
        assertEquals(SavedText, viewModel.state.value.feedback[SettingsSection.Sync])
    }
}
