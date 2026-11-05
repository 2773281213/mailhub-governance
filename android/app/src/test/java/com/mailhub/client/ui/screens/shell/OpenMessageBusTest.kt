package com.mailhub.client.ui.screens.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 通知点击总线：非正数 id 被忽略、同一个 id 只消费一次、
 * 未登录时保留、登录成功后才取走并打开详情。
 */
class OpenMessageBusTest {

    private lateinit var bus: OpenMessageBus

    @Before
    fun setUp() {
        bus = OpenMessageBus()
    }

    @Test
    fun nonPositiveIdsAreIgnored() {
        assertFalse(bus.publish(0L))
        assertFalse(bus.publish(-7L))
        assertNull(bus.pending.value)
        assertNull(bus.consume())
    }

    @Test
    fun theSameIdIsConsumedOnlyOnce() {
        assertTrue(bus.publish(9L))
        assertEquals(9L, bus.consume())
        assertNull(bus.consume())
        assertNull(bus.pending.value)
    }

    @Test
    fun idWaitsUntilLoginThenOpensTheDetail() {
        assertTrue(bus.publish(31L))

        // 未登录：不取走，id 留在总线里
        assertNull(openMessageIdToHandle(bus, loggedIn = false))
        assertEquals(31L, bus.pending.value)

        // 登录成功：外壳取走并落到「02 收件 → 详情」
        val id = openMessageIdToHandle(bus, loggedIn = true)
        assertEquals(31L, id)
        assertEquals(listOf<Route>(Route.Inbox, Route.Detail(31L)), stackForOpenMessage(id!!))
        assertNull(bus.pending.value)
    }

    @Test
    fun resetClearsThePendingId() {
        bus.publish(5L)
        bus.reset()
        assertNull(bus.pending.value)
    }
}
