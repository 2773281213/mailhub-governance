package com.mailhub.client.data.security

import com.mailhub.client.data.net.ApiJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO 与 app.py / sessions.py 的真实返回逐字段核对：审计的 INTEGER 0/1 列、
 * 数据库里的 NULL、会话的布尔 current，以及新令牌不进 toString。
 */
class SecurityDecodeTest {

    @Test
    fun decodesAuditWithIntegerFlags() {
        val page = ApiJson.decodeFromString<AuditPage>(
            """
            {"total":2,"items":[
              {"id":5,"ts":1793782440.5,"actor":"user","action":"soft_delete","tier":"B",
               "target_count":3,"reason":"批量删除","allowed":1,"reversible":1,"undone":0},
              {"id":4,"ts":1793782000.0,"actor":"system","action":"purge","tier":"C",
               "target_count":0,"reason":null,"allowed":0,"reversible":0,"undone":0}
            ]}
            """.trimIndent(),
        )

        assertEquals(2, page.total)
        val first = page.items[0]
        assertTrue(first.allowed)
        assertTrue(first.reversible)
        assertFalse(first.undone)
        assertEquals(3, first.targetCount)
        assertFalse(page.items[1].allowed)
        // SQLite 的 NULL 落到默认值
        assertEquals("", page.items[1].reason)
    }

    @Test
    fun decodesReviewTrashAndSessions() {
        val review = ApiJson.decodeFromString<List<ReviewItem>>(
            """
            [{"id":11,"subject":"账户存在异常登录","sender_addr":"notice@paypa1-secure.example",
              "sender_name":"service@paypal.com","date_ts":1793780640.0,"category":"可疑",
              "confidence":0.93,"ai_reason":null,"risk_level":"high","risk_reasons":"显示名伪装",
              "needs_review":0,"governance_ministry":"刑部","governance_source":"rule",
              "governance_reason":"","account_name":"工作邮箱","account_color":"#38bdf8"}]
            """.trimIndent(),
        )
        assertEquals("high", review.single().riskLevel)
        assertEquals("", review.single().aiReason)

        val trash = ApiJson.decodeFromString<List<TrashItem>>(
            """
            [{"id":21,"subject":"本周产品更新","sender_addr":"news@product.example",
              "date_ts":1793700000.0,"category":"通知","deleted_ts":1793781840.0,
              "account_name":"工作邮箱","account_color":"#38bdf8"}]
            """.trimIndent(),
        )
        assertEquals(1793781840.0, trash.single().deletedTs, 0.0)

        val sessions = ApiJson.decodeFromString<SessionsPage>(
            """
            {"items":[{"id":"a1b2c3d4e5f60718","label":"Pixel 8","client":"android",
              "created_ts":1793300000.0,"last_seen_ts":1793782380.0,"expires_ts":1795000000.0,
              "current":true}]}
            """.trimIndent(),
        )
        assertTrue(sessions.items.single().current)
    }

    @Test
    fun restoreRequestCarriesOnlyIds() {
        assertEquals("""{"ids":[21,23]}""", ApiJson.encodeToString(RestoreRequest(listOf(21L, 23L))))
    }

    @Test
    fun revokeAllResultNeverPrintsTheToken() {
        val result = ApiJson.decodeFromString<RevokeAllResult>(
            """{"ok":true,"revoked":3,"token":"secret-token-value","session_id":"sid","expires_ts":200.0}""",
        )

        assertEquals("secret-token-value", result.token)
        assertFalse(result.toString().contains("secret-token-value"))
    }
}
