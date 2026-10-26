package com.mailhub.client.ui.swiss

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mailhub.client.R
import kotlin.math.min

/**
 * Inter 只有 Regular 和 Bold 两个字重。中文走系统字体回退，同样只用常规和粗体。
 * 不提供 Medium/SemiBold/Black 等中间字重。
 */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * 超大字号最多只放大到 1.15 倍，避免系统字号调到最大时把版面撑破。
 * 返回的 sp 已经除过 fontScale，最终渲染尺寸是 base * min(fontScale, maxScale)。
 */
@Composable
fun clampedSp(base: Float, maxScale: Float = 1.15f): TextUnit {
    val fontScale = LocalDensity.current.fontScale
    return (base * min(fontScale, maxScale) / fontScale).sp
}

/** 全部文字样式。textAlign 一律 Start，不提供居中或两端对齐的入口。 */
object SwissType {

    @Composable
    fun display(): TextStyle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = clampedSp(112f),
        lineHeight = clampedSp(104f),
        letterSpacing = (-0.02f).em,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Start,
    )

    @Composable
    fun displaySmall(): TextStyle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = clampedSp(80f),
        lineHeight = clampedSp(76f),
        letterSpacing = (-0.02f).em,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Start,
    )

    @Composable
    fun title(): TextStyle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = clampedSp(56f),
        lineHeight = clampedSp(56f),
        letterSpacing = (-0.01f).em,
        textAlign = TextAlign.Start,
    )

    @Composable
    fun headline(): TextStyle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        textAlign = TextAlign.Start,
    )

    @Composable
    fun body(): TextStyle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Start,
    )

    @Composable
    fun caption(): TextStyle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        textAlign = TextAlign.Start,
    )

    @Composable
    fun label(): TextStyle = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.08f.em,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Start,
    )
}
