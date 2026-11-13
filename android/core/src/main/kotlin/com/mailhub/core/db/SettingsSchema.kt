package com.mailhub.core.db

import com.mailhub.core.text.Py
import com.mailhub.core.text.pyStrip
import java.util.Collections

/**
 * 单项普通设置的元信息，对应 Python `settings_schema.Setting`。
 *
 * @property key `settings` 表里的键名（也是 `/api/settings` 的 JSON 字段名）。
 * @property kind 类型标识：`"bool"` / `"int"` / `"str"`。
 * @property default `settings` 表缺失该键（或整数项为空串）时的字符串默认值。
 * @property lo 整数下限（含）；`null` 表示不限。
 * @property hi 整数上限（含）；`null` 表示不限。
 */
data class Setting(
    val key: String,
    val kind: String,
    val default: String,
    val lo: Long? = null,
    val hi: Long? = null,
)

/**
 * 普通设置项的单一真源，对应 Python `settings_schema.py`。
 *
 * 定义 9 个无需加解密或复杂校验的普通设置的默认值、取值范围与编解码规则。
 */
object SettingsSchema {

    private const val MAX_INT_BITS = 31

    /** 全部普通设置的定义表，顺序与 Python `settings_schema.SETTINGS` 一致。 */
    val SETTINGS: Map<String, Setting> = Collections.unmodifiableMap(
        listOf(
            Setting("clean_otp_days", "int", "0", lo = 0L),
            Setting("clean_server", "bool", "0"),
            Setting("clean_bill_minor_days", "int", "0", lo = 0L),
            Setting("sync_read_state", "bool", "0"),
            Setting("digest_hour", "int", "8", lo = 0L, hi = 23L),
            Setting("notify_tg_chat", "str", ""),
            Setting("notify_important", "bool", "1"),
            Setting("notify_otp", "bool", "0"),
            Setting("body_keep_days", "int", "90", lo = 0L),
        ).associateByTo(LinkedHashMap()) { it.key },
    )

    /** `PUT /api/settings` 直接遍历写入的键列表，顺序与 Python `settings_schema.PLAIN_KEYS` 一致。 */
    val PLAIN_KEYS: List<String> = listOf(
        "clean_otp_days",
        "clean_server",
        "sync_read_state",
        "digest_hour",
        "notify_tg_chat",
        "notify_important",
        "notify_otp",
        "body_keep_days",
        "clean_bill_minor_days",
    )

    private fun lookup(key: String): Setting =
        SETTINGS[key] ?: throw IllegalArgumentException("未知的设置项：$key")

    private fun definition(key: String, kind: String): Setting {
        val setting = lookup(key)
        if (setting.kind != kind) {
            throw IllegalArgumentException("设置项 $key 类型为 ${setting.kind}，非 $kind")
        }
        return setting
    }

    /** 读取布尔设置项，对应 Python `settings_schema.get_bool`。 */
    fun getBool(db: Db, key: String): Boolean {
        val setting = definition(key, "bool")
        return db.getSetting(key, setting.default) == "1"
    }

    /**
     * 读取整数设置项，对应 Python `settings_schema.get_int`。
     *
     * 缺键或空串回退到默认值；非数字文本或超出 32 位 `Int` 范围时与现行
     * `LocalApi.intSetting` 一致回退默认值（Python 端会抛 `ValueError`）。
     */
    fun getInt(db: Db, key: String): Int {
        val setting = definition(key, "int")
        val fallback = setting.default.toInt()
        val raw = db.getSetting(key, "").ifEmpty { setting.default }
        val value = Py.int(raw) ?: return fallback
        return if (value.bitLength() > MAX_INT_BITS) fallback else value.toInt()
    }

    /** 读取字符串设置项，对应 Python `settings_schema.get_str`。 */
    fun getStr(db: Db, key: String): String {
        val setting = definition(key, "str")
        return db.getSetting(key, setting.default)
    }

    /**
     * 把设置值编码为写入 `settings` 表的字符串，对应 Python `settings_schema.encode`。
     *
     * 布尔编为 `"1"`/`"0"`；整数先按 `lo` 后按 `hi` 钳位再转字符串；字符串按 Python
     * `str.strip()` 去除首尾空白。键不存在或值类型不符时抛出 `IllegalArgumentException`。
     */
    fun encode(key: String, value: Any): String {
        val setting = lookup(key)
        return when (setting.kind) {
            "bool" -> {
                require(value is Boolean) {
                    "设置项 $key 需要 Boolean，实际为 ${value::class.simpleName}"
                }
                if (value) "1" else "0"
            }
            "int" -> {
                var number = when (value) {
                    is Int -> value.toLong()
                    is Long -> value
                    is Short -> value.toLong()
                    is Byte -> value.toLong()
                    else -> throw IllegalArgumentException(
                        "设置项 $key 需要整数，实际为 ${value::class.simpleName}",
                    )
                }
                setting.lo?.let { number = maxOf(it, number) }
                setting.hi?.let { number = minOf(it, number) }
                number.toString()
            }
            else -> {
                require(value is String) {
                    "设置项 $key 需要 String，实际为 ${value::class.simpleName}"
                }
                value.pyStrip()
            }
        }
    }
}
