package com.mailhub.client.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.mailhub.client.ui.screens.common.LocalMailNow
import com.mailhub.client.ui.screens.login.LoginScreen
import com.mailhub.client.ui.screens.login.LoginUiState
import com.mailhub.client.ui.screens.security.SecurityBody
import com.mailhub.client.ui.screens.security.SecurityFixtureCategories
import com.mailhub.client.ui.screens.security.SecurityFixtureNowTs
import com.mailhub.client.ui.screens.security.SecurityFixtureZone
import com.mailhub.client.ui.screens.security.securityFixtureState
import com.mailhub.client.ui.screens.settings.SettingsBody
import com.mailhub.client.ui.screens.settings.settingsFixtureState
import com.mailhub.client.ui.screens.shell.LogoutConfirmPanel
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 本机模式截图：登录页的新入口、设置页（只有「同步」）、安全中心（没有「已登录设备」）、
 * 退出确认的本机文案。全部浅色 360dp，示例数据写死，「现在」固定，不访问网络。
 *
 * 服务器模式的基线不受影响：这些文件名都带 local，是新增的。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LocalModeScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.0f),
                LocalMailNow provides SecurityFixtureNowTs,
            ) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** 窗口取得比整页高一些：新入口在页面最下面，不能被裁掉。 */
    @Test
    @Config(qualifiers = "w360dp-h1000dp-xxhdpi")
    fun loginLocalEntryLightW360() {
        capture("login_local_light_w360") {
            LoginScreen(
                state = LoginUiState(
                    serverUrl = "https://mail.example.com",
                    password = "hunter2hunter2",
                ),
                meta = SampleData.pageMeta,
                onEvent = {},
            )
        }
        // 「本机独立使用」是这一页新增的内容，截图里必须完整可见
        composeRule.onNodeWithText("本机独立使用 →").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun settingsLocalLightW360() {
        capture("settings_local_light_w360") {
            SettingsBody(state = settingsFixtureState().copy(localMode = true), onEvent = {})
        }
        // 只有「同步」一块：编号 01，其余四块一个字都不出现
        composeRule.onNodeWithText("同步").assertExists()
        composeRule.onNodeWithText("治理与 AI").assertDoesNotExist()
        composeRule.onNodeWithText("推送").assertDoesNotExist()
        composeRule.onNodeWithText("清理与晨报").assertDoesNotExist()
        composeRule.onNodeWithText("管理密码").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h2200dp-xxhdpi")
    fun securityLocalLightW360() {
        capture("security_local_light_w360") {
            SecurityBody(
                state = securityFixtureState().copy(localMode = true),
                zoneId = SecurityFixtureZone,
                categories = SecurityFixtureCategories,
                onEvent = {},
            )
        }
        // 三块还在，「已登录设备」整块不渲染
        composeRule.onNodeWithText("风险观察").assertExists()
        composeRule.onNodeWithText("回收站").assertExists()
        composeRule.onNodeWithText("审计").assertExists()
        composeRule.onNodeWithText("已登录设备").assertDoesNotExist()
    }

    /** 确认面板贴在屏幕底部，所以用正常的手机高度。 */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun logoutConfirmLocalLightW360() {
        capture("logout_confirm_local_light_w360") {
            LogoutConfirmPanel(localMode = true, onConfirm = {}, onCancel = {})
        }
        composeRule.onNodeWithText("退出本机模式？").assertIsDisplayed()
    }
}
