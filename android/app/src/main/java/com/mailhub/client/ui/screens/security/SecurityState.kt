package com.mailhub.client.ui.screens.security

import com.mailhub.client.data.model.senderLabel
import com.mailhub.client.data.security.AuditEntry
import com.mailhub.client.data.security.DeviceSession
import com.mailhub.client.data.security.ReviewItem
import com.mailhub.client.data.security.TrashItem
import com.mailhub.client.ui.screens.common.MailRowData
import com.mailhub.client.ui.screens.common.displayCategoryNumber
import com.mailhub.client.ui.screens.inbox.EmptySubjectText

/** 安全中心的四个编号区块，按页面顺序排列。 */
enum class SecuritySection(val index: String, val title: String) {
    Review("01", "风险观察"),
    Trash("02", "回收站"),
    Audit("03", "审计"),
    Devices("04", "已登录设备"),
}

/** 一个区块的加载状态：四块各自加载，一块失败不影响其他块。 */
sealed interface Loadable<out T> {

    data object Loading : Loadable<Nothing>

    data class Failed(val message: String) : Loadable<Nothing>

    data class Ready<T>(val value: T) : Loadable<T>
}

/** 已加载的值；还在加载或失败时为 null。 */
fun <T> Loadable<T>.valueOrNull(): T? = (this as? Loadable.Ready<T>)?.value

/** 审计列表：按 offset 分页，[total] 是后端的总条数。 */
data class AuditList(
    val items: List<AuditEntry> = emptyList(),
    val total: Int = 0,
    val loadingMore: Boolean = false,
) {
    val hasMore: Boolean get() = items.size < total
}

/** 需要先确认才执行的动作。 */
sealed interface SecurityConfirm {

    data class RevokeDevice(val session: DeviceSession) : SecurityConfirm

    data object RevokeAll : SecurityConfirm
}

/** 安全中心的界面状态。 */
data class SecurityUiState(
    val review: Loadable<List<ReviewItem>> = Loadable.Loading,
    val trash: Loadable<List<TrashItem>> = Loadable.Loading,
    val audit: Loadable<AuditList> = Loadable.Loading,
    val devices: Loadable<List<DeviceSession>> = Loadable.Loading,
    /** 回收站里选中的邮件 id。 */
    val selectedTrash: Set<Long> = emptySet(),
    val confirming: SecurityConfirm? = null,
    /** 恢复、撤销、吊销进行中：同一时间只允许一个。 */
    val busy: Boolean = false,
    /** 各区块最近一次操作的结果，显示在该区块末尾。 */
    val notices: Map<SecuritySection, String> = emptyMap(),
    /**
     * 本机模式（`LOCAL_MODE.md` 附录 A）：没有其他设备，也没有可吊销的会话，
     * 所以「已登录设备」整块不显示，也不发 `/api/sessions`。
     */
    val localMode: Boolean = false,
) {
    /** 实际显示的区块：本机模式没有「已登录设备」，其余三块不变。 */
    val sections: List<SecuritySection>
        get() = SecuritySection.entries.filter { !localMode || it != SecuritySection.Devices }
}

sealed interface SecurityEvent {

    data class Retry(val section: SecuritySection) : SecurityEvent

    /** 点风险观察的一行：由外壳打开详情，安全中心自身不处理。 */
    data class OpenMessage(val messageId: Long) : SecurityEvent

    data class ToggleTrash(val messageId: Long) : SecurityEvent

    data object ClearTrashSelection : SecurityEvent

    data object RestoreSelected : SecurityEvent

    data class Undo(val auditId: Long) : SecurityEvent

    data object LoadMoreAudit : SecurityEvent

    /** 吊销某台设备：先进确认面板。本机不提供吊销。 */
    data class RequestRevoke(val sessionId: String) : SecurityEvent

    /** 退出其他所有设备：先进确认面板。 */
    data object RequestRevokeAll : SecurityEvent

    data object Confirm : SecurityEvent

    data object DismissConfirm : SecurityEvent
}

/**
 * 风险观察的一行：发件人一律显示真实地址（这里的邮件都是被标为可疑或有风险的，
 * 显示名不可信），摘要位置放风险原因。
 *
 * 不显示分类：这一块本来就全是可疑邮件，而且分类会占掉第 9–12 栏，
 * 长地址会被截成「notice@paypa1」，恰好把最要紧的域名藏起来。
 */
fun reviewRowOf(item: ReviewItem): MailRowData = MailRowData(
    id = item.id,
    subject = item.subject.ifBlank { EmptySubjectText },
    sender = senderLabel(item.senderName, item.senderAddr, isRisky = true),
    dateTs = item.dateTs,
    isUnread = false,
    snippet = item.riskReasons.ifBlank { item.aiReason },
)

