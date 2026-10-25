# 邮件客户端认证路径分析

分析对象：

- `Microsoft Outlook Android 5.2606.0`（`com.microsoft.office.outlook`，APK 内含 12 个 dex）
- `QQ邮箱 7.1.6`（`com.tencent.androidqqmail`，APK 内含 7 个 dex）

方法：jadx 1.5.1 逐类反编译 + 自写 DEX 字符串/方法/调用索引扫描（`tools/dexscan.py`、`tools/dexwhere.py`、`tools/refs.py`），再用 IMAP `CAPABILITY` 实测验证协议事实。本文件只记录可互操作、可复现的静态协议事实，不含任何用户凭据、Cookie 或动态令牌。

## Gmail

调用链：`GoogleAuthDelegate` → `VerifyWebAuthResultValidation` → `RedeemAuthCodeGoogleDelegate.redeemAuthorizationCode` → `GoogleFetchProfileDelegate`。

授权端点为 `https://accounts.google.com/o/oauth2/v2/auth`，参数由 `helpers/AuthHelper.kt` 统一拼装：

| 参数 | 值 |
|---|---|
| `client_id` | `445112211283-sk04feuogpcjd3dq8eshrdnr4bpm1sfk.apps.googleusercontent.com` |
| `response_type` | `code` |
| `redirect_uri` | `https://olmoauth.outlook.com/api/googleoauthredir/outlook-oauth://<包名>/android/google/oauth2redirect` |
| `scope` | `profile email https://mail.google.com/ https://www.googleapis.com/auth/calendar https://www.googleapis.com/auth/contacts https://www.googleapis.com/auth/user.birthday.read https://www.googleapis.com/auth/drive.file` |
| `code_challenge` / `code_challenge_method` | PKCE / `S256` |
| `access_type` / `prompt` | `offline` / `consent` |
| 附加 | `login_hint=<邮箱>`，`enable_granular_consent=true`（受 `AuthParameters.enableGoogleGranularPermission` 控制） |

回调后 APK **不自己兑换令牌**：`RedeemAuthCodeGoogleDelegate` 调 `Auth.INSTANCE.getRedeemAuthCodeFromBackend().redeemAuthCodeFromBackend(...)`，把 authorization code 交给 `olmoauth.outlook.com` 兑换，随后用 `www.googleapis.com/oauth2/v2/userinfo` 校验身份。也就是说这个 Client ID、回调域和后端兑换是一组绑定 Outlook 的私有注册。

MailHub 采用等价但公开的标准流程：部署者自建 Google OAuth Web 客户端，直接调用 `https://oauth2.googleapis.com/token` 兑换，只申请 IMAP 需要的 `https://mail.google.com/`，拿到 access/refresh token 后立刻用 IMAP XOAUTH2 登录目标邮箱验证身份。代码会拒绝 Outlook 的私有 Client ID 和 `olmoauth.outlook.com` 回调。

## Outlook / Hotmail

APK 使用 Microsoft OneAuth/MSAL 账户栈（`com.microsoft.identity.*`、`AuthenticationType.OutlookMSA/Office365`），最终创建 token-based MSA/O365 账户。MailHub 用等价的公开 Microsoft OAuth v2 能力：授权码 + PKCE（配置自有 Web 客户端时）或设备码流程，申请 `https://outlook.office.com/IMAP.AccessAsUser.All offline_access`，再以 IMAP XOAUTH2 验证并同步。

## 网易 163 / 126 / yeah / 188 / VIP

入口：`ui/onboarding/login/AddAccountBaseFragment.buildNetEaseOauthLoginIntent` → `OAuthActivity`（`AuthenticationType.NetEase_IMAPDirect`）→ `AuthHelper.getAuthConfigBasedOnAuthType`（case 19 直接使用预置 `OAuthConfig`）→ `NetEaseAuthDelegate.performPostInteractiveAuthenticationTasks`。配置源为 `authspecificconfigs/NetEase.kt`。

