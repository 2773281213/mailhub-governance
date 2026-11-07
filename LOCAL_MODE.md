# 安卓本机模式设计

本文是 `feature/android-local-mode` 分支的设计依据：安卓 App 在保留「连接服务器」模式的同时，新增不依赖任何服务器的「本机模式」。每个实现任务都以本文为准；与本文冲突的改动要先改本文。

修订记录：初稿 887ab48；按 Gemini 4 Argon 的设计审核（中继任务 j20261107-044619-2b84d7）修订：通知游标、请求头、按模式分支的页面、IMAP 安全、遗漏的 `oauth_auth.py` 接口、任务依赖和事实错误。

## 一、目标与非目标

**目标**

- 同一个 APK 两种模式：打开 App 先选「本机独立使用」或「连接服务器」。连接服务器模式的行为与现在完全一致。
- 本机模式在手机上完成收信、存储、三省六部治理、验证码提取、风险标记、搜索和通知，不需要服务器，也不需要网页端。
- 总览、收件箱、详情、验证码看板、账户管理这些业务页面，以及它们的 ViewModel、DTO 和连接服务器模式的截图基线保持不动。本机模式只替换「API 请求由谁回答」。
- 按模式分支的只有：登录页（多一个入口）、设置页、安全中心（隐藏已登录设备）、外壳的退出确认文案。这些页面要补本机模式的截图测试。

**非目标**

- 不改服务器端和网页端的行为（只新增导出金标语料的脚本和对应的 CI 检查）。
- 本机数据与服务器数据互不同步，不做合并、迁移或双写。
- 不移植 Onepush；本机模式的推送以系统通知为主。

## 二、两种模式的差异

| | 连接服务器模式 | 本机模式 |
|---|---|---|
| 进入方式 | 服务器地址 + 管理密码 | 直接进入（P1 起可选应用锁） |
| 数据位置 | 服务器 SQLite | 手机 SQLite（随 App 打包的 SQLite） |
| 收信 | 服务器 7×24，按账户 `poll_interval` | 前台即时；后台 JobScheduler ≥15 分钟；可选前台服务 + IMAP IDLE |
| 治理实现 | Python | Kotlin 移植，靠金标语料对齐 |
| 通知 | 服务器推送 + App 每 15 分钟拉 `/api/changes` | 本机同步完成后立即走通知流程 |
| 邮箱凭据 | 服务器保存，App 只显示「已配置 / 未配置」 | 手机保存（Keystore 加密），App 内填写 |
| OAuth | 服务器网页回调 | P2 起：公共客户端（Outlook 设备码 / PKCE）；Gmail 先用应用专用密码；网易用授权码 |
| 多设备、网页端 | 支持，会话可远程吊销 | 仅本机 |
| 断网 | 不可用 | 已同步的邮件可离线阅读和搜索 |
| 只在该模式出现 | 设备会话管理、退出其他设备、改管理密码、测试推送 | 凭据输入（P1）、实时收信开关（P2）、导出备份（P1） |

## 三、架构

```text
Compose 页面 ── Gateway ── Repository ── ApiClient(Retrofit + OkHttp)
                                              │  AuthInterceptor → LocalApiInterceptor（只在本机会话里挂）
                         ┌────────────────────┴───────────────────┐
                 连接服务器模式                                  本机模式
              HTTPS → MailHub 服务器              LocalApiInterceptor（进程内，不出网）
                                                              │
                                                  :core 模块（纯 Kotlin/JVM）
                                                  LocalApi 路由（移植 app.py 与 oauth_auth.py 的接口）
                                                  ├─ db         SQLite schema、FTS5 trigram、审计
                                                  ├─ mail       MIME 解析、IMAP 会话（Angus Mail）
                                                  ├─ providers  服务商注册表
                                                  ├─ sync       账户同步、水位、UIDVALIDITY、已读同步、同步协调器
                                                  ├─ rules      本地规则、验证码提取、外部动作策略
                                                  ├─ security   安全扫描、提示注入检测与脱敏
                                                  ├─ billing    账单画像与分级
                                                  ├─ governance 三省六部编排（AI 可选）
                                                  └─ text       Python 语义工具（Py.kt、PyHtml.kt）
```

### 3.1 接入点：进程内拦截器

