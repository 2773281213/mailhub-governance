package com.mailhub.client.ui.screens.security

import com.mailhub.client.data.security.AuditEntry
import com.mailhub.client.data.security.DeviceSession
import com.mailhub.client.data.security.ReviewItem
import com.mailhub.client.data.security.TrashItem

/** 截图里的「现在」：2026-11-04 16:54（上海）。 */
const val SecurityFixtureNowTs: Double = 1793782440.0

const val SecurityFixtureZone: String = "Asia/Shanghai"

/** 与收件箱相同的分类顺序，分类编号才对得上。 */
val SecurityFixtureCategories: List<String> =
    listOf("重要账单", "一般账单", "验证码", "重要", "安全", "可疑", "订阅", "通知")

val ReviewFixture: List<ReviewItem> = listOf(
    ReviewItem(
        id = 11,
        subject = "账户存在异常登录，请立即验证你的身份",
        senderName = "service@paypal.com",
        senderAddr = "notice@paypa1-secure.example",
        dateTs = SecurityFixtureNowTs - 1800,
        category = "可疑",
        riskLevel = "high",
        riskReasons = "显示名伪装成 service@paypal.com，发件域与官方域名仅差一个字符",
    ),
    ReviewItem(
        id = 12,
        subject = "您的快递因地址不详被退回",
        senderName = "顺丰速运",
        senderAddr = "notify@sf-express-cn.example",
        dateTs = SecurityFixtureNowTs - 86400,
        category = "可疑",
        riskLevel = "medium",
        riskReasons = "正文链接指向与发件人无关的域名",
    ),
)

val TrashFixture: List<TrashItem> = listOf(
    TrashItem(
        id = 21,
        subject = "本周产品更新",
        senderAddr = "news@product.example",
        category = "通知",
        deletedTs = SecurityFixtureNowTs - 600,
    ),
    TrashItem(
        id = 22,
        subject = "订阅即将续费",
        senderAddr = "no-reply@music.example",
        category = "订阅",
        deletedTs = SecurityFixtureNowTs - 7200,
    ),
    TrashItem(
        id = 23,
        subject = "季度对账提醒",
        senderAddr = "fin@studio.example",
        category = "重要账单",
        deletedTs = SecurityFixtureNowTs - 2 * 86400,
    ),
)

val AuditFixture: List<AuditEntry> = listOf(
    AuditEntry(
        id = 105, ts = SecurityFixtureNowTs - 300, actor = "user", action = "soft_delete", tier = "B",
        targetCount = 3, reason = "批量删除", allowed = true, reversible = true, undone = false,
    ),
    AuditEntry(
        id = 104, ts = SecurityFixtureNowTs - 900, actor = "user", action = "read", tier = "A",
        targetCount = 12, reason = "全部标为已读", allowed = true, reversible = true, undone = true,
    ),
    AuditEntry(
        id = 103, ts = SecurityFixtureNowTs - 3600, actor = "system", action = "purge", tier = "C",
        targetCount = 4, reason = "受保护邮件不能自动删除", allowed = false, reversible = false, undone = false,
    ),
    AuditEntry(
        id = 102, ts = SecurityFixtureNowTs - 5400, actor = "system", action = "governance_run", tier = "A",
        targetCount = 128, reason = "三省六部自动分拣完成：128 封，可疑 2 封", allowed = true,
    ),
)

val DevicesFixture: List<DeviceSession> = listOf(
    DeviceSession(
        id = "a1b2c3d4e5f60718", label = "Pixel 8", client = "android",
        createdTs = SecurityFixtureNowTs - 5 * 86400, lastSeenTs = SecurityFixtureNowTs - 60, current = true,
    ),
    DeviceSession(
        id = "0f1e2d3c4b5a6978", label = "", client = "web",
        createdTs = SecurityFixtureNowTs - 3 * 86400, lastSeenTs = SecurityFixtureNowTs - 3600,
    ),
    DeviceSession(
        id = "1234abcd5678ef90", label = "Xiaomi 14", client = "android",
        createdTs = SecurityFixtureNowTs - 20 * 86400, lastSeenTs = SecurityFixtureNowTs - 4 * 86400,
    ),
)

/** 四块都加载完成的状态。 */
fun securityFixtureState(): SecurityUiState = SecurityUiState(
    review = Loadable.Ready(ReviewFixture),
    trash = Loadable.Ready(TrashFixture),
    audit = Loadable.Ready(AuditList(items = AuditFixture, total = 9)),
    devices = Loadable.Ready(DevicesFixture),
)
