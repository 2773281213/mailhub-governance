# 邮箱登录配置

MailHub 按服务商能力选择认证方式，不会把普通邮箱密码伪装成 OAuth：

| 服务商 | 推荐方式 | 无 OAuth 配置时 |
|---|---|---|
| Microsoft Outlook / Hotmail | Microsoft 登录（授权码 + PKCE）；自有应用设备码登录备用 | 暂不启用登录 |
| Gmail / Google Workspace | Google 登录（授权码 + PKCE） | Google 应用专用密码 |
| Yahoo | Yahoo 应用专用密码（IMAP OAuth 需向 Yahoo 商务申请，未开放） | Yahoo 应用专用密码 |
| QQ 邮箱 | 服务商客户端授权码 | 必须使用授权码 |
| 腾讯企业邮 | 客户端专用密码 | 必须使用专用密码，主机固定 `imap.exmail.qq.com` |
| 网易企业邮 | 客户端授权密码 | 主机固定 `imap.qiye.163.com`，企业自有域名需手动选择 |
| 网易 163 / 126 / yeah.net / 188 / VIP | 已批准的合作方 OAuth，或客户端授权密码 | 客户端授权密码 |
| 自定义 IMAP | 服务商允许的密码或应用专用密码 | 手工填写 IMAP SSL 地址 |

QQ、网易及任意自定义服务商若没有面向第三方 IMAP 客户端开放 OAuth，MailHub 无法通过技术手段绕过其授权码或应用专用密码策略。

## 公共地址

```env
MAILHUB_PUBLIC_URL=https://<你的域名>
```

反向代理必须把 `/api/oauth/*` 原样转发给 MailHub。当前 nginx 的全路径代理无需新增 location。

## Microsoft 网页登录

在 Microsoft Entra 中注册应用，并登记以下 Web 重定向地址：

```text
https://<你的域名>/api/oauth/outlook/callback
```

可直接在 MailHub 的“设置 → Microsoft 登录导入”填写，也可以使用环境变量（完整环境变量配置优先）：

```env
MICROSOFT_CLIENT_ID=<应用客户端 ID>
MICROSOFT_CLIENT_SECRET=<应用客户端密钥>
```

网页回调按机密 Web 客户端配置，Client ID 和 Client Secret 均必须提供。应用需要委托权限 `https://outlook.office.com/IMAP.AccessAsUser.All` 和 `offline_access`。密钥会使用 `MAILHUB_SECRET` 派生的 Fernet 密钥加密保存，接口只返回是否已设置。

只配置 Client ID 时可以使用设备码登录，但必须在 Entra 应用的“身份验证”中开启“允许公共客户端流”。设备码模式不需要也不会发送 Client Secret。没有项目自有 Client ID 时，所有新的 Outlook 登录都会停用；系统不再借用 Outlook APK 或其他第三方应用的公开客户端身份。升级前已经保存的 Outlook 账户仍保留原有令牌刷新兼容路径，不会因升级立即中断。

## Google 网页登录

在 Google Cloud 创建 OAuth Web 客户端，并登记：

```text
https://<你的域名>/api/oauth/gmail/callback
```

配置有两种方式，完整的环境变量配置优先：

```env
GOOGLE_CLIENT_ID=<OAuth 客户端 ID>
GOOGLE_CLIENT_SECRET=<OAuth 客户端密钥>
```

也可以在 MailHub 的“设置 → Google 登录导入”中填写 Client ID 和 Client Secret。密钥使用 `MAILHUB_SECRET` 派生的 Fernet 密钥加密保存，设置接口只返回“是否已设置”，不会回显密钥。环境变量和设置页的字段不会交叉拼接，避免客户端 ID 与密钥错配。

Google IMAP XOAUTH2 使用 `https://mail.google.com/` 范围。配置完成后，用户通过 Google 网页同意授权即可导入 Gmail，不需要应用专用密码。该范围属于受限范围；面向非测试用户公开使用时，Google 可能要求应用验证和额外安全评估。测试阶段应在 OAuth 同意屏幕中添加测试用户。未完成配置时，界面自动回退到应用专用密码。

