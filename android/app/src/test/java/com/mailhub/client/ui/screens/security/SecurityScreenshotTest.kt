package com.mailhub.client.ui.screens.security

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
 * 安全中心截图：整页（浅色 / 深色 / 网格叠加）、回收站多选、吊销确认面板。
 * 只渲染无状态主体，示例数据写死，「现在」固定，不访问网络。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SecurityScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: SecurityUiState, overlay: Boolean = false) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.0f),
                LocalMailNow provides SecurityFixtureNowTs,
            ) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SecurityBody(
                            state = state,
                            zoneId = SecurityFixtureZone,
                            categories = SecurityFixtureCategories,
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
    @Config(qualifiers = "w360dp-h2600dp-xxhdpi")
    fun securityLightW360Fs10() = capture("security_light_w360_fs10", securityFixtureState())

    @Test
    @Config(qualifiers = "w360dp-h2600dp-night-xxhdpi")
    fun securityDarkW360Fs10() = capture("security_dark_w360_fs10", securityFixtureState())

    @Test
    @Config(qualifiers = "w360dp-h2600dp-xxhdpi")
    fun securityGridLightW360() = capture("security_grid_light_w360", securityFixtureState(), overlay = true)

    @Test
    @Config(qualifiers = "w360dp-h2600dp-xxhdpi")
    fun securityTrashSelectedLightW360() = capture(
        "security_trash_selected_light_w360",
        securityFixtureState().copy(
            selectedTrash = setOf(21L, 23L),
            notices = mapOf(SecuritySection.Audit to "已撤销，恢复 3 封"),
        ),
    )

    /** 确认面板贴在手机屏幕底部，所以用正常的手机高度。 */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun securityRevokeConfirmLightW360() = capture(
        "security_revoke_confirm_light_w360",
        securityFixtureState().copy(confirming = SecurityConfirm.RevokeDevice(DevicesFixture[2])),
    )
}