本机会话的地址是保留域名 `https://local.mailhub.invalid/`。`AppContainer` 里传给 `SessionManager` 的 `clientFactory` 看到这个主机名时，让 `ApiClient` 额外挂一个 OkHttp 应用拦截器 `LocalApiInterceptor`，**排在 `AuthInterceptor` 之后**，这样 `Authorization` 头和 401 处理照常生效。拦截器把请求（方法、路径、查询参数、请求头、JSON 请求体）交给 `:core` 的 `LocalApi.handle(...)`，按返回的状态码、响应头和 JSON 体直接构造响应，永远不调用 `chain.proceed`。所以它不会发出任何网络请求，也不监听任何端口。

- 响应要能带头。例如详情页删除受保护邮件时，服务器靠 403 加 `X-MailHub-Protected: 1` 让 App 弹二次确认（`app.py` 的 `/api/messages/batch`，`ApiClient.toApiError` 读这个头）。
- 选这一层的原因：App 的所有后端请求都经过 `ApiClient`，包括 8 个页面网关、总览和外壳直接持有的 `MailRepository`、后台的 `ChangesSync`。在这一层接入，一次就全覆盖。页面网关只是给测试用的薄壳，后台任务不经过它们。响应沿用服务器的 JSON 形状和错误语义（状态码 + `{"detail": "..."}`），现有 DTO、错误映射和测试都不用改。`:core` 的路由对照 `app.py` 移植，服务器就是规格。
- 会话恢复（`SessionManager.restore`）只读本地 DataStore，不发请求，不受影响。

**本机会话的约定**

- 进入本机模式走新的 `AuthRepository.enterLocalMode()`，不调用 `/api/login`：
  - 生成一个新的随机令牌，写入 `SessionStore`，`expiresTs = 0.0`（永不过期）；
  - 不写 `LastServerStore`；
  - 把通知游标设为本机库当前的 `max(id)`（见第五节）。
- `LocalApi` 不校验令牌，也从不返回 401：它只在进程内被调用，没有外部入口。这样恢复备份或清库都不会把用户踢出本机模式。
- 退出本机模式只清会话，不删本机数据库；再次进入时生成新令牌，看到原来的数据。删除本机数据另做明确入口（P1 的设置页）。
- `ServerUrl.parse` 拒绝以 `.invalid` 结尾的主机名，防止用户在「连接服务器」表单里手填保留域名。
- 外壳的退出确认按模式给文案：服务器模式仍是「需要重新输入管理密码」；本机模式说明「本机数据会保留」。

### 3.2 `:core` 模块

- 纯 Kotlin/JVM 模块（`android/core`，包名 `com.mailhub.core`），不依赖 Android SDK，单元测试在 JVM 上直接跑，不需要 Robolectric。
- **存储**用 `androidx.sqlite` 的打包驱动（`sqlite-bundled` 2.7.1，SQLite 3.50.1）。Android 8–12（API 26–32）自带的 SQLite 低于 3.34，没有 trigram 分词器（Android 13 起自带 3.39）；`minSdk` 是 26，所以打包。手机和 JVM 测试里用的是同一份 SQLite。SQL 尽量逐条照搬 `db.py` 与 `app.py`。
  - 打包的本地库四种 ABI 共约 4.9 MB，release 包从 2.2 MB 涨到 7.1 MB。L10 用 ABI 拆分或只保留 arm64 解决。
- **IMAP 与 MIME** 用 Eclipse Angus Mail 2.0.5（Jakarta Mail 实现）。
  - 只用 `imaps`（993，隐式 TLS），开启 `mail.imaps.ssl.checkserveridentity=true`。不允许明文 `imap`，不允许放行自签名证书。原因：Angus Mail 用原始 socket，不受 App 的 `network_security_config.xml` 约束，必须在代码里强制。
  - 连接超时 15 秒、读超时 30 秒（`mail.imaps.connectiontimeout` / `mail.imaps.timeout`）。协程取消时关闭 `IMAPStore`，后台任务被系统停止时能及时退出。
  - 网易的 RFC 2971 `ID`：只对 `providers.needs_imap_id(...)` 为真或账户带 `need_id` 的账户发送，时机是认证之后、`SELECT` 之前，与 `mail_client.py` 一致。用 `IMAPStore.id(mapOf("name" to "MailHub", "version" to "1.1", "vendor" to "MailHub", "support-email" to "mailhub@localhost"))`，字段与 `IMAP_ID_PAYLOAD` 一致。
  - `mail.mime.decodefilename` 与 `mail.mime.decodeparameters` 是 JVM 系统属性，由 `Mime` 初始化时设置（L1 已实现）。
