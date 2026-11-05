package com.mailhub.client.data

import com.mailhub.client.data.model.LoginResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 令牌不进 toString：data class 默认的实现会把 token 原样打出来，
 * 一旦有人把对象写进日志或崩溃报告，令牌就泄露了。
 */
class TokenRedactionTest {

    private val token = "tok-8f2c41d9e7"

    @Test
    fun loginResponseHidesTheToken() {
        val expires = 1_700_000_000.0
        val text = LoginResponse(
            ok = true,
            token = token,
            sessionId = "sid-1",
            expiresTs = expires,
        ).toString()

        assertFalse(text.contains(token))
        assertTrue(text.contains("***"))
        // 其他字段照常显示
        assertTrue(text.contains("sid-1"))
        assertTrue(text.contains("expiresTs=$expires"))
    }

    @Test
    fun sessionHidesTheToken() {
        val text = testSession(
            serverUrl = "https://mail.example.com",
            token = token,
            sessionId = "sid-1",
        ).toString()

        assertFalse(text.contains(token))
        assertTrue(text.contains("***"))
        // 其他字段照常显示
        assertTrue(text.contains("https://mail.example.com"))
        assertTrue(text.contains("sid-1"))
        assertTrue(text.contains("Pixel 8"))
    }

    @Test
    fun emptyTokenStaysEmpty() {
        val login = LoginResponse(token = "").toString()
        val session = testSession(serverUrl = "https://mail.example.com", token = "").toString()

        assertFalse(login.contains("***"))
        assertFalse(session.contains("***"))
        assertTrue(login.contains("token=,"))
        assertTrue(session.contains("token=,"))
    }
}
