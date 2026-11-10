package com.mailhub.core.db

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把 [value] 序列化成存进 SQLite 文本列的 JSON，对应 `db.py` 里的 `json.dumps(...)`。
 *
 * 与 Python 的差别只允许是**文本形式**上的（`LOCAL_MODE.md` 第 4 节第 5 条）：Python 默认
 * `ensure_ascii=True` 会把中文写成 `\uXXXX`、浮点格式也可能不同；这里写 UTF-8 原文。
 * 解析回来的值必须相同，L7–L9 读这些列时用 `Json.parseToJsonElement(...)` 即可。
 *
 * 支持的类型与 Python 写审计时用到的完全一致：
 * `null`、`Boolean`、`Int` / `Long` / `Short` / `Byte`、`Float` / `Double`、`String`、
 * `List` / `Array`（按数组写）、`Map`（键按 `toString()` 写，与 Python 的字典一致）、
 * 以及已经是 [JsonElement] 的值。`ByteArray` 与未列出的类型直接抛异常：
 * 工具层里出现二进制或自定义对象就是调用方的 bug，不要悄悄写成字符串。
 */
internal fun toJsonText(value: Any?): String = toJsonElement(value).toString()

/** [toJsonText] 的中间结果，方便调试与测试里比较结构。 */
internal fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Int, is Long, is Short, is Byte -> JsonPrimitive((value as Number).toLong())
    is Float, is Double -> JsonPrimitive((value as Number).toDouble())
    is String -> JsonPrimitive(value)
    is List<*> -> JsonArray(value.map { toJsonElement(it) })
    is Array<*> -> JsonArray(value.map { toJsonElement(it) })
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
    else -> throw IllegalArgumentException("无法序列化成 JSON 的类型：${value::class.java.name}")
}
