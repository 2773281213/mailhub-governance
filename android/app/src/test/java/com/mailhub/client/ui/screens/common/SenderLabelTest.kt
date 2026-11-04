package com.mailhub.client.ui.screens.common

import com.mailhub.client.data.model.MessageSummary
import com.mailhub.client.ui.screens.inbox.mailRowOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 发件人显示规则（安全）：只显示显示名等于替攻击者把任意字符串摆在发件人位置，
 * 显示名里嵌着别的地址时必须换成真实地址。
 *
 * 真机验收时一封钓鱼邮件的显示名是 `service@paypal.com`，真实地址却是
 * `notice@paypa1-secure.example`，收件箱里看到的却是前者。
 */
class SenderLabelTest {

    private val forgedAddress = "service@paypal.com"
    private val realAddress = "notice@paypa1-secure.example"

    @Test
    fun forgedDisplayNameIsReplacedByTheRealAddress() {
        assertEquals(realAddress, senderLabel(forgedAddress, realAddress, isRisky = false))
    }

    @Test
    fun normalDisplayNameIsKept() {
        assertEquals("云服务账单", senderLabel("云服务账单", "billing@cloud.example", isRisky = false))
    }

    @Test
    fun emptyDisplayNameFallsBackToTheRealAddress() {
        assertEquals(realAddress, senderLabel("", realAddress, isRisky = false))
        assertEquals(realAddress, senderLabel("   ", realAddress, isRisky = false))
    }

    @Test
    fun riskyMailAlwaysShowsTheRealAddress() {
        assertEquals(realAddress, senderLabel("PayPa1 安全中心", realAddress, isRisky = true))
        // 显示名和真实地址相同的正常邮件，风险判定下也只是再写一遍地址
        assertEquals(realAddress, senderLabel(realAddress, realAddress, isRisky = true))
    }

    @Test
    fun displayNameEqualToTheAddressIsNotTreatedAsForged() {
        assertEquals("Work@Example.com", senderLabel("Work@Example.com", "work@example.com", isRisky = false))
    }

    @Test
    fun inboxRowUsesTheLabelNotTheRawDisplayName() {
        val riskyMail = MessageSummary(
            id = 1,
            subject = "账户存在异常登录，请立即验证你的身份",
            senderName = forgedAddress,
            senderAddr = realAddress,
            riskLevel = "high",
        )
        val normalMail = MessageSummary(
            id = 2,
            subject = "本月账单已生成",
            senderName = "云服务账单",
            senderAddr = "billing@cloud.example",
            riskLevel = "none",
        )

        assertEquals(realAddress, mailRowOf(riskyMail, emptyList()).sender)
        assertEquals("云服务账单", mailRowOf(normalMail, emptyList()).sender)
    }
}
