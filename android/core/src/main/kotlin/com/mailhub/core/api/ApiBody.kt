package com.mailhub.core.api

import com.mailhub.core.text.Py
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * 写接口的 JSON 请求体解析，逐条对照 FastAPI 用 pydantic 模型解析请求体的行为。
 *
 * **服务器就是规格**：App 用强类型 DTO 序列化请求体，所以这里的重点不是宽松解析，而是
 * 「合法请求一个字段都不许拦、非法请求的状态码与 `{"detail": [...]}` 结构必须一致」。
 * pydantic 的做法是按模型字段**声明顺序**收集所有字段的错误，然后一次性回 422：
 *
 * ```text
 * {"detail": [{"type": "missing", "loc": ["body", "email"],
 *              "msg": "Field required", "input": {"provider": "custom"}}]}
 * ```
 *
 * 这里只实现 App 实际会碰到的几种字段类型与错误码（`missing` / `string_type` /
 * `int_parsing` / `int_from_float` / `float_parsing` / `bool_type` / `bool_parsing` /
 * `list_type` / `greater_than_equal` / `less_than_equal` / `json_invalid` /
 * `model_attributes_type`），与 `scripts/golden/api_write.py` 的用例一一对应。
 */

/** 请求体字段的标量类型，对应 pydantic 模型里的几种字段声明。 */
internal enum class BodyType {
    STRING,
    INT,
    FLOAT,
    BOOL,
    INT_LIST,
}

/**
 * 一个请求体字段的声明。
 *
 * @param required 无默认值的必填字段（pydantic 里没有 `= 默认值` 的字段）
 * @param default 缺省值；[required] 为真时忽略
 * @param nullable 字段类型带 `| None`（显式传 `null` 是合法的，等价于「不修改」）
 * @param ge / le 对应 `Field(ge=..., le=...)` 的上下限（只对 [BodyType.INT] 有效）
 */
internal class BodyField(
    val name: String,
    val type: BodyType,
    val required: Boolean = false,
    val default: Any? = null,
    val nullable: Boolean = false,
    val ge: Long? = null,
    val le: Long? = null,
)

/** 解析好的请求体：按字段名取值；缺失的可选字段拿到声明里的默认值。 */
internal class Body(private val values: Map<String, Any?>) {

    fun str(name: String): String = values[name] as? String ?: ""

    fun optStr(name: String): String? = values[name] as? String

    fun long(name: String): Long = (values[name] as? Number)?.toLong() ?: 0L

    fun optLong(name: String): Long? = (values[name] as? Number)?.toLong()

    fun bool(name: String): Boolean = values[name] as? Boolean ?: false

    fun optBool(name: String): Boolean? = values[name] as? Boolean

    fun optDouble(name: String): Double? = (values[name] as? Number)?.toDouble()

    @Suppress("UNCHECKED_CAST")
    fun longList(name: String): List<Long> = (values[name] as? List<Long>) ?: emptyList()
}

/**
 * 一次请求的上下文：查询参数、路径参数与原始请求体。
 *
 * 路径参数已按 FastAPI 的规则转好（见 [LocalApi.dispatch]），处理函数只取值。
 */
internal class Call(
    val query: Params,
    val pathParams: Map<String, String> = emptyMap(),
    internal val rawBody: String? = null,
) {

    /** 路径参数转整数，对应 FastAPI 的 `{acc_id: int}`：类型不对 422。 */
    fun pathId(name: String): Long = pathIdOrNull(name) ?: throw ApiException(404, "Not Found")

    fun pathIdOrNull(name: String): Long? = pathParams[name]?.let { parsePathId(name, it) }

    /** 路径参数原样取出（`{sid: str}` 这类）。 */
    fun pathText(name: String): String = pathParams[name] ?: ""

    /**
     * 请求体解析成 JSON。没有请求体时返回 null（对应 FastAPI 认定「没有 body」）。
     *
     * JSON 语法错误回 422 `json_invalid`：与 pydantic 的 `ctx.error` 不同，这里不写解析器的
     * 原始错误文本（各版本措辞不同，写进去只会变成噪声）。
     */
    fun json(): JsonElement? {
        val text = rawBody ?: return null
        if (text.isBlank()) return null
        return try {
            Json.parseToJsonElement(text)
        } catch (error: IllegalArgumentException) {
            throw ApiException(
                422,
                jsonOf(
                    listOf(
                        linkedMapOf<String, Any?>(
                            "type" to "json_invalid",
                            "loc" to listOf("body", 0),
                            "msg" to "JSON decode error",
                            "input" to emptyMap<String, Any?>(),
                        ),
                    ),
                ),
            )
        }
    }
}

/**
 * 按 [spec] 解析请求体，返回 [Body]；不合法时抛 422（`detail` 是错误列表）。
 *
 * 与 pydantic 一样按字段声明顺序收集全部错误；某个字段出错时不再对它做后续检查。
 */
