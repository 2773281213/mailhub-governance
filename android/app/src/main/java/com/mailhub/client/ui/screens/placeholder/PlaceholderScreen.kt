package com.mailhub.client.ui.screens.placeholder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mailhub.client.ui.screens.common.PageMeta
import com.mailhub.client.ui.screens.shell.AppTabs
import com.mailhub.client.ui.screens.shell.IndexActionLabel
import com.mailhub.client.ui.swiss.EmptyState
import com.mailhub.client.ui.swiss.PageTitle
import com.mailhub.client.ui.swiss.SwissScaffold
import com.mailhub.client.ui.swiss.SwissTabBar

/**
 * 占位页：收件、验证码、安全以及索引层里的二级页面在本阶段都用它，
 * 内容是一个 EmptyState（编号 + 页面名 + 说明）。
 *
 * 和正式页面一样，固定区只有一行页头，页面名这个大标题是滚动内容的第一项。
 */
@Composable
fun PlaceholderScreen(
    index: String,
    section: String,
    title: String,
    meta: PageMeta,
    modifier: Modifier = Modifier,
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    onOpenIndex: () -> Unit = {},
) {
    SwissScaffold(
        index = index,
        section = section,
        meta = meta.line,
        modifier = modifier,
        actionLabel = IndexActionLabel,
        onAction = onOpenIndex,
        bottomBar = {
            SwissTabBar(items = AppTabs, selected = selectedTab, onSelect = onSelectTab)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            PageTitle(text = title)
            EmptyState(index = index, title = title, body = "下一阶段实现")
        }
    }
}
