# Outlook Android 邮箱认证路径分析

分析对象：Microsoft Outlook Android `5.2606.0`，包名 `com.microsoft.office.outlook`。本文件只记录可互操作的静态协议事实，不包含用户凭据、Cookie 或动态令牌。

## Gmail

调用链为 `GoogleAuthDelegate` -> `VerifyWebAuthResultValidation` -> `RedeemAuthCodeGoogleDelegate` -> `GoogleFetchProfileDelegate`。授权回调校验后以 authorization code 和 PKCE verifier 换取 access/refresh token，再取得 Google profile。MailHub 对应实现使用 Google 官方授权端点、`https://mail.google.com/` scope、PKCE S256、离线访问和 IMAP XOAUTH2。

Outlook APK 的 code 兑换实际经过 Microsoft 自有后端；该私有后端不适合复用。MailHub 改为使用部署者自己的 Google OAuth Web 客户端直接调用 Google token endpoint。

## Outlook / Hotmail

APK 使用 Microsoft OneAuth/MSAL 账户栈，最终创建 token-based MSA/O365 账户。MailHub 使用等价的公开 Microsoft OAuth v2 能力：授权码 + PKCE（配置自有 Web 客户端时）或设备码流程，申请 `https://outlook.office.com/IMAP.AccessAsUser.All offline_access`，再以 IMAP XOAUTH2 验证和同步。

## 网易 163 / 126 / yeah.net

入口由 `AuthUIHelper` 构造，认证类型为 `NetEase_IMAPDirect`：

- scope: `imap`
- response type: `token`
- 参数：`uid`、`appid`、`device_id`、`responseType`、`redirectUrl`、`state`
- 163 endpoint: `https://mail.163.com/fgw/mailsrv-oauth2-fapi/oauth2/authorize/entry`
- 126 endpoint: `https://mail.126.com/fgw/mailsrv-oauth2-fapi/oauth2/authorize/entry`
- yeah endpoint: `https://mail.yeah.net/fgw/mailsrv-oauth2-fapi/oauth2/authorize/entry`

`NetEaseAuthDelegate` 从成功回调读取 `uid`、`access_token`、`state`，逐项校验邮箱和 state，将同一个 token 同时作为 access/refresh token，并设置约十年有效期。随后 `HxCreateAccountActorDelegate.createNetEaseAccount` 调用 `CreateTokenBasedImapAccount` 创建专用 token-based IMAP 账户。

Outlook 使用私有 appid `rjg1fubwqzie5unhx6`，回调受 `olmoauth.outlook.com` 控制。这是 Outlook 与网易的合作注册，不是可移植的公共客户端身份。MailHub 因此只提供自有批准客户端的可配置实现，且默认关闭；未配置时继续使用网易客户端授权密码和 RFC 2971 `ID`。
