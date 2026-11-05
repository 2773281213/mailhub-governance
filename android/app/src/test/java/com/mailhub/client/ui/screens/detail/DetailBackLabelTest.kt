package com.mailhub.client.ui.screens.detail

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.mailhub.client.ui.screens.shell.Route
import com.mailhub.client.ui.screens.shell.backLabelFor
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 详情页的返回文字由外壳按页面栈给出：从安全中心打开写「← 安全」，
 * 不传时保持原来的「← 收件」。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class DetailBackLabelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(backLabel: String?) {
        composeRule.setContent {
            SwissTheme {
                if (backLabel == null) {
                    DetailScreen(
                        state = detailFixtureState(plainDetail()),
                        meta = DetailFixtureMeta,
                        onEvent = {},
                    )
                } else {
                    DetailScreen(
                        state = detailFixtureState(plainDetail()),
                        meta = DetailFixtureMeta,
                        onEvent = {},
                        backLabel = backLabel,
                    )
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun detailOpenedFromSecurityCenterShowsSecurityLabel() {
        show(backLabel = backLabelFor(listOf(Route.Security, Route.Detail(91))))

        composeRule.onNodeWithText("← 安全", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("← 收件", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun defaultBackLabelIsStillInbox() {
        show(backLabel = null)

        composeRule.onNodeWithText("← 收件", useUnmergedTree = true).assertExists()
    }
}
