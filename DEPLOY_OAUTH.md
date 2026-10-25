# 服务器端部署流程：让 QQ / 163 登录一次配好、长期可用

本文是生产部署的落地流程，配套四份分析文档（[Outlook APK](OUTLOOK_APK_AUTH_PATHS.md)、[邮箱大师](MAIL_MASTER_ANALYSIS.md)、[企业微信](WECOM_MAIL_ANALYSIS.md)、[QQ 邮箱](QQMAIL_ANALYSIS.md)）。

## 0. 先对齐事实（决定流程形态）

| 服务商 | 真正的能力 | 部署侧要做什么 |
|---|---|---|
| 网易 163/126/yeah/188/VIP | 合作方 OAuth（implicit，`responseType=token`，令牌 3650 天、**无 refresh**）+ 客户端授权密码 | 有网易批准的 appid 才启用 OAuth；否则走授权密码，功能等价 |
| QQ 邮箱 | **没有** IMAP OAuth。授权码是唯一可互操作凭据 | 部署侧零配置；把授权码流程做顺（引导 + 校验 + 失效提醒） |
| Gmail | 自建 Google OAuth Web 客户端 | 注册客户端，填 Client ID/Secret |
| Outlook | 自建 Entra 应用 | Client ID（+Secret 可开网页登录） |

所以“顺畅”不是“都走 OAuth”，而是：**每个服务商都走它真实存在的那条通道，且用户只面对一个向导、一个回调域、一套失败提示**。

---

## 1. 拓扑与硬性前提

```text
Internet ──HTTPS──> nginx ──> 127.0.0.1:8018 (uvicorn)
                        │
                        └── /api/oauth/<brand>/callback 必须原样转发，不缓存、不改写
```

- 一个公网域名，`MAILHUB_PUBLIC_URL` 必须与它完全一致（含协议、无尾斜杠）。
- 证书有效：OAuth 弹窗和隐式回调都在这个域下，证书错误会直接断掉登录。
- 网易隐式 flow 的令牌在 URL **fragment** 里，由回调页的 JS 读取后 POST 回 `/api/oauth/<brand>/fragment`；因此不能在该路径上加会吞掉页面的 WAF 规则，也不能强制跳转。
- 建议对 `/api/oauth/*/callback` 关闭 access log（`state` 会出现在查询串里）。

nginx 片段：

```nginx
location /api/oauth/ {
    proxy_pass http://127.0.0.1:8018;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Real-IP $remote_addr;
    access_log off;              # state / uid 不落日志
    proxy_buffering off;
}
```

---

## 2. 准备凭据

### 2.1 网易 163

1. 向网易申请**邮件客户端合作方** appid 与设备标识。
2. 在网易侧登记回调，按实际使用的品牌逐个登记：

```text
https://<你的域名>/api/oauth/163/callback
https://<你的域名>/api/oauth/126/callback
https://<你的域名>/api/oauth/yeah/callback
https://<你的域名>/api/oauth/188/callback
https://<你的域名>/api/oauth/vip163/callback
https://<你的域名>/api/oauth/vip126/callback
https://<你的域名>/api/oauth/vip188/callback
```

3. 写入 `NETEASE_CLIENT_ID`（`NETEASE_DEVICE_ID` 可留空，首次使用时生成并持久化）。

没有 appid 时，系统自动使用"客户端授权密码"，界面上仍然是同一个向导。早期版本的 `NETEASE_CHANNEL=outlook` 通道借用了 Outlook 的合作方 appid，已经移除；用它登录过的账户升级后会出现在"待重新授权"列表里。

### 2.2 QQ 邮箱

无任何部署侧注册。用户侧动作只有两步：网页版「设置 → 账号」开启 IMAP/SMTP，生成 16 位授权码。

### 2.3 Gmail / Outlook

- Google Cloud → OAuth Web 客户端，重定向 URI：`https://<你的域名>/api/oauth/gmail/callback`
- Entra → 应用注册，Web 重定向：`https://<你的域名>/api/oauth/outlook/callback`，或只填 Client ID 用设备码。

