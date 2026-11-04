package com.mailhub.client.ui.screens.inbox

import com.mailhub.client.data.model.MessageSummary
import com.mailhub.client.data.net.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收件箱的纯映射函数：列表行数据、账单分类拆参数、去重合并。 */
class InboxStateTest {

    @Test
    fun mailRowPrefersDisplayCategoryAndFallsBackToCategory() {
        val displayed = mailRowOf(
            inboxSummary(id = 1, category = "重要账单", unread = true),
            InboxFixtureCategories,
        )
        // 分类编号取 /api/meta 的顺序，账单展示分类排第 1
        assertEquals("重要账单", displayed.categoryName)
        assertEquals(1, displayed.categoryIndex)
        assertTrue(displayed.isUnread)

        val plain = mailRowOf(
            inboxSummary(id = 2, category = "通知").copy(displayCategory = ""),
            InboxFixtureCategories,
        )
        assertEquals("通知", plain.categoryName)
        assertEquals(8, plain.categoryIndex)
    }

    @Test
    fun mailRowFillsPlaceholdersForMissingSubjectAndSender() {
        val row = mailRowOf(
            inboxSummary(id = 3).copy(subject = "", senderName = "", senderAddr = "a@example.com"),
            InboxFixtureCategories,
        )

        assertEquals("(无主题)", row.subject)
        assertEquals("a@example.com", row.sender)
    }

    @Test
    fun billDisplayCategoriesBecomeCategoryPlusTier() {
        assertEquals("账单" to "重要", categoryParams("重要账单"))
        assertEquals("账单" to "一般", categoryParams("一般账单"))
        assertEquals("可疑" to "", categoryParams("可疑"))
        assertEquals("" to "", categoryParams(""))
    }

    @Test
    fun appendDistinctKeepsOrderAndDropsDuplicates() {
        val current = listOf(inboxSummary(1), inboxSummary(2))
        val merged = appendDistinct(current, listOf(inboxSummary(2), inboxSummary(3)))

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
    }

    @Test
    fun errorTextStaysPlainForEachApiFailure() {
        assertEquals("无法连接服务器，请检查网络后重试", inboxErrorText(ApiError.Network("timeout")))
        assertEquals("登录已过期，请重新登录", inboxErrorText(ApiError.Unauthorized()))
        assertEquals("受保护邮件", inboxErrorText(ApiError.Forbidden("受保护邮件", protected = true)))
    }

    /** 发件人防伪造：列表行必须走共享的显示规则，不能直接用显示名。 */
    @Test
    fun inboxRowUsesTheLabelNotTheRawDisplayName() {
        val riskyMail = MessageSummary(
            id = 1,
            subject = "账户存在异常登录，请立即验证你的身份",
            senderName = "service@paypal.com",
            senderAddr = "notice@paypa1-secure.example",
            riskLevel = "high",
        )
        val normalMail = MessageSummary(
            id = 2,
            subject = "本月账单已生成",
            senderName = "云服务账单",
            senderAddr = "billing@cloud.example",
            riskLevel = "none",
        )

        assertEquals("notice@paypa1-secure.example", mailRowOf(riskyMail, emptyList()).sender)
        assertEquals("云服务账单", mailRowOf(normalMail, emptyList()).sender)
    }
}
