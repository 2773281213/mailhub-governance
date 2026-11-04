package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight

/** 标签栏只能有 2、3、4、6 项，这样 12 栏才除得尽。 */
private val SupportedTabCounts = setOf(2, 3, 4, 6)

/**
 * 每项占的栏数：2 项各占 6 栏，3 项各占 4 栏，4 项各占 3 栏，6 项各占 2 栏。
 * 其他项数直接失败，不会退化成对不齐栏线的平均分配。
 */
fun tabSpanFor(itemCount: Int): Int {
    require(itemCount in SupportedTabCounts) {
        "标签栏只支持 2、3、4、6 项，收到 $itemCount 项；其他项数在 12 栏网格里除不尽。"
    }
    return 12 / itemCount
}

/**
 * 底部文字标签栏：参数是「编号 to 名称」的列表。每项显示 01 总览，按 [tabSpanFor] 分栏，
 * 当前项粗体、上方有 4dp 红线；顶部有 1dp 细线；整体高度 48dp。
 * 文字容器没有水平内边距，所以每项文字左边缘正好落在该项起始栏线上；
 * 红色指示线宽度等于这一项的栏宽总和。
 */
@Composable
fun SwissTabBar(
    items: List<Pair<String, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = tabSpanFor(items.size)
    val colors = LocalSwissColors.current
    val interactionSources = remember(items.size) { List(items.size) { MutableInteractionSource() } }
    Column(modifier = modifier.fillMaxWidth()) {
        HairlineRule()
        GridRow(modifier = Modifier.height(SwissSpace.Xl)) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .cols(start = index * span + 1, span = span)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            interactionSource = interactionSources[index],
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SwissSpace.Xxs)
                            .background(if (isSelected) colors.accent else colors.background),
                    )
                    Spacer(modifier = Modifier.height(SwissSpace.Xs))
                    SText(
                        text = "${item.first} ${item.second}",
                        style = SwissType.caption().copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = colors.foreground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
