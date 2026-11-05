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
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 添加账户截图：第 1 步（已识别出服务商）、第 3 步授权码页、第 3 步设备码页，都是浅色 360dp。
 * 只渲染无状态主体，示例数据写死，不访问网络。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AddAccountScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: AddAccountUiState) {
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AddAccountBody(state = state, onEvent = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun accountsAddEmailLightW360() =
        capture("accounts_add_email_light_w360", state = addEmailFixtureState())

    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun accountsAddPasswordLightW360() =
        capture("accounts_add_password_light_w360", state = addPasswordFixtureState())

    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun accountsAddDeviceLightW360() =
        capture("accounts_add_device_light_w360", state = addDeviceFixtureState())
}
