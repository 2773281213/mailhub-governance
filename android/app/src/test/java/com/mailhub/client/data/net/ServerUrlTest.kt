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
}
