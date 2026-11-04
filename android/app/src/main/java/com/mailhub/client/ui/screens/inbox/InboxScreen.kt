package com.mailhub.client.ui.screens.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mailhub.client.data.model.Account
import com.mailhub.client.ui.screens.common.MailRow
import com.mailhub.client.ui.screens.common.PageMeta
import com.mailhub.client.ui.screens.common.accountLabel
import com.mailhub.client.ui.screens.common.categoryLabel
import com.mailhub.client.ui.screens.shell.AppTabs
import com.mailhub.client.ui.screens.shell.IndexActionLabel
import com.mailhub.client.ui.swiss.ConfirmPanel
import com.mailhub.client.ui.swiss.EmptyState
import com.mailhub.client.ui.swiss.ErrorState
import com.mailhub.client.ui.swiss.FilterOption
import com.mailhub.client.ui.swiss.FilterOptionTextBottomInset
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.LoadingRule
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.PageTitle
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
 * 收件箱（无状态）：大标题、搜索框、状态筛选、分类筛选（两位编号）、账户筛选（A1 A2）、
 * 刷新入口和共享的邮件行列表，全部放在同一个 [LazyColumn] 里——只有页头那一行固定，
 * 其余内容（包括「收件」这个大标题）都跟着列表一起向上滚走。
 * 多选模式下底部标签栏换成操作栏。
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
    onOpenIndex: () -> Unit = {},
) {
    Box(modifier = modifier) {
        SwissScaffold(
            index = "02",
            section = "收件",
            meta = meta.line,
            actionLabel = IndexActionLabel,
            onAction = onOpenIndex,
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
            InboxList(state = state, meta = meta, onEvent = onEvent)
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

/**
 * 搜索框 + 状态行 + 一行摘要；分类和账户两组筛选项默认收起，
 * 点「筛选 →」才在摘要下方展开（同时按钮变成「收起 →」）。
 */
@Composable
private fun FilterSection(state: InboxUiState, onEvent: (InboxEvent) -> Unit) {
    SearchField(
        value = state.filters.query,
        onValueChange = { onEvent(InboxEvent.QueryChanged(it)) },
    )
    Spacer(modifier = Modifier.height(SwissSpace.S))
    GridRow {
        FilterOption(
            text = AllFilterText,
            selected = !state.filters.unreadOnly,
            onClick = { onEvent(InboxEvent.SetUnreadOnly(false)) },
            modifier = Modifier.cols(1, 4),
        )
        FilterOption(
            text = UnreadFilterText,
            selected = state.filters.unreadOnly,
            onClick = { onEvent(InboxEvent.SetUnreadOnly(true)) },
            modifier = Modifier.cols(5, 4),
        )
        val expandText = if (state.filtersExpanded) CollapseFiltersText else ExpandFiltersText
        RowTextButton(
            text = expandText,
            onClick = { onEvent(InboxEvent.ToggleFilters) },
            modifier = Modifier.cols(9, 4),
            labelModifier = Modifier.padding(
                top = filterRowLabelTopPadding(tabText = AllFilterText, buttonText = expandText),
            ),
        )
    }
    Spacer(modifier = Modifier.height(SwissSpace.Xs))
    GridRow {
        SText(
            text = filtersSummary(state.filters, state.categories, state.accounts),
            style = SwissType.caption(),
            modifier = Modifier.cols(1, 12),
            color = LocalSwissColors.current.foreground,
            maxLines = 1,
        )
    }
    if (state.filtersExpanded) {
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        FilterGroupLabel(text = "分类")
        CategoryFilters(
            categories = state.categories,
            selected = state.filters.category,
            onSelect = { onEvent(InboxEvent.SelectCategory(it)) },
        )
        if (state.accounts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(SwissSpace.S))
            FilterGroupLabel(text = "账户")
            AccountFilters(
                accounts = state.accounts,
                selectedId = state.filters.accountId,
                onSelect = { onEvent(InboxEvent.SelectAccount(it)) },
            )
        }
    }
}

/** 展开按钮的文字。 */
private const val ExpandFiltersText = "筛选 →"

/** 收起按钮的文字。 */
private const val CollapseFiltersText = "收起 →"

/** 状态筛选「全部」的文字。 */
private const val AllFilterText = "全部"

/** 状态筛选「未读」的文字。 */
private const val UnreadFilterText = "未读"

/**
 * 让「筛选 →」与同一行的两个筛选项共用同一条首行基线的顶部内边距。
 *
 * 根因：`FilterOption` 的触控区固定 48dp，文字压在底部，下沿之下还留着
 * [FilterOptionTextBottomInset]（文字与下划线之间的留白 + 下划线本身）；
 * 而这行里的纯文字按钮是顶边摆放的，于是两者的首行基线差出一整段。
 *
 * 做法：把 caption 的首行基线换算成「离触控区底边多远」，再把 label 的首行基线
 * 从它自己的顶边减掉，差多少就补多少顶部内边距。参与运算的全是测量结果和设计 token，
 * 不含任何硬编码的间距数字。文字下移不影响触控区（仍是 48dp），也不动下划线的位置。
 *
 * 测量必须用这一行里真实的那两段文字：中英文字形回退到不同字体，行高和基线都不一样，
 * 拿别的字符去量会差好几 dp（[tabText] 传筛选项的文字，[buttonText] 传按钮的文字）。
 */
@Composable
private fun filterRowLabelTopPadding(tabText: String, buttonText: String): Dp {
    val caption = SwissType.caption()
    val label = SwissType.label()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, caption, label, tabText, buttonText, density) {
        with(density) {
            val captionLine = measurer.measure(
                text = AnnotatedString(tabText),
                style = caption,
                maxLines = 1,
            )
            val labelLine = measurer.measure(
                text = AnnotatedString(buttonText),
                style = label,
                maxLines = 1,
            )
            // caption 首行基线到触控区底边的距离：底部留白 + 基线到文字下沿的距离
            val captionBaselineFromBottom =
                FilterOptionTextBottomInset.toPx() + (captionLine.size.height - captionLine.firstBaseline)
            val targetFromTop = SwissSpace.Xl.toPx() - captionBaselineFromBottom
            (targetFromTop - labelLine.firstBaseline).coerceAtLeast(0f).toDp()
        }
    }
}

/** 一组筛选项上方的 label。 */
@Composable
private fun FilterGroupLabel(text: String) {
    GridRow {
        SText(
            text = text,
            style = SwissType.caption(),
            modifier = Modifier.cols(1, 12),
            color = LocalSwissColors.current.foreground,
            maxLines = 1,
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

/**
 * 收件箱的全部滚动内容：页头大标题、搜索框、筛选行、摘要、展开后的筛选项、「刷新 →」
 * 和邮件行，放在同一个 [LazyColumn] 里，前面几项作为 header item。
 * 滚到底自动翻页的逻辑不变：离列表底部还剩 [LoadMoreAhead] 条时加载下一页。
 */
@Composable
private fun InboxList(
    state: InboxUiState,
    meta: PageMeta,
    onEvent: (InboxEvent) -> Unit,
) {
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
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item(key = TitleKey) {
            PageTitle(text = InboxTitle)
        }
        item(key = FiltersKey) {
            FilterSection(state = state, onEvent = onEvent)
        }
        item(key = DividerKey) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(SwissSpace.S))
                HairlineRule()
                Spacer(modifier = Modifier.height(SwissSpace.Xs))
            }
        }
        item(key = RefreshKey) {
            GridRow {
                RowTextButton(
                    text = RefreshText,
                    onClick = { onEvent(InboxEvent.Refresh) },
                    modifier = Modifier.cols(1, 3),
                )
            }
        }
        when {
            state.error != null && state.items.isEmpty() -> item(key = StateKey) {
                Spacer(modifier = Modifier.height(SwissSpace.S))
                ErrorState(
                    message = state.error,
                    onRetry = { onEvent(InboxEvent.Refresh) },
                )
            }

            state.loading && state.items.isEmpty() -> item(key = StateKey) {
                Spacer(modifier = Modifier.height(SwissSpace.M))
                LoadingRule()
            }

            state.isEmpty -> item(key = StateKey) {
                Spacer(modifier = Modifier.height(SwissSpace.M))
                EmptyState(
                    index = "02",
                    title = "没有邮件",
                    body = "换个筛选条件，或点上面的刷新再试一次。",
                )
            }

            else -> items(items = state.items, key = { it.id }) { item ->
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
        }
        if (state.loadingMore) {
            item(key = LoadingMoreKey) {
                Spacer(modifier = Modifier.height(SwissSpace.S))
                LoadingRule()
            }
        }
    }
}

/** 列表里各分区项的 key，翻页合并时不会重建页头和筛选区。 */
private const val TitleKey = "page-title"
private const val FiltersKey = "filters"
private const val DividerKey = "divider"
private const val RefreshKey = "refresh"
private const val StateKey = "state"
private const val LoadingMoreKey = "loading-more"

/** 页面大标题的文字。 */
private const val InboxTitle = "收件"

/** 刷新入口的文字。 */
private const val RefreshText = "刷新 →"

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

/**
 * 文字按钮：只有一行 label 文字和最小触控高度，不做底色、不做胶囊。
 * [labelModifier] 加在文字上，用来和同一行的其它文字对齐首行基线（默认不缩进）。
 */
@Composable
private fun RowTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelModifier: Modifier = Modifier,
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
            modifier = labelModifier,
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
    onOpenIndex: () -> Unit = {},
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
        onOpenIndex = onOpenIndex,
    )
}
