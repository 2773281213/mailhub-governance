package com.mailhub.client.ui.screens.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 敏感剪贴板：验证码写入后 60 秒自动清空。
 * 剪贴板对象和时钟都注入假实现，用虚拟时间直接把 60 秒跑完。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SensitiveClipboardTest {

    /** 假剪贴板：只保存一段文本，记录清除次数。 */
    private class FakeClipboard : ClipboardHandle {
        var text: String? = null
        var clears: Int = 0

        override fun copy(text: String, sensitive: Boolean) {
            this.text = text
        }

        override fun primaryText(): String? = text

        override fun clear() {
            text = null
            clears++
        }
    }

    @Test
    fun clearsTheCodeAfterOneMinute() = runTest {
        val handle = FakeClipboard()
        val clipboard = SensitiveClipboard(handle, backgroundScope, clock = { testScheduler.currentTime })

        clipboard.copy("482913")
        assertEquals("482913", handle.text)

        advanceTimeBy(SensitiveClipTtlMillis - 1)
        runCurrent()
        assertEquals("还没到期，不应该清空", "482913", handle.text)

        advanceTimeBy(1)
        runCurrent()
        assertNull(handle.text)
        assertEquals(1, handle.clears)
    }

    @Test
    fun keepsClipboardContentTheUserCopiedLater() = runTest {
        val handle = FakeClipboard()
        val clipboard = SensitiveClipboard(handle, backgroundScope, clock = { testScheduler.currentTime })

        clipboard.copy("111111")
        advanceTimeBy(30_000)
        // 用户中途复制了别的内容，到期时不能把人家的新内容清掉
        clipboard.copy("222222")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("222222", handle.text)
        assertEquals(0, handle.clears)

        advanceTimeBy(30_000)
        runCurrent()
        assertNull(handle.text)
        assertEquals(1, handle.clears)
    }

    @Test
    fun ignoresEmptyText() = runTest {
        val handle = FakeClipboard()
        val clipboard = SensitiveClipboard(handle, backgroundScope, clock = { testScheduler.currentTime })

        clipboard.copy("")

        assertNull(handle.text)
        assertEquals(0, handle.clears)
    }
}