internal fun parseBody(raw: JsonElement?, spec: List<BodyField>): Body {
    if (raw == null) {
        // 缺少请求体：pydantic 报 `{"type": "missing", "loc": ["body"], "input": null}`
        throw ApiException(
            422,
            jsonOf(
                listOf(
                    linkedMapOf<String, Any?>(
                        "type" to "missing",
                        "loc" to listOf("body"),
                        "msg" to "Field required",
                        "input" to null,
                    ),
                ),
            ),
        )
    }
    if (raw !is JsonObject) {
        throw ApiException(
            422,
            jsonOf(
                listOf(
                    linkedMapOf<String, Any?>(
                        "type" to "model_attributes_type",
                        "loc" to listOf("body"),
                        "msg" to "Input should be a valid dictionary or object to extract fields from",
                        "input" to sanitizedInput(raw),
                    ),
                ),
            ),
        )
    }
    val errors = ArrayList<JsonElement>(spec.size)
    val values = LinkedHashMap<String, Any?>(spec.size * 2)
    for (field in spec) {
        val element = raw[field.name]
        if (element == null) {
            if (field.required) {
                errors += error("missing", "Field required", listOf("body", field.name), raw)
            } else {
                values[field.name] = field.default
            }
            continue
        }
        // `str | None` 这类字段：显式传 null 是合法的，等价于「不修改」
        if (element is JsonNull && field.nullable) {
            values[field.name] = null
            continue
        }
        values[field.name] = readField(field, element, errors)
    }
    if (errors.isNotEmpty()) {
        throw ApiException(422, jsonOf(errors))
    }
    return Body(values)
}

/** 单个字段的解析；出错时往 [errors] 里追加一条并返回 null。 */
private fun readField(field: BodyField, element: JsonElement, errors: MutableList<JsonElement>): Any? {
    val loc = listOf<Any?>("body", field.name)
    return when (field.type) {
        BodyType.STRING -> {
            if (element is JsonPrimitive && element !is JsonNull && element.isString) {
                element.content
            } else {
                errors += error("string_type", "Input should be a valid string", loc, element)
                null
            }
        }
        BodyType.INT -> readInt(field, element, loc, errors)
        BodyType.FLOAT -> readFloat(element, loc, errors)
        BodyType.BOOL -> readBool(element, loc, errors)
        BodyType.INT_LIST -> readIntList(field, element, errors)
    }
}

/** 整数：接受 JSON 整数、无小数部分的浮点（`993.0`）与整数字符串（pydantic 的宽松模式）。 */
private fun readInt(
    field: BodyField,
    element: JsonElement,
    loc: List<Any?>,
    errors: MutableList<JsonElement>,
): Long? {
    val value = readIntValue(element, loc, errors) ?: return null
    if (field.ge != null && value < field.ge) {
        errors += error(
            "greater_than_equal",
            "Input should be greater than or equal to ${field.ge}",
            loc,
            element,
            mapOf("ge" to field.ge),
        )
        return null
    }
    if (field.le != null && value > field.le) {
        errors += error(
            "less_than_equal",
            "Input should be less than or equal to ${field.le}",
            loc,
            element,
            mapOf("le" to field.le),
        )
        return null
    }
    return value
}

/** 单个数转成 long，含 pydantic 的几种解析错误码。 */
private fun readIntValue(
    element: JsonElement,
    loc: List<Any?>,
    errors: MutableList<JsonElement>,
): Long? {
    if (element is JsonNull) {
        errors += error("int_type", "Input should be a valid integer", loc, element)
        return null
    }
    if (element is JsonPrimitive && element.isString) {
        val parsed = Py.int(element.content)
        if (parsed == null || parsed.bitLength() > MAX_INTEGER_BITS) {
            errors += error(INT_PARSING, INT_PARSING_MSG, loc, element)
            return null
        }
        return parsed.toLong()
    }
    if (element !is JsonPrimitive) {
        errors += error("int_type", "Input should be a valid integer", loc, element)
        return null
    }
    // JSON 的 true / false：pydantic 不接受布尔当整数
    if (element.content == "true" || element.content == "false") {
        errors += error("int_type", "Input should be a valid integer", loc, element)
        return null
    }
    val decimal = element.content.toBigDecimalOrNull()
    if (decimal == null) {
        errors += error(INT_PARSING, INT_PARSING_MSG, loc, element)
        return null
    }
    val stripped = decimal.stripTrailingZeros()
    if (stripped.scale() > 0) {
        errors += error(
            "int_from_float",
            "Input should be a valid integer, got a number with a fractional part",
            loc,
            element,
        )
        return null
    }
    val exact = try {
        stripped.toBigIntegerExact()
    } catch (error: ArithmeticException) {
        errors += error(INT_PARSING, INT_PARSING_MSG, loc, element)
        return null
    }
    if (exact.bitLength() > MAX_INTEGER_BITS) {
        errors += error(INT_PARSING, INT_PARSING_MSG, loc, element)
        return null
    }
    return exact.toLong()
}

