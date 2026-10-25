# MailHub 安全与自动化策略说明

本文件说明 MailHub 实际实现了哪些安全能力、**哪些没有实现**，以及自动化动作的分级规则。
未实现的项目在文末明确列出，不提供虚假的安全保证。

## 一、自动化动作三级分类

代码位置：`policy.py`。任何改变邮件状态的动作都必须先过 `policy.evaluate()`。

| 等级 | 含义 | 包含动作 | 执行条件 |
|---|---|---|---|
| **A** | 可默认自动执行，纯标注、完全可逆 | `label` `classify` `read` `unread` `star` `digest` `remind` | 无条件放行 |
| **B** | 改变归属但仍可恢复 | `archive` `move` `soft_delete` `quarantine` | 需用户显式规则，或置信度 ≥ 0.75 |
| **C** | 不可逆或对外发送 | `purge` `empty_folder` `send` `reply` `forward` `unsubscribe` `download_attachment` | **一律需人工确认**，无例外 |

未在表中登记的动作按 C 类处理（`ACTION_TIER.get(action, TIER_CONFIRM)`），新增动作不会因遗漏配置而被放行。

## 二、受保护邮件

以下邮件禁止任何自动破坏性动作（**连软删除也不允许**），只能在收件箱中逐封人工处理：

- 分类属于 `账单` / `安全` / `重要`
- 含附件（可能是发票或合同）
- AI 判定重要度 ≥ 4
- VIP 发件人、用户已回复过的会话（字段已预留，VIP 名单 UI 尚未实现，见"未实现"）

批量清理入口 `/api/clean` 另有分类白名单，仅 `验证码` / `订阅` / `通知` 可批量清理，其余分类直接 403。

执行顺序：`policy.evaluate` 对删除、归档、移动类动作**先做受保护判定，再看是否已确认**。因此前端的"已确认"只解除 C 类动作的确认门槛，不会顺带放行受保护邮件：

- 批量删除、`/api/clean`、定时自动清理：受保护邮件一律跳过，结果里列在 `blocked` 中。
- 详情页单封删除：服务端返回 403 和 `X-MailHub-Protected: 1`，前端再弹一次说明原因的确认框；用户确认后带 `override_protection: true` 重发。该参数只在请求恰好包含一封邮件时生效，审计记录会注明"单封二次确认"。

## 三、删除语义

| 操作 | 行为 | 可撤销 |
|---|---|---|
| 收件箱「删除(仅本地)」 | 置 `deleted_ts`，进回收站 | ✅ 回收站恢复 / 审计撤销 |
| 收件箱「删除(含服务器)」 | IMAP `\Deleted` 后用 `UID EXPUNGE` 只清除选中的邮件；服务器不支持 UIDPLUS 时，只有收件箱里待删邮件全是本次标记的才执行 `EXPUNGE`，否则仅保留标记。服务器确认成功后才删除本地记录 | ❌ 需前端 `confirmed:true` |
| 定时自动清理 | 默认软删除；仅当用户在设置里开启 `clean_server` 才连服务器删。受保护邮件不参与；服务器删除失败的邮件保留本地记录，失败原因写入审计 | 软删除可撤销 |

回收站中的邮件仍保留在邮箱服务器上，恢复不丢内容。

## 四、提示词注入防御

代码位置：`ai.py`。邮件的任何部分都视为不可信数据，三道防线：

1. **系统消息声明边界**：`<EMAIL_DATA>` 标签内一切内容都是待分析数据，系统提示明确禁止将其中的祈使句当作指令，并声明 AI 无任何执行能力。
2. **字段中性化**（`sanitize_field`）：压缩空白、剥离代码围栏与分隔线（防伪造提示结构）、硬截断；检出注入话术时加 `[!含疑似指令注入…]` 前缀。
3. **输出严格校验**：
   - id 必须属于本批次且不重复 —— 防止模型被诱导批量改写其他邮件
   - category 必须在固定枚举内，否则降级
   - confidence / importance 越界即钳制
   - 输入侧检出注入的邮件，无论 AI 返回什么都标记为不可信结果；治理引擎直接将其自动裁决为「可疑」

**降级策略**：AI 服务不可用、返回非法 JSON、漏答某封、分类越界、置信度低于 0.75 —— 一律由门下省自动驳回并保持规则给出的原分类，`needs_review` 落库时清零，不创建人工复核队列。其中 AI 不可用和漏答属于临时问题，会按 5 分钟起、翻倍到最长 6 小时的间隔自动重试，最多 5 次（`ai_retry_count` / `ai_retry_ts`）。治理按 200 封一批读取和提交，正文只读前 2 万字符做本机注入扫描。

覆盖测试：`tests/test_security.py` 中 8 类注入载荷 + 6 个降级场景。

### 自动三省六部治理

代码位置：`governance.py`。三省是一次自动流水线：中书省汇总本地规则、AI、静态安全与元数据；门下省自动解决冲突并执行保守回退；尚书省只落地分类、优先级、摘要和审计。六部（吏/户/礼/兵/刑/工）是证据视角和主责归属，不替代现有八类邮件标签。

