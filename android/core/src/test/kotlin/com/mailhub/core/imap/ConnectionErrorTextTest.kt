package com.mailhub.core.imap

import jakarta.mail.MessagingException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [connectionErrorText] 的分支对照 `mail_client.connection_error_text`，重点覆盖审核指出的两处：
 *
 * - `SocketException` 不能一律说成「连接被中断」：只有 reset / aborted / broken pipe 这类才是，
 *   其余是普通 IO 失败（Python 的 `OSError`）；
 * - `ENETUNREACH`（网络不可达）不能报成「邮箱服务器拒绝连接」：Python 那边落在 `OSError`
 *   →「无法连接邮箱服务器」。
 *
 * 这个文件放在 `src/test`，纯逻辑、不碰网络，手机上也能回放。
 */
class ConnectionErrorTextTest {

    @Test
    fun unreachableIsNotReportedAsConnectionRefused() {
        assertEquals(
            "Android 上 ENETUNREACH 也可能包在 ConnectException 里，仍然不是「拒绝连接」",
            "无法连接邮箱服务器",
            connectionErrorText(ConnectException("failed to connect: ENETUNREACH (Network is unreachable)")),
        )
        assertEquals(
            "普通 SocketException 文本里的 unreachable 也是「无法连接」",
            "无法连接邮箱服务器",
            connectionErrorText(SocketException("Network is unreachable")),
        )
    }

    @Test
    fun socketExceptionsAreMappedByTheirText() {
        assertEquals(
            "只有 reset / aborted 这类才算「连接被中断」",
            "连接被邮箱服务器中断，请稍后重试",
            connectionErrorText(SocketException("Connection reset")),
        )
        assertEquals(
            "普通 SocketException 是 IO 失败，不是「连接被中断」",
            "无法连接邮箱服务器",
            connectionErrorText(SocketException("unexpected socket failure")),
        )
        assertEquals(
            "读超时优先按超时处理",
            "连接邮箱服务器超时",
            connectionErrorText(SocketTimeoutException("Read timed out")),
        )
        assertEquals(
            "域名解析失败",
            "找不到邮箱服务器，请检查服务器地址",
            connectionErrorText(UnknownHostException("imap.example.com")),
        )
    }

    /**
     * Angus 把读超时 / 连接异常换成 `* BYE Jakarta Mail Exception: <原始异常>`，类型信息只剩文本，
     * 必须按文本还原成 Python 那边会落进的分支。
     */
    @Test
    fun synthesizedByeTextRestoresOriginalBranch() {
        assertEquals(
            "连接邮箱服务器超时",
            connectionErrorText(
                MessagingException(
                    "* BYE Jakarta Mail Exception: java.net.SocketTimeoutException: Read timed out",
                ),
            ),
        )
        assertEquals(
            "无法连接邮箱服务器",
            connectionErrorText(
                MessagingException(
                    "* BYE Jakarta Mail Exception: java.net.ConnectException: " +
                        "ENETUNREACH (Network is unreachable)",
                ),
            ),
        )
        assertEquals(
            "连接被邮箱服务器中断，请稍后重试",
            connectionErrorText(
                MessagingException("* BYE Jakarta Mail Exception: java.net.SocketException: Connection reset"),
            ),
        )
    }
}
