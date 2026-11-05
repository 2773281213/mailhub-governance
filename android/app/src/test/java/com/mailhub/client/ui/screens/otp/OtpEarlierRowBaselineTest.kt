package com.mailhub.client.ui.screens.otp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
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
 * 验证码看板「更早」列表的基线对齐：一行里的验证码（headline 24/28）、
 * 发件人（label 10/12）、时间（caption 11/16）三种字号必须首行基线对齐。
 *
 * 做法：渲染整页看板，取「更早」第一条那一行的三个文字节点，
 * 从语义里读文本布局结果（`firstBaseline` 是节点内的像素偏移），
 * 加上节点在根布局里的顶边，得到根坐标系下的首行基线像素位置，任意两者之差小于 1dp 即通过。
 * 只按顶边摆放时，最小的 label 会比 24sp 的验证码高出十来个 dp，这个断言会直接失败。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OtpEarlierRowBaselineTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 「更早」列表里的每一条：验证码、发件人、时间在这一页里都只出现一次（发件人中英混排都有）。 */
    private val earlierItems = otpFixtureState().earlier

    private fun render() {
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    OtpScreen(
                        state = otpFixtureState(),
                        meta = OtpFixtureMeta,
                        onEvent = {},
                    )
                }
            }
        }
    }

    /** 文字节点，取未合并的语义树（整行可点击，会把里面的文字合并上去）。 */
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

    private fun assertEarlierRowsShareOneBaseline(widthDp: Int) {
        render()
        val limitPx = 1f * composeRule.density.density
        earlierItems.forEach { item ->
            val texts = listOf(
                "验证码" to item.otpCode,
                // 有风险的条目来源栏是「有风险」标注，也是这一行里的真实文字
                "来源" to otpEarlierSourceText(item),
                "时间" to otpClockText(item.dateTs, OtpFixtureMeta.zoneId),
            )
            val baselines = texts.associate { (name, text) -> name to baselineInRoot(text) }
            val lowest = baselines.values.max()
            val highest = baselines.values.min()
            baselines.forEach { (name, baseline) ->
                assertTrue(
                    "宽 ${widthDp}dp 时「${item.otpCode}」这一行的$name「" +
                        "${texts.first { it.first == name }.second}」首行基线在 ${baseline}px，" +
                        "比这一行最低的基线（${lowest}px）高 ${lowest - baseline}px，" +
                        "超过了 ${limitPx}px（1dp）",
                    abs(lowest - baseline) <= limitPx,
                )
            }
            assertTrue(
                "宽 ${widthDp}dp 时「${item.otpCode}」这一行的首行基线跨度是 ${lowest - highest}px，" +
                    "超过了 ${limitPx}px（1dp）：$baselines",
                lowest - highest <= limitPx,
            )
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun earlierRowsShareOneBaselineAt360() = assertEarlierRowsShareOneBaseline(widthDp = 360)

    @Test
    @Config(qualifiers = "w411dp-h1840dp-xxhdpi")
    fun earlierRowsShareOneBaselineAt411() = assertEarlierRowsShareOneBaseline(widthDp = 411)
}
