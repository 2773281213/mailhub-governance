package com.mailhub.client.ui.screens.otp

import com.mailhub.client.ui.screens.common.ClipboardHandle
import com.mailhub.client.ui.screens.common.SensitiveClipboard
import com.mailhub.client.ui.screens.common.SensitiveClipTtlMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 看板复制用的是 common 的 SensitiveClipboard（本页不再写一份），
 * 这里注入假剪贴板，断言它给看板带来的两个分支：
 * 到期时剪贴板里仍是这个验证码就清空，用户中途复制了别的内容就不动。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtpClipboardCleanupTest {

    /** 假剪贴板：只保存一段纯文本，记录清除次数。 */
    private class FakeClipboardHandle : ClipboardHandle {
        var text: String? = null
        var clears: Int = 0
        var sensitiveFlags: MutableList<Boolean> = mutableListOf()

        override fun copy(text: String, sensitive: Boolean) {
            this.text = text
            sensitiveFlags += sensitive
        }

        override fun primaryText(): String? = text

        override fun clear() {
            text = null
            clears++
        }
    }

    @Test
    fun clearsTheCodeWhenItIsStillOnTheClipboard() = runTest {
        val handle = FakeClipboardHandle()
        val clipboard = SensitiveClipboard(
            clipboard = handle,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )

        // 点「复制 →」或点一行：验证码写进剪贴板并按敏感内容标记
        clipboard.copy("482913")
        assertEquals("482913", handle.text)
        assertEquals(listOf(true), handle.sensitiveFlags)

        advanceTimeBy(SensitiveClipTtlMillis)
        runCurrent()
        assertNull("60 秒后仍然是这个验证码，应当清空", handle.text)
        assertEquals(1, handle.clears)
    }

    @Test
    fun keepsTheContentTheUserCopiedInstead() = runTest {
        val handle = FakeClipboardHandle()
        val clipboard = SensitiveClipboard(
            clipboard = handle,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )

        clipboard.copy("482913")
        advanceTimeBy(20_000)
        // 用户复制了别的内容：到期时不能把人家的新内容清掉
        clipboard.copy("别的内容")
        advanceTimeBy(SensitiveClipTtlMillis - 20_000)
        runCurrent()
        assertEquals("别的内容", handle.text)
        assertEquals(0, handle.clears)
    }
}
