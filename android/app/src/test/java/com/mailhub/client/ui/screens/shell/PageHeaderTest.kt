package com.mailhub.client.ui.screens.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mailhub.client.ui.screens.common.LocalMailNow
import com.mailhub.client.ui.screens.inbox.InboxScreen
import com.mailhub.client.ui.screens.inbox.InboxFixtureMeta
import com.mailhub.client.ui.screens.inbox.InboxFixtureNowTs
import com.mailhub.client.ui.screens.inbox.inboxFixtureState
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 固定区只有一行页头：编号（第 1–2 栏）、栏目名（第 3–6 栏）、年份地点（第 7–10 栏）、
 * 操作入口「索引」（第 11–12 栏），下面一条 4dp 粗线；56sp 的大标题属于滚动内容。
 *
 * 这里用真实的收件箱页面验证两件事：
 * - 360dp 下「索引」与编号「02」的首行基线落在同一条线上（误差不超过 1dp）；
 * - 列表向上滚动之后大标题离开可见区域，而页头那一行仍然在。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PageHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 页头编号与操作入口的文字。 */
    private val indexText = "02"
    private val actionText = IndexActionLabel

    /**
     * 收件箱的大标题。页头的栏目名也是「收件」（label 样式，很小），
     * 所以断言大标题时要靠高度把它挑出来。
     */
    private val titleText = "收件"

    private fun render() {
        composeRule.setContent {
            CompositionLocalProvider(LocalMailNow provides InboxFixtureNowTs) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        InboxScreen(state = inboxFixtureState(), meta = InboxFixtureMeta, onEvent = {})
                    }
                }
            }
        }
    }

    /** 文字节点：取未合并的语义树，按钮的可点击语义不会把里面的文字合并上去。 */
    private fun textNode(text: String): SemanticsNode =
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()

    /** 首行基线在根布局里的像素位置：节点顶边 + 节点内首行基线。 */
    private fun baselineInRoot(text: String): Float {
        val node = textNode(text)
        val layouts = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("「$text」拿不到文本布局结果", action != null && action.invoke(layouts))
        return node.boundsInRoot.top + layouts.first().firstBaseline
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun indexEntrySharesTheHeaderBaseline() {
        render()

        val indexBaseline = baselineInRoot(indexText)
        val actionBaseline = baselineInRoot(actionText)
        val limitPx = composeRule.density.density

        assertTrue(
            "「$actionText」的首行基线在 ${actionBaseline}px，编号「$indexText」在 ${indexBaseline}px，" +
                "相差 ${abs(actionBaseline - indexBaseline)}px，超过了 1dp",
            abs(actionBaseline - indexBaseline) <= limitPx,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun indexEntryKeepsTheMinimumTouchTarget() {
        render()

        val text = textNode(actionText)
        val target = generateSequence(text) { it.parent }
            .first { it.config.contains(SemanticsActions.OnClick) }
        val heightDp = target.boundsInRoot.height / composeRule.density.density
        assertTrue("「$actionText」的触控区只有 ${heightDp}dp，小于 48dp", heightDp >= 48f)
    }

    /** 页面大标题（56sp）比页头里的小字高得多，用高度把它从「收件」的几个节点里挑出来。 */
    private fun bigTitleNodes(): List<SemanticsNode> {
        val minHeightPx = with(composeRule.density) { 32.dp.toPx() }
        return composeRule.onAllNodesWithText(titleText, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.size.height > minHeightPx }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun pageTitleScrollsAwayWhileTheHeaderRowStays() {
        render()

        assertTrue("第一屏应该能看到 56sp 的大标题", bigTitleNodes().isNotEmpty())

        // 滚到列表靠后的一行：大标题跟着内容离开可见区域
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("订阅即将续费", substring = true))

        assertTrue(
            "向上滚动后大标题应该已经离开可见区域，实际还剩 ${bigTitleNodes().size} 个",
            bigTitleNodes().isEmpty(),
        )
        // 页头那一行仍然固定在上面
        composeRule.onNodeWithText(indexText, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(actionText, useUnmergedTree = true).assertExists()
        val rootTop = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.top
        val headerTop = textNode(indexText).boundsInRoot.top
        assertTrue(
            "页头编号应该仍然贴在屏幕顶部：编号顶边 ${headerTop}px，根顶边 ${rootTop}px",
            headerTop - rootTop <= composeRule.density.density * 96f,
        )
        assertEquals(
            "滚动不应该影响页头编号的首行基线",
            baselineInRoot(indexText),
            baselineInRoot(actionText),
            1f * composeRule.density.density,
        )
    }
}
