package com.mailhub.client.ui.screens.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.mailhub.client.ui.screens.common.LocalMailNow
import com.mailhub.client.ui.swiss.GridOverlay
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 收件箱截图：浅色 / 深色 360dp、浅色 411dp 字号 1.3、网格叠加版，以及浅色 360dp 的多选模式。
 * 只渲染无状态版本，示例数据写死，并把「当前时间」固定下来，
 * 这样「当天显示 HH:mm、其他日期显示 MM-dd」不会随录制日期变化。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class InboxScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        fontScale: Float,
        overlay: Boolean,
        state: InboxUiState = inboxFixtureState(),
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = fontScale),
                LocalMailNow provides InboxFixtureNowTs,
            ) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        InboxScreen(
                            state = state,
                            meta = InboxFixtureMeta,
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
    fun inboxLightW360Fs10() =
        capture("inbox_light_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-night-xxhdpi")
    fun inboxDarkW360Fs10() =
        capture("inbox_dark_w360_fs10", fontScale = 1.0f, overlay = false)

    @Test
    @Config(qualifiers = "w411dp-h1840dp-xxhdpi")
    fun inboxLightW411Fs13() =
        capture("inbox_light_w411_fs13", fontScale = 1.3f, overlay = false)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun inboxGridLightW360() =
        capture("inbox_grid_light_w360", fontScale = 1.0f, overlay = true)

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun inboxSelectLightW360() = capture(
        name = "inbox_select_light_w360",
        fontScale = 1.0f,
        overlay = false,
        state = inboxFixtureState(selectMode = true, selected = setOf(1L, 4L)),
    )
}
