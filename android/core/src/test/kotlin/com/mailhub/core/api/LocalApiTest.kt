package com.mailhub.core.api

import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.withTempDb
import com.mailhub.core.governance.Governance
import com.mailhub.core.text.Py
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机模式独有的行为：与服务器不同的地方不能进金标（金标只录服务器返回），
 * 所以用普通单元测试逐条钉死。
 *
 * 覆盖 `LOCAL_MODE.md` 3.1、第五节与附录 A：
 * - 不校验令牌、从不返回 401；
 * - `/api/changes` 的两处不同：`after_id == 0` 从头列出、`after_id < 0` 才是初始化，
 *   以及 `local_initial_import_<账户 id>` 的跳过；
 * - `/api/sessions` 空列表、`/api/oauth/providers` 全部 OAuth 不可用。
 *
 * 另外覆盖框架本身：404 / 405 / 422、未捕获异常变 500 且不带内部信息、游标编解码与搜索回退。
 */
class LocalApiTest {

    // ---------------- 框架 ----------------

    @Test
    fun neverRejectsRequestsForMissingToken() {
        withSeededApi { api, _, _ ->
            // 没有任何 Authorization 头、也没有 cookie：本机模式照样回答
            for (path in LOCAL_READ_ROUTES) {
                val response = api.handle(LocalRequest("GET", path))
                assertEquals("$path 不应要求令牌", 200, response.status)
            }
        }
    }

    @Test
    fun neverReturnsUnauthorizedEvenWithBadHeaders() {
        withSeededApi { api, _, _ ->
            val response = api.handle(
                LocalRequest(
                    "GET",
                    "/api/meta",
                    headers = mapOf("Authorization" to "Bearer not-a-real-token"),
                ),
            )
            assertEquals(200, response.status)
            val detail = api.handle(LocalRequest("GET", "/api/does-not-exist"))
            assertEquals(404, detail.status)
        }
    }

    @Test
    fun unknownRouteIs404AndWrongMethodIs405() {
        withSeededApi { api, _, _ ->
            val missing = api.handle(LocalRequest("GET", "/api/nope"))
            assertEquals(404, missing.status)
            assertEquals("Not Found", missing.body.detailText())

            val wrongMethod = api.handle(LocalRequest("POST", "/api/messages"))
            assertEquals(405, wrongMethod.status)
            assertEquals("Method Not Allowed", wrongMethod.body.detailText())
            assertEquals("GET", wrongMethod.headers["Allow"])

            val detailMethod = api.handle(LocalRequest("DELETE", "/api/messages/1"))
            assertEquals(405, detailMethod.status)
        }
    }

    @Test
    fun wrongParameterTypesReturn422() {
        withSeededApi { api, _, _ ->
            val cases = listOf(
                "/api/messages" to mapOf("unread" to "全部"),
                "/api/messages" to mapOf("page_size" to "十"),
                "/api/messages" to mapOf("cursor" to "abc"),
                "/api/messages/abc" to emptyMap(),
                "/api/messages/1" to mapOf("mark_read" to "也许"),
            )
            for ((path, query) in cases) {
                val response = api.handle(LocalRequest("GET", path, query))
                val expected = if (query["cursor"] != null) 400 else 422
                assertEquals("$path $query", expected, response.status)
                if (expected == 422) {
                    val detail = response.body.jsonObject.getValue("detail").jsonArray
                    assertEquals(1, detail.size)
                    assertNotNull(detail[0].jsonObject["type"])
                }
            }
        }
    }

    @Test
    fun uncaughtErrorsBecome500WithoutInternals() {
        // 没跑 initDb 的库：任何查询都会抛 SQLite 异常
        withTempDb(FakeClock(ApiSeed.load().now)) { db ->
            val api = LocalApi(db = db, cipher = com.mailhub.core.db.FakeSecretCipher())
            for (path in listOf("/api/overview", "/api/settings", "/api/messages")) {
                val response = api.handle(LocalRequest("GET", path))
                assertEquals("$path 应返回 500", 500, response.status)
                assertEquals("Internal Server Error", response.body.detailText())
                assertEquals(
                    "响应里不能出现内部信息",
                    """{"detail":"Internal Server Error"}""",
                    response.body.toString(),
                )
            }
        }
    }

    // ---------------- /api/changes 的两处不同 ----------------

    @Test
    fun changesWithZeroAfterIdListsFromTheBeginning() {
        withSeededApi { api, db, _ ->
            // 种子里的 #6 是「刚入库且待治理」的邮件，屏障照样生效
            val blocked = api.handle(LocalRequest("GET", "/api/changes", mapOf("after_id" to "0")))
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), blocked.itemIds())
            assertEquals(5L, blocked.number("cursor"))

