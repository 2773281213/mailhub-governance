package com.mailhub.core.api

import com.mailhub.core.db.Db
import com.mailhub.core.db.FakeClock
import com.mailhub.core.db.withTempDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MessageOps` 的单元测试，逐条对应 `message_ops.py` 的约定（`LOCAL_MODE.md` 第四节规则 1：
 * Python 是规格）。
 *
 * 路由层的等价性由金标回放（[ApiWriteGoldenTest]）保证，这里只钉住那些**不会出现在响应里**、
 * 却决定安全性的细节：
 * - `purge` 不传 `serverConfirmed` 必须抛异常且一行都不删（规则 8 由接口强制）；
 * - `soft_delete` 的返回值、撤销载荷与 `deleted_ts`（时钟必须来自调用方，不能取系统时间）；
 * - `gate` 两个列表都保持输入顺序，被拦截项带 `protected`；
 * - `recordAction` 的 `allowed` 默认值与显式值；
 * - `chunkIds` 的去重升序与分块，以及几万封邮件下分块真的生效（SQLite 一条语句最多
 *   32766 个绑定参数）。
 */
class MessageOpsTest {

    // ---------------- finishRemoval ----------------

    @Test
    fun purgeWithoutServerConfirmationRefusesToDeleteAnything() {
        withMessages(5) { db, clock ->
            val error = runCatching {
                MessageOps.finishRemoval(db, clock, "purge", listOf(1L, 2L))
            }.exceptionOrNull()
            assertTrue(
                "purge 不传 serverConfirmed 必须抛 IllegalArgumentException，实际是 $error",
                error is IllegalArgumentException,
            )
            assertEquals("本地一行都不能动", listOf(1L, 2L, 3L, 4L, 5L), messageIds(db))
        }
    }

    @Test
    fun purgeDeletesOnlyServerConfirmedRows() {
        withMessages(5) { db, clock ->
            val (targets, undoPayload) = MessageOps.finishRemoval(
                db,
                clock,
                "purge",
                listOf(1L, 2L, 3L),
                serverConfirmed = listOf(2L, 3L),
            )
            assertEquals("返回值就是服务器确认的那些 id", listOf(2L, 3L), targets)
            assertNull("purge 不可撤销，没有撤销载荷", undoPayload)
            assertEquals("只删确认过的行", listOf(1L, 4L, 5L), messageIds(db))

            // 服务器一封都没确认（全部失败）：本地也一封都不删
            val (none, _) = MessageOps.finishRemoval(
                db,
                clock,
                "purge",
                listOf(4L),
                serverConfirmed = emptyList(),
            )
            assertTrue(none.isEmpty())
            assertEquals(listOf(1L, 4L, 5L), messageIds(db))
        }
    }

    @Test
    fun softDeleteReturnsInputOrderAndUsesTheGivenTimestamp() {
        withMessages(5) { db, clock ->
            val ts = 1_800_000_000.5
            val (targets, undoPayload) = MessageOps.finishRemoval(
                db,
                clock,
                "soft_delete",
                listOf(3L, 1L, 2L),
                ts = ts,
            )
            // 去重升序只发生在 SQL 上：返回值与撤销载荷保持输入顺序（响应里的 count 与
            // 审计的 target_ids 都取它）
            assertEquals(listOf(3L, 1L, 2L), targets)
            assertEquals(mapOf("ids" to listOf(3L, 1L, 2L)), undoPayload)
            for (id in listOf(3L, 1L, 2L)) {
                assertEquals(ts, deletedTs(db, id), 1e-9)
            }
            assertEquals("没选中的邮件不动", 0.0, deletedTs(db, 4L), 1e-9)
        }
    }

    @Test
    fun softDeleteFallsBackToTheInjectedClock() {
        withMessages(3) { db, clock ->
            clock.current = 1_750_000_000.25
            val (_, undoPayload) = MessageOps.finishRemoval(db, clock, "soft_delete", listOf(2L))
            assertEquals(clock.current, deletedTs(db, 2L), 1e-9)
            assertEquals(mapOf("ids" to listOf(2L)), undoPayload)
        }
    }

    @Test
    fun softDeleteWithNoIdsTouchesNothing() {
        withMessages(3) { db, clock ->
            val (targets, undoPayload) = MessageOps.finishRemoval(db, clock, "soft_delete", emptyList())
            assertTrue(targets.isEmpty())
            assertEquals(mapOf("ids" to emptyList<Long>()), undoPayload)
            assertEquals(listOf(0.0, 0.0, 0.0), (1L..3L).map { deletedTs(db, it) })
        }
    }

    @Test
    fun unknownRemovalActionIsRejected() {
        withMessages(3) { db, clock ->
            val error = runCatching {
                MessageOps.finishRemoval(db, clock, "archive", listOf(1L))
            }.exceptionOrNull()
            assertTrue("未知动作必须抛 IllegalArgumentException，实际是 $error", error is IllegalArgumentException)
            assertEquals("不支持的动作 archive", error?.message)
            assertEquals(listOf(1L, 2L, 3L), messageIds(db))
        }
    }

    // ---------------- gate ----------------

    @Test
    fun gateKeepsInputOrderAndMarksProtectedRows() {
        val messages = listOf(
            message(30L, "通知"),
            message(10L, "账单"),
            message(20L, "验证码"),
        )
        // 用户确认过：受保护判定仍然先于「已确认」，10 号照样被拦下
        val (allowed, blocked) = MessageOps.gate(
            "soft_delete",
            messages,
            actor = "user",
            confirmed = true,
        )
        assertEquals("放行的保持输入顺序", listOf(30L, 20L), allowed.map { it["id"] })
        assertEquals("拦截的保持输入顺序", listOf(10L), blocked.map { it["id"] })
        assertEquals("被拦截项的三个键按 id / reason / protected 写", listOf("id", "reason", "protected"), blocked[0].keys.toList())
        assertEquals(true, blocked[0]["protected"])
        assertEquals(
            "分类「账单」属于受保护类别（交易/安全/重要），禁止自动删除",
            blocked[0]["reason"],
        )
    }

    @Test
    fun gateReportsProtectionForEveryBlockedRow() {
        val messages = listOf(
            message(30L, "通知"),
            message(10L, "账单", hasAttach = 0L, importance = 5L),
            message(20L, "验证码", hasAttach = 1L),
        )
        // C 类动作没确认：三封全被拦下，protected 逐封判定
        val (allowed, blocked) = MessageOps.gate("purge", messages, actor = "user")
        assertTrue(allowed.isEmpty())
        assertEquals(listOf(30L, 10L, 20L), blocked.map { it["id"] })
        assertEquals(listOf(false, true, true), blocked.map { it["protected"] })
        assertEquals(
            "动作「purge」属 C 类（不可逆/对外），必须人工确认",
            blocked[0]["reason"],
        )
    }

    @Test
    fun gatePassesTheUserRuleFlagThroughToThePolicyEngine() {
        val messages = listOf(message(30L, "通知"), message(10L, "账单"))
        val (allowed, blocked) = MessageOps.gate(
            "purge",
            messages,
            actor = "system",
            confirmed = true,
            userRule = true,
        )
        // 用户在设置里开启的长期规则能放行 C 类动作，但受保护的账单仍然被拦下
        assertEquals(listOf(30L), allowed.map { it["id"] })
        assertEquals(listOf(10L), blocked.map { it["id"] })
    }

    // ---------------- recordAction ----------------

    @Test
    fun recordActionDefaultsAllowedFromTheTargetIds() {
        withMessages(3) { db, _ ->
            val auditId = MessageOps.recordAction(
                db,
                "soft_delete",
                actor = "user",
                ids = listOf(3L, 1L),
                reason = "用户批量操作",
                undoPayload = linkedMapOf<String, Any?>("ids" to listOf(3L, 1L)),
            )
            val row = db.queryOne("SELECT * FROM audit_log WHERE id=?", auditId)!!
            assertEquals("soft_delete 是 B 类", "B", row.str("tier"))
            assertEquals("ids 非空时 allowed 默认 true", 1L, row.long("allowed"))
            assertEquals("有撤销载荷就是可撤销", 1L, row.long("reversible"))
            assertEquals(2L, row.long("target_count"))
            assertEquals("用户批量操作", row.str("reason"))
            assertTrue(
                "撤销载荷要写成 JSON",
                (row.str("undo_payload") ?: "").contains("\"ids\""),
            )

            // ids 为空时 allowed 默认 false
            val emptyId = MessageOps.recordAction(
                db,
                "read",
                actor = "user",
                ids = emptyList(),
                reason = "无可执行目标",
                undoPayload = null,
            )
            assertEquals(0L, db.queryOne("SELECT allowed FROM audit_log WHERE id=?", emptyId)!!.long("allowed"))
        }
    }

    @Test
    fun recordActionHonoursExplicitAllowedAndUnknownTiers() {
        withMessages(3) { db, _ ->
            val refused = MessageOps.recordAction(
                db,
                "purge",
                actor = "user",
                ids = listOf(1L),
                reason = "手动标记为拦截",
                undoPayload = null,
                allowed = false,
            )
            val row = db.queryOne("SELECT * FROM audit_log WHERE id=?", refused)!!
            assertEquals("显式传的 allowed 优先", 0L, row.long("allowed"))
            assertEquals("purge 是 C 类", "C", row.str("tier"))
            assertEquals("没有撤销载荷就不可撤销", 0L, row.long("reversible"))
            assertEquals("", row.str("undo_payload"))

            // 未知动作按最严的 C 类记
            val unknown = MessageOps.recordAction(
                db,
                "unknown_action",
                actor = "user",
                ids = listOf(1L),
                reason = "",
                undoPayload = null,
            )
            assertEquals("C", db.queryOne("SELECT tier FROM audit_log WHERE id=?", unknown)!!.str("tier"))
        }
    }

    // ---------------- chunkIds ----------------

    @Test
    fun chunkIdsDeduplicatesSortsAndSplits() {
        assertTrue("空列表没有分块", MessageOps.chunkIds(emptyList()).isEmpty())

        val ids = (1L..1005L).toList() + (1L..100L).toList()
        val chunks = MessageOps.chunkIds(ids.shuffled())
        assertEquals("去重后按 500 一块", listOf(500, 500, 5), chunks.map { it.size })
        assertEquals("合起来就是去重升序的全部 id", (1L..1005L).toList(), chunks.flatten())
        for (chunk in chunks) {
            assertEquals("块内升序", chunk.sorted(), chunk)
        }
    }

    // ---------------- 分块真的生效 ----------------

    @Test
    fun chunkingHandlesMoreIdsThanSqliteAllowsInOneStatement() {
        // SQLite 一条语句最多 32766 个绑定参数；33000 塞进一条必然报 too many SQL variables。
        // 这里只验证分块之后 restore / soft_delete / loadForPolicy 都不报错、计数正确。
        val count = 33000
        withMessages(count) { db, clock ->
            val ids = (1L..count.toLong()).toList()

            assertEquals("loadForPolicy 要取回全部邮件", count, MessageOps.loadForPolicy(db, ids).size)

            val (targets, _) = MessageOps.finishRemoval(db, clock, "soft_delete", ids, ts = 1.0)
            assertEquals(count, targets.size)
            assertEquals(
                "全部进回收站",
                count.toLong(),
                db.queryOne("SELECT COUNT(*) c FROM messages WHERE deleted_ts>0")!!.long("c"),
            )

            assertEquals(
                "restore 返回实际恢复的行数",
                count.toLong(),
                MessageOps.restore(db, ids),
            )
            assertEquals(
                "全部回到收件箱",
                count.toLong(),
                db.queryOne("SELECT COUNT(*) c FROM messages WHERE deleted_ts=0")!!.long("c"),
            )
        }
    }

    // ---------------- 工具 ----------------

    /** 一个只带策略引擎要看的列的最小邮件行（与 `loadForPolicy` 的列一致）。 */
    private fun message(
        id: Long,
        category: String,
        hasAttach: Long = 0L,
        importance: Long = 0L,
    ): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "category" to category,
        "bill_tier" to "",
        "has_attach" to hasAttach,
        "importance" to importance,
        "sender_addr" to "sender@example.com",
        "unread" to 1L,
        "deleted_ts" to 0.0,
    )

    /**
     * 建一个临时库、插一个账户与 [count] 封邮件（id / uid 都是 1..count），再交给 [block]。
     *
     * 邮件全在收件箱里（`deleted_ts=0`）、都未读、分类都是「通知」——不受保护，可以随便删。
     */
    private fun withMessages(count: Int, block: (Db, FakeClock) -> Unit) {
        val clock = FakeClock(1_700_000_000.0)
        withTempDb(clock) { db ->
            db.initDb()
            db.execute(
                """INSERT INTO accounts(id, name, provider, email, imap_host, imap_port)
                   VALUES(?,?,?,?,?,?)""",
                1L,
                "本地账户",
                "custom",
                "local@example.com",
                "imap.example",
                993L,
            )
            if (count > 0) {
                db.executeMany(
                    "INSERT INTO messages(id, account_id, uid, category, unread) VALUES(?,?,?,?,?)",
                    (1..count).map { arrayOf<Any?>(it.toLong(), 1L, it.toLong(), "通知", 1L) },
                )
            }
            block(db, clock)
        }
    }

    private fun messageIds(db: Db): List<Long> =
        db.query("SELECT id FROM messages ORDER BY id").mapNotNull { it.long("id") }

    private fun deletedTs(db: Db, id: Long): Double =
        db.queryOne("SELECT deleted_ts FROM messages WHERE id=?", id)!!.double("deleted_ts")!!
}
