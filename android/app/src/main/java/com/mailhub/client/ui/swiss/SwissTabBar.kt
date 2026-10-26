package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 底部文字标签栏：参数是「编号 to 名称」的列表。每项显示 01 总览，
 * 当前项粗体且上方有 4dp 红线；顶部有 1dp 细线；整体高度 56dp；
 * 按 12 栏平均分配，4 项各占 3 栏。
 */
@Composable
fun SwissTabBar(
    items: List<Pair<String, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSwissColors.current
    val interactionSources = remember(items.size) { List(items.size) { MutableInteractionSource() } }
    Column(modifier = modifier.fillMaxWidth()) {
        HairlineRule()
        GridRow(modifier = Modifier.height(56.dp)) {
            items.forEachIndexed { index, item ->
                val interactionSource = interactionSources[index]
                val isSelected = index == selected
                val labelStyle = TextStyle(
                    fontFamily = InterFamily,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 0.02f.em,
                )
                Column(
                    modifier = Modifier
                        .cols(start = index * 3 + 1, span = 3)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelect(index) },
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(if (isSelected) colors.accent else colors.background),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SText(
                            text = item.first,
                            style = labelStyle,
                            color = colors.foreground,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        SText(
                            text = item.second,
                            style = labelStyle,
                            color = colors.foreground,
                        )
                    }
                }
            }
        }
    }
}
