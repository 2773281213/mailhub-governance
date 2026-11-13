package com.mailhub.core.api

import com.mailhub.core.Clock
import com.mailhub.core.db.Db
import com.mailhub.core.db.Row
import com.mailhub.core.rules.Policy
import com.mailhub.core.text.Py
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 邮件动作的公共流程，逐函数对应 `message_ops.py`：策略闸门、本地删除、恢复、撤销、
 * 已读状态登记（`LOCAL_MODE.md` 第四节规则 1：Python 是规格，函数名一一对应）。
 *
 * 与 Python 模块一致的边界：
 * - 本对象**不开事务、不 commit**，事务边界与「触发同步」都由调用方负责；
 * - 只有 [recordAction] 写审计，其余函数一律不写（撤销的 `undo` 审计也由路由写）；
 * - `LOCAL_MODE.md` 第四节规则 8「不可逆的服务器动作，服务器确认成功之后才更新本地状态」
 *   由 [finishRemoval] 的签名强制：`purge` 不传 `serverConfirmed` 就抛
 *   [IllegalArgumentException]，本地一行都不会动。
 *
 * 所有 `id IN (...)` 都按 [SQL_CHUNK] 分块执行：SQLite 一条语句最多 32766 个绑定参数，
 * 几万封邮件塞进一条语句必然报 `too many SQL variables`。
 */
internal object MessageOps {

    /**
     * 一条 `id IN (...)` 最多绑定多少个参数，对应 `message_ops.SQL_CHUNK`。
     *
     * SQLite 的上限是 32766（本项目实测：32766 可以，32767 报 `too many SQL variables`），
     * 500 只是留足余量的分块大小：块越小，单条语句越不容易撞上限，也越不容易让查询计划
     * 从主键索引退化成全表扫描。
     */
    const val SQL_CHUNK: Int = 500

    /** 撤销软删除时最多恢复多少封，对应 `message_ops.undo` 里的 `payload["ids"][:2000]`。 */
    private const val UNDO_MAX_IDS: Int = 2000

    /**
     * 把 id 列表去重升序后按 [SQL_CHUNK] 分块，供 `id IN (...)` 逐块执行。
     * 对应 `message_ops.chunk_ids`。
     *
     * 去重升序与原来的单条语句结果一致：SQLite 对 `id IN (...)` 按主键升序返回每个匹配行，
     * 重复的 id 也只算一次。分块只把一条语句拆成几条，不改变事务边界，commit 仍由调用方负责。
     * 空列表返回空的分块列表（调用方据此一条 SQL 都不发）。
     */
    fun chunkIds(ids: List<Long>): List<List<Long>> {
        val unique = ids.toSortedSet().toList()
        val chunks = ArrayList<List<Long>>((unique.size + SQL_CHUNK - 1) / SQL_CHUNK)
        var start = 0
        while (start < unique.size) {
            val end = minOf(start + SQL_CHUNK, unique.size)
            chunks += unique.subList(start, end).toList()
            start = end
        }
        return chunks
    }

    /**
     * 只取策略引擎要看的列；回收站里的邮件不算。对应 `message_ops.load_for_policy`。
     *
     * 分块查询后按块顺序合并：块内按 id 升序、块与块之间也是升序，因此合并结果与单条
     * `id IN (...)` 查询相同（按 id 升序，重复 id 只返回一行）。
     */
    fun loadForPolicy(db: Db, ids: List<Long>): List<Map<String, Any?>> {
        val rows = ArrayList<Map<String, Any?>>(ids.size)
        for (chunk in chunkIds(ids)) {
            rows += db.query(
                """SELECT id, category, bill_tier, has_attach, importance, sender_addr, unread, deleted_ts
                   FROM messages WHERE deleted_ts=0 AND id IN (${placeholders(chunk.size)})""",
                *chunk.toTypedArray(),
            ).map { it.toMap() }
        }
        return rows
    }

