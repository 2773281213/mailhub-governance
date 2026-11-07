package com.mailhub.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 可读的分支覆盖：策略引擎的分级、受保护判定和白名单。期望值同样来自 Python 原实现。 */
class PolicyTest {

    private val normal: Map<String, Any?> = mapOf("category" to "订阅")
    private val protectedBill: Map<String, Any?> = mapOf("category" to "账单")

    @Test
    fun everyActionHasATierAndUnknownDefaultsToConfirm() {
        assertTrue(Policy.ACTION_TIER.isNotEmpty())
        assertEquals(Policy.TIER_AUTO, Policy.ACTION_TIER["label"])
        assertEquals(Policy.TIER_GUARDED, Policy.ACTION_TIER["archive"])
        assertEquals(Policy.TIER_CONFIRM, Policy.ACTION_TIER["purge"])
        val unknown = Policy.evaluate("some_new_action", normal)
        assertFalse(unknown.allowed)
        assertEquals(Policy.TIER_CONFIRM, unknown.tier)
        assertEquals("动作「some_new_action」属 C 类（不可逆/对外），必须人工确认", unknown.reason)
    }

    @Test
    fun protectionComesBeforeConfirmation() {
        val blocked = Policy.evaluate("soft_delete", protectedBill, actor = "user", confirmed = true)
        assertFalse(blocked.allowed)
        assertEquals("分类「账单」属于受保护类别（交易/安全/重要），禁止自动删除", blocked.reason)
        // 单封二次确认才能绕过受保护判定
        assertTrue(Policy.evaluate("soft_delete", protectedBill, actor = "user", confirmed = true,
            overrideProtection = true).allowed)
    }

    @Test
    fun minorBillLosesOnlyCategoryProtection() {
        val minor: Map<String, Any?> = mapOf("category" to "账单", "bill_tier" to "一般")
        assertEquals(ProtectionResult(false, ""), Policy.isProtected(minor))
        assertTrue(Policy.isProtected(minor + mapOf("has_attach" to 1)).protected)
    }

    @Test
    fun guardedTierNeedsConfidenceOrUserRule() {
        val low = Policy.evaluate("archive", normal, actor = "ai", confidence = 0.125)
        assertFalse(low.allowed)
        assertEquals("AI 置信度 0.12 低于阈值 0.75，转人工审核", low.reason)
        assertTrue(Policy.evaluate("archive", normal, actor = "ai", confidence = 0.75).allowed)
        assertTrue(Policy.evaluate("archive", normal, actor = "system", userRule = true).allowed)
        assertEquals(
            "缺少用户规则且置信度不足，转人工审核",
            Policy.evaluate("archive", normal, actor = "system", confidence = 0.7).reason,
        )
    }

    @Test
    fun cleanWhitelist() {
        listOf("验证码", "订阅", "通知").forEach { assertTrue(it, Policy.filterCleanable(it).allowed) }
        val rejected = Policy.filterCleanable("账单")
        assertFalse(rejected.allowed)
        assertEquals(
            "分类「账单」不在可清理白名单内（仅允许 订阅、通知、验证码），请在收件箱中逐封确认删除",
            rejected.reason,
        )
        assertEquals(Rules.CATEGORIES.toSet(), Policy.CLEANABLE_CATEGORIES + Policy.PROTECTED_CATEGORIES +
            setOf("可疑", "其他"))
    }

    @Test
    fun nonNumericImportanceIsRejectedLikePythonsTypeError() {
        // Python：`(msg.get("importance") or 0) >= 4` 遇到字符串 "5" 抛 TypeError，
        // 裁决拿不到结果，删除动作因此被阻断。Kotlin 对应抛 IllegalArgumentException。
        val broken: Map<String, Any?> = mapOf("category" to "通知", "importance" to "5")
        assertThrows(IllegalArgumentException::class.java) { Policy.isProtected(broken) }
        assertThrows(IllegalArgumentException::class.java) {
            Policy.evaluate("soft_delete", broken, actor = "user", confirmed = true)
        }
        // 非破坏性动作不经过受保护判定，不抛错
        assertTrue(Policy.evaluate("label", broken, actor = "system").allowed)
        // 布尔值是 int 的子类（True == 1）：不抛错，也不构成「≥ 4」
        assertEquals(ProtectionResult(false, ""),
            Policy.isProtected(mapOf("category" to "通知", "importance" to true)))
        assertEquals(ProtectionResult(false, ""),
            Policy.isProtected(mapOf("category" to "通知", "importance" to false)))
    }
}
