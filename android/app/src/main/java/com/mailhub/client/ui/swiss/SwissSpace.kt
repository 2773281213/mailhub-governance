package com.mailhub.client.ui.swiss

import androidx.compose.ui.unit.dp

/**
 * 基线网格的间距 token。全工程只有这八档，都是 4 的倍数。
 * 设计系统之外的代码（包括下一阶段的页面层）只能用这里的值，不准出现别的间距数字。
 */
object SwissSpace {
    /** 4dp：最小间距，也是粗线的线宽。 */
    val Xxs = 4.dp

    /** 8dp：紧凑间距。 */
    val Xs = 8.dp

    /** 16dp：标准间距。 */
    val S = 16.dp

    /** 24dp：区块内间距。 */
    val M = 24.dp

    /** 32dp：区块间距。 */
    val L = 32.dp

    /** 48dp：大留白，也是最小触控高度。 */
    val Xl = 48.dp

    /** 64dp：段落分隔。 */
    val Xxl = 64.dp

    /** 96dp：最大留白，用于页面级空白。 */
    val Xxxl = 96.dp
}