private fun readFloat(
    element: JsonElement,
    loc: List<Any?>,
    errors: MutableList<JsonElement>,
): Double? {
    if (element is JsonNull) {
        errors += error("float_type", "Input should be a valid number", loc, element)
        return null
    }
    if (element is JsonPrimitive && element.isString) {
        val parsed = Py.float(element.content)
        if (parsed == null) {
            errors += error(FLOAT_PARSING, FLOAT_PARSING_MSG, loc, element)
        }
        return parsed
    }
    if (element !is JsonPrimitive || element.content == "true" || element.content == "false") {
        errors += error("float_type", "Input should be a valid number", loc, element)
        return null
    }
    val parsed = element.content.toDoubleOrNull()
    if (parsed == null) {
        errors += error(FLOAT_PARSING, FLOAT_PARSING_MSG, loc, element)
    }
    return parsed
}

/** 布尔：只认 pydantic 宽松模式接受的那一组字面值。 */
private fun readBool(
    element: JsonElement,
    loc: List<Any?>,
    errors: MutableList<JsonElement>,
): Boolean? {
    if (element is JsonNull) {
        errors += error("bool_type", "Input should be a valid boolean", loc, element)
        return null
    }
    if (element !is JsonPrimitive) {
        errors += error("bool_type", "Input should be a valid boolean", loc, element)
        return null
    }
    if (element.isString) {
        return when (element.content.lowercase(Locale.ROOT)) {
            "1", "on", "t", "true", "y", "yes" -> true
            "0", "off", "f", "false", "n", "no" -> false
            else -> {
                errors += error(BOOL_PARSING, BOOL_PARSING_MSG, loc, element)
                null
            }
        }
    }
    return when (element.content) {
        "true", "1", "1.0" -> true
        "false", "0", "0.0" -> false
        else -> {
            errors += error(BOOL_PARSING, BOOL_PARSING_MSG, loc, element)
            null
        }
    }
}

/** 整数列表：对应 `list[int]`，元素错误的位置带上下标。 */
private fun readIntList(
    field: BodyField,
    element: JsonElement,
    errors: MutableList<JsonElement>,
): List<Long>? {
    if (element !is JsonArray) {
        errors += error(
            "list_type",
            "Input should be a valid list",
            listOf("body", field.name),
            element,
        )
        return null
    }
    val out = ArrayList<Long>(element.size)
    for ((index, item) in element.withIndex()) {
        val loc = listOf<Any?>("body", field.name, index)
        val value = readIntValue(item, loc, errors) ?: continue
        out += value
    }
    return out
}

/** 组装一条 pydantic 风格的字段错误。 */
private fun error(
    type: String,
    message: String,
    loc: List<Any?>,
    input: JsonElement,
    ctx: Map<String, Any?>? = null,
): JsonElement {
    val map = LinkedHashMap<String, Any?>(8)
    map["type"] = type
    map["loc"] = loc
    map["msg"] = message
    map["input"] = sanitizedInput(input)
    if (ctx != null) map["ctx"] = ctx
    return jsonOf(map)
}

/**
 * 422 的 `input` 里不回显凭据（**有意偏差**，服务器会原样回显整个请求体）。
 *
 * 服务器把解析失败的整个请求体塞进 `input`，本机模式没有理由照着做：`ApiClient.toApiError`
 * 遇到数组形式的 `detail` 直接得 null，App 不读 `input`，删掉它不会影响任何调用方。
 * 规则：递归删掉键名恰为 `secret` / `password` / `old_password` / `new_password`，
 * 以及键名（不区分大小写）含 `key` / `token` / `secret` / `password` 的项。
 */
internal fun sanitizedInput(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries.fold(LinkedHashMap<String, JsonElement>(element.size)) { out, (name, value) ->
            if (!isSecretInputKey(name)) out[name] = sanitizedInput(value)
            out
        },
    )
    is JsonArray -> JsonArray(element.map(::sanitizedInput))
    else -> element
}

/** `input` 里必须删掉的键名：四个明确的凭据字段，以及名字带凭据含义的字段。 */
private fun isSecretInputKey(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    if (lower in SECRET_INPUT_KEYS) return true
    return SECRET_INPUT_MARKERS.any { lower.contains(it) }
}

/** 与服务器 `SettingsBody` / `AccountBody` 里全部凭据字段对应的键名。 */
private val SECRET_INPUT_KEYS = setOf("secret", "password", "old_password", "new_password")

/** 键名里出现这些词就当作凭据（`ai_key`、`notify_tg_token`、`oauth_client_secret` 等）。 */
private val SECRET_INPUT_MARKERS = listOf("key", "token", "secret", "password")

/** pydantic 的 `int_parsing` 错误码与说明。 */
internal const val INT_PARSING = "int_parsing"
internal const val INT_PARSING_MSG =
    "Input should be a valid integer, unable to parse string as an integer"

/** pydantic 的 `float_parsing` 错误码与说明。 */
internal const val FLOAT_PARSING = "float_parsing"
internal const val FLOAT_PARSING_MSG =
    "Input should be a valid number, unable to parse string as a number"

/** pydantic 的 `bool_parsing` 错误码与说明。 */
internal const val BOOL_PARSING = "bool_parsing"
internal const val BOOL_PARSING_MSG =
    "Input should be a valid boolean, unable to interpret input"

/** SQLite INTEGER 是带符号 64 位；比它大的整数一律当成解析失败（Python 那边会在绑定时炸）。 */
private const val MAX_INTEGER_BITS = 63
