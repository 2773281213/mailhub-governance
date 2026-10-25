# 网易邮箱大师分析（7.22.10）

分析对象：`Mail+Master_7.22.10_APKPure.apk`（`com.netease.mail`，191 MB，单个 `classes.dex` 44.6 MB）。

目的：确认网易自家客户端是否使用（或提供）可移植的 163 OAuth / refresh 端点，并校正 IMAP 主机表。

## 加壳情况

`classes.dex` 只是 NetEase 的加载壳：

- 头部只声明 32 个类、925 条字符串，section 到 `0xF24C` 就结束；
- 真正的应用 dex 体（44 MB）被拼在壳后面，头部和各 id 表被改写，jadx 按原样读不出类；
- 但**字符串池是明文的**：`Lcom/netease/` 出现 9180 次，`refreshToken` 等在 `0x17311DC` 处可直接读到。

因此用 `tools/dexstrings.py` 直接对该文件做可打印串抽取，得到 508 764 条字符串，足以还原协议常量（本文件所有结论都来自这份字符串池，未经运行时验证的部分会注明）。

```bash
python -c "import zipfile;open('classes.dex','wb').write(zipfile.ZipFile('Mail+Master.apk').read('classes.dex'))"
python tools/dexstrings.py classes.dex strings.txt
```

## NetEase 账号登录：URS，不是 OAuth

字符串池里登录端点是一整套 URS 接口：

```text
/uns/sdk/login/mail/pwd/v1/login
/uns/sdk/login/mail/pwd/v1/send
/uns/sdk/login/mail/pwd/v1/verify
/uns/sdk/login/mob/pwd/v1/login
/uns/sdk/login/mob/sms/v1/login
/uns/sdk/login/mob/oc/v1/login
/uns/sdk/login/oauth/v1/login     ← 第三方账号绑定登录，不是邮箱 OAuth
/uns/sdk/login/qr/v1/qrCheck
```

配套常量 `&appid=`、`&scope=`、`&ursAppId=`、`AUTOLOGIN_URS_COOKIE`、`QIYE_SESS`/`QIYE_TOKEN`。也就是说 163/126/yeah 账号在邮箱大师里走的是**账号体系 cookie 会话 + Web/私有 API**（`/js6/s`、`/fgw/...`），而不是 IMAP OAuth。

关键否定证据：整个 44 MB dex 里**没有** `mailsrv-oauth2`、没有 `olmoauth`、也没有 Outlook 那个 appid —— 网易自己的客户端根本不用 Outlook 的合作方 OAuth，因此也无法提供它的 refresh 端点。

## IMAP 组件：只服务“其他邮箱”

字符串池里有完整的 JavaMail + XOAUTH2 痕迹：

```text
AUTH XOAUTH2
AUTHENTICATE XOAUTH2
AUTHENTICATE XOAUTH2 command result:
auth=Bearer
user=
ID (
```

以及 `gmail:oauth1` / `gmail:oauth2` / `hotmail:oauth1` / `hotmail:oauth2` / `exchange:oauth1|oauth2`、`OAUTH_REFRESH_FAILED`、`refresh oauth token and login again`、`OAuthLoginActivity$OAuthAccountType`。这些都是给 Gmail / Outlook / Exchange 这类第三方账户用的，与 Outlook APK 的实现同构；163 账号本身不需要这些。

## 企业邮（qiye.163.com）

- `QiyeAuthActivity` + `qiye_oauth_login_url`：WebView 打开企业邮授权页；
- JS 桥 `GET_QIYE_ACCOUNT_OAUTH_LOGIN_PWD` 把凭据回传给原生（`registerJsFunction`）；
- `https://open.qiye.163.com/config/config?service=smtp,imap,carddav,qiyecarddav,setting` 是客户端配置发现接口，实测直接请求返回 `{"code":403}`，需要应用侧凭证。

同样不是面向第三方的 OAuth2 注册。

## 主机表（原文提取）

| 协议 | 主机 |
|---|---|
| IMAP | `imap.163.com`、`imap.126.com`、`imap.yeah.net`、`imap.188.com`、`imap.vip.163.com`、`imap.vip.126.com`、`imap.vip.188.com`、`imap.qiye.163.com`、`imap.gmail.com`、`imap.qq.com`、`imap.exmail.qq.com` |
| SMTP | `smtp.163.com`、`smtp.126.com`、`smtp.yeah.net`、`smtp.188.com`、`smtp.vip.163.com`、`smtp.vip.126.com`、`smtp.vip.188.com`、`smtp.qiye.163.com` |
| POP3 | `pop3.qiye.163.com` |

注意：VIP 品牌在邮箱大师里是 `imap.vip.*`，而 Outlook 的合作方 OAuth 配置用的是 `imapmail.vip.*`；两套主机都能连，MailHub 按登录方式分别使用。

## 对 MailHub 的影响

| 结论 | 落地 |
|---|---|
| 163 个人账号：URS 会话 + Web API，无 IMAP OAuth | 保持“合作方 OAuth（配置则启用）+ 客户端授权码”两条路 |
| 不存在可移植的 refresh 端点 | 网易 OAuth 令牌过期后仍要求重新交互登录（与 Outlook APK 的 `NetEase account doesn't support token refresh` 一致） |
| VIP 品牌的密码登录主机是 `imap.vip.*` | `vip163/vip126/vip188` 预设改为 `imap.vip.163.com` 等 |
| 网易企业邮使用 `imap.qiye.163.com` | 新增 `qiye163` 服务商预设（客户端授权密码，自有域名需手动选择） |
| `188.com` 在邮箱大师里是 `imap.188.com` | 同步修正 `188` 预设 |

## 复现

```bash
python tools/dexscan.py Mail+Master_*.apk "mailsrv|oauth"
python tools/dexstrings.py tools/mm/classes.dex tools/mm/strings.txt
python - <<'PY'
import re
rows=[l.split('\t',1) for l in open('tools/mm/strings.txt',encoding='utf-8')]
for off,s in rows:
    if re.search(r'imap\.|smtp\.|qiye|XOAUTH2', s) and len(s)<120: print(off, s.strip())
PY
```
