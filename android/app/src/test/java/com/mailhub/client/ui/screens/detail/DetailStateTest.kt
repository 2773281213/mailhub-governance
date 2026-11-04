package com.mailhub.client.ui.screens.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页的纯规则：风险邮件一律不显示验证码区块。
 * 钓鱼邮件里的「验证码」是攻击者写进正文的，做成大号数字加复制按钮等于帮着骗人。
 */
class DetailStateTest {

    @Test
    fun riskyMailNeverShowsTheOtpBlock() {
        // 即使 otpCode 不为空，风险邮件也不显示（服务端已经清零，客户端再挡一层）
        assertFalse(showsOtpBlock(isRisky = true, otpCode = "482913"))
        assertFalse(showsOtpBlock(isRisky = true, otpCode = ""))
    }

    @Test
    fun safeMailShowsTheOtpBlockOnlyWhenThereIsACode() {
        assertTrue(showsOtpBlock(isRisky = false, otpCode = "482913"))
        assertFalse(showsOtpBlock(isRisky = false, otpCode = ""))
        assertFalse(showsOtpBlock(isRisky = false, otpCode = "   "))
    }

    @Test
    fun riskyScreenshotFixtureKeepsTheCodeSoTheRuleIsVisible() {
        val message = riskyDetail()

        // 风险截图（detail_risk_*）的示例数据保留 otpCode，用来证明它确实被隐藏了
        assertTrue("风险示例数据要保留验证码", message.otpCode.isNotBlank())
        assertTrue(message.isRisky)
        assertFalse(showsOtpBlock(message.isRisky, message.otpCode))
    }
}
