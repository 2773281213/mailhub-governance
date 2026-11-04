package com.mailhub.client.data.model

import com.mailhub.client.data.net.ApiJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 后端字段类型与 DTO 的对齐测试：SQLite INTEGER 在 JSON 里是数字，risk_level 是字符串。 */
class ModelDecodingTest {

    @Test
    fun decodesRiskLevelAsString() {
        val detail = ApiJson.decodeFromString<MessageDetail>(
            """
            {"id":7,"subject":"中奖通知","risk_level":"high",
             "risk_reasons":"可疑链接","confidence":0.87,"unread":1,"has_attach":0}
            """.trimIndent(),
        )

        assertEquals("high", detail.riskLevel)
        assertTrue(detail.isRisky)
        assertEquals("可疑链接", detail.riskReasons)
        assertEquals(0.87, detail.confidence, 0.0)
        assertEquals(1, detail.unread)
    }

    @Test
    fun treatsMediumAsRiskyAndNoneAsSafe() {
        assertTrue(detail(riskLevel = "medium").isRisky)
        assertFalse(detail(riskLevel = "none").isRisky)
        // 后端没给这个字段时按 none 处理
        assertFalse(detail(riskLevel = null).isRisky)
        assertEquals("none", detail(riskLevel = null).riskLevel)
    }

    @Test
    fun decodesEnabledFromIntegerAndBoolean() {
        assertTrue(account("""{"id":1,"enabled":1}""").enabled)
        assertFalse(account("""{"id":2,"enabled":0}""").enabled)
        assertTrue(account("""{"id":3,"enabled":true}""").enabled)
        assertFalse(account("""{"id":4,"enabled":false}""").enabled)
        // 后端以后改成字符串布尔也能读
        assertTrue(account("""{"id":5,"enabled":"1"}""").enabled)
    }

    @Test
    fun encodesEnabledAsBoolean() {
        val json = ApiJson.encodeToString(Account(id = 1, enabled = true)).let(ApiJson::parseToJsonElement)

        assertEquals("true", json.jsonObject["enabled"]?.jsonPrimitive?.content)
    }

    @Test
    fun decodesAccountSyncState() {
        val account = account(
            """{"id":1,"enabled":1,"last_sync":1700000000.5,"last_error":"",
                "total":12,"unread":3,
                "sync_state":{"state":"syncing","msg":"下拉","ts":1.5,"new":2}}""",
        )

        assertEquals("syncing", account.syncState.state)
        assertEquals(2, account.syncState.newCount)
        assertEquals(12, account.total)
        assertEquals(1700000000.5, account.lastSync, 0.0)
    }

    @Test
    fun metaCarriesGovernanceVersionString() {
        val meta = ApiJson.decodeFromString<Meta>(
            """{"api_version":1,"governance_version":"mailhub-three-six-v2",
                "features":{"token_auth":true}}""",
        )

        assertEquals(1, meta.apiVersion)
        assertEquals("mailhub-three-six-v2", meta.governanceVersion)
        assertTrue(meta.features["token_auth"] == true)
    }

    @Test
    fun governanceStatusCarriesVersionString() {
        val status = ApiJson.decodeFromString<GovernanceStatus>(
            """{"version":"mailhub-three-six-v2","running":false,"pending":3,
                "ministries":["吏部"],"last_run":null}""",
        )

        assertEquals("mailhub-three-six-v2", status.version)
        assertEquals(3, status.pending)
        assertEquals(listOf("吏部"), status.ministries)
    }

    private fun account(json: String): Account = ApiJson.decodeFromString<Account>(json)

    private fun detail(riskLevel: String?): MessageDetail {
        val body = if (riskLevel == null) {
            """{"id":7}"""
        } else {
            """{"id":7,"risk_level":"$riskLevel"}"""
        }
        return ApiJson.decodeFromString<MessageDetail>(body)
    }
}
