package com.mailhub.client.ui.screens.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mailhub.client.data.model.Account
import com.mailhub.client.ui.screens.common.MailRow
import com.mailhub.client.ui.screens.common.PageMeta
import com.mailhub.client.ui.screens.common.accountLabel
import com.mailhub.client.ui.screens.common.categoryLabel
import com.mailhub.client.ui.screens.shell.AppTabs
import com.mailhub.client.ui.swiss.ConfirmPanel
import com.mailhub.client.ui.swiss.EmptyState
import com.mailhub.client.ui.swiss.ErrorState
import com.mailhub.client.ui.swiss.FilterOption
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SearchField
import com.mailhub.client.ui.swiss.SwissScaffold
import com.mailhub.client.ui.swiss.SwissSnackbar
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissTabBar
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.cols

/** 筛选项每行放 3 个，每个占 4 栏，正好把 12 栏铺满。 */
private const val ChipsPerRow = 3

/** 距离列表底部还剩这么多条时就开始加载下一页。 */
private const val LoadMoreAhead = 3

/** 分类「全部」的编号，与 /api/meta 的分类编号连成 00、01、02 … */
private const val AllCategoryIndex = 0

/** 「全部」这一项的显示文字：编号 00 让整行编号连续。 */
private const val AllCategoryText = "00 全部"

/**
 * 收件箱（无状态）：搜索框、状态筛选、分类筛选（两位编号）、账户筛选（A1 A2），
 * 下面是共享的邮件行列表。多选模式下底部标签栏换成操作栏。
 * 整屏没有红色主操作按钮，红色只出现在未读方块和分类小方块上。
 */
@Composable
fun InboxScreen(
    state: InboxUiState,
    meta: PageMeta,
    onEvent: (InboxEvent) -> Unit,
    modifier: Modifier = Modifier,
    selectedTab: Int = 1,
    onSelectTab: (Int) -> Unit = {},
) {
    Box(modifier = modifier) {
        SwissScaffold(
            index = "02",
            section = "收件",
            meta = meta.line,
            title = "收件",
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val notice = state.notice
                    if (notice != null) {
                        DismissibleSnackbar(
                            message = notice,
                            onDismiss = { onEvent(InboxEvent.DismissNotice) },
                        )
                    }
                    if (state.selectMode) {
                        SelectActionBar(state = state, onEvent = onEvent)
                    } else {
                        SwissTabBar(items = AppTabs, selected = selectedTab, onSelect = onSelectTab)
                    }
                }
            },
        ) {
            FilterSection(state = state, onEvent = onEvent)
            Spacer(modifier = Modifier.height(SwissSpace.S))
            HairlineRule()
            Spacer(modifier = Modifier.height(SwissSpace.Xs))
            GridRow {
                RowTextButton(
                    text = "刷新 →",
                    onClick = { onEvent(InboxEvent.Refresh) },
                    modifier = Modifier.cols(1, 3),
                )
            }
            MailList(
                state = state,
                meta = meta,
                onEvent = onEvent,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.confirmingDelete) {
            ConfirmPanel(
                title = "删除 ${state.selectedCount} 封？",
                message = "邮件进入回收站，可以在安全中心恢复。",
                confirmText = "删除",
                onConfirm = { onEvent(InboxEvent.ConfirmDelete) },
                onCancel = { onEvent(InboxEvent.DismissDelete) },
                danger = true,
            )
        }
    }
}

/** 搜索框 + 状态、分类、账户三组筛选项。 */
@Composable
private fun FilterSection(state: InboxUiState, onEvent: (InboxEvent) -> Unit) {
    SearchField(
        value = state.filters.query,
        onValueChange = { onEvent(InboxEvent.QueryChanged(it)) },
    )
    Spacer(modifier = Modifier.height(SwissSpace.S))
    GridRow {
        FilterOption(
            text = "全部",
            selected = !state.filters.unreadOnly,
            onClick = { onEvent(InboxEvent.SetUnreadOnly(false)) },
            modifier = Modifier.cols(1, 4),
        )
        FilterOption(
            text = "未读",
            selected = state.filters.unreadOnly,
            onClick = { onEvent(InboxEvent.SetUnreadOnly(true)) },
            modifier = Modifier.cols(5, 4),
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xs))
    CategoryFilters(
        categories = state.categories,
        selected = state.filters.category,
        onSelect = { onEvent(InboxEvent.SelectCategory(it)) },
    )
    if (state.accounts.isNotEmpty()) {
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        AccountFilters(
            accounts = state.accounts,
            selectedId = state.filters.accountId,
            onSelect = { onEvent(InboxEvent.SelectAccount(it)) },
        )
    }
}

/** 分类筛选：00 全部 + /api/meta 的 display_categories，编号从 01 起。 */
@Composable
private fun CategoryFilters(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val chips = ArrayList<CategoryChip>(categories.size + 1)
    chips += CategoryChip(index = AllCategoryIndex, name = "", isAll = true)
    categories.forEachIndexed { index, name ->
        chips += CategoryChip(index = index + 1, name = name, isAll = false)
    }
    chips.chunked(ChipsPerRow).forEach { row ->
        GridRow {
            row.forEachIndexed { column, chip ->
                FilterOption(
                    text = if (chip.isAll) AllCategoryText else categoryLabel(chip.index, chip.name),
                    selected = if (chip.isAll) selected.isEmpty() else chip.name == selected,
                    onClick = { onSelect(if (chip.isAll) "" else chip.name) },
                    modifier = Modifier.cols(1 + column * 4, 4),
                )
            }
        }
    }
}

