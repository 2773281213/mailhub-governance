# QQ 邮箱 App 分析（7.1.6）

分析对象：`QQmail_7.1.6_APKPure.apk`（`com.tencent.androidqqmail`，114 MB，7 个 dex，未加壳，可直接反编译）。

目的：确认 QQ 邮箱自身是否提供可移植的 OAuth（IMAP 用），以及 App 内“授权码”机制的实际形态。

## 登录链：腾讯自有账号栈，不是 OAuth

`com.tencent.qqmail.account.fragment.LoginQQMailFragment` 是主登录页，关键方法是 `doWxLogin()` 与 `doWxVerifyLogin()`：

```text
WXAccount.Companion.refreshToken()      → XMailAccount
  .j(refreshPwd …)                      → 用 qimei36 设备标识换取登录态
  .s(...)                               → 得到 XMailAccount 并入库
```

`loginQQAccountByCode(String)` 是“账号 + 验证码”的补充路径。整条链走的是腾讯账号体系（wtlogin/MSF 一脉）与 `XMailAccount`，**没有 authorization code / PKCE / token 端点**。

旁证：整个 dex 里没有 `imap.qq.com`、`smtp.qq.com` 常量——App 收发 QQ 邮件走自家协议与 HTTP CGI（`i.mail.qq.com`、`iwx.mail.qq.com`、`wx.mail.qq.com`、`mail.qq.com/cgi-bin/...`），不是 IMAP。

## OAuth 只用于“其他邮箱”

QQ 邮箱 App 支持代管 Gmail / Outlook / Exchange：

- `LoginGmailAuthFragment` + `LoginGmailWebFragment` + `LoginGmailResultActivity`；
- 回调 `onGetTokenSuccess(accessToken, refreshToken, tokenType, expiresIn, idToken)`、`onGetTokenError`；
- 字符串 `isOutlookOAuth`、`oauthByWebView`、`oauthUseEas`、`oauthUsePop`、`oauth_use_eas/pop`。

这与 Outlook APK、Mail Master 的做法同构：第三方 OAuth 拿 token，再用 XOAUTH2/EWS 登录。与 QQ 邮箱自身账号无关。

## 授权码机制

App 内有完整的授权码管理：

| 证据 | 含义 |
|---|---|
| `com.tencent.qqmail.activity.setting.security.model.AuthCodeInfo`、`NewDeviceInfo` | 授权码/设备模型 |
| `AuthCodeDetailActivity`、`SecurityManager.stopAuthCode` | 查看与停用授权码 |
| 埋点 `Imap_device_management_authorizationcode_invalid_verify_click` | 设置页的“IMAP 设备管理 / 授权码”入口 |
| 资源键 `my_auth_code`、`get_auth_code`、`auth_code_remark`、`auth_code_not_used`、`stop_auth_code_message` | 授权码页面文案 |

对应网页版路径就是「设置 → 账号 → 开启 IMAP/SMTP → 生成授权码」。授权码由腾讯账号体系签发，只对 IMAP/SMTP/POP 有效，不是 OAuth token。

## 服务端能力实测

`imap.qq.com:993` 的 `CAPABILITY` 返回：

```text
IMAP4 IMAP4rev1 ID AUTH=PLAIN AUTH=LOGIN AUTH=XOAUTH2 NAMESPACE CHILDREN ID UIDPLUS
```

服务端确实支持 XOAUTH2，但腾讯没有面向个人 QQ 邮箱开放第三方 OAuth 应用注册；`QQ 互联`（`graph.qq.com`）的 token 不带邮件权限。所以 XOAUTH2 只对腾讯自家客户端或企业邮生态有效。

## 对 MailHub 的影响

| 结论 | 落地 |
|---|---|
| QQ 邮箱自身登录是腾讯账号栈，无 OAuth | 保持授权码 |
| 授权码是唯一可互操作的 IMAP 凭据 | 界面加 QQ 专用引导清单（开启 IMAP/SMTP → 生成 16 位授权码） |
| `imap.qq.com` 支持 XOAUTH2 但不对第三方开放 | 不实现伪 OAuth |
| App 内的授权码管理与网页版一致 | `setup_url` 指向 `https://mail.qq.com/`，文案说明网页版路径 |

## 复现

```bash
python tools/dexscan.py QQmail_*.apk "authcode|oauth2|XOAUTH2"
python tools/dexwhere.py tools/dex_qq "AuthCode|Login"
tools/jadx/bin/jadx --no-res -d out --single-class \
  com.tencent.qqmail.account.fragment.LoginQQMailFragment tools/dex_qq/classes4.dex
python tools/probe_imap.py       # imap.qq.com CAPABILITY 实测
```
