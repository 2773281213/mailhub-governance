package com.mailhub.core.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail

/** 一条金标用例：Python 原实现对 [args] 调用 [fn] 得到 [expected]。格式见 scripts/golden/_common.py。 */
data class GoldenCase(
    val id: String,
    val fn: String,
    val args: JsonObject,
    val expected: JsonElement,
)

object Golden {

    /** 读取 `src/test/resources/golden/<module>.json`。 */
    fun load(module: String): List<GoldenCase> {
        val stream = Golden::class.java.getResourceAsStream("/golden/$module.json")
            ?: error("找不到金标语料 golden/$module.json，先运行 python scripts/golden/$module.py")
        val root = Json.parseToJsonElement(stream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        return root.getValue("cases").jsonArray.map { element ->
            val o = element.jsonObject
            GoldenCase(
                id = o.str("id"),
                fn = o.str("fn"),
                args = o.getValue("args").jsonObject,
                expected = o.getValue("expected"),
            )
        }
    }

    /**
     * 逐条回放。[check] 返回 null 表示一致，否则返回差异说明。
     * 所有用例跑完再统一失败，一次就能看到全部差异（最多列出前 40 条）。
     * 用例为空也算失败，防止 JSON 没生成时测试悄悄通过。
     */
    fun replay(module: String, cases: List<GoldenCase> = load(module), check: (GoldenCase) -> String?) {
        if (cases.isEmpty()) fail("金标语料 $module 没有用例")
        val failures = cases.mapNotNull { case ->
            val problem = try {
                check(case)
            } catch (e: Throwable) {
                "抛出 ${e::class.simpleName}: ${e.message}"
            }
            problem?.let { "[${case.id}] ${case.fn}(${case.args.toString().take(160)}): $it" }
        }
        if (failures.isNotEmpty()) {
            fail("$module：${failures.size}/${cases.size} 条与 Python 不一致\n" + failures.take(40).joinToString("\n"))
        }
    }

    /** 期望与实际不同时生成统一格式的说明。 */
    fun diff(expected: Any?, actual: Any?): String? =
        if (expected == actual) null else "期望 $expected，实际 $actual"
}

fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

fun JsonObject.strOrNull(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

fun JsonObject.bool(key: String): Boolean =
    getValue(key).jsonPrimitive.booleanOrNull ?: error("$key 不是布尔值")

fun JsonObject.double(key: String): Double =
    getValue(key).jsonPrimitive.doubleOrNull ?: error("$key 不是数字")

fun JsonObject.strList(key: String): List<String> =
    (get(key) as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

/** `{"list_id": true, ...}` 这类布尔字典；缺失或 null 返回 null。 */
fun JsonObject.boolMapOrNull(key: String): Map<String, Boolean>? =
    (get(key) as? JsonObject)?.mapValues { (_, v) -> v.jsonPrimitive.booleanOrNull ?: false }

/** 把 JSON 值转成 Python 端等价的 Kotlin 值：数字统一成 Double 或 Long，对象成 Map，数组成 List。 */
fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        content.contains('.') || content.contains('e') || content.contains('E') -> doubleOrNull
        else -> content.toLongOrNull() ?: doubleOrNull
    }
    is JsonArray -> map { it.toPlain() }
    is JsonObject -> mapValues { (_, v) -> v.toPlain() }
}
