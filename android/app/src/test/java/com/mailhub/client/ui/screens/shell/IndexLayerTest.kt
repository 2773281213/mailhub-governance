package com.mailhub.client.ui.screens.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mailhub.client.ui.screens.common.LocalMailNow
import com.mailhub.client.ui.screens.inbox.InboxFixtureMeta
import com.mailhub.client.ui.screens.inbox.InboxFixtureNowTs
import com.mailhub.client.ui.screens.inbox.InboxScreen
import com.mailhub.client.ui.screens.inbox.inboxFixtureState
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 索引入口与全屏索引层：编号列表、点“退出”走二次确认回调、二级页面带上编号。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class IndexLayerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun indexLayerListsAccountSettingsAndLogout() {
        var closed = 0
        var logout = 0
        var secondary: Pair<String, String>? = null

        composeRule.setContent {
            SwissTheme {
                IndexLayer(
                    onOpenSecondary = { index, title -> secondary = index to title },
                    onLogout = { logout++ },
                    onClose = { closed++ },
                )
            }
        }

        composeRule.onNodeWithText("账户").assertExists()
        composeRule.onNodeWithText("设置").assertExists()
        composeRule.onNodeWithText("退出").assertExists()

        composeRule.onNodeWithText("退出").performClick()
        assertEquals(1, logout)
        assertEquals(null, secondary)

        composeRule.onNodeWithText("账户").performClick()
        assertEquals("05" to "账户", secondary)

        composeRule.onNodeWithText("关闭").performClick()
        assertEquals(1, closed)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun indexEntryOpensIndexLayer() {
        var opened = 0

        // 索引入口现在放在页头固定行的第 11–12 栏，用真实的收件箱页面渲染
        composeRule.setContent {
            CompositionLocalProvider(LocalMailNow provides InboxFixtureNowTs) {
                SwissTheme {
                    InboxScreen(
                        state = inboxFixtureState(),
                        meta = InboxFixtureMeta,
                        onEvent = {},
                        onOpenIndex = { opened++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText(IndexActionLabel).performClick()
        assertEquals(1, opened)
    }
}