- **凭据**加解密通过 `com.mailhub.core.SecretCipher` 注入，语义与 `security.py` 一致：空串进空串出，解不开返回空串。App 用 Android Keystore 的 AES-GCM 实现，密钥别名独立，不复用会话令牌的密钥；测试用假实现。`:core` 里不出现任何密钥。
- **时间**一律来自 `com.mailhub.core.Clock`（对应 `time.time()`），测试注入可控时钟。

## 四、移植规则（每个移植任务都必须遵守）

1. **Python 是规格。** 移植以 `main` 上的 Python 源码为准，逐个函数对照，保留函数名的对应关系（Kotlin 用驼峰），在 KDoc 里写明对应的 Python 函数。行为不同的地方必须在任务总结的「偏差」里写明。
2. **金标语料。** 每个纯逻辑模块配一个导出脚本 `scripts/golden/<模块>.py`（公共部分在 `_common.py`）：用 Python 原实现跑合成输入，把输入和输出写成 `android/core/src/test/resources/golden/<模块>.json`，Kotlin 测试逐条回放，结果必须完全一致。
   - 输入只用合成数据（`tests/classification_corpus.py` 与各测试里的 fixture），不准出现真实邮件、地址或验证码。
   - 导出脚本支持 `--check`。`scripts/golden/check_all.py` 会逐个检查目录下的所有脚本，CI 的 golden 任务在 Python 3.13 上运行它。新脚本放进目录就会自动纳入。
3. **Python 语义统一走 `com.mailhub.core.text`：**
   - 正则只用 `PyRegex.compile` / `compileUser` / `escape`，不准直接 `Regex(...)` 或 `Pattern.compile(...)`。它已经处理了：`\w \s \b` 按 Python 定义重写（Java 把 emoji 变体选择符 U+FE0F 算作单词字符）、`UNIX_LINES`（Python 只把 `\n` 当换行）、`(?P<name>…)`、`\Z`、`{,n}`、字符类里的 `[` 和 `&&`。Unicode 大小写折叠（含 `İ`、`ı`、`ſ`、开尔文符号）实测与 Python 3.13 一致。
   - `re.match` 对应 `lookingAt()`，`re.search` 对应 `find()`，`re.fullmatch` 对应 `matches()`。带位置的 `pattern.match(text, pos)` 用 `region(pos, len)`，并设置 `useTransparentBounds(true)`、`useAnchoringBounds(false)`。
   - 下标、切片、`len` 一律按码点：`cpLen`、`cpSlice`、`cpIndex`。
   - 空白与换行用 `isPySpace`、`pySplit`、`pyStrip`、`pyLstrip`、`pySplitLines`（Python 的 `isspace` 认 `&nbsp;`）。
   - `float()` / `int()` / `round()` / `f"{x:.2f}"` 分别用 `Py.float`、`Py.int`、`Py.round`、`Py.formatFixed`。`\d` 能匹配全角数字，所以数字解析必须认全角。
   - 真值判断用 `Py.truthy`；`html.unescape` 用 `PyHtml.unescape`。
   - Python 源码里的不可见字符或特殊空白（例如 `_FENCE_RE` 里的 U+2028、U+3000）在 Kotlin 里一律写成 `\uXXXX` 转义，不要直接粘贴。
4. **Python 自身的不确定性要避开。** Python 的 `set` 遍历顺序受每个进程随机的字符串哈希影响（例如 `security_scan.scan` 的 `list(hosts)[:40]`）。Kotlin 用插入顺序；金标里不放结果依赖这种顺序的用例；任务总结里写明。
5. **写数据库的 JSON 文本**不必与 Python `json.dumps` 逐字相同，但解析回来的值必须相同。
6. **安全约束不变。** 自动治理只允许 `label_only`；邮件正文、主题、发件人、附件名一律按不可信输入处理；不可逆的服务器动作在服务器成功后才更新本地状态并写审计。
7. **注释和 KDoc 用中文**，与仓库现有约定一致。

## 五、收信与通知

- **同步协调器**（`:core` 的 sync 包）取代 `sync.py` 的 30 秒心跳线程和 `_force_queue`：手机进程没有常驻循环，照搬会导致第 4 个以后的账户永远轮不到。
  - 协调器用协程 `Semaphore(3)` 并发处理全部到期或被点名的账户（并发上限与服务器一致）。
  - 某个账户有新邮件（`n > 0`）时，立即对这批邮件跑治理，与 `sync.py` 里 `sync_account` 之后紧跟 `ai_classify_pending` / 治理的顺序一致。这样 `/api/changes` 的「待治理屏障」能及时解除。
