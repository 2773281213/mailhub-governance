package com.mailhub.client.ui.screens.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import com.mailhub.client.ui.screens.common.LocalMailNow
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 收件箱筛选行的基线对齐：「全部」（第 1–4 栏）、「未读」（第 5–8 栏）、
 * 「筛选 →」（第 9–12 栏）三者的首行基线必须落在同一条线上。
 *
 * 做法：渲染整页收件箱（默认收起状态，此时这三个文字各只有一个节点），
 * 从每个文字节点的语义里取文本布局结果——`firstBaseline` 是节点内的像素偏移——
 * 再加上节点在根布局里的顶边，得到根坐标系下的首行基线像素位置，两两比较。
 * 这一行在 [InboxScreen] 里由两个 `FilterOption` 和一个纯文字按钮组成，
 * 两者的触控区内部留白不同，所以必须真的对齐，不能靠顶边摆放。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class InboxFilterRowBaselineTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 「全部」「未读」「筛选 →」三格的文字。 */
    private val rowTexts = listOf("全部", "未读", "筛选 →")

    private fun render(state: InboxUiState = inboxFixtureState()) {
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

    /** 文字节点，取未合并的语义树（按钮的可点击语义会把里面的文字合并上去）。 */
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

    private fun assertRowSharesOneBaseline(widthDp: Int) {
        render()
        val baselines = rowTexts.associateWith { baselineInRoot(it) }
        val lowest = baselines.values.max()
        val highest = baselines.values.min()
        val limitPx = 1f * composeRule.density.density
        baselines.forEach { (text, baseline) ->
            assertTrue(
                "宽 ${widthDp}dp 时「$text」的首行基线在 ${baseline}px，" +
                    "比这一行最低的基线（${lowest}px）高 ${lowest - baseline}px，" +
                    "超过了 ${limitPx}px（1dp）",
                abs(lowest - baseline) <= limitPx,
            )
        }
        assertTrue(
            "宽 ${widthDp}dp 时这一行的首行基线跨度是 ${lowest - highest}px，" +
                "超过了 ${limitPx}px（1dp）：$baselines",
            lowest - highest <= limitPx,
        )
    }

    /** 文字下移不能把触控区压到 48dp 以下。 */
    private fun assertTouchTargetsKeepMinimumHeight() {
        render()
        rowTexts.forEach { text ->
            val heightDp = textNode(text).boundsInRoot.height / composeRule.density.density
            assertTrue("「$text」所在行的文字节点高度异常：${heightDp}dp", heightDp > 0f)
        }
        // 「筛选 →」是这一行里唯一没有下划线的按钮，它的触控区仍要满足 48dp
        val button = composeRule.onNodeWithText("筛选 →", useUnmergedTree = true).fetchSemanticsNode()
        val target = generateSequence(button) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        val heightDp = target.boundsInRoot.height / composeRule.density.density
        assertTrue("「筛选 →」的触控区只有 ${heightDp}dp，小于 48dp", heightDp >= 48f)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun filterRowSharesOneBaselineAt360() = assertRowSharesOneBaseline(widthDp = 360)

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun filterRowButtonKeepsTheMinimumTouchTarget() = assertTouchTargetsKeepMinimumHeight()
}
