package com.mailhub.client.ui.screens.accounts

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
 * 账户列表截图：浅色 / 深色 360dp，以及一张叠加网格检查层。
 * 只渲染无状态主体，示例数据写死（三个账户分别是空闲、同步中、出错），不访问网络。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AccountsScreenshotTest {

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
                        AccountsBody(
                            state = accountsFixtureState(),
                            zoneId = AccountsFixtureZone,
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
    @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    fun accountsLightW360Fs10() =
        capture("accounts_light_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1400dp-night-xxhdpi")
    fun accountsDarkW360Fs10() =
        capture("accounts_dark_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    fun accountsGridLightW360() =
        capture("accounts_grid_light_w360", fontScale = 1.0f, overlay = true)
}