发布状态的选择：

- **测试**：只有同意屏幕里列出的测试用户能登录，且授权 7 天后失效，账户每周都要重新授权。
- **正式版（In production）**：任何 Google 账号都能登录；未经验证时会显示「Google 未验证此应用」，点「高级 → 继续」即可。未验证应用在项目生命周期内总共最多 100 个用户，个人或小团队自用足够。

### 为什么普通 Google 账号用不了应用专用密码

Google 只给开启了两步验证的账号提供应用专用密码。没开两步验证时，打开 `myaccount.google.com/apppasswords` 会显示「您的账号不支持您正在尝试的设置」。即使开启了两步验证，下列账号也无法创建：

- 两步验证只设置了安全密钥；
- 工作、学校等组织账号（管理员可能禁用）；
- 开启了高级保护计划。

因此添加 Gmail 时，界面会先引导开启两步验证，并提示管理员配置 Google 登录；配置 Google OAuth 客户端后，普通账号不需要两步验证和应用专用密码。

Outlook APK 也使用 OAuth + PKCE，但其 Client ID、Android 包名/签名、`olmoauth.outlook.com` 回调和 Microsoft token 兑换后端构成一组私有注册。MailHub 会明确拒绝该 Client ID 和回调；复制它们既不能把授权结果送回 MailHub，也会把 authorization code 交给 Microsoft 控制的服务。

## QQ 与网易 163 / 126 / yeah.net / 188 / VIP

QQ 邮箱与网易都没有面向普通第三方 IMAP 客户端的公开 OAuth 注册，因此不能安全实现与 Google、Microsoft 完全相同的 Token 登录。QQ 互联或网易开放平台的普通身份登录 Token 不等于邮箱 IMAP 访问权限；QQ 邮箱 APK 自身也只做腾讯账号栈 + 自家协议，没有 IMAP OAuth（见 [QQMAIL_ANALYSIS.md](QQMAIL_ANALYSIS.md)）。企业微信 5.0.13 的邮件模块（`com.tencent.qqmail.*` + native mailplugin）同样如此：企业邮登录返回私有 `tkspwd` ticket，QQ 邮箱登录用的是应用内 QQ 互联 SSO，二者都不对第三方开放，详见 [WECOM_MAIL_ANALYSIS.md](WECOM_MAIL_ANALYSIS.md)。

APK 静态分析确认 Outlook 使用网易邮件合作方接口（`responseType=token` 的 implicit flow），并非通用网易开放平台登录。MailHub 实现了相同的参数与回调契约，但**只使用部署者自己向网易申请的合作方 appid**；未配置时回退客户端授权密码。

```env
NETEASE_CLIENT_ID=<网易批准给本部署的 appid>
NETEASE_DEVICE_ID=<固定设备标识；留空时首次生成并写入 settings>
NETEASE_163_REDIRECT_URI=https://<你的域名>/api/oauth/163/callback
NETEASE_126_REDIRECT_URI=https://<你的域名>/api/oauth/126/callback
NETEASE_YEAH_REDIRECT_URI=https://<你的域名>/api/oauth/yeah/callback
NETEASE_188_REDIRECT_URI=https://<你的域名>/api/oauth/188/callback
NETEASE_VIP163_REDIRECT_URI=https://<你的域名>/api/oauth/vip163/callback
NETEASE_VIP126_REDIRECT_URI=https://<你的域名>/api/oauth/vip126/callback
NETEASE_VIP188_REDIRECT_URI=https://<你的域名>/api/oauth/vip188/callback
```

回调地址需要在网易侧逐个登记。早期版本提供过复用 Outlook 合作方 appid 的 `NETEASE_CHANNEL=outlook` 通道，该通道借用的是微软的客户端身份，已经移除；这些环境变量现在会被忽略，用该通道登录过的账户在升级后会被标记为需要重新授权。

