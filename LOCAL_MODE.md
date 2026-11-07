# 安卓本机模式设计

本文是 `feature/android-local-mode` 分支的设计依据：安卓 App 在保留「连接服务器」模式的同时，新增不依赖任何服务器的「本机模式」。每个实现任务都以本文为准；与本文冲突的改动要先改本文。

## 一、目标与非目标

**目标**

- 同一个 APK 两种模式：打开 App 先选「本机独立使用」或「连接服务器」。连接服务器模式的行为与现在完全一致。
- 本机模式在手机上完成收信、存储、三省六部治理、验证码提取、风险标记、搜索和通知，不需要服务器，也不需要网页端。
- 所有页面、ViewModel、DTO 与截图测试保持不动；本机模式只替换「API 请求由谁回答」。

**非目标**

- 不改服务器端和网页端的行为（只新增导出金标语料的脚本）。
- 本机数据与服务器数据互不同步，不做合并、迁移或双写。
- 不移植 Onepush；本机模式的推送以系统通知为主。

## 二、两种模式的差异

| | 连接服务器模式 | 本机模式 |
|---|---|---|
| 进入方式 | 服务器地址 + 管理密码 | 直接进入（以后可选生物识别锁） |
| 数据位置 | 服务器 SQLite | 手机 SQLite（随 App 打包的 SQLite） |
| 收信 | 服务器 7×24，按账户 `poll_interval` | 前台即时；后台 JobScheduler ≥15 分钟；可选前台服务 + IMAP IDLE |
| 治理实现 | Python | Kotlin 移植，靠金标语料对齐 |
| 通知 | 服务器推送 + App 每 15 分钟拉 `/api/changes` | 本机系统通知 |
| 邮箱凭据 | 服务器保存，App 只显示「已配置 / 未配置」 | 手机保存（Keystore 加密），App 内填写 |
| OAuth | 服务器网页回调 | 公共客户端（Outlook 设备码 / PKCE）；Gmail 先用应用专用密码；网易用授权码 |
| 多设备、网页端 | 支持，会话可远程吊销 | 仅本机 |
| 断网 | 不可用 | 已同步的邮件可离线阅读和搜索 |
| 只在该模式出现 | 设备会话管理、退出其他设备、改管理密码 | 凭据输入、实时收信开关、导出备份 |

## 三、架构

```text
Compose 页面 ── Gateway ── Repository ── ApiClient(Retrofit + OkHttp)
                                              │
                         ┌────────────────────┴───────────────────┐
                 连接服务器模式                                  本机模式
              HTTPS → MailHub 服务器              LocalApiInterceptor（进程内，不出网）
                                                              │
                                                  :core 模块（纯 Kotlin/JVM）
                                                  LocalApi 路由（移植 app.py）
                                                  ├─ db       SQLite schema、FTS5 trigram、审计
                                                  ├─ mail     MIME 解析、IMAP 会话（Angus Mail）
                                                  ├─ sync     账户同步、水位、UIDVALIDITY、已读同步
                                                  ├─ rules    本地规则、验证码提取
                                                  ├─ security 安全扫描、外部动作策略
                                                  ├─ billing  账单画像与分级
                                                  ├─ governance 三省六部编排（AI 可选）
                                                  └─ ai       OpenAI 兼容接口（后期）
```

### 3.1 接入点：进程内拦截器

本机模式下 `ApiClient` 的地址是保留域名 `https://local.mailhub.invalid/`，并多挂一个 OkHttp 应用拦截器 `LocalApiInterceptor`。拦截器把每个请求（方法、路径、查询参数、JSON 请求体）交给 `:core` 的 `LocalApi.handle(...)`，直接构造响应返回，永远不调用 `chain.proceed`，所以不会发出任何网络请求，也不监听任何端口。

选这一层的原因：

- App 的所有请求（7 个页面网关、后台 `ChangesSync`、会话恢复）都经过 `ApiClient`；在这一层接入，一次就全覆盖。页面网关是给测试用的薄壳，后台任务也不经过它们。
- 响应沿用服务器的 JSON 形状和错误语义（HTTP 状态码 + `{"detail": "..."}`），现有 DTO、错误映射和测试都不用改。`:core` 的路由逐个对照 `app.py` 移植，服务器就是规格。
- 会话不需要新类型：本机会话就是一个普通 `Session`（地址是保留域名，令牌是安装时生成的随机值），`SessionManager.newClient` 看到保留域名时挂上拦截器。

### 3.2 `:core` 模块

- 纯 Kotlin/JVM 模块（`android/core`，包名 `com.mailhub.core`），不依赖 Android SDK，单元测试在 JVM 上直接跑，不需要 Robolectric。
- 存储用 `androidx.sqlite` 的打包驱动（`sqlite-bundled`）：Android 8–13 自带的 SQLite 低于 3.34，没有 trigram 分词器；打包驱动在手机和 JVM 测试里用的是同一份 SQLite。SQL 尽量逐条照搬 `db.py` 与 `app.py`。
- IMAP 与 MIME 用 Eclipse Angus Mail（Jakarta Mail 实现）。网易要求的 RFC 2971 `ID` 必须在认证之后、`SELECT` 之前发送，用 `IMAPStore.id(...)`，不要等打开文件夹再发。载荷与 `mail_client.py` 的 `IMAP_ID_PAYLOAD` 一致。
- 凭据加解密通过 `:core` 定义的 `SecretCipher` 接口注入：App 用 Android Keystore 的 AES-GCM 实现（独立的密钥别名，不复用会话令牌的密钥），测试用假实现。`:core` 里不出现任何密钥。

