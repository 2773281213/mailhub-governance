package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 样张页面：从上到下把设计系统的每个组件展示一遍，供截图测试固定下来。
 * 页面本身可以纵向滚动，外层是 SwissScaffold（固定的一行页头 + 底部标签栏 + 安全区），
 * 大标题「样张」是滚动内容的第一项。
 */
@Composable
fun SwissSpecimen(
    modifier: Modifier = Modifier,
    selectedTab: Int = 1,
    onSelectTab: (Int) -> Unit = {},
) {
    val colors = LocalSwissColors.current
    var email by remember { mutableStateOf("hello@mailhub.dev") }
    var password by remember { mutableStateOf("hunter2hunter2") }
    var query by remember { mutableStateOf("") }
    var notifyOn by remember { mutableStateOf(true) }
    var wifiOnly by remember { mutableStateOf(false) }
    var filter by remember { mutableIntStateOf(1) }

    SwissScaffold(
        index = "00",
        section = "设计系统",
        meta = "2026 — 上海",
        modifier = modifier,
        bottomBar = {
            SwissTabBar(
                items = listOf("01" to "总览", "02" to "收件箱", "03" to "规则", "04" to "设置"),
                selected = selectedTab,
                onSelect = onSelectTab,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            PageTitle(text = "样张")

            SpecimenSection(index = "01", title = "字体") {
                TypeRow("128", SwissType.display())
                TypeRow("收件箱", SwissType.displaySmall())
                TypeRow("收件箱 Inbox", SwissType.title())
                TypeRow("收件箱 Inbox 128", SwissType.headline())
                TypeRow("收件箱 Inbox 128", SwissType.body())
                TypeRow("收件箱 Inbox 128", SwissType.caption())
                TypeRow("收件箱 Inbox 128", SwissType.label())
            }

            SpecimenSection(index = "02", title = "颜色") {
                GridRow {
                    // 深色模式下 Ink 色块和背景同色，必须像 Paper 色块一样加 1dp 前景色描边才看得见
                    ColorSwatch("Ink #000000", SwissPalette.Ink, Modifier.cols(1, 2), outlined = true)
                    ColorSwatch("Paper #F2EDE4", SwissPalette.Paper, Modifier.cols(5, 2), outlined = true)
                    ColorSwatch("Red #DA291C", SwissPalette.Red, Modifier.cols(9, 2))
                }
            }

            SpecimenSection(index = "03", title = "网格") {
                GridRow {
                    repeat(12) { index ->
                        Box(
                            modifier = Modifier
                                .cols(index + 1, 1)
                                .height(SwissSpace.M)
                                .background(colors.foreground),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(SwissSpace.S))
                HairlineRule(color = colors.foreground)
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
                HeavyRule(color = colors.foreground, start = 1, span = 8)
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
                FullBleedRule(color = colors.foreground)
            }

            SpecimenSection(index = "04", title = "按钮") {
                GridRow {
                    SwissButton("主要操作", {}, SwissButtonKind.Primary, modifier = Modifier.cols(1, 12))
                }
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
                GridRow {
                    SwissButton("危险操作", {}, SwissButtonKind.Accent, modifier = Modifier.cols(1, 12))
                }
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
                GridRow {
                    SwissButton("次要操作", {}, SwissButtonKind.Outline, modifier = Modifier.cols(1, 12))
                }
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
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
                        placeholder = "name@example.com",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.cols(1, 10),
                    )
                }
                Spacer(modifier = Modifier.height(SwissSpace.S))
                GridRow {
                    SwissTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "授权码",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = true,
                        errorText = "授权码不正确",
                        modifier = Modifier.cols(1, 10),
                    )
                }
                Spacer(modifier = Modifier.height(SwissSpace.S))
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                )
                Spacer(modifier = Modifier.height(SwissSpace.S))
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
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
                GridRow {
                    FilterOption("全部", filter == 0, { filter = 0 }, Modifier.cols(1, 2))
                    FilterOption("未读", filter == 1, { filter = 1 }, Modifier.cols(3, 2))
                    FilterOption("已读", filter == 2, { filter = 2 }, Modifier.cols(5, 2))
                }
            }

            SpecimenSection(index = "06", title = "图表") {
                BarChart(
                    values = listOf(38, 52, 41, 66, 74, 58, 49, 81, 63, 55, 70, 44, 60, 92),
                    accent = listOf(0, 8, 0, 12, 20, 0, 6, 24, 0, 10, 18, 0, 4, 30),
                    labels = List(14) { index -> (index + 1).toString().padStart(2, '0') },
                )
            }

            SpecimenSection(index = "07", title = "数据") {
                StatNumber(value = "128", label = "未读邮件")
                Spacer(modifier = Modifier.height(SwissSpace.M))
                StatNumber(value = "12840", label = "累计处理", accent = true)
                Spacer(modifier = Modifier.height(SwissSpace.M))
                TableRow(label = "账户", value = "hello@mailhub.dev")
                TableRow(label = "上次同步", value = "2026-11-04 16:54")
                TableRow(label = "状态", value = "已连接")
            }

            SpecimenSection(index = "08", title = "状态") {
                SwissSnackbar(message = "已同步 128 封邮件")
                Spacer(modifier = Modifier.height(SwissSpace.M))
                LoadingRule()
                Spacer(modifier = Modifier.height(SwissSpace.M))
                EmptyState(
                    index = "00",
                    title = "没有未读邮件",
                    body = "所有邮件都已读过。新的邮件到达时会出现在这里。",
                )
                Spacer(modifier = Modifier.height(SwissSpace.M))
                ErrorState(
                    message = "无法连接服务器，请检查网络后重试。",
                    onRetry = {},
                )
            }

            SpecimenSection(index = "09", title = "确认面板") {
                ConfirmPanelContent(
                    title = "永久删除 128 封邮件？",
                    message = "删除后无法恢复，也不会进入废纸篓。",
                    confirmText = "永久删除",
                    onConfirm = {},
                    onCancel = {},
                    danger = true,
                )
            }
        }
    }
}

@Composable
private fun TypeRow(text: String, style: androidx.compose.ui.text.TextStyle) {
    val colors = LocalSwissColors.current
    GridRow {
        SText(
            text = text,
            style = style,
            modifier = Modifier.cols(1, 12),
            color = colors.foreground,
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xxs))
}

@Composable
private fun ColorSwatch(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
) {
    val colors = LocalSwissColors.current
    Column(modifier = modifier) {
        GeoSquare(
            span = 2,
            color = color,
            modifier = if (outlined) Modifier.border(1.dp, colors.foreground) else Modifier,
        )
        Spacer(modifier = Modifier.height(SwissSpace.Xxs))
        SText(
            text = label,
            style = SwissType.label(),
            color = colors.foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun SpecimenSection(
    index: String,
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(modifier = Modifier.height(SwissSpace.L))
    SectionHeader(index = index, title = title)
    Spacer(modifier = Modifier.height(SwissSpace.S))
    content()
    Spacer(modifier = Modifier.height(SwissSpace.M))
}
