package com.mailhub.client.ui.screens.security

import com.mailhub.client.data.security.AuditEntry
import com.mailhub.client.data.security.DeviceSession
import com.mailhub.client.data.security.ReviewItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** 审计行的三种状态、风险观察行的真实地址、设备行与退出所有设备的文案。 */
class SecurityStateTest {

    @Test
    fun auditRowStatuses() {
        val undoable = AuditEntry(action = "soft_delete", allowed = true, reversible = true, undone = false)
        val undone = undoable.copy(undone = true)
        val blocked = AuditEntry(action = "purge", allowed = false, reason = "受保护邮件")

        assertEquals(AuditStatus.Undoable, auditStatusOf(undoable))
        assertEquals(AuditStatus.Undone, auditStatusOf(undone))
        assertEquals(AuditStatus.Blocked, auditStatusOf(blocked))
        assertEquals(AuditStatus.Final, auditStatusOf(AuditEntry(action = "purge", allowed = true)))
        assertEquals("已拦截 · 受保护邮件", auditReasonText(blocked))
        assertEquals("已拦截", auditReasonText(blocked.copy(reason = "")))
        assertEquals("批量删除", auditReasonText(undoable.copy(reason = "批量删除")))
    }

    @Test
    fun auditActionNamesAreChineseAndUnknownOnesStayVisible() {
        assertEquals("移入回收站 · 3 封", auditActionText(AuditEntry(action = "soft_delete", targetCount = 3)))
        assertEquals("退出所有设备", auditActionText(AuditEntry(action = "revoke_sessions")))
        assertEquals("future_action", auditActionText(AuditEntry(action = "future_action")))
        assertEquals("—", auditTierText(AuditEntry(tier = "")))
    }

    @Test
    fun reviewRowShowsTheRealAddress() {
        val forged = ReviewItem(
            id = 1,
            subject = "账户存在异常登录",
            senderName = "service@paypal.com",
            senderAddr = "notice@paypa1-secure.example",
            category = "可疑",
            riskReasons = "显示名伪装",
        )
        // 显示名里没有地址、看起来很正常的也一样：风险观察里的显示名都不可信
        val plain = forged.copy(senderName = "PayPal 安全中心", riskLevel = "none")

        assertEquals("notice@paypa1-secure.example", reviewRowOf(forged).sender)
        assertEquals("notice@paypa1-secure.example", reviewRowOf(plain).sender)
        assertEquals("显示名伪装", reviewRowOf(forged).snippet)
        // 不显示分类，发件地址才能占满第 5–12 栏，域名不会被截掉
        assertEquals("", reviewRowOf(forged).categoryName)
    }

    @Test
    fun deviceTexts() {
        val current = DeviceSession(id = "a", label = "Pixel 8", client = "android", current = true)
        val web = DeviceSession(id = "b", label = "", client = "web", createdTs = 10.0)

        assertEquals("Pixel 8", deviceName(current))
        assertEquals("Android · 本机", deviceMetaText(current))
        assertEquals("网页", deviceName(web))
        // 设备名已经是「网页」，下一行不再重复
        assertEquals("未报告设备名", deviceMetaText(web))
        assertEquals("本机", deviceMetaText(web.copy(current = true)))
        assertEquals(10.0, deviceSeenTs(web), 0.0)
    }

    @Test
    fun revokeAllCountsOnlyOtherDevices() {
        assertEquals("已退出 2 台设备", revokeAllText(revoked = 3, replaced = true))
        assertEquals("没有其他已登录的设备", revokeAllText(revoked = 1, replaced = true))
        assertEquals("已退出所有设备，请重新登录", revokeAllText(revoked = 3, replaced = false))
    }
}
