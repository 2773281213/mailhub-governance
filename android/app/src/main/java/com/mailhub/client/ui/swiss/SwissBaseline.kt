package com.mailhub.client.ui.swiss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/**
 * 同一行里两种字号的文字要首行基线对齐：把后一种样式的首行基线减去前一种的首行基线，
 * 得出的差值补成前一种文字的顶部内边距即可。栏位不变，只挪文字。
 *
 * 测量必须用这一行真实的两段文字：中英文会回退到不同字体，行高和基线都不一样。
 */
@Composable
fun baselineTopPadding(
    fromStyle: TextStyle,
    fromText: String,
    toStyle: TextStyle,
    toText: String,
): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, fromStyle, fromText, toStyle, toText, density) {
        with(density) {
            val fromLine = measurer.measure(
                text = AnnotatedString(fromText),
                style = fromStyle,
                maxLines = 1,
            )
            val toLine = measurer.measure(
                text = AnnotatedString(toText),
                style = toStyle,
                maxLines = 1,
            )
            (toLine.firstBaseline - fromLine.firstBaseline).coerceAtLeast(0f).toDp()
        }
    }
}

/**
 * 同一行里多段同字号的文字（例如页头的编号、栏目名、元信息和操作入口）共用一条首行基线：
 * 以整行最低的那条首行基线为目标，其它每段各自往下补多少。
 *
 * 与 [baselineTopPadding] 一样必须用真实文字测量：中文回退到系统字体后首行基线比数字低，
 * 同一段文字换成另一段字，补出来的数值就可能不一样。只补不缩，栏位不变。
 */
@Composable
fun baselineTopPaddings(style: TextStyle, texts: List<String>): List<Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, style, texts) {
        val baselines = texts.map { text ->
            measurer.measure(
                text = AnnotatedString(text),
                style = style,
                maxLines = 1,
            ).firstBaseline
        }
        val target = baselines.maxOrNull() ?: 0f
        with(density) { baselines.map { (target - it).coerceAtLeast(0f).toDp() } }
    }
}