/** 分类筛选的一项：编号 + 分类名，「全部」单独标记。 */
private data class CategoryChip(val index: Int, val name: String, val isAll: Boolean)

/** 账户筛选：按账户列表顺序编 A1 A2，不用账户颜色。 */
@Composable
private fun AccountFilters(
    accounts: List<Account>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
) {
    val chips = ArrayList<Pair<Int, String>>(accounts.size + 1)
    chips += 0 to "全部账户"
    accounts.forEach { account ->
        chips += account.id to "${accountLabel(accounts, account.id)} ${account.name}"
    }
    chips.chunked(ChipsPerRow).forEach { row ->
        GridRow {
            row.forEachIndexed { column, chip ->
                FilterOption(
                    text = chip.second,
                    selected = chip.first == selectedId,
                    onClick = { onSelect(chip.first) },
                    modifier = Modifier.cols(1 + column * 4, 4),
                )
            }
        }
    }
}

/** 邮件列表：加载中、空、失败各自一个状态；正常时用共享行铺开，滚到底自动翻页。 */
@Composable
private fun MailList(
    state: InboxUiState,
    meta: PageMeta,
    onEvent: (InboxEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when {
            state.error != null && state.items.isEmpty() -> {
                Spacer(modifier = Modifier.height(SwissSpace.S))
                ErrorState(
                    message = state.error,
                    onRetry = { onEvent(InboxEvent.Refresh) },
                )
            }

            state.loading && state.items.isEmpty() -> {
                Spacer(modifier = Modifier.height(SwissSpace.M))
                LoadingRule()
            }

            state.isEmpty -> {
                Spacer(modifier = Modifier.height(SwissSpace.M))
                EmptyState(
                    index = "02",
                    title = "没有邮件",
                    body = "换个筛选条件，或点上面的刷新再试一次。",
                )
            }

            else -> MailRows(state = state, meta = meta, onEvent = onEvent)
        }
    }
}

/** 正常的行列表。key 用邮件 id，翻页合并时不会重建已有行。 */
@Composable
private fun MailRows(state: InboxUiState, meta: PageMeta, onEvent: (InboxEvent) -> Unit) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LoadMoreAhead
        }
    }
    LaunchedEffect(shouldLoadMore, state.items.size, state.endReached) {
        if (shouldLoadMore && !state.endReached) {
            onEvent(InboxEvent.LoadMore)
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        items(items = state.items, key = { it.id }) { item ->
            MailRow(
                data = mailRowOf(item, state.categories),
                zoneId = meta.zoneId,
                selected = if (state.selectMode) item.id in state.selected else null,
                onClick = {
                    if (state.selectMode) {
                        onEvent(InboxEvent.ToggleSelected(item.id))
                    } else {
                        onEvent(InboxEvent.Open(item.id))
                    }
                },
                onLongClick = { onEvent(InboxEvent.LongPress(item.id)) },
            )
        }
        if (state.loadingMore) {
            item(key = "loading-more") {
                Spacer(modifier = Modifier.height(SwissSpace.S))
                LoadingRule()
            }
        }
    }
}

/** 多选模式下的底部操作栏：已读、未读、删除、取消，各占 3 栏。 */
@Composable
private fun SelectActionBar(state: InboxUiState, onEvent: (InboxEvent) -> Unit) {
    val enabled = !state.busy && state.selectedCount > 0
    Column(modifier = Modifier.fillMaxWidth()) {
        HairlineRule()
        GridRow(modifier = Modifier.height(SwissSpace.Xl)) {
            RowTextButton(
                text = "已读",
                enabled = enabled,
                onClick = { onEvent(InboxEvent.SetRead(true)) },
                modifier = Modifier.cols(1, 3),
            )
            RowTextButton(
                text = "未读",
                enabled = enabled,
                onClick = { onEvent(InboxEvent.SetRead(false)) },
                modifier = Modifier.cols(4, 3),
            )
            RowTextButton(
                text = "删除",
                enabled = enabled,
                onClick = { onEvent(InboxEvent.RequestDelete) },
                modifier = Modifier.cols(7, 3),
            )
            RowTextButton(
                text = "取消",
                onClick = { onEvent(InboxEvent.CancelSelect) },
                modifier = Modifier.cols(10, 3),
            )
        }
    }
}

/** 文字按钮：只有一行 label 文字和最小触控高度，不做底色、不做胶囊。 */
@Composable
private fun RowTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalSwissColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .sizeIn(minHeight = SwissSpace.Xl)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        SText(
            text = text,
            style = SwissType.label(),
            color = colors.foreground,
            maxLines = 1,
        )
    }
}

/** 点一下就关掉的提示条。 */
@Composable
private fun DismissibleSnackbar(message: String, onDismiss: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onDismiss,
            ),
    ) {
        SwissSnackbar(message = message)
    }
}

/**
 * 收件箱的有状态外层。ViewModel 由外壳持有：从详情返回时不会再从头加载，
 * 已读状态和删除结果已经由外壳回写进同一个状态。
 */
@Composable
fun InboxScreenHost(
    viewModel: InboxViewModel,
    meta: PageMeta,
    onOpenMessage: (Long) -> Unit,
    modifier: Modifier = Modifier,
    selectedTab: Int = 1,
    onSelectTab: (Int) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.onEnter()
    }
    InboxScreen(
        state = state,
        meta = meta,
        onEvent = { event ->
            if (event is InboxEvent.Open) {
                onOpenMessage(event.id)
            } else {
                viewModel.onEvent(event)
            }
        },
        modifier = modifier,
        selectedTab = selectedTab,
        onSelectTab = onSelectTab,
    )
}