## 四、移植规则（每个移植任务都必须遵守）

1. **Python 是规格。** 移植以 `main` 上的 Python 源码为准，逐个函数对照，保留函数名的对应关系（Kotlin 用驼峰），在 KDoc 里写明对应的 Python 函数。行为不同的地方必须在任务总结的「偏差」里写明。
2. **金标语料。** 每个纯逻辑模块配一个导出脚本 `scripts/golden/<模块>.py`：用 Python 原实现跑合成输入，把输入和输出写成 `android/core/src/test/resources/golden/<模块>.json`；Kotlin 测试逐条回放，结果必须完全一致。输入只用合成数据（`tests/classification_corpus.py` 与各测试里的 fixture），不准出现真实邮件、地址或验证码。导出脚本支持 `--check`：重新生成并与已提交的 JSON 比较，不一致就以非零退出，CI 用它发现两边规则漂移。
3. **正则语义。** Python 3 的 `str` 正则默认按 Unicode 匹配：`\w`、`\d`、`\b`、`\s` 都认 Unicode（`\d` 连全角数字也算），`re.I` 做 Unicode 大小写折叠。Java 默认只认 ASCII。所有移植的正则一律通过 `:core` 的统一构造函数编译，固定带 `UNICODE_CHARACTER_CLASS`（需要忽略大小写时再加 `CASE_INSENSITIVE` 与 `UNICODE_CASE`），不准直接 `Regex(...)` 或 `Pattern.compile(...)`。`re.match` 对应从开头匹配，`re.search` 对应查找，不要混用 `matches()`。
4. **安全约束不变。** 自动治理只允许 `label_only`；邮件正文、主题、发件人、附件名一律按不可信输入处理；不可逆的服务器动作在服务器成功后才更新本地状态并写审计。
5. **注释和 KDoc 用中文**，与仓库现有约定一致。

## 五、后台收信

- **前台：** App 启动和回到前台时（距上次同步超过 60 秒）触发 `/api/sync-all`，本机引擎在进程内并发同步各账户（并发上限 3，与服务器一致）。
- **后台：** 沿用现有 `ChangesJobService`（JobScheduler，15 分钟）。本机模式下任务先执行到期账户的同步与治理（总时长控制在 8 分钟内），再走现有的 `/api/changes` → `ChangeRules` → `ChangesNotifier` 通知流程；通知可见性与锁屏脱敏规则不变。
- **实时（后期）：** 设置里的「实时收信」开关启动前台服务，对选定账户保持 IMAP IDLE。各家对 IDLE 的支持要先用 `CAPABILITY` 实测。

## 六、安全模型的变化

- 本机模式下邮箱授权码、OAuth 令牌和 AI Key 都保存在手机上，用 Keystore 密钥加密，`allowBackup=false` 不变。没有了服务端吊销兜底：手机丢失或被 root 后，凭据的保护只剩 Keystore 和系统沙箱。
- 「App 不接收任何凭据」这条边界只在连接服务器模式下保留；本机模式下凭据输入框只在本机会话里出现。
- 本机模式不监听端口，所有 API 请求在进程内完成。
- SECURITY.md 要新增「安卓本机模式」一节，在本机模式可用之前完成（任务 L11）。

## 七、阶段与任务

任务文件放在 `.agenthub/tasks/`，由规划方派给执行方，审核方审查后合并。括号里是依赖。

**P0：本机模式最小可用**（授权码类账户、收信、本地规则、通知、搜索）

| 编号 | 内容 | 依赖 |
|---|---|---|
| L1 | `:core` 模块地基：建模块、引依赖，验证打包 SQLite 的 FTS5 trigram、Angus Mail 解析、release 包 R8 能过；接入检查脚本和 CI | — |
| L2 | `rules.py` + `policy.py` 移植，金标语料，统一正则构造函数 | L1 |
| L3 | `security_scan.py` 移植（含 `ai.py` 里的注入检测与脱敏函数），金标语料 | L1 |
| L4 | `billing.py` 移植，金标语料 | L1 |
| L5 | `providers.py` + `mail_client.py` 解析部分移植，金标语料 | L1 |
| L6 | `db.py` schema 与迁移、设置读写、审计、FTS 搜索 | L1 |
| L7 | IMAP 会话与 `sync_account` 移植（测试用本地假 IMAP 服务） | L5、L6 |
| L8 | `governance.py` 移植（AI 关闭路径） | L2、L3、L4、L6 |
| L9 | `LocalApi` 路由：App 用到的全部接口，按 `app.py` 移植 | L6、L7、L8 |
| L10 | App 接入：拦截器、登录页「本机独立使用」入口、Keystore `SecretCipher`、后台任务、设置页按模式显示 | L9 |
| L11 | SECURITY.md / README 更新，MuMu 真机验收 | L10 |

**P1：** AI 判定与门下省护栏、账单分级相关操作、晨报、清理、导出备份。
**P2：** Outlook OAuth（设备码 / PKCE 公共客户端）、实时收信前台服务。
**P3：** Gmail OAuth、生物识别锁。

## 八、验收约定

- 纯逻辑任务：`:core:test` 全绿，金标回放 100% 一致，导出脚本 `--check` 通过。
- 涉及 App 的任务：`android\scripts\check.ps1`（风格守卫、单元测试、截图比对、lint、打包）全过；涉及依赖或 R8 的任务另跑 `:app:assembleRelease`。
- P0 结束时在 MuMu 上真机验收：加一个授权码类账户，收信、分类、验证码通知、搜索、删除都要走通。
