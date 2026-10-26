package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 样张页面：从上到下把设计系统的每个部分展示一遍，供截图测试固定下来。
 * 不放滚动容器，截图时把窗口高度设得足够大。
 */
@Composable
fun SwissSpecimen(
    modifier: Modifier = Modifier,
    selectedTab: Int = 1,
    onSelectTab: (Int) -> Unit = {},
) {
    val colors = LocalSwissColors.current
    var email by remember { mutableStateOf("hello@mailhub.dev") }
    var code by remember { mutableStateOf("000000") }
    var notifyOn by remember { mutableStateOf(true) }
    var wifiOnly by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background),
    ) {
        GridRow {
            IndexLabel(index = "00", modifier = Modifier.cols(1, 2))
            MetaLine(year = 2026, place = "上海", modifier = Modifier.cols(9, 4))
        }
        Spacer(modifier = Modifier.height(8.dp))
        HeavyRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(8.dp))
        GridRow {
            SText(
                text = "样张",
                style = SwissType.title(),
                modifier = Modifier.cols(1, 12),
                color = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))

        SpecimenSection(index = "01", title = "字体") {
            GridRow {
                SText("128", SwissType.display(), Modifier.cols(1, 12), color = colors.foreground)
            }
            Spacer(modifier = Modifier.height(4.dp))
            GridRow {
                SText("收件箱", SwissType.displaySmall(), Modifier.cols(1, 12), color = colors.foreground)
            }
            Spacer(modifier = Modifier.height(4.dp))
            GridRow {
                SText("收件箱 Inbox", SwissType.title(), Modifier.cols(1, 12), color = colors.foreground)
            }
            Spacer(modifier = Modifier.height(4.dp))
            GridRow {
                SText("收件箱 Inbox 128", SwissType.headline(), Modifier.cols(1, 12), color = colors.foreground)
            }
            Spacer(modifier = Modifier.height(4.dp))
            GridRow {
                SText("收件箱 Inbox 128", SwissType.body(), Modifier.cols(1, 12), color = colors.foreground)
            }
            Spacer(modifier = Modifier.height(4.dp))
            GridRow {
                SText("收件箱 Inbox 128", SwissType.caption(), Modifier.cols(1, 12), color = colors.foreground)
            }
            Spacer(modifier = Modifier.height(4.dp))
            GridRow {
                SText("收件箱 Inbox 128", SwissType.label(), Modifier.cols(1, 12), color = colors.foreground)
            }
        }

        SpecimenSection(index = "02", title = "颜色") {
            GridRow {
                Column(modifier = Modifier.cols(1, 3)) {
                    GeoSquare(size = 64.dp, color = SwissPalette.Ink)
                    Spacer(modifier = Modifier.height(4.dp))
                    SText("Ink #000000", SwissType.label(), color = colors.foreground)
                }
                Column(modifier = Modifier.cols(5, 3)) {
                    GeoSquare(
                        size = 64.dp,
                        color = SwissPalette.Paper,
                        modifier = Modifier.border(1.dp, colors.foreground),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SText("Paper #F2EDE4", SwissType.label(), color = colors.foreground)
                }
                Column(modifier = Modifier.cols(9, 3)) {
                    GeoSquare(size = 64.dp, color = SwissPalette.Red)
                    Spacer(modifier = Modifier.height(4.dp))
                    SText("Red #DA291C", SwissType.label(), color = colors.foreground)
                }
            }
        }

        SpecimenSection(index = "03", title = "网格") {
            GridRow {
                repeat(12) { index ->
                    Box(
                        modifier = Modifier
                            .cols(index + 1, 1)
                            .height(24.dp)
                            .background(colors.foreground),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            GridRow {
                SText("128", SwissType.displaySmall(), Modifier.cols(1, 8), color = colors.foreground)
                SText("未读", SwissType.label(), Modifier.cols(10, 3), color = colors.foreground)
            }
        }

        SpecimenSection(index = "04", title = "按钮") {
            GridRow {
                SwissButton("主要操作", {}, SwissButtonKind.Primary, modifier = Modifier.cols(1, 12))
            }
            Spacer(modifier = Modifier.height(8.dp))
            GridRow {
                SwissButton("危险操作", {}, SwissButtonKind.Accent, modifier = Modifier.cols(1, 12))
            }
            Spacer(modifier = Modifier.height(8.dp))
            GridRow {
                SwissButton("次要操作", {}, SwissButtonKind.Outline, modifier = Modifier.cols(1, 12))
            }
            Spacer(modifier = Modifier.height(8.dp))
            GridRow {
                SwissButton(
                    text = "不可用操作",
                    onClick = {},
                    kind = SwissButtonKind.Primary,
                    enabled = false,
                    modifier = Modifier.cols(1, 12),
                )
            }
        }

        SpecimenSection(index = "05", title = "输入") {
            GridRow {
                SwissTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "邮箱地址",
                    modifier = Modifier.cols(1, 10),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            GridRow {
                SwissTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = "授权码",
                    modifier = Modifier.cols(1, 10),
                    isError = true,
                    errorText = "授权码不正确",
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            GridRow {
                SwissToggle(
                    checked = notifyOn,
                    onCheckedChange = { notifyOn = it },
                    label = "同步通知",
                    modifier = Modifier.cols(1, 6),
                )
                SwissToggle(
                    checked = wifiOnly,
                    onCheckedChange = { wifiOnly = it },
                    label = "仅 Wi-Fi",
                    modifier = Modifier.cols(7, 6),
                )
            }
        }

        SpecimenSection(index = "06", title = "图表") {
            GridRow {
                BarChart(
                    values = listOf(38, 52, 41, 66, 74, 58, 49, 81, 63, 55, 70, 44, 60, 92),
                    accent = listOf(0, 8, 0, 12, 20, 0, 6, 24, 0, 10, 18, 0, 4, 30),
                    labels = List(14) { index -> (index + 1).toString().padStart(2, '0') },
                    modifier = Modifier.cols(1, 12),
                )
            }
        }

        SpecimenSection(index = "07", title = "确认面板") {
            ConfirmPanelContent(
                title = "永久删除 128 封邮件？",
                message = "删除后无法恢复，也不会进入废纸篓。",
                confirmText = "永久删除",
                onConfirm = {},
                onCancel = {},
                danger = true,
            )
        }

        SwissTabBar(
            items = listOf("01" to "总览", "02" to "收件箱", "03" to "规则", "04" to "设置"),
            selected = selectedTab,
            onSelect = onSelectTab,
        )
    }
}

@Composable
private fun SpecimenSection(
    index: String,
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = LocalSwissColors.current
    HairlineRule(color = colors.foreground)
    Spacer(modifier = Modifier.height(16.dp))
    GridRow {
        IndexLabel(index = index, modifier = Modifier.cols(1, 2))
        SText(
            text = title,
            style = SwissType.headline(),
            modifier = Modifier.cols(3, 10),
            color = colors.foreground,
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    content()
    Spacer(modifier = Modifier.height(24.dp))
}
