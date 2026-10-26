# MailHub Governance

MailHub Governance 是一个本地优先的多邮箱聚合与自动治理服务。它把邮件当作不可信数据，使用适配邮件场景的“三省六部”机制完成分类、摘要、优先级和风险标记，并把自动动作限制为可审计的标签写入。

> 当前版本适合个人部署或受控内网部署。生产部署前必须阅读 [SECURITY.md](SECURITY.md) 和 [OAUTH_SETUP.md](OAUTH_SETUP.md)。

## 功能概览

- 聚合 QQ、网易 163/126/yeah.net、Gmail、Outlook、Yahoo 和自定义 IMAP 邮箱。
- 输入邮箱地址后自动识别服务商；未知域名才显示高级 IMAP 设置。
- 网易邮箱使用各自的 IMAP SSL 主机，并在认证后发送 RFC 2971 `ID`。
- Outlook 支持项目自有 Microsoft 应用的浏览器 OAuth 和设备码登录，设备凭据保留在服务器端。
- 网易合作方 OAuth 是 implicit flow：回调页先读取 URL fragment，再提交给服务端校验，令牌不经过第三方中转；需要部署者自己向网易申请的 appid，未配置时使用客户端授权密码。
- 三省六部自动治理：启用 AI 后由 AI 阅读邮件内容判定分类（验证码遮蔽、链接只留域名），本地规则作为提示和兜底；AI 失败或低置信度时自动回退本地规则。判定范围可在设置页改为「规则拿不准的」或「仅规则未识别的」。
- 本地规则识别群发邮件头（List-Id、Precedence、Feedback-ID、常见群发平台），营销邮件里的 offer/deadline 不会被判为重要，周刊正文提到改密码也不会被判为安全告警。
- AI 不会自动删除、回复、转发或退订邮件，也不会创建人工复核队列。
- 软删除可撤销；服务器永久删除失败时不会移除本地记录。
- SQLite 保存同步、治理判定、回退来源和安全审计。
- 重要/安全邮件和验证码可推送到 Bark、Telegram，或通过 [Onepush](https://github.com/y1ndan/onepush) 推送到 Server酱、pushplus、企业微信、钉钉、Discord、邮件等。

## 治理流程

```text
同步邮件：本地规则即时打标签（带置信度）+ 安全扫描；验证码立即推送
   |
   v
中书省：规则初判、元数据、安全扫描汇总；按判定范围把邮件交 AI 读内容判定
   |
   +--> 六部证据视角：吏 / 户 / 礼 / 兵 / 刑 / 工
   |
   v
门下省：注入和中高风险直接判可疑；驳回非法输出和低置信度 AI 结果；
        移出账单/安全/重要/验证码或判为可疑需要更高置信度
   |
   v
尚书省：只写分类、摘要、优先级、风险标签和审计；重要/安全新邮件裁决后推送
```

治理引擎的动作固定为 `label_only`。外部不可逆动作必须经过独立的用户操作和安全策略，不属于自动治理流程。

分类效果可以用合成语料检查：`python scripts/eval_classifier.py` 打印本地规则的混淆矩阵，加 `--ai` 同时评估当前配置的 AI 接口（只发送合成语料）。

## 账户接入

所有服务商的域名、IMAP 主机和认证方式只在 `providers.py` 里定义一次，前端添加账户的界面按它渲染。认证方式只有四种：

| 方式 | 说明 | 何时可用 |
|---|---|---|
| 浏览器 OAuth（`oauth_browser`） | 弹窗打开服务商登录页，授权后自动添加 | 部署者配置了该服务商的自有 OAuth 客户端 |
| 设备码（`oauth_device`） | 在 Microsoft 登录页输入设备码 | 配置了 `MICROSOFT_CLIENT_ID` |
| 授权码（`app_password`） | 服务商生成的授权码 / 应用专用密码 / 客户端专用密码 | 始终可用（Outlook 除外） |
| 密码（`password`） | 自定义 IMAP 的普通密码 | 仅自定义邮箱 |

| 服务商 | 自动识别域名 | 可用方式（按推荐顺序） | IMAP 主机 |
|---|---|---|---|
| QQ | `qq.com`, `foxmail.com` | 授权码 | `imap.qq.com:993` |
| 网易 163 / 126 / yeah.net / 188 | `163.com`, `126.com`, `yeah.net`, `188.com` | 浏览器 OAuth（需网易批准的 appid）、授权码 | 授权码 `imap.<品牌>`；OAuth `imapmail.<品牌>` |
| 网易 VIP 163 / 126 / 188 | `vip.163.com`, `vip.126.com`, `vip.188.com` | 浏览器 OAuth（同上）、授权码 | 授权码 `imap.vip.<品牌>`；OAuth `imapmail.vip.<品牌>`（VIP 188 为 `imapmail.188.com`） |
| 腾讯企业邮 | 企业自有域名，需手动选择 | 授权码（客户端专用密码） | `imap.exmail.qq.com:993` |
| 网易企业邮 | 企业自有域名，需手动选择 | 授权码（客户端授权密码） | `imap.qiye.163.com:993` |
| Gmail | `gmail.com`, `googlemail.com` | 浏览器 OAuth（需 Google 客户端）、应用专用密码 | `imap.gmail.com:993` |
| Yahoo | `yahoo.com`、`ymail.com`、`rocketmail.com` 及各地区 `yahoo.*` | 应用专用密码 | `imap.mail.yahoo.com:993` |
| Outlook / Hotmail | `outlook.com`, `hotmail.com`, `live.com`, `msn.com` | 浏览器 OAuth、设备码（需 Microsoft 客户端） | `outlook.office365.com:993` |
| 自定义 | 其他域名 | 密码 | 手工填写 |

网易邮箱登录名必须是完整邮箱地址，凭据必须是客户端授权密码而不是网页登录密码。同一个邮箱不能重复添加，需要更换凭据或重新授权时在账户卡片上「编辑」或「重新授权」。OAuth 客户端的配置见 [OAUTH_SETUP.md](OAUTH_SETUP.md)。

## 项目结构

```text
app.py             FastAPI 路由、账户和消息操作
providers.py       服务商注册表：域名、IMAP 主机、认证方式
mail_client.py     IMAP 会话、网易 ID、邮件解析
oauth_auth.py      OAuth、设备码、可用认证方式和账户探测
sync.py            多账户同步与凭据解析
governance.py      三省六部治理编排和审计
rules.py           本地分类规则（验证码提取、群发信号、置信度）
policy.py          外部动作安全策略
security_scan.py   提示注入和邮件风险扫描
db.py              SQLite schema、迁移和审计
static/            前端界面
tests/             隔离数据库测试
SECURITY.md        威胁模型和安全边界
OAUTH_SETUP.md     OAuth/IMAP 配置说明
```

## 配置

复制示例配置并填写至少一个随机的 `MAILHUB_SECRET`：

```bash
cp .env.example .env
```

生产环境必须使用长度足够、不会提交到 Git 的密钥。账户授权码、OAuth Token、数据库和备份均属于运行时数据，已由 `.gitignore` 排除。首次启动生成的管理密码保存在数据库所在目录的 `initial-admin-password.txt`，修改密码后该文件会被删除。

`MAILHUB_PUBLIC_URL` 默认是 `http://127.0.0.1:8018`，只适合本机使用；对外部署时必须改成你自己的 HTTPS 域名，否则 OAuth 回调无法完成。AI 接口没有默认地址，启用 AI 前请在设置页填写 OpenAI 兼容接口地址（例如本地 Ollama）。

`MAILHUB_TZ` 决定「今天」、近 14 天统计和晨报使用的时区，默认 `Asia/Shanghai`；填了无效的时区名会在启动时打印一行警告并回退到 `Asia/Shanghai`。

## 本地运行

```bash
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
set -a && source .env && set +a
uvicorn app:app --host 127.0.0.1 --port 8018
```

Windows PowerShell：

```powershell
python -m venv venv
.\venv\Scripts\Activate.ps1
pip install -r requirements.txt
Get-Content .env | ForEach-Object {
  if ($_ -match '^([^#][^=]*)=(.*)$') { Set-Item "Env:$($matches[1])" $matches[2] }
}
uvicorn app:app --host 127.0.0.1 --port 8018
```

打开 `http://127.0.0.1:8018`。

## 测试与质量门槛

```bash
python -m pytest tests -q
python -m py_compile *.py
node --check static/app.js
```

测试进程会强制使用临时 SQLite 数据库，不会接触生产邮件数据。提交前应保证测试、语法检查和敏感信息扫描全部通过。

## 部署原则

`deploy/install.sh <部署包> <域名>` 用于首次安装，`deploy/update.sh <部署包>` 用于更新。更新流程：

1. 在新目录里安装依赖并运行测试，此时线上版本照常服务；
2. 测试通过后停服务，复制 `.env` 和数据目录，再切换目录；
3. 健康检查失败时，自动恢复旧版本和更新前的数据。

旧版本保留在 `/opt/mailhub.backup.<时间戳>`。systemd 配置见 `deploy/mailhub.service`。不要把真实的 `.env`、数据库或授权凭据复制到代码仓库。

## 推送通知

设置页「通知推送」除 Bark、Telegram 外，还支持 Onepush。配置写法与 [AzurLaneAutoScript 的 Onepush 配置](https://github.com/LmeSzinc/AzurLaneAutoScript/wiki/Onepush-configuration-%5BCN%5D)相同：一段 YAML，`provider` 指定服务商，其余是该服务商的参数。例如：

```yaml
provider: serverchanturbo
sctkey: SCT16xxxxxxxxxxxxxxxx
```

```yaml
provider: smtp
host: smtp.qq.com
user: 123456789@qq.com
password: 邮箱授权码
port: 465
```

```yaml
provider: custom
url: https://your.web.hook/path
method: post
data:
  source: mailhub
```

- 保存时会检查 YAML 格式、服务商名称和必填参数。配置里含令牌，会加密保存，设置页只显示服务商名。
- 自定义 Webhook 会以 JSON 发送 `data`，并自动加入 `title` 和 `content`。
- 各服务商都可以加 `proxies` 参数，写法与 requests 相同。
- 可以用「发送测试推送」验证配置；失败时会提示具体原因。

## 相关文档

- [部署与登录通道流程](DEPLOY_OAUTH.md)
- [安全边界与威胁模型](SECURITY.md)
- [OAuth、IMAP 和账户接入](OAUTH_SETUP.md)
- [贡献规范](CONTRIBUTING.md)