固定字段：

| 字段 | 值 |
|---|---|
| `APP_ID` | `rjg1fubwqzie5unhx6` |
| `SCOPE` | `imap` |
| `REDIRECT_URL`（普通 WebView） | `https://olmoauth.outlook.com/api/neteaseoauthredir` |
| `REDIRECT_URL`（Custom Tabs） | `https://olmoauth.outlook.com/api/neteaseoauthredir/outlook-oauth://<包名>/android/netease/oauth2redirect` |

品牌表（`domainToConfigMap`，原文逐条提取）：

| 域名 | 授权入口 | IMAP | SMTP |
|---|---|---|---|
| `163.com` | `https://mail.163.com/fgw/mailsrv-oauth2-fapi/oauth2/authorize/entry` | `imapmail.163.com:993` SSL/TLS | `smtp.163.com:465` |
| `126.com` | `https://mail.126.com/fgw/...` | `imapmail.126.com:993` | `smtp.126.com:465` |
| `yeah.net` | `https://mail.yeah.net/fgw/...` | `imapmail.yeah.net:993` | `smtp.yeah.net:465` |
| `188.com` | `https://mail.188.com/fgw/...` | `imapmail.188.com:993` | `smtp.188.com:465` |
| `vip.163.com` | `https://vip.163.com/fgw/...` | `imapmail.vip.163.com:993` | `smtp.vip.163.com:465` |
| `vip.126.com` | `https://vip.126.com/fgw/...` | `imapmail.vip.126.com:993` | `smtp.vip.126.com:465` |
| `vip.188.com` | `https://vip.188.com/fgw/...` | APK 未单列，实际复用 `imapmail.188.com` | 同左 |

授权请求参数：`uid`（完整邮箱）、`appid`、`device_id`、`scope=imap`、`responseType=token`、`redirectUrl`、`state`。**注意是 implicit flow**：网易把结果放在跳转 URL 的 fragment 里，`NetEaseAuthDelegate` 用 `Uri.getQueryParameter` 读取 `uid`、`accessToken`（驼峰）、`state` 三项。

参数名取自 APK 的 `OAuthConfig` 拼装结果与回调读取代码；网易可能还要求合作方专有的附加参数，这部分只有拿到真实 appid 才能验证——用一个无效 appid 请求授权入口只会返回 `{"code":202,"errorMsg":"PARAMETER_ERROR"}`，不会回显缺哪个字段。

校验与落库顺序（`NetEaseAuthDelegate` 原文语义）：

1. `successUri` 必须存在；
2. `uid` 与本次登录的邮箱一致（忽略大小写）；
3. `state` 与下发的 state 完全一致；
4. `accessToken` 非空；
5. 用同一个 token 同时作为 access/refresh，有效期 `TimeUnit.DAYS.toMillis(3650)`；
6. `HxCreateAccountActorDelegate.createNetEaseAccount` → `CreateTokenBasedImapAccount` 建立专用 token IMAP 账户。

`classes11` 里还有一条明确字符串：`NetEase account doesn't support token refresh. Need interactive auth.`——网易不提供 refresh 端点，令牌过期只能重新走交互登录。

实测：`imapmail.163.com` / `imap.126.com` / `imap.yeah.net` 的 `CAPABILITY` 都返回 `AUTH=XOAUTH2`（同时有 `ID`、`SASL-IR`、`UIDPLUS`），因此拿到的 token 可以直接用于 IMAP XOAUTH2；网易仍要求认证后发送 RFC 2971 `ID`，否则 `SELECT` 会返回 `Unsafe Login`。

Outlook 使用私有 appid，回调受 `olmoauth.outlook.com` 控制，这是 Outlook 与网易的合作注册，不是可移植的公共客户端身份。

## QQ 邮箱

