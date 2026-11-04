package com.mailhub.client.ui.screens.placeholder

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mailhub.client.ui.screens.common.PageMeta
import com.mailhub.client.ui.screens.shell.AppTabs
import com.mailhub.client.ui.swiss.EmptyState
import com.mailhub.client.ui.swiss.SwissScaffold
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTabBar

/**
 * 占位页：收件、验证码、安全以及索引层里的二级页面在本阶段都用它，
 * 内容是一个 EmptyState（编号 + 页面名 + 说明）。
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
) {
    SwissScaffold(
        index = index,
        section = section,
        meta = meta.line,
        title = title,
        modifier = modifier,
        bottomBar = {
            SwissTabBar(items = AppTabs, selected = selectedTab, onSelect = onSelectTab)
        },
    ) {
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        EmptyState(index = index, title = title, body = "下一阶段实现")
    }
}
