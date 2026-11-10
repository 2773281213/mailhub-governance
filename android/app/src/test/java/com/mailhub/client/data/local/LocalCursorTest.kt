package com.mailhub.client.data.local

import com.mailhub.client.data.InMemoryDataStore
import com.mailhub.client.work.ChangesCursorStore
import com.mailhub.client.work.originOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 进入本机模式时通知游标的初始化（`LOCAL_MODE.md` 3.1 与第五节）：
 * 游标 = 本机库当前 `max(id)`，空库为 0，键是本机会话的来源（`originOf`）。
 */
class LocalCursorTest {

    private val cursors = ChangesCursorStore(InMemoryDataStore())

    private fun origin(): String = requireNotNull(originOf(LOCAL_SERVER_URL))

    @Test
    fun emptyDatabaseSetsCursorToZero() = runBlocking {
        assertNull("开始之前没有游标", cursors.get(origin()))

        initializeLocalCursor(cursors, maxMessageId = { 0L })

        // 0 也是一个有效的游标：不能留空，否则 ChangesSync 会走「第一次执行」把水位当成当前值
        assertEquals(0L, cursors.get(origin()))
    }

    @Test
    fun cursorIsTheCurrentMaxMessageId() = runBlocking {
        initializeLocalCursor(cursors, maxMessageId = { 42L })

        assertEquals(42L, cursors.get(origin()))
    }

    @Test
    fun cursorKeyIsTheLocalSessionOrigin() {
        // 与 ChangesJobService 读游标时用的是同一个键：scheme://host:port
        assertEquals("https://local.mailhub.invalid:443", origin())
    }
}
