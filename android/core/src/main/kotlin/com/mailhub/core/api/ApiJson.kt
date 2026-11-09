package com.mailhub.core.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger

/**
 * 把实现里的 Kotlin 值转成响应 JSON，对应 Python 侧 `json.dumps` 对同一批值的输出。
 *
 * 类型必须与服务器一致：App 的 DTO 用严格类型解析，整数和浮点不能混。
 * - [Long] / [Int] 等整数 → 不带小数点的数字；
 * - [Double] → 带小数点的数字（`0.0` 仍是 `0.0`，与 Python 的 `json.dumps(0.0)` 相同）；
 * - [Boolean] → `true` / `false`，**不要**用 0/1 代替；
 * - `null` → `null`（SQLite 的 NULL 原样透出）；
 * - [Map] / [Iterable] 递归转换，键按传入顺序保留（Python 的 dict 也是插入有序）。
 */
internal fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Short -> JsonPrimitive(value.toInt())
    is Byte -> JsonPrimitive(value.toInt())
    is Long -> JsonPrimitive(value)
    is BigInteger -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value.toDouble())
    is Number -> JsonPrimitive(value)
    is CharSequence -> JsonPrimitive(value.toString())
    is Map<*, *> -> JsonObject(
        value.entries.associateTo(LinkedHashMap(value.size * 2)) { (key, item) ->
            key.toString() to jsonOf(item)
        },
    )
    is Iterable<*> -> JsonArray(value.map(::jsonOf))
    is Array<*> -> JsonArray(value.map(::jsonOf))
    else -> throw IllegalArgumentException("无法序列化成 JSON 的类型：${value::class.java.name}")
}
