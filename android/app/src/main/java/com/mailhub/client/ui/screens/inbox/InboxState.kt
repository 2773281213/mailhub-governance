package com.mailhub.client.ui.screens.inbox

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.MessageSummary
import com.mailhub.client.data.net.ApiError
import com.mailhub.client.ui.screens.common.MailRowData
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
 */
fun mailRowOf(item: MessageSummary, categories: List<String>): MailRowData {
    val categoryName = item.displayCategory.ifBlank { item.category }
    return MailRowData(
        id = item.id,
        subject = item.subject.ifBlank { EmptySubjectText },
        sender = item.senderName.ifBlank { item.senderAddr },
        dateTs = item.dateTs,
        isUnread = item.isUnread,
        snippet = item.summary.ifBlank { item.snippet },
        categoryIndex = displayCategoryNumber(categories, categoryName),
        categoryName = categoryName,
    )
}

/** 取数据和批量操作的失败文案，一律用黑色正文说明，不用红色小字。 */
fun inboxErrorText(error: Throwable): String = when (error) {
    is ApiError.Network -> "无法连接服务器，请检查网络后重试"
    is ApiError.Unauthorized -> "登录已过期，请重新登录"
    is ApiError.Decode -> "服务器返回的数据格式不正确"
    is ApiError.TooManyRequests -> "请求过于频繁，请稍后再试"
    is ApiError.Forbidden -> error.detail ?: "操作被拒绝"
    is ApiError.Conflict -> error.detail ?: "服务器状态冲突，请稍后再试"
    is ApiError.Server -> error.detail ?: "服务器错误 ${error.code}"
    else -> "加载失败，请重试"
}
