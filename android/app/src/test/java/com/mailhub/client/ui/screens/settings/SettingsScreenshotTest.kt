package com.mailhub.client.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.mailhub.client.ui.swiss.GridOverlay
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置页截图：整页（浅色 / 深色 / 网格叠加）、clean_server 确认面板、密码校验出错。
 * 只渲染无状态主体，示例数据写死，不访问网络。整页很长，窗口高度取 2600dp 一次拍全。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SettingsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: SettingsUiState, overlay: Boolean = false) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.0f),
            ) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SettingsBody(state = state, onEvent = {})
                        if (overlay) {
                            GridOverlay()
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h2600dp-xxhdpi")
    fun settingsLightW360Fs10() = capture("settings_light_w360_fs10", settingsFixtureState())

    @Test
    @Config(qualifiers = "w360dp-h2600dp-night-xxhdpi")
    fun settingsDarkW360Fs10() = capture("settings_dark_w360_fs10", settingsFixtureState())

    @Test
    @Config(qualifiers = "w360dp-h2600dp-xxhdpi")
    fun settingsGridLightW360() =
        capture("settings_grid_light_w360", settingsFixtureState(), overlay = true)

    /** 确认面板贴在手机屏幕底部，所以用正常的手机高度。 */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun settingsCleanServerConfirmLightW360() = capture(
        "settings_clean_server_confirm_light_w360",
        settingsFixtureState().copy(confirmingCleanServer = true),
    )

    @Test
    @Config(qualifiers = "w360dp-h2600dp-xxhdpi")
    fun settingsPasswordErrorLightW360() = capture(
        "settings_password_error_light_w360",
        settingsFixtureState().copy(
            password = PasswordDraft(old = "old-pass-123", new = "short", confirm = "short-2"),
            passwordErrors = mapOf(
                PasswordField.New to "新密码至少 8 位",
                PasswordField.Confirm to "两次输入的新密码不一致",
            ),
            feedback = mapOf(SettingsSection.Ai to SavedText),
        ),
    )
}