- **通知统一在同步之后立即触发**：前台同步和后台任务完成后，都在同一个协调流程里调用一次 `ChangesSync.runOnce()`，不等下一个 15 分钟周期。
  - **前台**：App 启动和回到前台时（距上次同步超过 60 秒）触发。
  - **后台**：沿用现有 `ChangesJobService`（JobScheduler，15 分钟），本机模式下先同步再通知，总时长控制在 8 分钟内。`onStopJob` 取消协程，并关闭进行中的 IMAP 连接。
- **本机 `/api/changes` 与服务器的两处不同：**
  1. `after_id = 0` 表示「从头列出」，不是服务器那样「只返回水位」。进入本机模式时 App 把游标设为本机库当前的 `max(id)`（空库为 0），所以第一次同步导入的邮件不会被当成历史基线吞掉。`after_id < 0` 才是初始化。
  2. 账户**首次同步**（`last_uid == 0`）导入的最近 30 天历史邮件不通知。同步结束时，在 `settings` 里记下 `local_initial_import_<账户 id>` = 这批邮件的最大 id；`/api/changes` 跳过该账户 id 不超过这个值的邮件，但游标照常前进。
- **实时（P2）：** 设置里的「实时收信」开关启动前台服务，对选定账户保持 IMAP IDLE。各家对 IDLE 的支持要先用 `CAPABILITY` 实测。

## 六、安全模型的变化

- **凭据在手机上。** 本机模式下邮箱授权码、OAuth 令牌和 AI Key 都保存在手机上，用 Keystore 密钥加密，`allowBackup=false` 不变。没有了服务端吊销兜底：手机丢失或被 root 后，凭据的保护只剩 Keystore 和系统沙箱。
- **邮件内容明文落盘。** 连接服务器模式不在手机上保存任何邮件；本机模式的 `messages` 表（正文、发件人、验证码）在应用沙箱里是明文，没有用 SQLCipher。设备被 root 或做物理镜像后可以直接读取。P0 接受这一点并在 SECURITY.md 写明；是否加整库加密在 P1 评估。
- **没有应用锁，退出也不是锁定。** 服务器模式退出后要重输管理密码，并有防爆破退避；本机模式在应用锁落地之前直接进入。拿到已解锁手机的人点「本机独立使用」就能看到全部邮件和验证码。应用锁（生物识别或设备凭据）提前到 P1。
- **导出备份（P1）**一旦写成文件就离开了沙箱和 `allowBackup=false` 的保护。导出必须剥离全部凭据；包含邮件内容时，必须用用户口令加密（PBKDF2 或 Argon2 + AES-GCM）。
- **IMAP 原始 socket 不受 `network_security_config.xml` 约束**，所以在代码里强制 `imaps` 和主机名校验（见 3.2）。本机配置的 AI 地址（P1）在正式版只允许 `https://`。
- **OAuth 公共客户端（P2）**没有 `client_secret`，授权码通过本机 URI 回调，存在同 scheme 被抢注的风险。必须使用 PKCE S256，回跳优先用经过验证的 App Links；Outlook 优先用设备码。
- **「App 不接收任何凭据」这条边界只在连接服务器模式下保留**；本机模式下凭据输入框只在本机会话里出现。`SettingsUpdate` 扩展凭据字段时，`toString()` 必须遮蔽密钥。
- **本机模式不监听端口**，所有 API 请求在进程内完成。
- SECURITY.md 要新增「安卓本机模式」一节，写清以上各点，在本机模式可用之前完成（任务 L11）。

## 七、阶段与任务

任务文件放在 `.agenthub/tasks/`，由规划方写、派给执行方，审核方审查后合并。共用的基础件（`Py.kt`、`PyHtml.kt`、`Golden.kt`、`_common.py`、`check_all.py`、`Clock`、`SecretCipher`）由规划方维护，执行方不改。

**P0：本机模式最小可用**（授权码类账户、收信、本地规则、通知、搜索）

