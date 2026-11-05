package com.mailhub.client.ui.screens.accounts

import com.mailhub.client.data.accounts.ConnectionTest
import com.mailhub.client.data.model.Account
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 后端 sync.py 里的三种同步状态：syncing / idle / error。 */
const val SyncStateSyncing: String = "syncing"
const val SyncStateIdle: String = "idle"
const val SyncStateError: String = "error"

/** 账户列表的界面状态。 */
data class AccountsUiState(
    val accounts: List<Account> = emptyList(),
    val loading: Boolean = true,
    /** 整页还没有内容时的失败说明；局部动作失败走 [notice]。 */
    val error: String? = null,
    /** 展开了操作行的账户 id；0 表示都收起。 */
    val expandedId: Int = 0,
    /** 正在等确认删除的账户 id；0 表示没有确认面板。 */
    val confirmingDeleteId: Int = 0,
    /** 同步 / 测试 / 删除进行中，操作行里的按钮置为不可用。 */
    val busy: Boolean = false,
    /** 一次性提示，显示成 Snackbar。 */
    val notice: String? = null,
)

sealed interface AccountsEvent {

    data object Refresh : AccountsEvent

    /** 点一行：展开或收起这一行的操作。 */
    data class Toggle(val accountId: Int) : AccountsEvent

    data object SyncAll : AccountsEvent

    data class SyncNow(val accountId: Int) : AccountsEvent

    data class TestConnection(val accountId: Int) : AccountsEvent

    /** 删除要先进确认面板，不直接发请求。 */
    data class RequestDelete(val accountId: Int) : AccountsEvent

    data object ConfirmDelete : AccountsEvent

    data object DismissDelete : AccountsEvent

    data object DismissNotice : AccountsEvent

    /** 「添加账户 →」：由外壳压栈打开添加流程，账户列表自身不处理。 */
    data object OpenAdd : AccountsEvent
}

/** 后端没给状态时按空闲处理。 */
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** 时区名不可用时退回设备时区，不让整行失败。 */
private fun zoneOrSystem(zoneId: String): ZoneId =
    runCatching { ZoneId.of(zoneId) }.getOrElse { ZoneId.systemDefault() }

/** 上次同步时间：HH:mm，按服务器时区；时间戳无效时返回空串。 */
fun syncClockText(ts: Double, zoneId: String): String {
    if (ts <= 0.0) return ""
    val at = Instant.ofEpochSecond(ts.toLong()).atZone(zoneOrSystem(zoneId))
    return at.format(clockFormat)
}

/**
 * 第 10–12 栏的同步状态：「同步中」「空闲 · HH:mm」「出错」。
 * 空闲但没有上次同步时间时只写「空闲」。
 */
fun syncStatusText(account: Account, zoneId: String): String = when (account.syncState.state) {
    SyncStateSyncing -> "同步中"
    SyncStateError -> "出错"
    else -> {
        val clock = syncClockText(account.syncState.ts, zoneId)
        if (clock.isEmpty()) "空闲" else "空闲 · $clock"
    }
}

/** 出错说明：优先后端这次同步失败的原因，其次账户上留存的 last_error。 */
fun syncErrorText(account: Account): String {
    if (account.syncState.state != SyncStateError) return ""
    return account.syncState.msg.ifBlank { account.lastError }
}

/** 测试连接的结论：ok 时用后端给的「连接正常」，否则原样显示 msg。 */
fun testResultText(result: ConnectionTest): String =
    if (result.ok) result.msg.ifBlank { "连接正常" } else result.msg.ifBlank { "连接失败" }

/** 删除确认面板的标题：删除账户 A1？ */
fun deleteTitle(label: String): String = if (label.isEmpty()) "删除这个账户？" else "删除账户 $label？"

/** 删除确认面板的正文，和任务要求的措辞一致。 */
const val DeleteMessage: String = "该账户的本地邮件会一并删除，邮箱服务器上的邮件不受影响。"
