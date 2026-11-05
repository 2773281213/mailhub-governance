package com.mailhub.client.ui.screens.inbox

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.MessageSummary
import com.mailhub.client.data.model.senderDisplay
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.ui.screens.common.MailRowData
import com.mailhub.client.ui.screens.common.accountLabel
import com.mailhub.client.ui.screens.common.apiErrorText
import com.mailhub.client.ui.screens.common.categoryLabel
import com.mailhub.client.ui.screens.common.displayCategoryNumber

/** 每页 30 条，游标分页。 */
const val InboxPageSize: Int = 30

/** 搜索输入防抖时间（毫秒）。 */
const val InboxSearchDebounceMillis: Long = 300

/** 「重要账单」「一般账单」这类展示分类对应的账单等级，供筛选参数使用。 */
private val BillDisplayCategories: List<String> = listOf("重要账单", "一般账单")

/**
 * 收件箱的筛选条件。分类用的是 /api/meta 的 display_categories 里的展示名，
 * 空串表示不限。
 */
data class InboxFilters(
    val query: String = "",
    val unreadOnly: Boolean = false,
    val category: String = "",
    val accountId: Int = 0,
)

/**
 * 收件箱界面状态。[error] 只描述「整页还没有内容」的失败；
 * 局部动作失败和受保护邮件提示都走 [notice]。
 */
data class InboxUiState(
    val filters: InboxFilters = InboxFilters(),
    val items: List<MessageSummary> = emptyList(),
    val categories: List<String> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
    /** 多选模式：长按进入，取消或操作结束后退出。 */
    val selectMode: Boolean = false,
    val selected: Set<Long> = emptySet(),
    /** 删除二次确认面板是否打开。 */
    val confirmingDelete: Boolean = false,
    /**
     * 分类 / 账户两组筛选项是否展开。默认收起，只留搜索框、状态行和一行摘要，
     * 让 360×800 的第一屏能看到邮件；展开与收起只是界面状态，不触发重新加载。
     */
    val filtersExpanded: Boolean = false,
    /** 批量操作进行中，按钮置为不可用。 */
    val busy: Boolean = false,
    /** 一次性提示，显示成 Snackbar。 */
    val notice: String? = null,
) {
    /** 整页加载成功但没有邮件。 */
    val isEmpty: Boolean get() = !loading && error == null && items.isEmpty()

    val selectedCount: Int get() = selected.size
}

sealed interface InboxEvent {

    /** 搜索框输入；界面立即回显，300ms 后才真正查询。 */
    data class QueryChanged(val text: String) : InboxEvent

    /** 只看未读（[unreadOnly] 为 false 表示全部）。 */
    data class SetUnreadOnly(val unreadOnly: Boolean) : InboxEvent

    /** 选分类；空串表示全部。 */
    data class SelectCategory(val category: String) : InboxEvent

    /** 选账户；0 表示全部账户。 */
    data class SelectAccount(val accountId: Int) : InboxEvent

    /** 展开 / 收起分类与账户两组筛选项；纯界面状态，不发请求。 */
    data object ToggleFilters : InboxEvent

    data object Refresh : InboxEvent

    data object LoadMore : InboxEvent

    /** 打开一封邮件：由外壳压栈，收件箱自身不处理。 */
    data class Open(val id: Long) : InboxEvent

    data class LongPress(val id: Long) : InboxEvent

    data class ToggleSelected(val id: Long) : InboxEvent

    data object CancelSelect : InboxEvent

    /** 批量标为已读（[read] 为 false 时标为未读）。 */
    data class SetRead(val read: Boolean) : InboxEvent

    data object RequestDelete : InboxEvent

    data object ConfirmDelete : InboxEvent

    data object DismissDelete : InboxEvent

    data object DismissNotice : InboxEvent
}

/**
 * 展示分类换成 /api/messages 的查询参数：账单的两个展示分类要拆成
 * category=账单 + bill_tier，其余分类原样传。
 */
fun categoryParams(displayCategory: String): Pair<String, String> {
    if (displayCategory in BillDisplayCategories) {
        return "账单" to displayCategory.removeSuffix("账单")
    }
    return displayCategory to ""
}

/**
 * 收起状态下的一行筛选摘要：「分类：全部 · 账户：全部账户」；
 * 有生效的筛选时写具体值，例如「分类：05 安全 · 账户：A1 工作邮箱」。
 */
fun filtersSummary(
    filters: InboxFilters,
    categories: List<String>,
    accounts: List<Account>,
): String {
    val category = if (filters.category.isBlank()) {
        AllCategoriesSummary
    } else {
        categoryLabel(
            displayCategoryNumber(categories, filters.category),
            filters.category,
        )
    }
    val account = accountSummary(filters.accountId, accounts)
    return "分类：$category · 账户：$account"
}

/** 摘要里分类「不限」的写法。 */
private const val AllCategoriesSummary = "全部"

/** 摘要里账户「不限」的写法，和账户筛选项的第一项同名。 */
private const val AllAccountsSummary = "全部账户"

/** 账户摘要：「A1 工作邮箱」；对不上账户列表时退回「全部账户」。 */
private fun accountSummary(accountId: Int, accounts: List<Account>): String {
    if (accountId <= 0) return AllAccountsSummary
    val account = accounts.firstOrNull { it.id == accountId } ?: return AllAccountsSummary
    val label = accountLabel(accounts, account.id)
    val name = account.name
    return when {
        name.isBlank() -> label.ifBlank { AllAccountsSummary }
        label.isBlank() -> name
        else -> "$label $name"
    }
}

/** 追加一页并按下发顺序去重（服务端翻页边界可能重复一条）。 */
fun appendDistinct(
    current: List<MessageSummary>,
    incoming: List<MessageSummary>,
): List<MessageSummary> {
    val seen = current.mapTo(HashSet(current.size)) { it.id }
    val merged = ArrayList<MessageSummary>(current.size + incoming.size)
    merged.addAll(current)
    incoming.forEach { item ->
        if (seen.add(item.id)) merged += item
    }
    return merged
}

/** 没有主题时的占位文字。 */
const val EmptySubjectText: String = "(无主题)"

/**
 * 列表里的一封邮件换成共享行的数据：分类用 display_category（账单展开成
 * 「重要账单」「一般账单」），编号取 /api/meta 的下发顺序。
 *
 * 发件人一律走 [senderDisplay]：风险邮件、显示名里嵌着别的地址的邮件都显示真实地址，
 * 不替攻击者展示伪造成官方样子的显示名。
 */
fun mailRowOf(item: MessageSummary, categories: List<String>): MailRowData {
    val categoryName = item.displayCategory.ifBlank { item.category }
    return MailRowData(
        id = item.id,
        subject = item.subject.ifBlank { EmptySubjectText },
        sender = item.senderDisplay,
        dateTs = item.dateTs,
        isUnread = item.isUnread,
        snippet = item.summary.ifBlank { item.snippet },
        categoryIndex = displayCategoryNumber(categories, categoryName),
        categoryName = categoryName,
    )
}

/**
 * 取数据和批量操作的失败文案，一律用黑色正文说明，不用红色小字。
 * 除兜底一句外都与 [apiErrorText] 相同，这里只保留不同的那一支。
 */
fun inboxErrorText(error: Throwable): String = when (error) {
    is ApiError.Network,
    is ApiError.Unauthorized,
    is ApiError.Decode,
    is ApiError.TooManyRequests,
    is ApiError.Forbidden,
    is ApiError.Conflict,
    is ApiError.Server -> apiErrorText(error)

    else -> "加载失败，请重试"
}
