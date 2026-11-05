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
}
