package com.mailhub.client.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.mailhub.client.MailHubApp
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 接线冒烟测试：空存储启动时应停在登录页，并显示表单与主按钮。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MailHubRootSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "没有找到「$text」",
            composeRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun emptyStoreLandsOnLoginScreen() {
        val app = RuntimeEnvironment.getApplication() as MailHubApp

        composeRule.setContent {
            SwissTheme {
                MailHubRoot(container = app.container)
            }
        }

        waitForText("服务器地址")
        waitForText("连接")
    }
}
