package com.mailhub.core.api

import com.mailhub.core.Clock
import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.FakeSecretCipher
import com.mailhub.core.golden.double
import com.mailhub.core.golden.str
import com.mailhub.core.golden.strList
import com.mailhub.core.golden.toPlain
import com.mailhub.core.governance.GovernanceService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneId

/**
 * `android/core/src/test/resources/api/read_seed.json` 的读取与建库。
 *
 * Python（`scripts/golden/api_read.py`）与 Kotlin 读**同一份**种子：账户、邮件、设置、审计、
 * 晨报、同步状态，以及固定时间与时区。两边的插入逻辑必须逐字对应——列名、缺省值、
 * `hours` / `created_hours` / `deleted_hours` 这几个相对时间的换算都写在这里。
 */
internal class ApiSeed(private val root: JsonObject) {

    /** 固定的「现在」，对应打桩后的 `time.time()` 与注入的 [Clock]。 */
    val now: Double = root.double("now")

    /** 时区名，对应 `sync.TZ_NAME`。 */
    val tz: String = root.str("tz")

    /** 本机会话的保留地址，对应 `MAILHUB_PUBLIC_URL`。 */
    val publicBaseUrl: String = root.str("public_base_url")

    private val zone: ZoneId = ZoneId.of(tz)

    /** 本地当天零点，对应 `datetime.fromtimestamp(now, TZ).replace(hour=0, ...)`。 */
    val dayStart: Double = Instant.ofEpochSecond(now.toLong()).atZone(zone).toLocalDate()
        .atStartOfDay(zone).toEpochSecond().toDouble()

    /** 对应 `sync.get_status()` 的内容。 */
    fun syncStatus(): Map<String, Map<String, Any?>> =
        root.getValue("sync_status").jsonObject.mapValues { (_, value) ->
            val entry = value.jsonObject
            linkedMapOf<String, Any?>(
                "state" to entry.str("state"),
                "msg" to entry.str("msg"),
                "ts" to now + entry.double("ts_offset_seconds"),
                "new" to entry.getValue("new").toPlain().let { (it as Number).toInt() },
            )
        }

    /** 建表并插入全部种子数据（显式写 id，保证两边关联一致）。 */
    fun insertInto(db: Db) {
        val accountColumns = root.strList("account_columns")
        val accountDefaults = root.getValue("account_defaults").jsonObject
        for (account in root.getValue("accounts").jsonArray) {
            insert(db, "accounts", accountColumns, account.jsonObject, accountDefaults)
        }
        val messageColumns = root.strList("message_columns")
        val messageDefaults = root.getValue("message_defaults").jsonObject
        for (message in root.getValue("messages").jsonArray) {
            insert(db, "messages", messageColumns, messageRow(message.jsonObject), messageDefaults)
        }
        for ((key, value) in root.getValue("settings").jsonObject) {
            db.execute("INSERT INTO settings(k,v) VALUES(?,?)", key, value.toPlain() as String)
        }
        for (audit in root.getValue("audit").jsonArray) {
            val row = audit.jsonObject
            db.execute(
                """INSERT INTO audit_log(ts, actor, action, tier, target_count, target_ids,
                                         reason, allowed, reversible, undone, undo_payload)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                now + row.double("ts_offset_hours") * 3600.0,
                row.str("actor"),
                row.str("action"),
                row.str("tier"),
                row.getValue("target_count").toPlain(),
                row.str("target_ids"),
                row.str("reason"),
                row.getValue("allowed").toPlain(),
                row.getValue("reversible").toPlain(),
                row.getValue("undone").toPlain(),
                row.str("undo_payload"),
            )
        }
        for (digest in root.getValue("digests").jsonArray) {
            val row = digest.jsonObject
            db.execute(
                "INSERT INTO digests(day, content, created_ts) VALUES(?,?,?)",
                row.str("day"),
                row.str("content"),
                dayStart + row.double("hours") * 3600.0,
            )
        }
    }

    /** 把 `hours` / `created_hours` / `deleted_hours` 换成三个绝对时间戳。 */
    private fun messageRow(message: JsonObject): JsonObject {
        val row = LinkedHashMap<String, JsonElement>(message)
        val hours = (message.getValue("hours").toPlain() as Number).toDouble()
        val createdHours =
            (message["created_hours"]?.toPlain() as? Number)?.toDouble() ?: DEFAULT_CREATED_HOURS
        val deletedHours = (message["deleted_hours"]?.toPlain() as? Number)?.toDouble()
        row.remove("hours")
        row.remove("created_hours")
        row.remove("deleted_hours")
        row["date_ts"] = JsonPrimitive(dayStart + hours * 3600.0)
        row["created_ts"] = JsonPrimitive(now + createdHours * 3600.0)
        row["deleted_ts"] = JsonPrimitive(if (deletedHours == null) 0.0 else now + deletedHours * 3600.0)
        return JsonObject(row)
    }

    private fun insert(
        db: Db,
        table: String,
        columns: List<String>,
        row: JsonObject,
        defaults: JsonObject,
    ) {
        val values = columns.map { column ->
            val element = row[column] ?: defaults[column] ?: error("$table.$column 既没有值也没有默认值")
            element.toPlain()
        }
        db.execute(
            "INSERT INTO $table(${columns.joinToString(",")}) " +
                "VALUES(${columns.joinToString(",") { "?" }})",
            *values.toTypedArray(),
        )
    }

    companion object {
        /** 消息没有给 `created_hours` 时的默认值：两天前入库（不属于「刚入库」，不会进屏障）。 */
        private const val DEFAULT_CREATED_HOURS = -48.0

        /** 读 `src/test/resources/api/<name>.json`。 */
        fun load(name: String = "read_seed"): ApiSeed {
            val stream = ApiSeed::class.java.getResourceAsStream("/api/$name.json")
                ?: error("找不到种子数据 api/$name.json")
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            return ApiSeed(Json.parseToJsonElement(text).jsonObject)
        }
    }
}

/** 用种子建好的 [LocalApi]，测试里直接复用。 */
internal fun apiFor(
    db: Db,
    seed: ApiSeed,
    clock: Clock = FakeClock(seed.now),
    onReadStateChanged: (List<Int>) -> Unit = {},
): LocalApi = LocalApi(
    db = db,
    clock = clock,
    zone = ZoneId.of(seed.tz),
    status = SyncStatusSource { seed.syncStatus() },
    governance = GovernanceService(db, clock),
    cipher = FakeSecretCipher(),
    publicBaseUrl = seed.publicBaseUrl,
    onReadStateChanged = onReadStateChanged,
)

/** 建库 + 插种子的组合，供各用例复用。 */
internal fun seedDatabase(db: Db, seed: ApiSeed = ApiSeed.load()) {
    db.initDb()
    seed.insertInto(db)
}
