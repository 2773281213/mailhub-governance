package com.mailhub.client.ui.screens.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.mailhub.client.data.model.MessageDetail
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 验证码区块在真实渲染里的可见性：风险邮件一律不显示大号数字和「复制」按钮。
 *
 * 用的就是风险截图（detail_risk_*）那张的示例数据 —— 它保留 otpCode，
 * 所以这里断言「区块没渲染出来」才真正证明了隐藏规则生效。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DetailOtpVisibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 验证码区块的 group label（「验证码」这一行）。 */
    private val otpLabel = "验证码"

    /** 复制按钮上的文字（SwissButton 会在末尾统一加箭头）。 */
    private val otpCopyLabel = "复制 →"

    private fun render(message: MessageDetail) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = density.density, fontScale = 1.0f),
            ) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        DetailScreen(
                            state = detailFixtureState(message),
                            meta = DetailFixtureMeta,
                            onEvent = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun riskyMailWithACodeRendersNoOtpBlock() {
        val message = riskyDetail()
        render(message)

        // 示例数据里确实有验证码，但界面上一律不出现
        composeRule.onNodeWithText(otpLabel).assertDoesNotExist()
        composeRule.onNodeWithText(otpCopyLabel).assertDoesNotExist()
        composeRule.onNodeWithText(message.otpCode).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun safeMailWithACodeStillShowsTheOtpBlock() {
        // 非风险邮件：验证码区块照常显示，说明隐藏规则只作用于风险邮件
        render(plainDetail().copy(otpCode = "482913"))

        composeRule.onNodeWithText(otpLabel).assertIsDisplayed()
        composeRule.onNodeWithText(otpCopyLabel).assertIsDisplayed()
        composeRule.onNodeWithText("482913").assertIsDisplayed()
    }
}