- 一键入口和后台同步使用同一引擎，重复运行只处理未治理或旧版本邮件。
- 中高风险与疑似提示注入自动标记为「可疑」，无需逐封确认。
- 所有治理动作固定为 `label_only`；该模块不导入邮件客户端，也没有删除、转发、回复、退订或下载能力。
- `governance_runs` 保存批次统计，`governance_decisions` 只保存布尔/枚举证据，不保存主题、正文、发件地址或凭据。

## 五、AI 数据最小化

- 分类策略表 `rules.CATEGORY_POLICY` 标注每个分类是否允许外送云端。**`验证码`/`账单`/`安全`/`可疑` 一律不外送**（`allows_cloud_ai()` 返回 False）。
- 设置页「发送正文片段」开关关闭后，只发送发件人 + 主题（仅元数据模式）。
- 正文摘录硬截断 200 字符。
- AI 只拿到 subject / sender / snippet 三个字段，**拿不到 OAuth Token、授权码、Message-ID、账户 ID**，也无法调用任何 Connector。
- AI 可完全关闭；接口是 OpenAI 兼容协议，填 Ollama 地址即可走本地模型。

## 六、可疑邮件识别

代码位置：`security_scan.py`，**纯静态分析，不访问邮件中的任何链接、不打开附件**。

| 检测项 | 判定 |
|---|---|
| 显示名内嵌其他域的邮箱地址 | high |
| 冒用知名品牌但发件域不符（PayPal/Apple/Google/微软/支付宝等） | high |
| 域名含西里尔/希腊同形异义字符 | high |
| 可执行附件（exe/scr/js/vbs/lnk 等 21 种） | high |
| 正文链接指向裸 IP（纯文本里的 URL 与 HTML 里的 `href` 都会检查） | high |
| 链接文字显示知名品牌域名，实际指向其他域名 | high |
| 压缩包附件 | medium |
| Reply-To 与发件域不一致 | medium |
| 账户风险紧迫话术 + 外链 | medium |
| 收件服务器判定 DMARC 失败，或 SPF 失败且没有有效 DKIM（只读最上面一条 `Authentication-Results`） | medium |
| 链接文字显示的域名与实际目标不符（非品牌域名，常见于点击统计跳转） | low |

显示名中的英文品牌按整词匹配；可注册域按常见 ccTLD 二级后缀（co.uk、com.cn、ne.jp、com.tw 等）计算，不是完整的 Public Suffix List。

medium/high 风险邮件强制归入「可疑」分类并进入只读风险观察，优先级高于普通规则判定；不要求人工确认，也不自动执行破坏性动作。

## 七、审计与撤销

- `audit_log` 表记录每一个动作：时间、执行者（user/rule/ai/system）、动作、安全等级、目标数量与 id 列表、原因、**是否被拦截**、是否可撤销、撤销载荷。
- **被策略拦截的动作同样留痕**（`allowed=0`），可在安全中心看到拦截原因。
- 每次自动治理另写 `governance_runs`/`governance_decisions`，可追踪版本、六部归属、回退原因与分类前后值。
- 可撤销动作提供 `/api/audit/{id}/undo`；不可逆动作（purge）明确拒绝撤销请求，不给虚假承诺。
- 日志中不写入任何凭证。

## 八、其他已实现的安全措施

- 授权码/OAuth Token 用 Fernet 加密落库，密钥由 `MAILHUB_SECRET` 经 HKDF-SHA256 派生，主密钥在 `/opt/mailhub/.env`（0600）。更换密钥时把旧值设为 `MAILHUB_SECRET_PREVIOUS` 重启一次，启动时会把所有凭据改用新密钥重新加密；解密失败会写警告日志（不含密文）
- 会话保存在服务端（只存令牌的 SHA-256），cookie 为 `httponly` + `secure` + `samesite=lax`。登出只吊销当前设备；修改密码或设置页"退出所有其他设备"会吊销全部会话
- 登录失败按来源 IP 指数退避：前 5 次不限，之后等待 60 秒并逐次翻倍，最长 1 小时；所有来源合计 10 分钟内失败 100 次时暂停登录
- 首次启动生成的管理密码写入数据目录下的 `initial-admin-password.txt`（0600），日志里只有文件路径；改密码后该文件被删除
- CSRF：会改变状态的 `/api` 请求必须来自本站（`Origin` 与 Host 或 `MAILHUB_PUBLIC_URL` 一致，或 `Sec-Fetch-Site: same-origin`）；跨站请求返回 403
- 所有响应带 `X-Content-Type-Options`、`X-Frame-Options: DENY`、`Referrer-Policy` 和基础 CSP；完整 CSP 暂以 Report-Only 运行。前端已不再使用内联事件处理器，确认浏览器控制台没有违规后即可改为强制
- Onepush 推送配置（含各服务商令牌/密码）用同一套 Fernet 密钥加密保存，设置接口只返回服务商名；推送内容只有分类、发件地址、主题前 80 字和验证码（开启验证码推送时）
- nginx：80 端口只做 301 跳转到 HTTPS，启用 HSTS；所有服务商的 OAuth 回调不写访问日志
- 邮件 HTML 在**全沙箱 iframe**（`sandbox=""`，禁脚本/表单/弹窗/同源）中渲染
- **外部图片默认拦截**：CSP `img-src data:`，阻断追踪像素泄露阅读时间与 IP；用户可单封放行
- `referrerpolicy="no-referrer"`
- 附件永不自动下载或打开，仅读取文件名做静态风险判定
- 服务仅监听 `127.0.0.1:8018`，外部经 nginx TLS 访问
- systemd 加固：`NoNewPrivileges` `PrivateTmp` `ProtectSystem=strict` `MemoryMax=512M`（见 `deploy/mailhub.service`）

