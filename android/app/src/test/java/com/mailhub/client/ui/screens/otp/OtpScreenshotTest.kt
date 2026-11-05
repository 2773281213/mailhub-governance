package com.mailhub.client.ui.screens.otp

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
 * 验证码看板截图：浅色 360（最新一条为红色）、深色 360、浅色 411 字号 1.3、
 * 浅色 360 网格叠加版、空状态，共 5 张。
 * 只渲染无状态版本，示例数据写死，「现在」也固定在 [OtpFixtureNowTs]，
 * 这样红色判定和「N 分钟前」不会随录制日期变化。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OtpScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        fontScale: Float,
        overlay: Boolean,
        state: OtpUiState = otpFixtureState(),
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
            ) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        OtpScreen(
                            state = state,
                            meta = OtpFixtureMeta,
                            onEvent = {},
                        )
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
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun otpLightW360Fs10() =
        capture("otp_light_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-night-xxhdpi")
    fun otpDarkW360Fs10() =
        capture("otp_dark_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w411dp-h1840dp-xxhdpi")
    fun otpLightW411Fs13() =
        capture("otp_light_w411_fs13", fontScale = 1.3f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun otpGridLightW360() =
        capture("otp_grid_light_w360", fontScale = 1.0f, overlay = true)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun otpEmptyLightW360() = capture(
        name = "otp_empty_light_w360",
        fontScale = 1.0f,
        overlay = false,
        state = otpEmptyFixtureState(),
    )
}