`QQ邮箱 7.1.6` 的 dex 中没有任何 `imap.qq.com` / `smtp.qq.com` 硬编码，登录走腾讯账号栈（`LoginQQMailFragment.doWxLogin` → `WXAccount.refreshToken()` → `refreshPwd` → `qimei36`）与自家 CGI/HTTP，不存在“QQ 邮箱 OAuth 换取 IMAP 令牌”的实现。APK 里出现的 `AUTH XOAUTH2`、`POP3 authenticate with OAuth`、`isOutlookOAuth`、`oauthUseEas` 属于它作为**其他服务商的邮件客户端**时的能力（例如添加 Outlook/Gmail 账户），不是 QQ 邮箱自身的 OAuth 授权。完整分析见 [QQMAIL_ANALYSIS.md](QQMAIL_ANALYSIS.md)。

实测 `imap.qq.com` 的 `CAPABILITY`：`IMAP4 IMAP4rev1 ID AUTH=PLAIN AUTH=LOGIN AUTH=XOAUTH2 NAMESPACE`——服务端确实支持 XOAUTH2，但腾讯没有面向个人 QQ 邮箱开放第三方 OAuth 应用注册，`QQ 互联`（`graph.qq.com`）的 token 也不携带 IMAP 权限。因此个人 QQ 邮箱唯一可互操作的 IMAP 凭据仍是“客户端授权码”。

结论：MailHub 对 QQ 保持授权码流程，不做伪造的 OAuth；如果将来接入腾讯企业邮（exmail）的 OAuth，需要腾讯侧的 client_id/secret 才能启用，届时按与 Gmail 相同的授权码 + PKCE 路径扩展。

## MailHub 的落地差异

| 项目 | APK | MailHub |
|---|---|---|
| Gmail 客户端 | Outlook 私有 Client ID + 微软后端兑换 | 部署者自有 Web 客户端，直接换 token |
| Outlook | OneAuth/MSAL | 公开 OAuth v2：授权码 + PKCE 或设备码 |
| 网易 | 私有 appid + `olmoauth` 回调 | 双通道：`NETEASE_CHANNEL=own` 用自有 appid；`=outlook` 直接复用 `rjg1fubwqzie5unhx6` |
| 网易回调 | fragment 里的 `accessToken` | 回调页读取 fragment 后 POST 到 `/api/oauth/<brand>/fragment`，同时兼容 `access_token`/`accessToken` |
| 网易令牌 | 同 token 兼作 refresh，3650 天 | 相同，但过期后强制重新交互登录 |
| QQ | QQ SSO + WebView | 客户端授权码 + RFC 2971 `ID` |

网易通道在代码里是显式开关：默认 `own` 会拒绝 `rjg1fubwqzie5unhx6` 与 `olmoauth.outlook.com`；设置 `NETEASE_CHANNEL=outlook` 后按 APK 原样复用该 appid，`redirectUrl` 仍指向本项目回调（`#uid&accessToken&state` 由捕获页读回），`NETEASE_REDIRECT_STYLE=olmoauth` 则还原中转地址用于协议对照。Google 的 Outlook 私有 Client ID 依旧始终拒绝——它绑定 Android 包名与微软后端兑换，Web 端拿不到结果。网易自家客户端（邮箱大师）走 URS 登录 + `imap.vip.*` 主机表，没有可移植的 refresh 端点，见 [MAIL_MASTER_ANALYSIS.md](MAIL_MASTER_ANALYSIS.md)。

## 复现

```bash
python tools/dexscan.py "Microsoft+Outlook_5.2606.0_APKPure (1).apk" "netease|imapmail\.|fgw/"
python tools/dexwhere.py tools/dex "NetEase|Netease"
python tools/refs.py tools/dex s:device_id s:redirectUrl
tools/jadx/bin/jadx --no-res -d out --single-class \
  com.microsoft.office.outlook.authspecificconfigs.NetEase tools/dex/classes10.dex
python tools/probe_imap.py    # IMAP CAPABILITY 实测
```