**隐式回调**：网易把结果放在跳转 URL 的 fragment（`#uid=...&accessToken=...&state=...`）里，浏览器不会把 fragment 发给服务器。因此 `/api/oauth/<品牌>/callback` 在没有任何查询参数时会返回一个极小的捕获页：它读取 fragment、`history.replaceState` 抹掉地址栏、把 `state`/`uid`/`accessToken` POST 到 `/api/oauth/<品牌>/fragment`，成功后再向打开的窗口 `postMessage` 并自动关闭。该页面使用 `no-store` 与 `default-src 'none'; connect-src 'self'` 的 CSP。查询参数形式的 `accessToken`/`access_token` 也被同样接受。

服务端校验顺序与 APK 的 `NetEaseAuthDelegate` 一致：`state` 单次消费 → `uid` 必须等于本次登录邮箱 → 令牌非空 → 先以 XOAUTH2 实测目标 IMAP → 再加密入库。令牌同时作为 access/refresh 保存，有效期 3650 天；网易没有 refresh 端点，令牌失效后只能重新交互登录。

OAuth 账户按 APK 配置连接 `imapmail.163.com`、`imapmail.126.com`、`imapmail.yeah.net`、`imapmail.188.com`、`imapmail.vip.163.com`、`imapmail.vip.126.com`；客户端授权密码账户按网易邮箱大师的主机表连接 `imap.163.com`、`imap.126.com`、`imap.yeah.net`、`imap.188.com`、`imap.vip.163.com`、`imap.vip.126.com`、`imap.vip.188.com`，网易企业邮为 `imap.qiye.163.com`（见 [MAIL_MASTER_ANALYSIS.md](MAIL_MASTER_ANALYSIS.md)）。

代码会拒绝 Outlook 的私有 appid `rjg1fubwqzie5unhx6` 与 `olmoauth.outlook.com` 回调。Google 的 Outlook 私有 Client ID（`445112211283-…`）仍然始终拒绝：它绑定了 Android 包名与微软的 token 兑换后端，粘到 Web 端既回不来也用不了。

MailHub 会提供分步式官方设置引导：先确认完整邮箱地址，再前往网易网页开启 IMAP/SMTP、生成客户端授权密码，最后回到 MailHub 进行真实 IMAP 验证并加密保存授权密码。界面会明确区分客户端授权密码和网页登录密码。禁止通过模拟网页登录、保存主密码、拦截短信验证码或抓取 Cookie 来绕过服务商限制。

添加账户采用邮箱地址优先的自动识别流程：`163.com`、`126.com`、`yeah.net` 分别使用各自的 IMAP SSL 服务器，未知域名才展开高级设置。网易账户认证成功后，MailHub 会在选择收件箱前发送 RFC 2971 `ID`，字段包含 `name`、`version`、`vendor` 和 `support-email`，与 Outlook 等可用客户端的接入思路一致。登录名必须是完整邮箱地址，凭据必须是客户端授权密码而非网页登录密码。

## 安全属性

- 浏览器登录使用随机 `state`、PKCE S256、十分钟过期和单次消费事务。
- 网易合作方回调会立即清除浏览器地址栏参数，nginx 对相应 callback 禁用访问日志。
- 设备登录的 `device_code` 仅加密存储在服务器，不再回传浏览器轮询。
- OAuth 成功后先以 XOAUTH2 登录目标邮箱并打开 `INBOX`，验证邮箱地址和令牌确实匹配，再写入账户。
- access token 与 refresh token 使用现有 `MAILHUB_SECRET` 派生的 Fernet 密钥加密。
- 同一账户的令牌刷新串行执行，避免 refresh token 轮换竞争。
- OAuth 账户按“服务商 + 邮箱地址”更新，不重复创建；再次登录即完成重新授权。
- OAuth 邮箱地址不能通过普通编辑直接更换，必须重新登录。
