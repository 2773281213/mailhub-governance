package com.mailhub.client.ui.screens.detail

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
 * 详情页截图：一封带风险块和验证码的邮件、一封普通邮件，各录浅色 360dp 与网格叠加版。
 * 两封的正文都是纯文本，截图里不出现 WebView。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DetailScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: DetailUiState, overlay: Boolean) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.0f),
            ) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DetailScreen(
                            state = state,
                            meta = DetailFixtureMeta,
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
    fun detailRiskLightW360Fs10() =
        capture("detail_risk_light_w360_fs10", detailFixtureState(riskyDetail()), overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun detailRiskGridLightW360() =
        capture("detail_risk_grid_light_w360", detailFixtureState(riskyDetail()), overlay = true)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun detailPlainLightW360Fs10() =
        capture("detail_plain_light_w360_fs10", detailFixtureState(plainDetail()), overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun detailPlainGridLightW360() =
        capture("detail_plain_grid_light_w360", detailFixtureState(plainDetail()), overlay = true)
}
