# 邮箱登录配置

MailHub 按服务商能力选择认证方式，不会把普通邮箱密码伪装成 OAuth：

| 服务商 | 推荐方式 | 无 OAuth 配置时 |
|---|---|---|
| Microsoft Outlook / Hotmail | Microsoft 登录（授权码 + PKCE）；保留设备登录备用 | 设备登录 |
| Gmail / Google Workspace | Google 登录（授权码 + PKCE） | Google 应用专用密码 |
| QQ 邮箱 | 服务商客户端授权码 | 必须使用授权码 |
| 网易 163 / 126 / yeah.net | 服务商客户端授权密码 | 必须使用客户端授权密码 |
| 自定义 IMAP | 服务商允许的密码或应用专用密码 | 手工填写 IMAP SSL 地址 |

QQ、网易及任意自定义服务商若没有面向第三方 IMAP 客户端开放 OAuth，MailHub 无法通过技术手段绕过其授权码或应用专用密码策略。

## 公共地址

```env
MAILHUB_PUBLIC_URL=https://email.11451405.xyz
```

反向代理必须把 `/api/oauth/*` 原样转发给 MailHub。当前 nginx 的全路径代理无需新增 location。

## Microsoft 网页登录

在 Microsoft Entra 中注册应用，并登记以下 Web 重定向地址：

```text
https://email.11451405.xyz/api/oauth/outlook/callback
```

配置：

```env
MICROSOFT_CLIENT_ID=<应用客户端 ID>
MICROSOFT_CLIENT_SECRET=<应用客户端密钥>
```

网页回调按机密 Web 客户端配置，客户端 ID 和密钥均必须提供。应用需要委托权限 `https://outlook.office.com/IMAP.AccessAsUser.All` 和离线访问。若需要继续使用设备登录，应允许公共客户端流。

未配置自有客户端 ID 时，系统只保留原有 Outlook 设备登录兼容路径，不启用网页回调登录。

## Google 网页登录

在 Google Cloud 创建 OAuth Web 客户端，并登记：

```text
https://email.11451405.xyz/api/oauth/gmail/callback
```

配置有两种方式，完整的环境变量配置优先：

```env
GOOGLE_CLIENT_ID=<OAuth 客户端 ID>
GOOGLE_CLIENT_SECRET=<OAuth 客户端密钥>
```

也可以在 MailHub 的“设置 → Google 登录导入”中填写 Client ID 和 Client Secret。密钥使用 `MAILHUB_SECRET` 派生的 Fernet 密钥加密保存，设置接口只返回“是否已设置”，不会回显密钥。环境变量和设置页的字段不会交叉拼接，避免客户端 ID 与密钥错配。

Google IMAP XOAUTH2 使用 `https://mail.google.com/` 范围。该范围属于受限范围；面向非测试用户公开使用时，Google 可能要求应用验证和额外安全评估。未完成配置时，界面自动回退到应用专用密码。

## QQ 与网易 163 / 126 / yeah.net

当前没有可供普通第三方 IMAP 客户端使用的公开邮箱 OAuth 配置，因此不能安全实现与 Google、Microsoft 完全相同的 Token 登录。QQ Connect 或网易开放平台的普通身份登录 Token 不等于邮箱 IMAP 访问权限。

MailHub 会提供直达官方邮箱网页的登录按钮，引导开启 IMAP 并生成客户端授权密码；授权密码经真实 IMAP 连接验证后加密保存。禁止通过模拟网页登录、保存主密码、拦截短信验证码或抓取 Cookie 来绕过服务商限制。

添加账户采用邮箱地址优先的自动识别流程：`163.com`、`126.com`、`yeah.net` 分别使用各自的 IMAP SSL 服务器，未知域名才展开高级设置。网易账户认证成功后，MailHub 会在选择收件箱前发送 RFC 2971 `ID`，字段包含 `name`、`version`、`vendor` 和 `support-email`，与 Outlook 等可用客户端的接入思路一致。登录名必须是完整邮箱地址，凭据必须是客户端授权密码而非网页登录密码。

## 安全属性

- 浏览器登录使用随机 `state`、PKCE S256、十分钟过期和单次消费事务。
- 设备登录的 `device_code` 仅加密存储在服务器，不再回传浏览器轮询。
- OAuth 成功后先以 XOAUTH2 登录目标邮箱并打开 `INBOX`，验证邮箱地址和令牌确实匹配，再写入账户。
- access token 与 refresh token 使用现有 `MAILHUB_SECRET` 派生的 Fernet 密钥加密。
- 同一账户的令牌刷新串行执行，避免 refresh token 轮换竞争。
- OAuth 账户按“服务商 + 邮箱地址”更新，不重复创建；再次登录即完成重新授权。
- OAuth 邮箱地址不能通过普通编辑直接更换，必须重新登录。
