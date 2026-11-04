package com.mailhub.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.ui.swiss.SwissType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 排版层级：字号和行高固定，行高必须是 4 的倍数（基线网格），
 * 只有 display / displaySmall / title 允许 1.15 倍的字号缩放上限。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SwissTypeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun collectStyles(): Map<String, TextStyle> {
        val styles = LinkedHashMap<String, TextStyle>()
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    styles["display"] = SwissType.display()
                    styles["displaySmall"] = SwissType.displaySmall()
                    styles["title"] = SwissType.title()
                    styles["headline"] = SwissType.headline()
                    styles["body"] = SwissType.body()
                    styles["caption"] = SwissType.caption()
                    styles["label"] = SwissType.label()
                }
            }
        }
        composeRule.waitForIdle()
        return styles
    }

    @Test
    fun allLineHeightsAreMultiplesOfFour() {
        val styles = collectStyles()
        assertEquals("排版层级数量", 7, styles.size)
        styles.forEach { (name, style) ->
            val lineHeight = style.lineHeight
            assertTrue("$name 的行高必须是 sp", lineHeight.isSp)
            assertEquals(
                "$name 的行高必须是 4 的倍数，实际 ${lineHeight.value}",
                0f,
                lineHeight.value % 4f,
                0.0001f,
            )
        }
    }

    @Test
    fun typeScaleMatchesTheTable() {
        val styles = collectStyles()
        val expected = mapOf(
            "display" to (112f to 104f),
            "displaySmall" to (80f to 80f),
            "title" to (56f to 56f),
            "headline" to (24f to 28f),
            "body" to (13f to 20f),
            "caption" to (11f to 16f),
            "label" to (10f to 12f),
        )
        expected.forEach { (name, sizes) ->
            val style = requireNotNull(styles[name]) { "缺少样式 $name" }
            assertEquals("$name 字号", sizes.first, style.fontSize.value, 0.0001f)
            assertEquals("$name 行高", sizes.second, style.lineHeight.value, 0.0001f)
        }
    }
}