---

## 3. 配置清单

```env
# 必填
MAILHUB_SECRET=<32 字节以上随机串>
MAILHUB_PUBLIC_URL=https://<你的域名>
MAILHUB_DB=/var/lib/mailhub/mailhub.db

# 网易 OAuth（网易批准给本部署的 appid；留空则使用客户端授权密码）
NETEASE_CLIENT_ID=
# 留空时首次启动生成并持久化到 settings，之后保持不变
NETEASE_DEVICE_ID=
# 每个品牌的回调可单独覆盖，默认 ${MAILHUB_PUBLIC_URL}/api/oauth/<brand>/callback
NETEASE_163_REDIRECT_URI=
NETEASE_VIP163_REDIRECT_URI=

# Gmail / Outlook（可选）
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
MICROSOFT_CLIENT_ID=
MICROSOFT_CLIENT_SECRET=
```

`MAILHUB_SECRET` 用于加密已保存的授权码和 token，必须与数据库一起备份。需要更换时，把旧值写进 `MAILHUB_SECRET_PREVIOUS` 并重启一次，启动时会用新密钥重新加密所有凭据；确认日志里没有"无法解开"的警告后即可删除该变量。

---

## 4. 部署

```bash
sudo useradd -r -m -d /opt/mailhub mailhub
sudo -u mailhub git clone <repo> /opt/mailhub/app
cd /opt/mailhub/app
sudo -u mailhub python3 -m venv venv
sudo -u mailhub ./venv/bin/pip install -r requirements.txt
sudo -u mailhub install -m 600 /dev/null /opt/mailhub/app/.env   # 写入第 3 节内容
```

systemd：

```ini
[Unit]
Description=MailHub
After=network-online.target

[Service]
User=mailhub
WorkingDirectory=/opt/mailhub/app
EnvironmentFile=/opt/mailhub/app/.env
ExecStart=/opt/mailhub/app/venv/bin/uvicorn app:app --host 127.0.0.1 --port 8018
Restart=on-failure
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now mailhub
```

---

## 5. 上线自检（三步）

**① 命令行预检**

```bash
cd /opt/mailhub/app
sudo -u mailhub ./venv/bin/python scripts/preflight.py
```

检查项：`MAILHUB_SECRET` 长度、公开地址协议、数据库目录可写、回调域名解析、公网 `/api/health`、网易凭据与回调是否误用 `olmoauth`、`imap.163.com` / `imap.qq.com` 是否广播 `AUTH=XOAUTH2`。必须项失败会返回非 0。

只查本地配置：`--skip-network`。

**② 接口自检**

```bash
curl -s -b "session=<你的会话 Cookie>" https://<域名>/api/oauth/status | jq
```

返回 `channels`（每个服务商当前走 OAuth 还是授权码、回调地址）与 `checks`（HTTPS、各客户端是否配置、是否有待重授权账户）。`ok=false` 只代表必须项失败。

**③ 界面自检**

设置页 →「登录通道自检」卡片：一眼看到每个服务商走哪条通道、回调地址、待重授权账户。

---

## 6. 账户接入流程（用户视角）

### 6.1 163（已配 appid）

```text
输入邮箱 → 自动识别品牌 → 点「使用网易登录」
→ 弹窗打开 mail.163.com/fgw/.../authorize/entry?uid&appid&device_id&scope=imap&responseType=token&redirectUrl&state
→ 网易登录页授权 → 跳回 /api/oauth/163/callback#uid=..&accessToken=..&state=..
→ 捕获页读取 fragment、清地址栏、POST /api/oauth/163/fragment
→ 服务端校验 state（单次）→ uid 必须等于本次邮箱 → 立刻用 XOAUTH2 登录 imapmail.163.com 验证 → 加密入库
→ 弹窗关闭，账户出现
```

### 6.2 163（无 appid）

