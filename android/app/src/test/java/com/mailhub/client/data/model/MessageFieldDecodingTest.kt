package com.mailhub.client.data.model

import com.mailhub.client.data.net.ApiJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4 新增字段的解码：列表的 risk_level / display_category，
 * 详情的 display_category / body_truncated。字段缺失时一律退回默认值。
 */
class MessageFieldDecodingTest {

    @Test
    fun summaryDecodesRiskLevelAndDisplayCategory() {
        val page = ApiJson.decodeFromString<MessagePage>(
            """
            {"total":1,"next_cursor":null,"items":[
              {"id":3,"subject":"本月账单","risk_level":"medium",
               "display_category":"重要账单","unread":1}
            ]}
            """.trimIndent(),
        )

        val item = page.items.single()
        assertEquals("medium", item.riskLevel)
        assertEquals("重要账单", item.displayCategory)
        assertTrue(item.isUnread)
    }

    @Test
    fun summaryFallsBackWhenNewFieldsAreMissing() {
        val item = ApiJson.decodeFromString<MessagePage>("""{"items":[{"id":1}]}""").items.single()

        // 后端没给字段时按 none / 空串处理，界面自己回退到 category
        assertEquals("none", item.riskLevel)
        assertEquals("", item.displayCategory)
    }

    @Test
    fun detailDecodesDisplayCategoryAndBodyTruncated() {
        val detail = ApiJson.decodeFromString<MessageDetail>(
            """
            {"id":9,"display_category":"可疑","body_truncated":1,
             "body_text":"开场白","risk_level":"none"}
            """.trimIndent(),
        )

        assertEquals("可疑", detail.displayCategory)
        assertEquals(1, detail.bodyTruncated)
        assertTrue(detail.isBodyTruncated)
        assertEquals("开场白", detail.bodyText)
    }

    @Test
    fun detailWithoutTruncationIsNotTruncated() {
        val detail = ApiJson.decodeFromString<MessageDetail>("""{"id":9}""")

        assertEquals(0, detail.bodyTruncated)
        assertEquals("", detail.displayCategory)
        assertFalse(detail.isBodyTruncated)
    }
}
