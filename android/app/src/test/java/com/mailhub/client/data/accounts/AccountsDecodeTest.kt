package com.mailhub.client.data.accounts

import com.mailhub.client.data.model.Account
import com.mailhub.client.data.model.SyncState
import com.mailhub.client.data.net.ApiJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO 与后端源码的逐字段核对：JSON 都照 app.py / oauth_auth.py 的真实返回结构写，
 * 数据库的 INTEGER 0/1 列（enabled）与数字时间戳也要能解。
 */
class AccountsDecodeTest {

    @Test
    fun decodesAccountListWithIntegerEnabled() {
        val accounts = ApiJson.decodeFromString<List<Account>>(
            """
            [
              {"id":1,"name":"工作邮箱","provider":"qq","email":"work@example.com",
               "imap_host":"imap.qq.com","imap_port":993,"auth_type":"password",
               "poll_interval":300,"color":"#38bdf8","enabled":1,"created_ts":1793782440.0,
               "last_uid":12,"last_sync":1793782440.0,"last_error":"",
               "has_secret":true,"total":820,"unread":42,
               "sync_state":{"state":"idle","msg":"","ts":1793782440.0,"new":3}},
              {"id":2,"name":"私有邮箱","provider":"gmail","email":"me@gmail.com",
               "enabled":0,"sync_state":{"state":"syncing","msg":"拉取 12 封","ts":0.0,"new":0}}
            ]
            """.trimIndent(),
        )

        assertEquals(2, accounts.size)
        assertEquals("工作邮箱", accounts[0].name)
        assertTrue(accounts[0].enabled)
        assertEquals("idle", accounts[0].syncState.state)
        assertEquals(3, accounts[0].syncState.newCount)
        assertFalse(accounts[1].enabled)
        assertEquals("拉取 12 封", accounts[1].syncState.msg)
    }

    @Test
    fun decodesSyncStatusMap() {
        val status = ApiJson.decodeFromString<Map<String, SyncState>>(
            """{"1":{"state":"syncing","msg":"连接中","ts":1793782440.0,"new":0}}""",
        )

        assertEquals(setOf("1"), status.keys)
        assertEquals("syncing", status.getValue("1").state)
        assertEquals("连接中", status.getValue("1").msg)
    }

    @Test
    fun decodesProviders() {
        val response = ApiJson.decodeFromString<OAuthProvidersResponse>(
            """
            {"providers":{
              "qq":{"label":"QQ 邮箱","domains":["qq.com","foxmail.com"],"host":"imap.qq.com",
                    "port":993,"note":"只接受授权码",
                    "methods":[{"type":"app_password","label":"使用授权码",
                                "input_label":"QQ 邮箱授权码",
                                "setup_url":"https://mail.qq.com/","setup_label":"打开 QQ 邮箱",
                                "steps":["开启 IMAP/SMTP 服务","生成 16 位授权码"],
                                "links":[{"label":"打开 QQ 邮箱","url":"https://mail.qq.com/"}],
                                "troubleshoot":"仍然失败时重新生成授权码"}],
                    "oauth":{"browser":false,"device":false},"oauth_hint":"","unavailable":""},
              "outlook":{"label":"Outlook / Hotmail","domains":["outlook.com"],"host":"",
                         "port":993,"note":"","methods":[],
                         "oauth":{"browser":false,"device":false},"oauth_hint":"",
                         "unavailable":"Outlook 只能通过 Microsoft 登录添加"}
            }}
            """.trimIndent(),
        )

        val qq = response.providers.getValue("qq")
        assertEquals("QQ 邮箱", qq.label)
        assertEquals(listOf("qq.com", "foxmail.com"), qq.domains)
        val method = qq.methods.single()
        assertEquals("app_password", method.type)
        assertTrue(method.usesSecret)
        assertEquals("QQ 邮箱授权码", method.inputLabel)
        assertEquals("https://mail.qq.com/", method.setupUrl)
        assertEquals(2, method.steps.size)
        assertEquals("打开 QQ 邮箱", method.links.single().label)
        assertFalse(qq.oauth.browser)

        val outlook = response.providers.getValue("outlook")
        assertTrue(outlook.methods.isEmpty())
        assertEquals("Outlook 只能通过 Microsoft 登录添加", outlook.unavailable)
    }