/** 回收站的一行：时间是移入回收站的时间，发件人显示地址。 */
fun trashRowOf(item: TrashItem, categories: List<String>): MailRowData = MailRowData(
    id = item.id,
    subject = item.subject.ifBlank { EmptySubjectText },
    sender = item.senderAddr,
    dateTs = item.deletedTs,
    isUnread = false,
    categoryIndex = displayCategoryNumber(categories, item.category),
    categoryName = item.category,
)

/** 审计动作的中文名；没登记的动作照原样显示，审计记录不能把信息藏起来。 */
private val AuditActionNames: Map<String, String> = mapOf(
    "label" to "加标签",
    "classify" to "分类",
    "read" to "标为已读",
    "unread" to "标为未读",
    "star" to "加星标",
    "digest" to "生成摘要",
    "remind" to "建提醒",
    "archive" to "归档",
    "move" to "移动",
    "soft_delete" to "移入回收站",
    "quarantine" to "隔离",
    "purge" to "从服务器永久删除",
    "empty_folder" to "清空文件夹",
    "restore" to "从回收站恢复",
    "undo" to "撤销",
    "clean" to "自动清理",
    "bill_tier" to "账单定级",
    "delete_account" to "删除账户",
    "change_password" to "修改管理密码",
    "revoke_session" to "吊销设备",
    "revoke_sessions" to "退出所有设备",
    "governance_run" to "三省六部分拣",
)

fun auditActionName(action: String): String = AuditActionNames[action] ?: action

/** 第 2–9 栏首行：动作名，涉及邮件时接上封数。 */
fun auditActionText(entry: AuditEntry): String {
    val name = auditActionName(entry.action)
    return if (entry.targetCount > 0) "$name · ${entry.targetCount} 封" else name
}

/** 第 2–9 栏第二行：原因；被策略引擎拦截的在前面加「已拦截 · 」。 */
fun auditReasonText(entry: AuditEntry): String = when {
    entry.allowed -> entry.reason
    entry.reason.isBlank() -> "已拦截"
    else -> "已拦截 · ${entry.reason}"
}

/** 动作等级字母；旧记录没有等级时用占位符。 */
fun auditTierText(entry: AuditEntry): String = entry.tier.ifBlank { "—" }

/** 一条审计记录在页面上的状态：决定显示「撤销 →」「已撤销」还是什么都不显示。 */
enum class AuditStatus { Undoable, Undone, Blocked, Final }

fun auditStatusOf(entry: AuditEntry): AuditStatus = when {
    !entry.allowed -> AuditStatus.Blocked
    entry.undone -> AuditStatus.Undone
    entry.reversible -> AuditStatus.Undoable
    else -> AuditStatus.Final
}

/** 客户端类型：android → Android，web → 网页。 */
fun clientName(client: String): String = when (client) {
    "android" -> "Android"
    "web" -> "网页"
    else -> client.ifBlank { "未知" }
}

/** 设备名：登录时报的名字；网页会话通常没有名字，就用客户端类型。 */
fun deviceName(session: DeviceSession): String = session.label.ifBlank { clientName(session.client) }

/**
 * 设备名下面的一行：客户端类型，本机再接上「本机」。
 * 没有设备名时设备名已经是客户端类型，这一行不再重复它。
 */
fun deviceMetaText(session: DeviceSession): String {
    val parts = listOfNotNull(
        clientName(session.client).takeIf { session.label.isNotBlank() },
        "本机".takeIf { session.current },
    )
    return parts.joinToString(" · ").ifEmpty { "未报告设备名" }
}

/** 最近活动时间；从没活动过就用登录时间。 */
fun deviceSeenTs(session: DeviceSession): Double =
    session.lastSeenTs.takeIf { it > 0.0 } ?: session.createdTs

/** 退出所有设备的结果。revoked 里含本机原来的会话，所以其他设备是 revoked - 1。 */
fun revokeAllText(revoked: Int, replaced: Boolean): String {
    if (!replaced) return "已退出所有设备，请重新登录"
    val others = (revoked - 1).coerceAtLeast(0)
    return if (others == 0) "没有其他已登录的设备" else "已退出 $others 台设备"
}

const val RevokeAllTitle: String = "退出其他所有设备？"
const val RevokeAllMessage: String = "除本机外，所有设备会立即退出登录；本机自动换用新的登录凭据。"

fun revokeDeviceTitle(session: DeviceSession): String = "吊销「${deviceName(session)}」？"

const val RevokeDeviceMessage: String = "这台设备会立即退出登录，需要重新输入管理密码才能再用。"
