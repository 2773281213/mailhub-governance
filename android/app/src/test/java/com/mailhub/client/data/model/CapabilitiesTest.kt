package com.mailhub.client.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 能力表的推算规则：本机一律用固定的表；服务器只按 `/api/meta` 的 `sessions` 收窄。 */
class CapabilitiesTest {

    @Test
    fun localModeAlwaysUsesItsOwnTable() {
        assertEquals(Capabilities.Local, capabilitiesOf(isLocal = true, meta = null))
    }

    @Test
    fun localModeIgnoresTheSessionsFeatureInItsOwnMeta() {
        // 本机的 /api/meta 把 sessions 报成 true，不能凭它把「已登录设备」放出来
        val meta = Meta(features = mapOf("sessions" to true))

        assertFalse(capabilitiesOf(isLocal = true, meta = meta).deviceSessions)
        assertEquals(Capabilities.Local, capabilitiesOf(isLocal = true, meta = meta))
    }

    @Test
    fun serverWithoutMetaIsFullyCapable() {
        assertEquals(Capabilities.Server, capabilitiesOf(isLocal = false, meta = null))
    }

    @Test
    fun oldServerWithoutFeaturesIsFullyCapable() {
        // 旧服务器没有 features，视为全部支持
        val capabilities = capabilitiesOf(isLocal = false, meta = Meta(features = emptyMap()))

        assertEquals(Capabilities.Server, capabilities)
        assertTrue(capabilities.deviceSessions)
        assertTrue(capabilities.aiSettings)
        assertTrue(capabilities.pushSettings)
        assertTrue(capabilities.cleanupSettings)
        assertTrue(capabilities.adminPassword)
    }

    @Test
    fun serverOnlyNarrowsDeviceSessions() {
        val capabilities = capabilitiesOf(
            isLocal = false,
            meta = Meta(features = mapOf("sessions" to false)),
        )

        assertEquals(Capabilities.Server.copy(deviceSessions = false), capabilities)
        assertFalse(capabilities.deviceSessions)
        // 其余四项服务器还没有对应的键，保持可用
        assertTrue(capabilities.aiSettings)
        assertTrue(capabilities.pushSettings)
        assertTrue(capabilities.cleanupSettings)
        assertTrue(capabilities.adminPassword)
    }
}
