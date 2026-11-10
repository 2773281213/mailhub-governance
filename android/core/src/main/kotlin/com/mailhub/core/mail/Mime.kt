package com.mailhub.core.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.util.Properties

/**
 * 邮件原文解析的最小入口（L1 只负责把字节变成 [MimeMessage]）。
 *
 * 正文提取、附件落盘、分类和验证码提取都属于 L5，这里一概不做。
 * 实现用模块依赖里的 Eclipse Angus Mail（Jakarta Mail 实现）：只要 [Session] 不设置
 * 任何 Store/Transport，构造 [MimeMessage] 不会发起任何网络请求。
 */
object Mime {
    /** RFC 2231 参数解码开关（Jakarta Mail 默认是 true）。 */
    private const val DECODE_PARAMETERS = "mail.mime.decodeparameters"

    /** 附件名里的 RFC 2047 编码字解码开关（Jakarta Mail 默认是 false）。 */
    private const val DECODE_FILENAME = "mail.mime.decodefilename"

    init {
        // 关键偏差：这两个开关在 Jakarta Mail 里是 **系统属性**，不是 Session 属性——
        // ParameterList 和 MimeBodyPart/IMAPBodyPart 用 MimeUtility.getBooleanSystemProperty(...)
        // 读进 static final 字段，只在类初始化时读一次，Session 里设同名属性对它们无效
        // （实测：只设 Session 属性时，RFC 2231 的 filename* 能解码，RFC 2047 的 filename 不能）。
        // 所以在写进 Session 之外，也在本 object 初始化时把它们镜像到系统属性；
        // 只补默认值、不覆盖调用方已经显式设过的值。
        // Mime 必须先于 MimeBodyPart 被加载，本模块里所有 MIME 解析都从 [parseMime] 进入，
        // 因此这里是安全的。
        enableSystemProperty(DECODE_PARAMETERS)
        enableSystemProperty(DECODE_FILENAME)
    }

    /**
     * 把一封邮件的原始字节 [raw] 解析成 [MimeMessage]。
     *
     * 两个开关是国内邮件的实际需要：
     * - `mail.mime.decodeparameters=true`（Jakarta Mail 默认也是 true，这里显式写出）：
     *   按 RFC 2231 解码 `filename*=UTF-8''%E8%B4%A6%E5%8D%95.pdf` 这类参数；
     * - `mail.mime.decodefilename=true`（默认是 false）：把附件的 `filename`
     *   参数里出现的 RFC 2047 编码字（`=?UTF-8?B?...?=`）也解码成中文，
     *   很多国内邮件客户端的附件名就是这么写的。
     *
     * @throws jakarta.mail.MessagingException 原文不是合法 MIME 时抛出。
     */
    fun parseMime(raw: ByteArray): MimeMessage {
        val props = Properties()
        props[DECODE_PARAMETERS] = "true"
        props[DECODE_FILENAME] = "true"
        // 不带 Authenticator、不配置任何 Store：这是个纯解析用的、不连网的会话。
        val session = Session.getInstance(props)
        return MimeMessage(session, ByteArrayInputStream(raw))
    }

    /** 只在该系统属性还没被设置时写入 true，避免覆盖调用方的显式选择。 */
    private fun enableSystemProperty(key: String) {
        synchronized(Mime::class.java) {
            if (System.getProperty(key) == null) {
                System.setProperty(key, "true")
            }
        }
    }
}
