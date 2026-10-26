package com.mailhub.client.ui.swiss

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 12 栏模块化网格。margin 是左右页边距，gutter 是栏间距。
 * 所有几何计算都是纯函数，方便 JVM 单元测试。
 */
@Immutable
data class GridSpec(
    val columns: Int = 12,
    val margin: Dp,
    val gutter: Dp,
) {
    fun columnWidth(total: Dp): Dp = (total - margin * 2 - gutter * (columns - 1)) / columns

    fun x(startCol: Int, total: Dp): Dp {
        require(startCol in 1..columns) { "栏号必须在 1..$columns 之间，收到 $startCol" }
        return margin + (columnWidth(total) + gutter) * (startCol - 1)
    }

    fun spanWidth(span: Int, total: Dp): Dp {
        require(span in 1..columns) { "跨度必须在 1..$columns 之间，收到 $span" }
        return columnWidth(total) * span + gutter * (span - 1)
    }

    /** 元素越过第 12 栏时直接失败，不允许元素掉到网格外面。 */
    fun requirePlacement(start: Int, span: Int) {
        require(start in 1..columns && span in 1..columns && start + span - 1 <= columns) {
            "元素越界：start=$start, span=$span, columns=$columns"
        }
    }

    /** 一栏加一个栏间距，也就是相邻两条栏线之间的距离。 */
    fun step(total: Dp): Dp = columnWidth(total) + gutter
}

/** 宽度 < 600dp 用 16/8，< 840dp 用 24/16，否则 32/24。 */
fun gridSpecFor(width: Dp): GridSpec = when {
    width < 600.dp -> GridSpec(margin = 16.dp, gutter = 8.dp)
    width < 840.dp -> GridSpec(margin = 24.dp, gutter = 16.dp)
    else -> GridSpec(margin = 32.dp, gutter = 24.dp)
}

val LocalGridSpec = staticCompositionLocalOf { GridSpec(margin = 16.dp, gutter = 8.dp) }

/** 当前网格的可用宽度。文字内边距要用它来算 step，所以单独下发。 */
val LocalGridWidth = staticCompositionLocalOf { 360.dp }

/** 用 BoxWithConstraints 量出可用宽度，再下发对应的 GridSpec 和宽度。 */
@Composable
fun ProvideSwissGrid(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val spec = gridSpecFor(maxWidth)
        CompositionLocalProvider(
            LocalGridSpec provides spec,
            LocalGridWidth provides maxWidth,
        ) {
            content()
        }
    }
}

private data class ColsParentData(val start: Int, val span: Int)

private class ColsNode(var start: Int, var span: Int) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = ColsParentData(start, span)
}

private data class ColsElement(val start: Int, val span: Int) : ModifierNodeElement<ColsNode>() {
    override fun create(): ColsNode = ColsNode(start, span)

    override fun update(node: ColsNode) {
        node.start = start
        node.span = span
    }
}

/** 把子元素放到第 [start] 栏、占 [span] 栏。越界在测量阶段抛 IllegalArgumentException。 */
fun Modifier.cols(start: Int, span: Int): Modifier = this then ColsElement(start, span)

/**
 * 自定义网格行布局：横坐标取 x(start)，宽度取 spanWidth(span)，行高取子元素最大高度。
 * 子元素必须用 [cols] 指定位置。
 */
@Composable
fun GridRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val spec = LocalGridSpec.current
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val total = constraints.maxWidth.toDp()
        val placed = measurables.map { measurable ->
            val data = measurable.parentData as? ColsParentData ?: ColsParentData(start = 1, span = 1)
            spec.requirePlacement(data.start, data.span)
            val width = spec.spanWidth(data.span, total).roundToPx()
            val placeable = measurable.measure(
                Constraints(
                    minWidth = width,
                    maxWidth = width,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                ),
            )
            Triple(placeable, spec.x(data.start, total).roundToPx(), data)
        }
        val rowHeight = placed.maxOfOrNull { it.first.height } ?: 0
        layout(constraints.maxWidth, rowHeight) {
            placed.forEach { (placeable, x, _) -> placeable.placeRelative(x, 0) }
        }
    }
}

/**
 * 对齐检查层：只用 1px 红线画出每一栏的左右边缘，不填色。
 * 截图时叠在内容上方，用来看左边缘是否落在栏线上。
 */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    val spec = LocalGridSpec.current
    val accent = LocalSwissColors.current.accent
    Canvas(modifier = modifier.fillMaxSize()) {
        val total = size.width.toDp()
        val columnWidth = spec.columnWidth(total).toPx()
        for (column in 1..spec.columns) {
            val left = spec.x(column, total).toPx()
            drawLine(
                color = accent,
                start = Offset(left, 0f),
                end = Offset(left, size.height),
                strokeWidth = 1f,
            )
            val right = left + columnWidth
            drawLine(
                color = accent,
                start = Offset(right, 0f),
                end = Offset(right, size.height),
                strokeWidth = 1f,
            )
        }
    }
}