| 编号 | 内容 | 依赖 |
|---|---|---|
| L1 | ✅ `:core` 模块地基：打包 SQLite（FTS5 trigram）、Angus Mail、R8 正式包、检查脚本和 CI（e09034b） | — |
| L2 | `rules.py` + `policy.py` 移植，金标语料 | L1 |
| L3 | `security_scan.py` + `ai.py` 的注入检测与脱敏函数移植，金标语料 | L1 |
| L4 | `billing.py` 移植，金标语料 | L1 |
| L5a | `providers.py` + 邮件头解析（`email` 标准库的头部语义）移植，金标语料 | L1 |
| L5b | `mail_client.py` 的正文解析移植，金标语料 | L5a |
| L6 | `db.py`：schema 与迁移、`Db` 封装（锁、事务）、设置读写、审计、FTS 可用性 | L1 |
| L7 | IMAP 会话（`ImapSession` 全部，含 `store_flags`、`mark_read`、`delete`）、`probe_connection`、`sync_account`、已读状态双向同步、首次导入标记、同步协调器；测试用本地假 IMAP 服务 | L2、L3、L5b、L6 |
| L8 | `governance.py` 移植（AI 关闭路径）、`load_custom_rules`、账单的例行次数与偏好查询 | L2、L3、L4、L6 |
| L9 | `LocalApi`：附录 A 的全部 35 个接口，含 `app.py` 的短词搜索回退和 `oauth_auth.py` 的 `/api/oauth/providers` | L6、L7、L8 |
| L10 | App 接入：拦截器与 `clientFactory`、`enterLocalMode`、`ServerUrl` 拒绝 `.invalid`、Keystore `SecretCipher`、同步协调与通知、按模式分支的登录页 / 设置页 / 安全中心 / 退出文案及截图测试、ABI 拆分 | L9 |
| L11 | SECURITY.md / README 更新，MuMu 真机验收 | L10 |

**P1：** AI 判定与门下省护栏（`SettingsUpdate` 扩展凭据字段）、账单分级相关操作、晨报、清理、应用锁、导出备份（剥离凭据、口令加密）、删除本机数据入口。
**P2：** Outlook OAuth（设备码 / PKCE 公共客户端）、实时收信前台服务。
**P3：** Gmail OAuth。

## 八、验收约定

- 纯逻辑任务：`:core:test` 全绿，金标回放 100% 一致，`scripts/golden/check_all.py` 通过。
- 涉及 App 的任务：`android\scripts\check.ps1`（`:core:test`、风格守卫、单元测试、截图比对、lint、打包）全过；涉及依赖或 R8 的任务另跑 `:app:assembleRelease`。
- P0 结束时在 MuMu 上真机验收：加一个授权码类账户，收信、分类、验证码通知、搜索、删除都要走通；新加账户的历史邮件不弹通知。

## 附录 A：`LocalApi` 要覆盖的接口

App 的 5 个 `*Api.kt` 共 35 个接口。大部分照 `app.py` 移植，下表只列本机语义不同的。

| 接口 | 本机行为 |
|---|---|
| `POST /api/login` | 本机模式不调用。被调用时返回 400，`detail` 为「本机模式不需要登录」 |
| `POST /api/logout` | 返回 `{"ok": true}`，不做任何事（数据保留） |
| `GET /api/sessions` | 返回空列表；安全中心在本机模式下隐藏已登录设备块 |
| `DELETE /api/sessions/{id}`、`POST /api/sessions/revoke-all` | 返回 400，`detail` 为「本机模式没有其他设备」 |
| `PUT /api/settings` | 改管理密码的分支返回 400，`detail` 为「本机模式没有管理密码」；其余字段照服务器语义 |
| `POST /api/notify/test` | 返回 400，`detail` 为「本机模式使用系统通知」；设置页在本机模式下隐藏这个按钮 |
| `GET /api/oauth/providers` | 照 `oauth_auth.public_providers()` 的形状返回。P0 全部服务商的 `oauth_browser` 与 `oauth_device` 都不可用；只支持 OAuth 的服务商（Outlook）标明「本机模式暂不支持，P2 提供」 |
| `POST /api/oauth/start`、`GET /api/oauth/transactions/{id}`、`POST /api/oauth/outlook/device/start`、`POST /api/oauth/outlook/device/poll` | P0 返回 400，`detail` 为「本机模式暂不支持 OAuth 登录」 |
| `GET /api/changes` | 见第五节的两处不同 |
| `POST /api/messages/batch` | 照服务器语义，含受保护邮件的 403 与 `X-MailHub-Protected: 1` 头；`server=true` 时调用 IMAP 写操作 |
