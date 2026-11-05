package com.mailhub.client.ui.screens.otp

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 验证码看板的风险处理：有风险的条目不进大字区，全部有风险时大字区显示空状态，
 * 风险条目仍然留在下面的列表里：来源栏标注「有风险」，真实地址单独一行完整显示。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OtpRiskyBoardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun riskyFixtureState(): OtpUiState = OtpUiState(
        items = listOf(
            otpFixtureItem(
                id = 7,
                code = "551904",
                senderName = "Google",
                senderAddr = "alert@google-verify.example",
                ageSeconds = 60.0,
                riskLevel = "medium",
            ),
            otpFixtureItem(
                id = 8,
                code = "482913",
                senderName = "银行助手",
                senderAddr = "notice@bank-verify.example",
                ageSeconds = 120.0,
                riskLevel = "high",
            ),
        ),
        accounts = OtpFixtureAccounts,
        loading = false,
        nowTs = OtpFixtureNowTs,
    )

    @Test
    @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun allRiskyShowsTheEmptyStateButKeepsTheRows() {
        composeRule.setContent {
            SwissTheme {
                OtpScreen(
                    state = riskyFixtureState(),
                    meta = OtpFixtureMeta,
                    onEvent = {},
                )
            }
        }

        // 大字区没有最新验证码，沿用空状态写法
        composeRule.onNodeWithText(OtpEmptyTitle, useUnmergedTree = true).assertExists()
        // 风险条目留在列表里：每条都有「有风险」标注，并且完整显示真实地址而不是显示名
        composeRule.onAllNodesWithText(OtpRiskyMark, useUnmergedTree = true).assertCountEquals(2)
        composeRule.onNodeWithText("alert@google-verify.example", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("notice@bank-verify.example", useUnmergedTree = true).assertExists()
        // 显示名不再出现在看板上
        composeRule.onNodeWithText("Google", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }
}
