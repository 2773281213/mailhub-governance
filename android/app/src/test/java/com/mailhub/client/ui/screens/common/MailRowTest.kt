package com.mailhub.client.ui.screens.common

import com.mailhub.client.data.model.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享邮件行里的纯函数：分类编号映射、红色小方块规则、时间格式、账户编号。
 * 这些规则决定列表行和筛选行的显示，全部可以直接断言，不需要渲染。
 */
class MailRowTest {

    /** 与 /api/meta 的 display_categories 下发顺序一致。 */
    private val categories = listOf(
        "重要账单", "一般账单", "验证码", "重要", "安全", "可疑", "订阅", "通知",
    )

    @Test
    fun displayCategoryNumberFollowsMetaOrder() {
        assertEquals(1, displayCategoryNumber(categories, "重要账单"))
        assertEquals(2, displayCategoryNumber(categories, "一般账单"))
        assertEquals(4, displayCategoryNumber(categories, "重要"))
        assertEquals(8, displayCategoryNumber(categories, "通知"))
        // 分类不在 /api/meta 的列表里就没有编号
        assertEquals(0, displayCategoryNumber(categories, "不存在的分类"))
        assertEquals(0, displayCategoryNumber(categories, ""))
        assertEquals(0, displayCategoryNumber(emptyList(), "重要"))
    }

    @Test
    fun categoryLabelIsTwoDigitNumberPlusName() {
        assertEquals("01 重要账单", categoryLabel(1, "重要账单"))
        assertEquals("06 可疑", categoryLabel(6, "可疑"))
        assertEquals("12 通知", categoryLabel(12, "通知"))
        // 没有编号时只显示名称，不补 00
        assertEquals("可疑", categoryLabel(0, "可疑"))
        // 名称为空时整段隐藏
        assertEquals("", categoryLabel(3, ""))
    }

    @Test
    fun onlyImportantSecurityAndSuspiciousCarryTheRedMark() {
        assertTrue(categoryHasMark("重要"))
        assertTrue(categoryHasMark("安全"))
        assertTrue(categoryHasMark("可疑"))
        // 账单展开后的两个展示分类不算「重要」这一档
        assertFalse(categoryHasMark("重要账单"))
        assertFalse(categoryHasMark("一般账单"))
        assertFalse(categoryHasMark("验证码"))
        assertFalse(categoryHasMark("订阅"))
        assertFalse(categoryHasMark("通知"))
        assertFalse(categoryHasMark(""))
    }

    @Test
    fun stampShowsClockForTodayAndDateForOlderDays() {
        // 2026-11-04 08:54 UTC，上海是 16:54
        val now = 1793782440.0

        assertEquals("16:54", mailStampText(now, "Asia/Shanghai", now))
        assertEquals("16:44", mailStampText(now - 600, "Asia/Shanghai", now))
        // 按服务器时区换算：同一时刻在 UTC 是 08:54
        assertEquals("08:54", mailStampText(now, "UTC", now))
        // 不是同一天就只显示日期
        assertEquals("11-01", mailStampText(now - 3 * 86400, "Asia/Shanghai", now))
        // 缺值
        assertEquals("—", mailStampText(0.0, "Asia/Shanghai", now))
    }

    @Test
    fun stampFallsBackToSystemZoneForUnknownZone() {
        val now = 1793782440.0
        // 时区名不可用时退回设备时区，但不能抛异常、也不能变成占位符
        assertTrue(mailStampText(now, "Not/AZone", now).matches(Regex("""\d{2}:\d{2}|\d{2}-\d{2}""")))
    }

    @Test
    fun accountLabelCountsFromTheAccountListOrder() {
        val accounts = listOf(
            Account(id = 7, name = "工作邮箱"),
            Account(id = 3, name = "私有邮箱"),
        )

        assertEquals("A1", accountLabel(accounts, 7))
        assertEquals("A2", accountLabel(accounts, 3))
        assertEquals("", accountLabel(accounts, 99))
        assertEquals("", accountLabel(emptyList(), 7))
    }
}
