# Outlook Android 邮箱认证路径分析

分析对象：Microsoft Outlook Android `5.2606.0`，包名 `com.microsoft.office.outlook`。本文件只记录可互操作的静态协议事实，不包含用户凭据、Cookie 或动态令牌。

## Gmail

调用链为 `GoogleAuthDelegate` -> `VerifyWebAuthResultValidation` -> `RedeemAuthCodeGoogleDelegate` -> `GoogleFetchProfileDelegate`。`AuthHelper` 使用 Google 官方授权端点 `https://accounts.google.com/o/oauth2/v2/auth`，请求 `response_type=code`、PKCE S256、`access_type=offline` 和 `prompt=consent`。APK 的 scope 为 `profile email https://mail.google.com/`，并额外申请 Calendar、Contacts、Birthday 和 Drive File 权限。

APK 内置 Google Client ID `445112211283-sk04feuogpcjd3dq8eshrdnr4bpm1sfk.apps.googleusercontent.com`。回调先进入 `https://olmoauth.outlook.com/api/googleoauthredir/`，再跳转到绑定 Outlook Android 包名的 `outlook-oauth://.../android/google/oauth2redirect`。`RedeemAuthCodeGoogleDelegate` 调用 `redeemAuthCodeFromBackend`，证明 authorization code 由 Microsoft 自有后端兑换。该 Client ID、Android 回调和后端信任关系绑定 Outlook，不能移植到 MailHub 网站。

MailHub 使用等价的公开标准流程，但采用部署者自己的 Google OAuth Web 客户端，直接调用 `https://oauth2.googleapis.com/token`，并只申请邮箱所需的 `https://mail.google.com/`。取得 access/refresh token 后以 IMAP XOAUTH2 验证真实邮箱身份，因此用户不需要 Gmail 应用专用密码。代码会拒绝 Outlook 私有 Client ID 和 `olmoauth.outlook.com` 回调。

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