    @Test
    fun oauthStartBodyAlwaysSendsAndroidClient() {
        val body = OAuthStartBody(provider = "gmail", email = "me@gmail.com")
        val json = ApiJson.encodeToString(body).let(ApiJson::parseToJsonElement).jsonObject

        assertEquals("android", json["client"]?.jsonPrimitive?.content)
        assertEquals("gmail", json["provider"]?.jsonPrimitive?.content)
        // 后端字段名是下划线，别写成 pollInterval
        assertEquals("300", json["poll_interval"]?.jsonPrimitive?.content)
    }

    @Test
    fun decodesOAuthStartAndTransaction() {
        val start = ApiJson.decodeFromString<OAuthStartResponse>(
            """{"ok":true,"transaction_id":"tx-1",
                "authorization_url":"https://accounts.google.com/o/oauth2/v2/auth?state=abc",
                "expires_in":600}""",
        )
        assertEquals("tx-1", start.transactionId)
        assertTrue(start.authorizationUrl.startsWith("https://accounts.google.com"))
        assertEquals(600, start.expiresIn)

        val tx = ApiJson.decodeFromString<OAuthTransaction>(
            """{"id":"tx-1","provider":"gmail","status":"done","account_id":7,
                "message":"邮箱登录成功","expires_ts":1793782440.0}""",
        )
        assertEquals(TransactionDone, tx.status)
        assertEquals(7, tx.accountId)
        // 事务里不含任何凭据字段
        assertFalse(tx.toString().contains("token", ignoreCase = true))
    }

    @Test
    fun decodesDeviceCodeStartAndPoll() {
        val start = ApiJson.decodeFromString<DeviceCodeStart>(
            """{"ok":true,"transaction_id":"dev-1","user_code":"QZ7XK4JN9",
                "verification_uri":"https://microsoft.com/devicelogin",
                "verification_uri_complete":"https://microsoft.com/devicelogin?code=QZ7XK4JN9",
                "interval":5,"expires_in":900}""",
        )
        assertEquals("QZ7XK4JN9", start.userCode)
        assertEquals(5, start.interval)
        assertEquals(900, start.expiresIn)

        val pending = ApiJson.decodeFromString<DeviceCodePoll>("""{"pending":true,"interval":10}""")
        assertTrue(pending.pending)
        assertEquals(10, pending.interval)
        assertFalse(pending.ok)

        val done = ApiJson.decodeFromString<DeviceCodePoll>("""{"ok":true,"id":33}""")
        assertTrue(done.ok)
        assertEquals(33, done.id)
        assertFalse(done.pending)
    }

    @Test
    fun decodesConnectionTestResult() {
        val ok = ApiJson.decodeFromString<ConnectionTest>("""{"ok":true,"msg":"连接正常"}""")
        assertTrue(ok.ok)
        assertEquals("连接正常", ok.msg)

        val failed = ApiJson.decodeFromString<ConnectionTest>("""{"ok":false,"msg":"AUTHENTICATE failed"}""")
        assertFalse(failed.ok)
        assertEquals("AUTHENTICATE failed", failed.msg)
    }

    @Test
    fun accountBodyUsesBackendFieldNames() {
        val json = ApiJson.encodeToString(
            AccountBody(
                provider = "custom",
                email = "hello@studio.example",
                secret = "pw",
                imapHost = "imap.studio.example",
                imapPort = 1993,
            ),
        ).let(ApiJson::parseToJsonElement).jsonObject

        assertEquals("custom", json["provider"]?.jsonPrimitive?.content)
        assertEquals("imap.studio.example", json["imap_host"]?.jsonPrimitive?.content)
        assertEquals("1993", json["imap_port"]?.jsonPrimitive?.content)
        assertEquals("pw", json["secret"]?.jsonPrimitive?.content)
        assertEquals("true", json["enabled"]?.jsonPrimitive?.content)
    }
}
