package com.mailhub.client.ui.screens.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 域名识别服务商与认证方式排序：
 * - 按 /api/oauth/providers 的 domains 识别，识别不到回退到自定义 IMAP；
 * - 认证方式一律按后端 methods 的顺序编号 01、02 …；
 * - 服务商的 unavailable / oauth_hint 原样显示。
 */
class ProviderDetectionTest {

    @Test
    fun detectsProviderByDomain() {
        val target = detectTarget("work@qq.com", FixtureProviders)

        assertEquals("qq", target?.providerKey)
        assertEquals("QQ 邮箱", target?.label)
        assertFalse(target?.isCustom ?: true)
    }

    @Test
    fun detectionIgnoresCaseAndSpaces() {
        val target = detectTarget("  Work@QQ.Com  ", FixtureProviders)

        assertEquals("qq", target?.providerKey)
        assertEquals("QQ 邮箱", target?.label)
    }

    @Test
    fun detectsSecondDomainOfSameProvider() {
        assertEquals("qq", detectTarget("me@foxmail.com", FixtureProviders)?.providerKey)
        assertEquals("gmail", detectTarget("me@googlemail.com", FixtureProviders)?.providerKey)
    }

    @Test
    fun fallsBackToCustomImap() {
        val target = detectTarget("hello@studio.example", FixtureProviders)

        assertEquals("custom", target?.providerKey)
        assertTrue(target?.isCustom ?: false)
        assertEquals("自定义 IMAP", target?.label)
        // 自定义 IMAP 的密码方式来自后端的 custom 项
        assertEquals(1, methodRows(target?.info).size)
    }

    @Test
    fun incompleteAddressShowsNoTarget() {
        assertNull(detectTarget("", FixtureProviders))
        assertNull(detectTarget("work", FixtureProviders))
        assertNull(detectTarget("work@", FixtureProviders))
        // 域名还没写完时不猜，避免一敲字就跳出「自定义 IMAP」
        assertNull(detectTarget("work@qq", FixtureProviders))
    }

    @Test
    fun methodsKeepBackendOrder() {
        val rows = methodRows(FixtureProviders["gmail"])

        assertEquals(listOf("01", "02"), rows.map { it.index })
        assertEquals(listOf("oauth_browser", "app_password"), rows.map { it.method.type })
        assertEquals("使用 Google 登录", rows[0].method.label)
    }

    @Test
    fun unavailableProviderKeepsItsNotice() {
        assertEquals(
            UnavailableOutlookInfo.unavailable,
            providerNotice(UnavailableOutlookInfo),
        )
        assertTrue(methodRows(UnavailableOutlookInfo).isEmpty())
    }

    @Test
    fun oauthHintShownWhenProviderIsUsable() {
        val info = FixtureProviders.getValue("netease")

        assertEquals(info.oauthHint, providerNotice(info))
        assertTrue(methodRows(info).isEmpty())
    }

    @Test
    fun usableProviderHasNoNotice() {
        assertEquals("", providerNotice(FixtureProviders["qq"]))
        assertEquals("", providerNotice(null))
    }

    @Test
    fun deviceCodeSplitsIntoTwoLines() {
        assertEquals(listOf("QZ7XK", "4JN9"), deviceCodeLines("QZ7XK4JN9"))
        assertEquals(listOf("ABCD"), deviceCodeLines(" ABCD "))
        assertEquals(emptyList<String>(), deviceCodeLines("   "))
    }
}