    /**
     * 登记「待写回服务器的已读状态」，返回涉及的账户 id（去重、升序）。
     * 对应 `message_ops.register_flag_push`（Python 的 `_register_flag_push` 已改成调它）。
     *
     * flag：1 = 下次同步要把这些邮件在服务器上标为已读（本地同时置已读），
     * -1 = 标为未读（本地同时置未读）。调用方负责只传入状态确实发生变化的邮件，
     * 并在事务里调用（与 Python 的一次 `commit()` 对应）。
     */
    fun registerFlagPush(db: Db, ids: List<Long>, flag: Int): List<Int> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val flagUnread = if (flag > 0) 0L else 1L
        val flagPush = if (flag > 0) 1L else -1L
        for (chunk in chunkIds(ids)) {
            db.execute(
                "UPDATE messages SET unread=?, flag_push=? WHERE id IN (${placeholders(chunk.size)})",
                *(listOf<Any?>(flagUnread, flagPush) + chunk).toTypedArray(),
            )
        }
        val accounts = sortedSetOf<Int>()
        for (chunk in chunkIds(ids)) {
            accounts += db.query(
                "SELECT DISTINCT account_id FROM messages WHERE id IN (${placeholders(chunk.size)})",
                *chunk.toTypedArray(),
            ).mapNotNull { it.int("account_id") }
        }
        return accounts.toList()
    }

    /**
     * 逐封过 [Policy.evaluate]，对应 `message_ops.gate`。
     *
     * 返回（放行的邮件行，被拦截的列表）。被拦截的每一项是
     * `{"id": ..., "reason": ..., "protected": ...}`，其中 `protected` 表示「这是个会让邮件
     * 离开收件箱的动作，而且这封邮件受保护」。两个列表都保持 [msgs] 的原有顺序，
     * 被拦截项的三个键也按 id / reason / protected 的顺序写入。
     */
    fun gate(
        action: String,
        msgs: List<Map<String, Any?>>,
        actor: String,
        confirmed: Boolean = false,
        userRule: Boolean = false,
        overrideProtection: Boolean = false,
    ): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> {
        val allowed = ArrayList<Map<String, Any?>>(msgs.size)
        val blocked = ArrayList<Map<String, Any?>>()
        for (msg in msgs) {
            val decision = Policy.evaluate(
                action,
                msg,
                actor = actor,
                userRule = userRule,
                confirmed = confirmed,
                overrideProtection = overrideProtection,
            )
            // 与 Python 的 m["id"] 对应：取不到 id 的行两边都不算（现有数据里不会出现）
            val id = (msg["id"] as? Number)?.toLong() ?: continue
            if (decision.allowed) {
                allowed += msg
            } else {
                blocked += linkedMapOf<String, Any?>(
                    "id" to id,
                    "reason" to decision.reason,
                    "protected" to (action in Policy.DESTRUCTIVE_ACTIONS &&
                        Policy.isProtected(msg).protected),
                )
            }
        }
        return allowed to blocked
    }

    /**
     * 按动作改本地状态，返回（实际处理的 id，撤销载荷或 null）。对应
     * `message_ops.finish_removal`。不 commit，不开事务，不写审计。
     *
     * - `"purge"`（不可逆）：**必须**传 [serverConfirmed]——服务器确认已删除的本地 id。
     *   只 DELETE 这些行，返回 `(serverConfirmed, null)`。没传就抛 [IllegalArgumentException]
     *   （对应 Python 的 `ValueError`）：没有服务器的确认，本模块拒绝永久删除。
     * - `"soft_delete"`：把 [ids] 的 `deleted_ts` 置为 [ts]（没传就用 [clock] 的当前时间），
     *   返回 `(ids, {"ids": ids})`。
     * - 其他动作抛 [IllegalArgumentException]。
     *
     * [ids] / [serverConfirmed] 的输入顺序原样保留在返回值与撤销载荷里（去重升序只发生在
     * 真正执行的 SQL 上），响应里的 `count` 与审计的 `target_ids` 因此与 Python 一致。
     * 时钟必须由调用方传入，对应用户可见的 `deleted_ts` 不能取系统时间（否则金标回放对不上）。
     */
    fun finishRemoval(
        db: Db,
        clock: Clock,
        action: String,
        ids: List<Long>,
        serverConfirmed: List<Long>? = null,
        ts: Double? = null,
    ): Pair<List<Long>, Map<String, Any?>?> {
        if (action == "purge") {
            requireNotNull(serverConfirmed) {
                "purge 必须传 serverConfirmed：没有服务器确认的 id，拒绝删除本地邮件"
            }
            val targets = serverConfirmed.toList()
            for (chunk in chunkIds(targets)) {
                db.execute(
                    "DELETE FROM messages WHERE id IN (${placeholders(chunk.size)})",
                    *chunk.toTypedArray(),
                )
            }
            return targets to null
        }
        if (action == "soft_delete") {
            val targets = ids.toList()
            if (targets.isEmpty()) {
                return targets to linkedMapOf<String, Any?>("ids" to targets)
            }
            val deletedTs = ts ?: clock.now()
            for (chunk in chunkIds(targets)) {
                db.execute(
                    "UPDATE messages SET deleted_ts=? WHERE id IN (${placeholders(chunk.size)})",
                    *(listOf<Any?>(deletedTs) + chunk).toTypedArray(),
                )
            }
            return targets to linkedMapOf<String, Any?>("ids" to targets)
        }
        throw IllegalArgumentException("不支持的动作 $action")
    }

    /**
     * 写一条动作审计，返回审计 id，对应 `message_ops.record_action`。
     *
     * `tier` 取 [Policy.ACTION_TIER]（未知动作按 [Policy.TIER_CONFIRM]），`reversible` 取
     * `undoPayload != null`，[allowed] 没传时取 `ids.isNotEmpty()`。
     */
    fun recordAction(
        db: Db,
        action: String,
        actor: String,
        ids: List<Long>,
        reason: String,
        undoPayload: Any?,
        allowed: Boolean? = null,
    ): Long = db.audit(
        action,
        actor = actor,
        tier = Policy.ACTION_TIER[action] ?: Policy.TIER_CONFIRM,
        targetIds = ids,
        reason = reason,
        allowed = allowed ?: ids.isNotEmpty(),
        reversible = undoPayload != null,
        undoPayload = undoPayload,
    )

    /**
     * 把回收站里的邮件放回收件箱，返回实际恢复的行数。对应 `message_ops.restore`。
     * 不 commit，不开事务，不写审计。
     */
    fun restore(db: Db, ids: List<Long>): Long {
        var restored = 0L
        for (chunk in chunkIds(ids)) {
            restored += db.execute(
                "UPDATE messages SET deleted_ts=0 WHERE id IN (${placeholders(chunk.size)})",
                *chunk.toTypedArray(),
            ).changes
        }
        return restored
    }

    /**
     * 按审计记录里的撤销载荷恢复状态，并把这条审计标为 `undone=1`。对应
     * `message_ops.undo`。
     *
     * 返回（恢复的封数，需要触发已读同步的账户 id）。不 commit，不开事务，不写 `undo` 审计。
     * 调用方负责先确认这条记录存在、没撤销过、可撤销，并且把 [payload] 解析好（Python 在这个
     * 函数里 `json.loads`；Kotlin 的载荷解析与 500 文案留在路由，见 `undoActionRoute`）。
     *
     * [pushReadState] 为真时，撤销已读 / 未读会按恢复后的状态登记待推送（恢复成已读的登记 1，
     * 恢复成未读的登记 -1，与 `app.py` 的分组方式相同）。
     */
    fun undo(
        db: Db,
        auditRow: Row,
        payload: JsonObject,
        pushReadState: Boolean,
    ): Pair<Long, List<Int>> {
        val auditId = auditRow.long("id") ?: error("审计记录缺少 id")
        var restored = 0L
        var pushAccounts: List<Int> = emptyList()
        when (auditRow.str("action")) {
            "soft_delete" -> {
                val ids = (payload["ids"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                    ?.take(UNDO_MAX_IDS)
                    ?: emptyList()
                for (chunk in chunkIds(ids)) {
                    restored += db.execute(
                        "UPDATE messages SET deleted_ts=0 WHERE id IN (${placeholders(chunk.size)})",
                        *chunk.toTypedArray(),
                    ).changes
                }
            }
            "read", "unread" -> {
                val pairs = ArrayList<Pair<Long, Long>>()
                val unread = payload["unread"] as? JsonObject
                if (unread != null) {
                    for ((key, value) in unread) {
                        val id = Py.int(key)?.toLong() ?: continue
                        val prev = (value as? JsonPrimitive)?.content
                            ?.let { Py.int(it)?.toLong() }
                            ?: continue
                        pairs += id to prev
                    }
                }
                for ((id, prev) in pairs) {
                    restored += db.execute(
                        "UPDATE messages SET unread=? WHERE id=?",
                        prev,
                        id,
                    ).changes
                }
                if (pairs.isNotEmpty() && pushReadState) {
                    // 撤销把已读状态改了回来：按恢复后的状态登记待推送
                    // （恢复成未读标 -1，恢复成已读标 1）
                    val toUnread = pairs.filter { it.second == 0L }.map { it.first }
                    val toRead = pairs.filter { it.second != 0L }.map { it.first }
                    pushAccounts = registerFlagPush(db, toUnread, 1) +
                        registerFlagPush(db, toRead, -1)
                }
            }
        }
        db.execute("UPDATE audit_log SET undone=1 WHERE id=?", auditId)
        return restored to pushAccounts
    }

    /** `id IN (...)` 里的占位符串。调用方保证 [size] > 0（空列表不执行 SQL）。 */
    private fun placeholders(size: Int): String = List(size) { "?" }.joinToString(",")
}
