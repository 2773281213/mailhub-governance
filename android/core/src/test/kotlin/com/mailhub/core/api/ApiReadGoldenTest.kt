package com.mailhub.core.api

import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.withTempDb
import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.GoldenCase
import com.mailhub.core.golden.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LocalApi` 读接口的金标回放：`scripts/golden/api_read.py` 用 FastAPI 的 TestClient 在真服务器上
 * 跑同一份种子与同一串请求，这里逐条比较状态码、响应头与响应 JSON。
 *
 * 种子（账户 / 邮件 / 设置 / 审计 / 晨报 / 同步状态）与请求序列都由脚本写进
 * `golden/api_read.json`，测试只负责回放；序列里的翻页游标是 Python 侧生成的，Kotlin 必须
 * 用同一套编码解出来。
 *
 * 数字按**值**比较（`3` 与 `3.0` 相等）：两边 SQLite 驱动对同一列给出的类型可能不同
 * （例如 `SUM` 与 `COUNT`），但 App 解析出来的数值一样。布尔与字符串仍要求类型一致。
 */
class ApiReadGoldenTest {

    @Test
    fun replaysServerReadRoutes() {
        val seed = ApiSeed.load()
        val cases = Golden.load("api_read")
        var requests = 0
        var dbStates = 0
        withTempDb(FakeClock(seed.now)) { db ->
            seedDatabase(db, seed)
            val api = apiFor(db, seed)
            Golden.replay("api_read", cases) { case ->
                if (case.fn == "request") requests++
                if (case.fn == "db_state") dbStates++
                replay(api, db, case)
            }
        }
        // 用例数变少说明脚本没有导出全，防止金标被悄悄削小
        assertTrue("request 用例太少：$requests", requests >= 60)
        assertEquals("db_state 用例要恰好一条", 1, dbStates)
    }

    /**
     * 回放器自检：数字按值比较（`3` 与 `3.0` 相等），但类型与结构必须一致。
     * 没有这条，比较器写错时会静默通过。
     */
    @Test
    fun jsonComparisonAcceptsEqualNumbersButNotTypeConfusion() {
        assertNull(compare(JsonPrimitive(3), JsonPrimitive(3.0), ""))
        assertNull(compare(JsonPrimitive(0.1), JsonPrimitive(0.1), ""))
        assertNotNull(compare(JsonPrimitive(3), JsonPrimitive("3"), ""))
        assertNotNull(compare(JsonPrimitive(true), JsonPrimitive(1), ""))
        assertNotNull(
            compare(
                Json.parseToJsonElement("""{"a":1}"""),
                Json.parseToJsonElement("""{"a":1,"b":2}"""),
                "",
            ),
        )
        assertNotNull(
            compare(Json.parseToJsonElement("[1,2]"), Json.parseToJsonElement("[1]"), ""),
        )
    }

    /** 回放一条用例；返回 null 表示与 Python 一致。 */
    private fun replay(api: LocalApi, db: Db, case: GoldenCase): String? = when (case.fn) {
        "request" -> replayRequest(api, case)
        "settings" -> applySettings(db, case)
        "db_state" -> compare(case.expected, exportDbState(db), "")
        else -> "未知的用例类型 ${case.fn}"
    }

    private fun replayRequest(api: LocalApi, case: GoldenCase): String? {
        val method = case.args.str("method")
        val path = case.args.str("path")
        val query = case.args["query"]?.jsonObject
            ?.mapValues { (_, value) -> value.jsonPrimitive.content }
            ?: emptyMap()
        val response = api.handle(LocalRequest(method = method, path = path, query = query))
        val expected = case.expected.jsonObject
        val differences = ArrayList<String>(2)

        val status = expected.getValue("status").jsonPrimitive.int
        if (status != response.status) {
            differences.add("状态码期望 $status，实际 ${response.status}（响应体 ${response.body}）")
        }
        for ((name, value) in expected["headers"]?.jsonObject ?: JsonObject(emptyMap())) {
            val wanted = value.jsonPrimitive.content
            val actual = response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            if (actual == null || actual.value != wanted) {
                differences.add("响应头 $name 期望「$wanted」，实际「${actual?.value}」")
            }
        }
        compare(expected.getValue("json"), response.body, "")?.let { differences.add(it) }
        return differences.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    /** 对应脚本里的 `settings` 动作：直接写 settings 表，两侧用同一组键值。 */
    private fun applySettings(db: Db, case: GoldenCase): String? {
        val settings = case.args["settings"]?.jsonObject ?: return "用例缺少 args.settings"
        for ((key, value) in settings) {
            db.setSetting(key, value.jsonPrimitive.content)
        }
        return null
    }

    /**
     * 请求序列之后的写的副作用：`messages` 的 `id / unread / flag_push`。
     * 「打开即已读」和「登记待推送」的结论必须与服务器完全一致。
     */
    private fun exportDbState(db: Db): JsonElement = JsonObject(
        mapOf(
            "messages" to JsonArray(
                db.query("SELECT id, unread, flag_push FROM messages ORDER BY id").map { row ->
                    JsonObject(
                        linkedMapOf(
                            "id" to JsonPrimitive(row.long("id")),
                            "unread" to JsonPrimitive(row.long("unread")),
                            "flag_push" to JsonPrimitive(row.long("flag_push")),
                        ),
                    )
                },
            ),
        ),
    )

    /** 递归比较两个 JSON；[path] 只用于把差异定位到具体的字段。 */
    private fun compare(expected: JsonElement, actual: JsonElement, path: String): String? = when {
        expected is JsonNull && actual is JsonNull -> null
        expected is JsonObject && actual is JsonObject -> compareObjects(expected, actual, path)
        expected is JsonArray && actual is JsonArray -> compareArrays(expected, actual, path)
        expected is JsonPrimitive && actual is JsonPrimitive ->
            comparePrimitives(expected, actual, path)
        else -> mismatch(path, expected, actual)
    }

    private fun compareObjects(expected: JsonObject, actual: JsonObject, path: String): String? {
        val missing = expected.keys - actual.keys
        val extra = actual.keys - expected.keys
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            return "$path 的键不一致：缺少 ${missing.sorted()}，多出 ${extra.sorted()}"
        }
        for ((key, value) in expected) {
            compare(value, actual.getValue(key), "$path.$key")?.let { return it }
        }
        return null
    }

    private fun compareArrays(expected: JsonArray, actual: JsonArray, path: String): String? {
        if (expected.size != actual.size) {
            return "$path 的长度期望 ${expected.size}，实际 ${actual.size}"
        }
        for (index in expected.indices) {
            compare(expected[index], actual[index], "$path[$index]")?.let { return it }
        }
        return null
    }

    private fun comparePrimitives(
        expected: JsonPrimitive,
        actual: JsonPrimitive,
        path: String,
    ): String? {
        if (expected.isString != actual.isString) return mismatch(path, expected, actual)
        if (expected.isString) {
            return if (expected.content == actual.content) null else mismatch(path, expected, actual)
        }
        val expectedBool = expected.booleanOrNull
        if (expectedBool != null) {
            return if (actual.booleanOrNull == expectedBool) null else mismatch(path, expected, actual)
        }
        val left = expected.content.toBigDecimalOrNull() ?: return mismatch(path, expected, actual)
        val right = actual.content.toBigDecimalOrNull() ?: return mismatch(path, expected, actual)
        return if (left.compareTo(right) == 0) null else mismatch(path, expected, actual)
    }

    private fun mismatch(path: String, expected: JsonElement, actual: JsonElement): String =
        "${path.ifEmpty { "响应体" }} 期望 $expected，实际 $actual"
}
