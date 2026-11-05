package com.mailhub.client.ui.screens.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.mailhub.client.ui.screens.common.LocalMailNow
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 收件箱筛选区的真实渲染：默认收起时第一屏只剩搜索框、状态行和一行摘要，
 * 并且 360×800 的第一屏能看到至少 3 封邮件；展开后才出现分类与账户两组筛选项。
 *
 * 这里是 [InboxFilterPanelTest] 的界面层补充：那一层断言状态和请求，这一层断言画出来的东西。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class InboxFilterPanelLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 收起状态下的摘要文字。 */
    private val collapsedSummary = "分类：全部 · 账户：全部账户"

    /** 第一屏必须能看到的 3 封邮件（示例数据的前 3 封）。 */
    private val firstScreenSubjects = listOf(
        "账户存在异常登录，请立即验证你的身份",
        "本月账单已生成",
        "登录验证码",
    )

    private fun render(state: InboxUiState) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMailNow provides InboxFixtureNowTs) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        InboxScreen(state = state, meta = InboxFixtureMeta, onEvent = {})
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun collapsedStateLeavesRoomForTheMailList() {
        render(inboxFixtureState())

        // 默认收起：只有搜索框、状态行和一行摘要
        composeRule.onNodeWithText("筛选 →").assertIsDisplayed()
        composeRule.onNodeWithText(collapsedSummary).assertIsDisplayed()
        composeRule.onNodeWithText("收起 →").assertDoesNotExist()
        composeRule.onNodeWithText("分类").assertDoesNotExist()
        composeRule.onNodeWithText("账户").assertDoesNotExist()
        composeRule.onNodeWithText("00 全部").assertDoesNotExist()
        composeRule.onNodeWithText("A1 工作邮箱").assertDoesNotExist()

        // 360×800 的第一屏至少能看到 3 封邮件
        firstScreenSubjects.forEach { subject ->
            val matches = composeRule.onAllNodesWithText(subject, substring = true)
            assertTrue("第一屏找不到邮件「$subject」", matches.fetchSemanticsNodes().isNotEmpty())
            matches[0].assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun expandedStateShowsBothGroupsWithLabels() {
        render(inboxFixtureState().copy(filtersExpanded = true))

        composeRule.onNodeWithText("收起 →").assertIsDisplayed()
        // 每组上方一行 label
        composeRule.onNodeWithText("分类").assertExists()
        composeRule.onNodeWithText("账户").assertExists()
        // 分类与账户的筛选项都展开了
        composeRule.onNodeWithText("00 全部").assertExists()
        composeRule.onNodeWithText("05 安全").assertExists()
        composeRule.onNodeWithText("A1 工作邮箱").assertExists()
        composeRule.onNodeWithText("A2 私有邮箱").assertExists()
        // 摘要仍在，且写的就是当前筛选
        composeRule.onNodeWithText(collapsedSummary).assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun collapsedSummaryFollowsTheActiveFilters() {
        render(
            inboxFixtureState().copy(
                filters = InboxFilters(unreadOnly = true, category = "安全", accountId = 1),
            ),
        )

        composeRule.onNodeWithText("分类：05 安全 · 账户：A1 工作邮箱").assertIsDisplayed()
        composeRule.onNodeWithText(collapsedSummary).assertDoesNotExist()
    }
}
