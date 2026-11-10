package com.mailhub.client.ui.screens.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/** 退出确认文案：本机模式说明数据保留，服务器模式保持原来的「需要重新输入管理密码」。 */
class LogoutConfirmTextsTest {

    @Test
    fun localModeExplainsThatDataIsKept() {
        val texts = logoutConfirmTexts(localMode = true)

        assertEquals("退出本机模式？", texts.title)
        assertEquals("本机的邮件和账户都会保留，再次选择「本机独立使用」就能回来。", texts.message)
        assertEquals("退出", texts.confirmText)
    }

    @Test
    fun serverModeKeepsTheOriginalText() {
        val texts = logoutConfirmTexts(localMode = false)

        assertEquals("退出登录？", texts.title)
        assertEquals("退出后需要重新输入管理密码才能连回这台服务器。", texts.message)
        assertEquals("退出", texts.confirmText)
    }
}
