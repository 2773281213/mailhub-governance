package com.mailhub.client.ui.screens

import com.mailhub.client.data.model.Digest
import com.mailhub.client.data.model.GovernanceRun
import com.mailhub.client.data.model.GovernanceStatus
import com.mailhub.client.data.model.ImportantItem
import com.mailhub.client.data.model.Overview
import com.mailhub.client.data.model.OverviewStats
import com.mailhub.client.ui.screens.common.PageMeta
import com.mailhub.client.ui.screens.overview.OverviewUiState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 截图与测试共用的示例数据：不访问网络，全部写死。
 * 日期与分类计数按后端 /api/overview 的实际结构造：days 是 MM-dd，daily 每行带 day 字段。
 */
object SampleData {

    /** 页头元信息：2026 — 上海，服务器时区 Asia/Shanghai。 */
    val pageMeta: PageMeta = PageMeta(year = 2026, place = "上海", zoneId = "Asia/Shanghai")


    /** 服务器时间 2026-11-04 08:54:00 UTC，换算到上海是 16:54。 */
    const val FinishedTs: Double = 1793782440.0

    private val days: List<String> = listOf(
        "10-22", "10-23", "10-24", "10-25", "10-26", "10-27", "10-28",
        "10-29", "10-30", "10-31", "11-01", "11-02", "11-03", "11-04",
    )

    private val totals: List<Int> = listOf(18, 24, 12, 30, 42, 26, 20, 35, 28, 16, 44, 31, 25, 38)

    private val risks: List<Int> = listOf(0, 2, 0, 4, 6, 0, 1, 5, 0, 0, 8, 3, 2, 6)

    private val daily: List<Map<String, JsonElement>> = days.indices.map { index ->
        val total = totals[index]
        val risk = risks[index]
        buildMap {
            put("day", JsonPrimitive(days[index]))
            if (risk > 0) put("可疑", JsonPrimitive(risk))
            val rest = total - risk
            put("通知", JsonPrimitive(rest / 2))
            put("订阅", JsonPrimitive(rest - rest / 2))
        }
    }

    private val important: List<ImportantItem> = listOf(
        ImportantItem(
            id = 1,
            subject = "账户存在异常登录，请立即验证",
            senderAddr = "notice@paypa1-secure.example",
            dateTs = FinishedTs,
            category = "可疑",
            importance = 5,
            unread = 1,
            accountName = "工作邮箱",
        ),
        ImportantItem(
            id = 2,
            subject = "本月账单已生成",
            senderAddr = "billing@cloud.example",
            dateTs = FinishedTs - 5400,
            category = "重要账单",
            importance = 4,
            unread = 1,
            accountName = "工作邮箱",
        ),
        ImportantItem(
            id = 3,
            subject = "安全中心：新的登录设备",
            senderAddr = "security@mailhub.dev",
            dateTs = FinishedTs - 9000,
            category = "安全",
            importance = 4,
            unread = 0,
            accountName = "私有邮箱",
        ),
        ImportantItem(
            id = 4,
            subject = "季度对账提醒",
            senderAddr = "fin@studio.example",
            dateTs = FinishedTs - 20000,
            category = "重要",
            importance = 4,
            unread = 0,
            accountName = "私有邮箱",
        ),
        ImportantItem(
            id = 5,
            subject = "订阅即将续费",
            senderAddr = "no-reply@music.example",
            dateTs = FinishedTs - 40000,
            category = "订阅",
            importance = 4,
            unread = 0,
            accountName = "私有邮箱",
        ),
    )

    /** 总览数据：未读 128、重要未读 6、今日 38、今日验证码 9。 */
    val overview: Overview = Overview(
        stats = OverviewStats(
            total = 12840,
            unread = 128,
            today = 38,
            todayOtp = 9,
            importantUnread = 6,
            byCategory = mapOf("可疑" to 31, "通知" to 420),
        ),
        days = days,
        daily = daily,
        categories = listOf("验证码", "重要", "可疑", "订阅", "通知"),
        important = important,
        digest = Digest(
            day = "2026-11-04",
            content = "昨夜共收到 38 封新邮件，其中 6 封需要处理：1 封疑似冒充支付平台的钓鱼邮件已自动判为可疑。" +
                "两张账单将在本周到期，另有 1 台新设备登录过邮箱。",
            createdTs = FinishedTs - 3000,
        ),
        aiEnabled = true,
    )

    /** 治理状态：待治理 12 封，上次运行 16:54 完成，处理了 128 封。 */
    val governance: GovernanceStatus = GovernanceStatus(
        version = "mailhub-three-six-v2",
        running = false,
        pending = 12,
        ministries = listOf("吏部", "户部", "礼部"),
        lastRun = GovernanceRun(
            status = "done",
            startedTs = FinishedTs - 120,
            finishedTs = FinishedTs,
            processedCount = 128,
            aiCount = 96,
            fallbackCount = 32,
            suspiciousCount = 31,
        ),
    )

    /** 总览页就绪态。 */
    fun overviewReady(): OverviewUiState = OverviewUiState(
        loading = false,
        error = null,
        overview = overview,
        governance = governance,
        governanceRunning = false,
        notice = null,
    )
}
