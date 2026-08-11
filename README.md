# MailHub Governance

MailHub Governance 是一个本地优先的多邮箱聚合与自动治理服务。它把邮件当作不可信数据，使用适配邮件场景的“三省六部”机制完成分类、摘要、优先级和风险标记，并把自动动作限制为可审计的标签写入。

> 当前版本适合个人部署或受控内网部署。生产部署前必须阅读 [SECURITY.md](SECURITY.md) 和 [OAUTH_SETUP.md](OAUTH_SETUP.md)。

## 功能概览

- 聚合 QQ、网易 163/126/yeah.net、Gmail、Outlook 和自定义 IMAP 邮箱。
- 输入邮箱地址后自动识别服务商；未知域名才显示高级 IMAP 设置。
- 网易邮箱使用各自的 IMAP SSL 主机，并在认证后发送 RFC 2971 `ID`。
- Outlook 使用 OAuth 设备码事务，设备凭据保留在服务器端。
- 三省六部自动治理，AI 失败或低置信度时自动回退本地规则。
- AI 不会自动删除、回复、转发或退订邮件，也不会创建人工复核队列。
- 软删除可撤销；服务器永久删除失败时不会移除本地记录。
- SQLite 保存同步、治理判定、回退来源和安全审计。

## 治理流程

```text
同步邮件
   |
   v
中书省：规则、元数据、安全扫描、AI 结果汇总
   |
   +--> 六部证据视角：吏 / 户 / 礼 / 兵 / 刑 / 工
   |
   v
门下省：注入、非法输出、低置信度和风险结果驳回
   |
   v
尚书省：只写分类、摘要、优先级、风险标签和审计
```

治理引擎的动作固定为 `label_only`。外部不可逆动作必须经过独立的用户操作和安全策略，不属于自动治理流程。

## 账户接入

| 服务商 | 自动识别域名 | 认证方式 | 默认 IMAP |
|---|---|---|---|
| QQ | `qq.com`, `foxmail.com` | 客户端授权码 | `imap.qq.com:993` |
| 网易 163 | `163.com` | 客户端授权密码 | `imap.163.com:993` |
| 网易 126 | `126.com` | 客户端授权密码 | `imap.126.com:993` |
| 网易 yeah.net | `yeah.net` | 客户端授权密码 | `imap.yeah.net:993` |
| Gmail | `gmail.com`, `googlemail.com` | OAuth 或应用专用密码 | `imap.gmail.com:993` |
| Outlook | `outlook.com`, `hotmail.com`, `live.com`, `msn.com` | OAuth 设备码 | `outlook.office365.com:993` |
| 自定义 | 其他域名 | 服务商允许的密码/应用密码 | 手工填写 |

网易邮箱登录名必须是完整邮箱地址，凭据必须是客户端授权密码而不是网页登录密码。相关配置说明见 [OAUTH_SETUP.md](OAUTH_SETUP.md)。

## 项目结构

```text
app.py             FastAPI 路由、账户和消息操作
mail_client.py     IMAP 会话、网易 ID、邮件解析
oauth_auth.py      OAuth、设备码和账户探测
sync.py            多账户同步与凭据解析
governance.py      三省六部治理编排和审计
rules.py           本地分类规则
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

生产环境必须使用长度足够、不会提交到 Git 的密钥。账户授权码、OAuth Token、数据库和备份均属于运行时数据，已由 `.gitignore` 排除。

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

生产部署应使用时间戳备份、远端语法/测试、健康检查和失败回滚流程。systemd 示例见部署环境中的 `mailhub.service`，不要把真实 `.env`、数据库或授权凭据复制到代码仓库。

## 相关文档

- [安全边界与威胁模型](SECURITY.md)
- [OAuth、IMAP 和账户接入](OAUTH_SETUP.md)
- [贡献规范](CONTRIBUTING.md)
