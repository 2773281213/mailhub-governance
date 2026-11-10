package com.mailhub.client.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    @Test
    fun addsHttpsWhenSchemeMissing() {
        val url = ServerUrl.parse("mail.example.com", allowCleartextDebugHosts = false).getOrThrow()
        assertEquals("https://mail.example.com/", url.toString())
    }

    @Test
    fun rejectsCleartextInReleaseRules() {
        val result = ServerUrl.parse("http://mail.example.com", allowCleartextDebugHosts = false)
        assertTrue(result.isFailure)
    }

    @Test
    fun acceptsDebugLoopbackHost() {
        val url = ServerUrl.parse("http://10.0.2.2:8018", allowCleartextDebugHosts = true).getOrThrow()
        assertEquals("http://10.0.2.2:8018/", url.toString())
    }

    @Test
    fun keepsReverseProxyPath() {
        val url = ServerUrl.parse("https://host/mailhub/", allowCleartextDebugHosts = false).getOrThrow()
        assertEquals("/mailhub", url.encodedPath)
        assertEquals("https://host/mailhub", url.toString())
    }

    @Test
    fun rejectsBlankInput() {
        assertTrue(ServerUrl.parse("   ", allowCleartextDebugHosts = false).isFailure)
    }

    @Test
    fun schemeIsCaseInsensitive() {
        val url = ServerUrl.parse("HTTPS://Mail.Example.com", allowCleartextDebugHosts = false).getOrThrow()
        assertEquals("https", url.scheme)
        assertEquals("mail.example.com", url.host)
    }

    @Test
    fun rejectsUserInfo() {
        assertTrue(ServerUrl.parse("https://user:pw@host", allowCleartextDebugHosts = false).isFailure)
        assertTrue(ServerUrl.parse("https://user@host", allowCleartextDebugHosts = false).isFailure)
    }

    @Test
    fun rejectsQueryAndFragment() {
        assertTrue(ServerUrl.parse("https://host/?a=1", allowCleartextDebugHosts = false).isFailure)
        assertTrue(ServerUrl.parse("https://host/#frag", allowCleartextDebugHosts = false).isFailure)
    }

    // ---------------- 保留域名 .invalid（LOCAL_MODE.md 3.1） ----------------

    @Test
    fun rejectsReservedInvalidHost() {
        val result = ServerUrl.parse("https://mail.example.invalid", allowCleartextDebugHosts = false)
        assertTrue(result.isFailure)
        assertEquals("服务器地址不能使用保留域名", result.exceptionOrNull()?.message)
    }

    @Test
    fun rejectsTheLocalReservedAddress() {
        // 本机会话的地址不能被手填进「连接服务器」表单
        assertTrue(
            ServerUrl.parse("https://local.mailhub.invalid/", allowCleartextDebugHosts = false).isFailure,
        )
    }

    @Test
    fun rejectsBareInvalidHost() {
        assertTrue(ServerUrl.parse("invalid", allowCleartextDebugHosts = false).isFailure)
        assertTrue(ServerUrl.parse("https://invalid/", allowCleartextDebugHosts = false).isFailure)
    }

    @Test
    fun reservedHostCheckIgnoresCaseAndTrailingDot() {
        assertTrue(ServerUrl.parse("https://Mail.Example.INVALID/", allowCleartextDebugHosts = false).isFailure)
        assertEquals(
            "服务器地址不能使用保留域名",
            ServerUrl.parse("https://host.invalid./", allowCleartextDebugHosts = false)
                .exceptionOrNull()?.message,
        )
        assertEquals(
            "服务器地址不能使用保留域名",
            ServerUrl.parse("https://INVALID./", allowCleartextDebugHosts = false)
                .exceptionOrNull()?.message,
        )
        // 只是包含 invalid 字样的正常域名不受影响
        val ok = ServerUrl.parse("https://invalid.example.com", allowCleartextDebugHosts = false)
        assertTrue("invalid 出现在前缀里不算保留域名", ok.isSuccess)
    }
}