            // 治理完之后，after_id=0 从头列出全部邮件（服务器这里只返回水位）
            db.execute("UPDATE messages SET governance_version=? WHERE id=6", Governance.VERSION)
            val all = api.handle(LocalRequest("GET", "/api/changes", mapOf("after_id" to "0")))
            assertEquals(
                listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L,
                    17L, 18L, 19L, 20L, 21L, 22L, 23L, 24L, 25L, 26L),
                all.itemIds(),
            )
            assertEquals(26L, all.number("cursor"))
            assertFalse(all.body.jsonObject.getValue("reset").jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun changesWithNegativeAfterIdOnlyReportsWatermark() {
        withSeededApi { api, _, _ ->
            for (afterId in listOf("-1", "-99")) {
                val response = api.handle(LocalRequest("GET", "/api/changes", mapOf("after_id" to afterId)))
                assertEquals(emptyList<Long>(), response.itemIds())
                assertEquals("初始化只返回水位", 27L, response.number("cursor"))
                assertFalse(response.body.jsonObject.getValue("reset").jsonPrimitive.content.toBoolean())
            }
        }
    }

    @Test
    fun changesSkipsInitialImportButStillAdvancesCursor() {
        withSeededApi { api, db, _ ->
            db.execute("UPDATE messages SET governance_version=? WHERE id=6", Governance.VERSION)
            db.setSetting("local_initial_import_1", "3")
            db.setSetting("local_initial_import_2", "13")
            val response = api.handle(LocalRequest("GET", "/api/changes", mapOf("after_id" to "0")))
            // 账户 1 跳过 id<=3（#1 #2 #3），账户 2 跳过 id<=13（#11 #12 #13）
            assertEquals(
                listOf(4L, 5L, 6L, 7L, 8L, 9L, 10L, 14L, 15L, 17L, 18L, 19L, 20L,
                    21L, 22L, 23L, 24L, 25L, 26L),
                response.itemIds(),
            )
            assertEquals(26L, response.number("cursor"))
        }
    }

    @Test
    fun changesAdvancesCursorWhenEverythingIsSkipped() {
        withSeededApi { api, db, _ ->
            db.execute("UPDATE messages SET governance_version=? WHERE id=6", Governance.VERSION)
            db.setSetting("local_initial_import_1", "26")
            db.setSetting("local_initial_import_2", "27")
            val response = api.handle(LocalRequest("GET", "/api/changes", mapOf("after_id" to "0")))
            assertEquals(emptyList<Long>(), response.itemIds())
            // 一封都不发也要把游标推到水位，否则客户端会一直从头拉
            assertEquals(27L, response.number("cursor"))
        }
    }

    @Test
    fun changesIgnoresBrokenInitialImportMarks() {
        withSeededApi { api, db, _ ->
            db.execute("UPDATE messages SET governance_version=? WHERE id=6", Governance.VERSION)
            db.setSetting("local_initial_import_", "5")
            db.setSetting("local_initial_import_1", "不是数字")
            val response = api.handle(LocalRequest("GET", "/api/changes", mapOf("after_id" to "0")))
            assertEquals("写坏的标记只该少跳几封，不该报错", 200, response.status)
            assertEquals(25, response.itemIds().size)
        }
    }

    // ---------------- 会话与服务商 ----------------

    @Test
    fun sessionsAreAlwaysEmpty() {
        withSeededApi { api, _, _ ->
            val response = api.handle(LocalRequest("GET", "/api/sessions"))
            assertEquals(200, response.status)
            val items = response.body.jsonObject.getValue("items")
            assertTrue(items is JsonArray && items.isEmpty())
        }
    }

    @Test
    fun oauthProvidersDisableEveryOauthMethod() {
        withSeededApi { api, _, _ ->
            val response = api.handle(LocalRequest("GET", "/api/oauth/providers"))
            assertEquals(200, response.status)
            val providers = response.body.jsonObject.getValue("providers").jsonObject
            assertTrue("服务商表不能为空", providers.isNotEmpty())
            for ((key, value) in providers) {
                val entry = value.jsonObject
                val oauth = entry.getValue("oauth").jsonObject
                assertEquals("$key 的 oauth_browser", false, oauth.bool("browser"))
                assertEquals("$key 的 oauth_device", false, oauth.bool("device"))
                val methods = entry.getValue("methods").jsonArray
                for (method in methods) {
                    val type = method.jsonObject.getValue("type").jsonPrimitive.content
                    assertTrue("$key 不应提供 $type", type == "app_password" || type == "password")
                }
            }
            // 只支持 OAuth 的服务商要明确说明本机模式不支持
            val outlook = providers.getValue("outlook").jsonObject
            assertTrue(outlook.getValue("methods").jsonArray.isEmpty())
            assertEquals("本机模式暂不支持，P2 提供", outlook.str("unavailable"))
            assertTrue(providers.getValue("qq").jsonObject.getValue("methods").jsonArray.isNotEmpty())
            assertEquals(
                "password",
                providers.getValue("custom").jsonObject.getValue("methods").jsonArray[0]
                    .jsonObject.getValue("type").jsonPrimitive.content,
            )
        }
    }

    // ---------------- 已读状态回调 ----------------

    @Test
    fun readStateCallbackFiresOncePerTouchedAccount() {
        val calls = ArrayList<List<Int>>()
        withSeededApi(onReadStateChanged = { calls.add(it) }) { api, db, _ ->
            db.setSetting("sync_read_state", "1")
            api.handle(LocalRequest("GET", "/api/messages/2"))
            api.handle(LocalRequest("GET", "/api/messages/3"))
            api.handle(LocalRequest("GET", "/api/messages/13"))
            // 已读之后再打开不会重复登记
            api.handle(LocalRequest("GET", "/api/messages/2"))
            assertEquals(listOf(listOf(1), listOf(1), listOf(2)), calls)
            val flags = db.query("SELECT id, unread, flag_push FROM messages WHERE id IN (2,3,13) ORDER BY id")
                .map { it.long("id") to (it.long("unread") to it.long("flag_push")) }
            assertEquals(
                listOf(2L to (0L to 1L), 3L to (0L to 1L), 13L to (0L to 1L)),
                flags,
            )
        }
    }

    @Test
    fun previewDoesNotMarkReadAndDoesNotCallBack() {
        val calls = ArrayList<List<Int>>()
        withSeededApi(onReadStateChanged = { calls.add(it) }) { api, db, _ ->
            db.setSetting("sync_read_state", "1")
            val response = api.handle(
                LocalRequest("GET", "/api/messages/2", mapOf("mark_read" to "false")),
            )
            assertEquals(200, response.status)
            assertEquals(1L, response.body.jsonObject.getValue("unread").jsonPrimitive.long())
            assertEquals(1L, db.queryOne("SELECT unread FROM messages WHERE id=2")?.long("unread"))
            assertTrue(calls.isEmpty())
        }
    }

    // ---------------- 游标与搜索 ----------------

    @Test
    fun cursorRoundTripsWithoutLosingPrecision() {
        // 15 位有效数字的十进制转一手就会变，游标必须用 repr(float) 才能精确往返
        val values = listOf(
            1792951496.5123494,
            1_700_000_000.0,
            0.0,
            -1.5,
            1e-5,
            1.7976931348623157e308,
        )
        for (value in values) {
            val token = encodeCursor(value, 42)
            val (ts, id) = decodeCursor(token)
            assertEquals(value, ts, 0.0)
            assertEquals(42L, id)
        }
    }

    @Test
    fun invalidCursorsAreRejected() {
        val tokens = listOf(
            "abc",
            "====",
            base64Url(" "),
            base64Url("|"),
            base64Url("1792951496.5|0"),
            base64Url("abc|5"),
            base64Url("nan|5"),
        )
        for (token in tokens) {
            val error = runCatching { decodeCursor(token) }.exceptionOrNull()
            assertTrue("$token 应被判为无效游标", error is ApiException)
            assertEquals(400, (error as ApiException).status)
            assertEquals("无效的分页游标", (error.detail as JsonPrimitive).content)
        }
    }

    @Test
    fun searchFallsBackToLikeForShortQueries() {
        assertFalse(ftsSearchable("折扣", ftsAvailable = true))
        assertFalse(ftsSearchable("ab\u0000c", ftsAvailable = true))
        assertFalse(ftsSearchable("账单通知", ftsAvailable = false))
        assertTrue(ftsSearchable("账单通知", ftsAvailable = true))
        assertEquals("\"a\"\"b\"", ftsPhrase("a\"b"))
        assertEquals("50\\%", escapeLike("50%"))
        assertEquals("a\\_b\\\\c", escapeLike("a_b\\c"))
    }

    // ---------------- 工具 ----------------

    private fun withSeededApi(
        onReadStateChanged: (List<Int>) -> Unit = {},
        block: (LocalApi, Db, ApiSeed) -> Unit,
    ) {
        val seed = ApiSeed.load()
        withTempDb(FakeClock(seed.now)) { db ->
            seedDatabase(db, seed)
            block(apiFor(db, seed, onReadStateChanged = onReadStateChanged), db, seed)
        }
    }

    private fun base64Url(text: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun JsonElement.detailText(): String =
        jsonObject.getValue("detail").jsonPrimitive.content

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.bool(key: String): Boolean =
        getValue(key).jsonPrimitive.content.toBooleanStrict()

    private fun LocalResponse.itemIds(): List<Long> = body.jsonObject.getValue("items").jsonArray
        .map { it.jsonObject.getValue("id").jsonPrimitive.long() }

    private fun LocalResponse.number(key: String): Long =
        body.jsonObject.getValue(key).jsonPrimitive.long()

    private fun JsonPrimitive.long(): Long = content.let { Py.int(it)?.toLong() ?: error("不是整数：$it") }

    /** 本机模式的全部读接口：任何一个都不该要求令牌。 */
    private companion object {
        val LOCAL_READ_ROUTES = listOf(
            "/api/meta",
            "/api/overview",
            "/api/governance/status",
            "/api/accounts",
            "/api/status",
            "/api/messages",
            "/api/messages/1",
            "/api/changes",
            "/api/otp",
            "/api/review",
            "/api/trash",
            "/api/audit",
            "/api/settings",
            "/api/sessions",
            "/api/oauth/providers",
        )
    }
}
