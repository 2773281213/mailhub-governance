# 企业微信邮件模块分析（5.0.13.84802）

分析对象：`WeCom_android_5.0.13.84802_arm64_100038.apk`（`com.tencent.wework`，21 个 dex，521 MB）。

目的：确认企业微信的邮件登录是否提供可移植到 MailHub 的 OAuth（特别是腾讯企业邮 exmail 与 QQ 邮箱）。

方法：`tools/dexscan.py` 全量字符串扫描 + `tools/dexwhere.py` 成员索引 + `tools/refs.py` 字符串引用定位 + jadx 1.5.1 单类反编译。

## 模块结构

| 层 | 证据 | 说明 |
|---|---|---|
| 邮件插件 UI/桥 | `com.tencent.mailplugin.cpp.*`、`com.tencent.mailplugin.bridge.protocol.model.*` | Kotlin + Compose 写的邮件插件，通过 `MailCore.logToCpp` 与 native 通信 |
| 邮件 SDK | `com.tencent.qqmail.*`（账号、IMAP、日历、附件） | 直接把 QQ 邮箱的邮件 SDK 编进来了 |
| 企业微信自身 | `com.tencent.wework.foundation.logic.MailService` | JNI 服务，`nativeSyncMails` 等方法在 `libwework` 里 |

企业邮/QQ 邮箱的实际登录都在 native 侧完成，Java 层只负责收集参数、展示页面。

## 企业邮（exmail）

- 包：`com.tencent.qqmail.account.login.exmail.net.model.{LoginRsp, ExmailTicketData, ProtocolSyncMail, CloudSyncMail, ExmailAlias}`。
- `ExmailTicketData` 的字段是 `tkspwd`、`alias`、`forbidden`、`errorcode`、`mxerror`——登录结果是一张**私有 ticket（tkspwd）**，配合 `ProtocolSyncMail`（EAS/EWS 风格协议）同步邮件，不是 OAuth2 的 access/refresh token。
- 反编译 `LoginRsp`/`ProtocolSyncMail` 里没有 `access_token`/`refresh_token`/`expires_in` 之类的 OAuth 字段。
- 唯一的服务器地址证据是 `imap.exmail.qq.com`（classes18，`EMLPROTO_IMAP4` 分支），即企业邮的 IMAP 入口。
- Web 侧入口 `exmail.qq.com/qy_mng_logic/wemail/...`、`https://exmail.qq.com/freego`、`exmail.qq.com/wemailnative/open` 都是企业微信身份下的 WebView 授权页，没有面向第三方的 client_id 注册。

结论：企业邮在企业微信里走私有 ticket 协议；对普通第三方客户端，唯一的 IMAP 凭据仍是**客户端专用密码**。

## QQ 邮箱

- `ProtocolLoginViewModel$qqOauthLogin` 通过腾讯 `IUiListener`（`com.tencent.tauth`，即 QQ 互联 `https://openmobile.qq.com/oauth2.0/m_authorize`）拿到 `access_token`，然后 `loginParam.set_qqmail_oauth_login(true)` 把它交给 native 登录，日志标签是 `is_qqmail_oauth_login` / `kQQOauthLogin`。
- 这条链是“应用内 QQ SSO”：token 属于腾讯自家 App 的 QQ 互联身份，只对腾讯邮件协议有效；它不是 QQ 邮箱面向第三方的 OAuth2 注册，也不能直接拿去 `imap.qq.com` 做 XOAUTH2。
- 相关字符串还有 `wx.mail.qq.com/oauth/approvalnativeapp?response=code=`，属于微信/企业微信侧的 native app 授权回调，同样不可移植。

## 外部邮箱（Gmail / Outlook）

`classes16` 的 IMAP 栈里有 `XOAUTH2`、`AUTH XOAUTH2`，`classes13` 有 `outlookOauthEmail`、`qqmailOauthAccessToken`——这是 QQ 邮箱/企业微信作为**其他服务商客户端**时的能力，与 Outlook APK 的做法一致：第三方 OAuth 拿 token，再 XOAUTH2 登录 IMAP。MailHub 已经实现了同一条公开标准流程。

## 可移植到 MailHub 的部分

| 项目 | 处理 |
|---|---|
| `imap.exmail.qq.com:993` | 已新增 `exmail` 服务商预设，认证方式为客户端专用密码，企业自有域名需手动选择（无法按域名识别） |
| 企业邮 OAuth | 不存在可移植的公开注册，保持专用密码 |
| QQ 邮箱 OAuth | 应用内 QQ SSO，不对外，保持授权码 |
| 第三方邮箱 XOAUTH2 | MailHub 已用公开 OAuth 实现（Gmail/Outlook） |

## 复现

```bash
python tools/dexscan.py WeCom_*.apk "exmail|XOAUTH2|imap\.exmail"
python tools/refs.py tools/dex_wecom s:imap.exmail.qq.com s:XOAUTH2
tools/jadx/bin/jadx --no-res -d out --single-class \
  com.tencent.qqmail.account.login.exmail.net.model.ExmailTicketData \
  tools/dex_wecom/classes18.dex
```
