package com.mailhub.core.db

import com.mailhub.core.golden.Golden
import com.mailhub.core.golden.str
import com.mailhub.core.golden.strOrNull
import com.mailhub.core.golden.toPlain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 `:core` 的 `SettingsSchema` 与 Python `settings_schema.py` 金标完全一致。 */
class SettingsSchemaGoldenTest {

    @Test
    fun `matches python golden cases`() {
        withTempDb { db ->
            db.initDb()
            Golden.replay("settings_schema") { case ->
                val args = case.args
                val actual: Any? = when (case.fn) {
                    "settings" -> SettingsSchema.SETTINGS.values.map { s ->
                        linkedMapOf<String, Any?>(
                            "key" to s.key,
                            "kind" to s.kind,
                            "default" to s.default,
                            "lo" to s.lo,
                            "hi" to s.hi,
                        )
                    }
                    "plain_keys" -> SettingsSchema.PLAIN_KEYS
                    "encode" -> {
                        val key = args.str("key")
                        val value = args.getValue("value").toPlain()
                            ?: error("用例 ${case.id} 缺少 value")
                        SettingsSchema.encode(key, value)
                    }
                    "get_bool" -> {
                        val key = args.str("key")
                        seedSingleSetting(db, key, args.strOrNull("stored"))
                        SettingsSchema.getBool(db, key)
                    }
                    "get_int" -> {
                        val key = args.str("key")
                        seedSingleSetting(db, key, args.strOrNull("stored"))
                        SettingsSchema.getInt(db, key)
                    }
                    "get_str" -> {
                        val key = args.str("key")
                        seedSingleSetting(db, key, args.strOrNull("stored"))
                        SettingsSchema.getStr(db, key)
                    }
                    else -> error("未知金标函数：${case.fn}")
                }
                Golden.diff(case.expected.toPlain(), actual)
            }
        }
    }

    @Test
    fun `rejects unknown keys and mismatched kinds or value types`() {
        withTempDb { db ->
            db.initDb()
            assertIllegalArg { SettingsSchema.getBool(db, "nonexistent_key") }
            assertIllegalArg { SettingsSchema.getInt(db, "clean_server") }
            assertIllegalArg { SettingsSchema.getStr(db, "digest_hour") }
            assertIllegalArg { SettingsSchema.encode("nonexistent_key", "1") }
            assertIllegalArg { SettingsSchema.encode("clean_server", "true") }
            assertIllegalArg { SettingsSchema.encode("digest_hour", true) }
            assertIllegalArg { SettingsSchema.encode("notify_tg_chat", 123) }
        }
    }

    @Test
    fun `getInt falls back to default on non numeric or overflow text`() {
        withTempDb { db ->
            db.initDb()
            seedSingleSetting(db, "digest_hour", "not_a_number")
            assertEquals(8, SettingsSchema.getInt(db, "digest_hour"))
            seedSingleSetting(db, "body_keep_days", "99999999999999999999")
            assertEquals(90, SettingsSchema.getInt(db, "body_keep_days"))
        }
    }

    private fun seedSingleSetting(db: Db, key: String, stored: String?) {
        db.execute("DELETE FROM settings")
        if (stored != null) {
            db.setSetting(key, stored)
        }
    }

    private inline fun assertIllegalArg(block: () -> Unit) {
        val caught = try {
            block()
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("预期抛出 IllegalArgumentException", caught)
    }
}
