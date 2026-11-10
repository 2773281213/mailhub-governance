package com.mailhub.client.data.local

import com.mailhub.client.data.session.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 本机会话的标识：只看主机名，界面分支（L10b）与拦截器共用同一套判断。 */
class LocalModeTest {

    @Test
    fun localServerUrlPointsAtTheSameHostAsTheCoreApi() {
        // :core 的 LocalApi.LOCAL_BASE_URL 是 internal 常量，这里按约定钉住同一个主机名
        assertEquals(LOCAL_MODE_HOST, LOCAL_SERVER_URL.toHttpUrl().host)
        assertEquals("local.mailhub.invalid", LOCAL_MODE_HOST)
    }

    @Test
    fun onlyTheHostNameMatters() {
        assertTrue(LOCAL_SERVER_URL.toHttpUrl().isLocalMode())
        assertTrue("带端口也算", "https://local.mailhub.invalid:1234/".toHttpUrl().isLocalMode())
        assertTrue("http 同样算本机", "http://local.mailhub.invalid".toHttpUrl().isLocalMode())
        assertTrue("主机名不区分大小写", "https://LOCAL.MailHub.Invalid/".toHttpUrl().isLocalMode())
        assertFalse("同名子域不是本机", "https://x.local.mailhub.invalid/".toHttpUrl().isLocalMode())
        assertFalse("别的服务器", "https://mail.example.com/".toHttpUrl().isLocalMode())
    }

    @Test
    fun sessionIsLocalOnlyWhenItsUrlIs() {
        assertTrue(sessionOf(LOCAL_SERVER_URL).isLocal)
        assertFalse(sessionOf("https://mail.example.com/").isLocal)
        // 存储里的地址被改坏时按服务器模式处理，不能因为解析失败就进本机分支
        assertFalse(sessionOf("不是地址").isLocal)
    }

    private fun sessionOf(serverUrl: String): Session = Session(
        serverUrl = serverUrl,
        token = "tok",
        sessionId = "sid",
        expiresTs = 0.0,
        deviceName = "Pixel 8",
    )
}
