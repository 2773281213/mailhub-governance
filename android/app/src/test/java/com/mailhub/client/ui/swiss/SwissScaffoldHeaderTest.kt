package com.mailhub.client.ui.swiss

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.mailhub.client.ui.PixelProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 页头这一行的垂直排布：这一行被「索引」的 48dp 最小触控高度撑高，
 * 四段文字都贴在行的底部，文字最后一行与 4dp 粗线之间只留 [SwissSpace.Xs]；
 * 粗线与下面的滚动内容之间同样留一道 [SwissSpace.Xs] 的背景色间隙，
 * 内容向上滚动时不会和粗线连成一块。
 *
 * 粗线没有自己的语义节点，所以用像素扫描定位：在元信息右边缘和操作入口左边缘之间
 * 取一列，那里只有粗线是黑的。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SwissScaffoldHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val indexText = "02"
    private val sectionText = "收件"
    private val metaText = "2026 — 上海"
    private val actionText = "索引"
    private val contentText = "内容第一行"

    private fun render() {
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwissScaffold(
                        index = indexText,
                        section = sectionText,
                        meta = metaText,
                        actionLabel = actionText,
                        onAction = {},
                    ) {
                        SText(text = contentText, style = SwissType.body())
                    }
                }
            }
        }
    }

    /** 文字节点：取未合并的语义树，按钮的可点击语义不会把里面的文字合并上去。 */
    private fun textNode(text: String): SemanticsNode =
        composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()

    /**
     * 粗线在根布局里的上边缘（像素）：在元信息和操作入口之间取一列往下扫，
     * 第一段黑色像素就是那条 4dp 粗线。
     */
    private fun ruleTopPx(): Int {
        val gapLeft = textNode(metaText).boundsInRoot.right
        val gapRight = textNode(actionText).boundsInRoot.left
        val x = ((gapLeft + gapRight) / 2).toInt()

        val image = PixelProbe.capture(composeRule.onRoot(), "swiss-header")
        for (y in 0 until image.height) {
            if (PixelProbe.isInk(image.getRGB(x, y))) {
                // 再确认这一段黑像素正好是粗线的厚度：4dp
                var end = y
                while (end + 1 < image.height && PixelProbe.isInk(image.getRGB(x, end + 1))) end++
                val thicknessDp = (end - y + 1) / composeRule.density.density
                assertEquals(
                    "x=${x}px 处第一段黑像素应该是粗线（${SwissSpace.Xxs.value}dp）",
                    SwissSpace.Xxs.value,
                    thicknessDp,
                    0.5f,
                )
                return y
            }
        }
        throw AssertionError("页头下方没有找到粗线")
    }

    /** 四段文字的最后一行都贴在这一行的底部：底边到粗线顶边只留 Xs。 */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun headerTextSitsXsAboveTheHeavyRule() {
        render()

        val density = composeRule.density.density
        val ruleTop = ruleTopPx()
        for (text in listOf(indexText, sectionText, metaText, actionText)) {
            val bottom = textNode(text).boundsInRoot.bottom
            val distanceDp = (ruleTop - bottom) / density
            assertEquals(
                "「$text」的底边到粗线顶边应该是 Xs（8dp），实际 ${distanceDp}dp",
                SwissSpace.Xs.value,
                distanceDp,
                1f,
            )
        }
    }

    /** 文字贴底之后，「索引」的触控高度仍然不小于 48dp。 */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun actionEntryKeepsTheMinimumTouchTarget() {
        render()

        val text = textNode(actionText)
        val target = generateSequence(text) { it.parent }
            .first { it.config.contains(SemanticsActions.OnClick) }
        val heightDp = target.boundsInRoot.height / composeRule.density.density
        assertTrue("「$actionText」的触控区只有 ${heightDp}dp，小于 48dp", heightDp >= 48f)
    }

    /** 粗线和内容区之间有一道 Xs 的背景色间隙，内容从间隙下方开始。 */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun contentStartsXsBelowTheHeavyRule() {
        render()

        val density = composeRule.density.density
        val ruleTop = ruleTopPx()
        val ruleBottom = ruleTop + (SwissSpace.Xxs.value * density)
        val contentTop = textNode(contentText).boundsInRoot.top
        val gapDp = (contentTop - ruleBottom) / density
        assertEquals(
            "内容区顶边到粗线底边应该是一整道 Xs（8dp）的间隙，实际 ${gapDp}dp",
            SwissSpace.Xs.value,
            gapDp,
            1f,
        )
    }
}
