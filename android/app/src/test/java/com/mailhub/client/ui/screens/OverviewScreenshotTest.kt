package com.mailhub.client.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.mailhub.client.ui.screens.overview.OverviewScreen
import com.mailhub.client.ui.swiss.GridOverlay
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 总览页截图：浅色/深色 360dp、浅色 411dp 字号 1.3，以及一张叠加网格检查层。
 * 只渲染无状态版本，用写死的示例数据，不访问网络。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OverviewScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, fontScale: Float, overlay: Boolean) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
            ) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        OverviewScreen(
                            state = SampleData.overviewReady(),
                            meta = SampleData.pageMeta,
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
    fun overviewLightW360Fs10() =
        capture("overview_light_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-night-xxhdpi")
    fun overviewDarkW360Fs10() =
        capture("overview_dark_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w411dp-h1840dp-xxhdpi")
    fun overviewLightW411Fs13() =
        capture("overview_light_w411_fs13", fontScale = 1.3f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun overviewGridLightW360() =
        capture("overview_grid_light_w360", fontScale = 1.0f, overlay = true)
}
