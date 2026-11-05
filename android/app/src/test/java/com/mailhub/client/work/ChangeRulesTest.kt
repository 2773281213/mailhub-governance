package com.mailhub.client.work

import com.mailhub.client.data.model.MessageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 分类到渠道的映射、通知文字里的发件人规则、锁屏版本不带内容。 */
class ChangeRulesTest {

    private fun mail(
        category: String,
        importance: Int = 0,
        otp: String = "",
        unread: Int = 1,
        senderName: String = "云服务账单",
        senderAddr: String = "billing@cloud.example",
        riskLevel: String = "none",
    ) = MessageSummary(
        id = 42,
        subject = "本月账单已生成",
        senderName = senderName,
        senderAddr = senderAddr,
        category = category,
        importance = importance,
        otpCode = otp,
        unread = unread,
        riskLevel = riskLevel,
    )

    @Test
    fun categoriesMapToChannels() {
        assertEquals(NotifyChannel.Important, channelFor(mail("重要")))
        assertEquals(NotifyChannel.Important, channelFor(mail("账单", importance = 4)))
        assertEquals(NotifyChannel.Security, channelFor(mail("安全")))
        // 可疑邮件即使 importance 很高，也走安全提醒
        assertEquals(NotifyChannel.Security, channelFor(mail("可疑", importance = 5)))
        assertEquals(NotifyChannel.Otp, channelFor(mail("验证码", otp = "482913")))
    }

    @Test
    fun mailThatNeedsNoNotificationIsSkipped() {
        assertNull(channelFor(mail("推广")))
        assertNull(channelFor(mail("账单", importance = 3)))
        // 分类是验证码但没解析出验证码
        assertNull(channelFor(mail("验证码")))
        // 已经在别的客户端读过
        assertNull(channelFor(mail("重要", unread = 0)))
    }

    @Test
    fun forgedDisplayNameShowsTheRealAddress() {
        val text = notificationTextOf(
            mail("可疑", senderName = "service@paypal.com", senderAddr = "notice@paypa1-secure.example"),
            NotifyChannel.Security,
        )
        assertEquals("notice@paypa1-secure.example · 本月账单已生成", text.text)
    }

    @Test
    fun normalDisplayNameIsKept() {
        assertEquals("云服务账单 · 本月账单已生成", notificationTextOf(mail("重要"), NotifyChannel.Important).text)
    }

    @Test
    fun emptyDisplayNameFallsBackToTheAddress() {
        val text = notificationTextOf(mail("重要", senderName = ""), NotifyChannel.Important)
        assertEquals("billing@cloud.example · 本月账单已生成", text.text)
    }

    @Test
    fun riskyMailShowsTheRealAddress() {
        val text = notificationTextOf(
            mail("安全", senderName = "PayPa1 安全中心", senderAddr = "notice@paypa1-secure.example", riskLevel = "high"),
            NotifyChannel.Security,
        )
        assertEquals("notice@paypa1-secure.example · 本月账单已生成", text.text)
    }

    @Test
    fun otpTitleCarriesTheCodeButThePublicTitleDoesNot() {
        val text = notificationTextOf(mail("验证码", otp = "482913"), NotifyChannel.Otp)
        assertEquals("验证码 482913", text.title)
        assertEquals("收到新验证码", text.publicTitle)
        assertFalse(text.publicTitle.contains("482913"))
    }

    @Test
    fun publicTitlesNeverCarryMailContent() {
        NotifyChannel.entries.forEach { channel ->
            val text = notificationTextOf(mail("重要", otp = "482913"), channel)
            assertFalse(text.publicTitle.contains("482913"))
            assertFalse(text.publicTitle.contains("云服务账单"))
            assertFalse(text.publicTitle.contains("本月账单已生成"))
        }
    }

    @Test
    fun notificationIdIsTheMessageId() {
        assertEquals(42, notificationIdOf(42))
        assertEquals(notificationIdOf(7), notificationIdOf(7))
    }
}
