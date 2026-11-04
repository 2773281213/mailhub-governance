package com.mailhub.client.ui.screens.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.ui.swiss.gridSpecFor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 风险块里的米白文字必须从第 2 栏的栏线起排、到第 12 栏为止。
 *
 * 做法：渲染风险邮件详情，取正文里那行 risk_reasons 的节点边界，
 * 和 [gridSpecFor] 算出来的栏线比较（允许 1dp 的取整误差）。
 * 红底仍然占第 1–12 栏，所以文字比红底左缘缩进一栏。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RiskBlockLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 文字左右边缘与栏线的差不超过 1dp。 */
    private fun assertOnColumnLine(name: String, actual: Dp, expected: Dp) {
        val limitDp = 1f
        val delta = abs(actual.value - expected.value)
        assertTrue(
            "风险块文字${name}在 ${actual.value}dp，栏线在 ${expected.value}dp，差 ${delta}dp",
            delta <= limitDp,
        )
    }

    private fun assertRiskTextOnGrid(widthDp: Int) {
        val message = riskyDetail()
        composeRule.setContent {
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

        val total = widthDp.dp
        val spec = gridSpecFor(total)
        // 文字用 cols(2, 11)：左缘在第 2 栏线，右缘在第 12 栏的右缘
        val expectedLeft = spec.x(2, total)
        val expectedRight = spec.x(2, total) + spec.spanWidth(11, total)

        val bounds = composeRule.onNodeWithText(message.riskReasons, substring = true)
            .getBoundsInRoot()
        assertOnColumnLine("左缘", bounds.left, expectedLeft)
        assertOnColumnLine("右缘", bounds.right, expectedRight)
        // 红底从第 1 栏起，文字确实比红底缩进了一栏
        assertTrue(
            "风险块文字左缘 ${bounds.left.value}dp 应该在第 1 栏线 ${spec.x(1, total).value}dp 右侧",
            bounds.left.value > spec.x(1, total).value,
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h1700dp-xxhdpi")
    fun riskTextStartsOnTheSecondColumnLineAt360() = assertRiskTextOnGrid(widthDp = 360)

    @Test
    @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun riskTextStartsOnTheSecondColumnLineAt411() = assertRiskTextOnGrid(widthDp = 411)
}