同一个向导自动切换为两步清单：开启 IMAP/SMTP → 填客户端授权密码 → 后台立即用 IMAP 登录验证（认证后发 RFC 2971 `ID`，否则网易会返回 `Unsafe Login`）。

### 6.3 QQ

```text
输入 x@qq.com → 自动识别 → 两步清单（开启 IMAP/SMTP / 生成 16 位授权码）
→ 填授权码 → 后台登录 imap.qq.com:993 验证 → 入库
```

QQ 不发 `ID` 也能收信；这里不发，避免多余往返。

### 6.4 失败提示对照

| 现象 | 原因 | 处理 |
|---|---|---|
| 503 `网易合作方 OAuth 客户端尚未配置` | 缺 `NETEASE_CLIENT_ID`/`NETEASE_DEVICE_ID` | 补齐并重启，或用授权密码 |
| 弹窗停在空白页 | 弹窗被拦截 | 允许弹窗；向导里也提供直链 |
| 回调页 `登录状态无效或已使用` | state 被消费/重复回调/跨域 Cookie | 重新发起；确认只用一个域名访问 |
| `网易返回的邮箱地址与登录账户不一致` | 授权时登录了另一个网易账号 | 重新授权并选对账号 |
| `网易邮箱返回 Unsafe Login` | 缺 `ID` 或授权密码错误 | 用完整邮箱 + 授权密码；确认 IMAP 已开启 |
| `PARAMETER_ERROR`（网易授权入口） | appid 未被网易接受或参数不匹配 | 核对 appid、回调登记 |
| QQ `AUTHENTICATIONFAILED` | 填了 QQ 密码 / 未开 IMAP | 用 16 位授权码，网页版开启 IMAP |

---

## 7. 长期运维

**令牌生命周期**

- 网易 OAuth：3650 天，**没有 refresh 端点**。到期或被吊销后账户被标记 `oauth_reauth_required=1`，界面出现「重新授权」，一键用同一账户重跑授权（`account_id` 绑定，不允许改邮箱）。
- Gmail / Outlook：后台自动用 refresh token 刷新，失败才要求重授权。

**例行检查（建议每天一次）**

```bash
curl -fsS -b "session=$COOKIE" https://<域名>/api/oauth/status \
  | jq '{ok, reauth: .accounts.reauth_required}'
```

有 `reauth_required` 就推送提醒（项目自带 Bark / Telegram 通知）。

**备份**

```bash
sqlite3 /var/lib/mailhub/mailhub.db ".backup '/backup/mailhub-$(date +%F).db'"
```

数据库和 `MAILHUB_SECRET` 必须成对备份；只有库没有密钥，所有 token 都解不开。

**首次登录**：首次启动生成的管理密码在数据目录的 `initial-admin-password.txt`（仅 mailhub 用户可读），登录后在设置页修改密码，该文件会被自动删除。

**安全边界**

- 只用自有 appid，代码拒绝 Outlook 的 `rjg1fubwqzie5unhx6` 和 `olmoauth.outlook.com` 回调。
- 隐式 flow 的令牌会短暂出现在地址栏 fragment，回调页立即 `history.replaceState` 清除，并带 `Referrer-Policy: no-referrer` 与 `no-store`。
- 回代页 CSP：`default-src 'none'; script-src 'unsafe-inline'; connect-src 'self'`。
- OAuth 账户不能通过普通编辑改邮箱，必须重新登录。

---

## 8. 最小可用路径（10 分钟）

没有任何第三方注册时的完整可用组合：

| 账户 | 走法 |
|---|---|
| QQ / 163 / 126 / yeah / 188 / VIP | 客户端授权密码（向导内两步清单） |
| 腾讯企业邮 / 网易企业邮 | 客户端专用密码，主机已内置 |
| Gmail | 应用专用密码（或配 Google 客户端走 OAuth） |
| Outlook | 必须配 Entra Client ID（设备码即可） |

之后拿到网易 appid，再补 `NETEASE_*` 环境变量并重启，网易账户就能原地切换到 OAuth——同一套 UI、同一套回调、同一份审计。
