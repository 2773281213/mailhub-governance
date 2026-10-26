package com.mailhub.client

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
import com.mailhub.client.ui.swiss.SwissSpecimen
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 样张截图：浅色/深色 × 360dp/411dp × 字号 1.0/1.3，另外两张叠加网格检查层。
 * 窗口高度设成 3000dp，保证整页内容都能截进来（样张本身不加滚动）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SwissSpecimenScreenshotTest {

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
                        SwissSpecimen()
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
    @Config(qualifiers = "w360dp-h3000dp-xxhdpi")
    fun specimenLightW360Fs10() = capture("specimen_light_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h3000dp-xxhdpi")
    fun specimenLightW360Fs13() = capture("specimen_light_w360_fs13", fontScale = 1.3f, overlay = false)

    @Test
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun specimenLightW411Fs10() = capture("specimen_light_w411_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun specimenLightW411Fs13() = capture("specimen_light_w411_fs13", fontScale = 1.3f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h3000dp-night-xxhdpi")
    fun specimenDarkW360Fs10() = capture("specimen_dark_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h3000dp-night-xxhdpi")
    fun specimenDarkW360Fs13() = capture("specimen_dark_w360_fs13", fontScale = 1.3f, overlay = false)

    @Test
    @Config(qualifiers = "w411dp-h3000dp-night-xxhdpi")
    fun specimenDarkW411Fs10() = capture("specimen_dark_w411_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w411dp-h3000dp-night-xxhdpi")
    fun specimenDarkW411Fs13() = capture("specimen_dark_w411_fs13", fontScale = 1.3f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h3000dp-xxhdpi")
    fun specimenGridLightW360() = capture("specimen_grid_light_w360", fontScale = 1.0f, overlay = true)

    @Test
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun specimenGridLightW411() = capture("specimen_grid_light_w411", fontScale = 1.0f, overlay = true)
}
