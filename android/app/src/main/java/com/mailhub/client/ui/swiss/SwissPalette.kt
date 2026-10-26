package com.mailhub.client.ui.swiss

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 瑞士风格的三色。整个工程只有这个文件可以出现颜色字面量。
 * 颜色值和 res/values/colors.xml 保持一致。
 */
object SwissPalette {
    val Ink = Color(0xFF000000)
    val Paper = Color(0xFFF2EDE4)
    val Red = Color(0xFFDA291C)
}

/** 一套配色只描述三个角色：背景、前景、强调色。 */
@Immutable
data class SwissColors(
    val background: Color,
    val foreground: Color,
    val accent: Color,
) {
    companion object {
        val Light = SwissColors(
            background = SwissPalette.Paper,
            foreground = SwissPalette.Ink,
            accent = SwissPalette.Red,
        )
        val Dark = SwissColors(
            background = SwissPalette.Ink,
            foreground = SwissPalette.Paper,
            accent = SwissPalette.Red,
        )
    }
}

val LocalSwissColors = staticCompositionLocalOf { SwissColors.Light }