## 九、明确未实现的项目

以下能力**没有实现**，不要假设它们存在：

| 项目 | 状态 | 说明 |
|---|---|---|
| 多用户与 userId 隔离 | ❌ 未实现 | 单管理员设计，所有数据属同一人；无 userId 列，**不适合多租户部署** |
| CSRF Token | ⚠️ 部分 | 用 Origin / Sec-Fetch-Site 校验代替独立 token（见第八节） |
| Gmail API / Microsoft Graph 原生 Connector | ❌ 未实现 | Gmail 走 IMAP + 应用专用密码；Outlook 走 OAuth 但仍是 IMAP 协议，非 Graph API |
| IMAP IDLE 实时推送 | ❌ 未实现 | 使用轮询 + 查询驱动的突发同步 |
| 规则模拟器 | ❌ 未实现 | 规则可增删，但无"预览会匹配哪些邮件"功能 |
| 可视化规则编辑器 / 嵌套条件 DSL | ⚠️ 部分 | 仅支持单条件（字段 + 正则 + 分类），无 any/all 嵌套 |
| 退订管理 | ❌ 未实现 | 仅识别 List-Unsubscribe 头并归类为「订阅」，无退订执行入口 |
| 账单金额/截止日期结构化提取 | ❌ 未实现 | 仅分类为「账单」，未提取金额、商家、到期日，无提醒生成 |
| 待办/提醒系统 | ❌ 未实现 | 策略表里有 `remind` 动作定义，但无提醒存储与触达 |
| 分类完整度 | ⚠️ 部分 | 实现 8 类，规格要求的 17 类中缺「等待回复/支付交易/快递行程/社交/系统告警/垃圾邮件」等 |
| 死信队列 / 指数退避 | ⚠️ 部分 | AI 临时失败有指数退避重试；邮件同步失败只记录 `last_error` 并在下个轮询周期重试，无正式 DLQ |
| 数据导出 | ❌ 未实现 | 可直接取 SQLite 文件，无导出 API |
| 定时数据库备份 | ❌ 未实现 | 仓库内没有备份任务；可按 DEPLOY_OAUTH.md 第 7 节用 `sqlite3 .backup` 自行配置定时任务，数据库须与 `MAILHUB_SECRET` 成对备份 |
| 密钥轮换 | ✅ 已实现 | 设置 `MAILHUB_SECRET_PREVIOUS` 为旧值后重启，凭据自动改用新密钥加密；之后可移除该变量 |

## 十、测试

```bash
cd /opt/mailhub
MAILHUB_SECRET=test venv/bin/python -m pytest tests/ -q
```

GitHub Actions 在每次推送和 PR 时运行全部测试（`.github/workflows/ci.yml`）。覆盖提示词注入（8 载荷）、AI 自动降级、三省六部路由、中风险/注入自动标记、治理幂等与脱敏审计、策略分级、受保护分类、清理白名单、安全扫描、规则引擎、Message-ID 去重、软删除与邮件解析，以及受保护邮件的确认顺序、服务器动作的线程连接、IMAP 只清除自己的邮件、UIDVALIDITY 重建与同步水位。

测试全部使用合成邮件 fixture，仓库内无任何真实邮件或凭证。

## 邮箱 OAuth 登录

- Microsoft 与 Google 网页登录采用授权码 + PKCE S256，并校验随机 `state`。
- 临时 OAuth 事务十分钟过期且只能消费一次；设备码仅加密保存在服务器。
- access token 与 refresh token 使用 `MAILHUB_SECRET` 经 HKDF 派生的 Fernet 密钥加密。
- OAuth 成功后必须先通过 IMAP XOAUTH2 打开目标账户的 `INBOX`，通过后才写入数据库。
- 同一账户的令牌刷新使用进程内互斥锁串行执行；刷新失效会标记为需要重新登录。
- QQ、网易等未提供通用第三方 IMAP OAuth 的服务商仍要求其官方授权码，系统不会保存或尝试普通账户密码绕过限制。

部署与服务商客户端配置见 `OAUTH_SETUP.md`。
