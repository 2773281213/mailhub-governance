package com.mailhub.client.ui.screens.accounts

import com.mailhub.client.data.accounts.AuthMethod
import com.mailhub.client.data.accounts.ProviderInfo

/** 添加账户的三个步骤，编号直接用作区块标题。 */
enum class AddStep(val index: String, val title: String) {
    Email("01", "邮箱地址"),
    Method("02", "选择方式"),
    Execute("03", "执行"),
}

/** 自定义 IMAP 的服务商键，与 providers.py 里的键一致。 */
const val CustomProviderKey: String = "custom"

/** 识别不到服务商时显示的名称。 */
const val CustomProviderLabel: String = "自定义 IMAP"

/** 浏览器 OAuth 的轮询间隔：每 2 秒查一次事务状态。 */
const val BrowserPollIntervalMillis: Long = 2_000L

/** 浏览器 OAuth 最长等 10 分钟。 */
const val BrowserPollTimeoutMillis: Long = 10 * 60 * 1_000L

/** 设备码轮询间隔下限，与后端 device/start 的 max(5, …) 保持一致。 */
const val DeviceMinIntervalSeconds: Int = 5

/** 浏览器 OAuth 等待超时的说明。 */
const val BrowserTimeoutText: String = "等待登录结果超时，请重新授权"

/** 事务过期时的说明。 */
const val BrowserExpiredText: String = "登录已过期，请重新授权"

/** 事务失败但没有 message 时的兜底说明。 */
const val BrowserFailedText: String = "登录失败，请重试"

/** 设备码过期时的说明。 */
const val DeviceExpiredText: String = "设备码已过期，请重新获取"

/** 复制设备码成功后的提示。 */
const val DeviceCodeCopiedText: String = "已复制设备码"

/** 邮箱地址识别出的目标服务商。 */
data class AccountTarget(
    val providerKey: String,
    val label: String,
    val info: ProviderInfo?,
    val isCustom: Boolean,
)

/**
 * 按 `/api/oauth/providers` 的 domains 识别服务商，与后端 providers.detect 的行为一致：
 * 取 @ 后面的域名逐个匹配，按后端下发顺序取第一个命中的服务商；都匹配不上就是自定义 IMAP。
 *
 * 域名还没写出完整形态（没有 @、@ 后面为空、还没有点号）时返回 null，界面先不显示识别结果，
 * 避免用户刚敲两个字母就跳出「自定义 IMAP」和两个多余输入框。
 */
fun detectTarget(email: String, providers: Map<String, ProviderInfo>): AccountTarget? {
    val address = email.trim().lowercase()
    val at = address.indexOf('@')
    if (at < 0) return null
    val domain = address.substring(at + 1).trim()
    if (domain.isEmpty() || !domain.contains('.')) return null
    providers.entries
        .firstOrNull { (key, info) ->
            key != CustomProviderKey && info.domains.any { it.equals(domain, ignoreCase = true) }
        }
        ?.let { entry ->
            return AccountTarget(
                providerKey = entry.key,
                label = entry.value.label,
                info = entry.value,
                isCustom = false,
            )
        }
    val custom = providers[CustomProviderKey]
    return AccountTarget(
        providerKey = CustomProviderKey,
        label = custom?.label?.takeIf { it.isNotBlank() } ?: CustomProviderLabel,
        info = custom,
        isCustom = true,
    )
}

/** 一种认证方式 + 它的两位编号。 */
data class MethodRow(val index: String, val method: AuthMethod)

/**
 * 认证方式按后端 methods 的顺序编号：01、02 ……
 * 顺序由后端决定（OAuth 在前、授权码在后），客户端不重新排序。
 */
fun methodRows(info: ProviderInfo?): List<MethodRow> =
    info?.methods.orEmpty().mapIndexed { index, method ->
        MethodRow(index = (index + 1).toString().padStart(2, '0'), method = method)
    }

/** 服务商级别的说明：unavailable 优先，其次是 oauth_hint，两段都原样显示，不做改写。 */
fun providerNotice(info: ProviderInfo?): String {
    if (info == null) return ""
    if (info.unavailable.isNotBlank()) return info.unavailable
    return info.oauthHint
}

/** 设备码单行最多放几个字符：displaySmall 是 80sp，一行放得下 5 个字符。 */
private const val DeviceCodeCharsPerLine: Int = 5

/**
 * 设备码切行：不超过 5 个字符放一行，更长的均分成两行，
 * 这样 80sp 的大号等宽数字不会被行宽裁掉。
 */
fun deviceCodeLines(code: String): List<String> {
    val compact = code.trim()
    if (compact.isEmpty()) return emptyList()
    if (compact.length <= DeviceCodeCharsPerLine) return listOf(compact)
    val head = compact.take((compact.length + 1) / 2)
    return listOf(head, compact.drop(head.length))
}

/** 设备码页面的展示数据。 */
data class DeviceCodeUi(
    val code: String = "",
    val verificationUri: String = "",
    /** 「复制 →」点过一次后把提示显示出来。 */
    val copied: Boolean = false,
)

/** 添加账户的界面状态。 */
data class AddAccountUiState(
    val step: AddStep = AddStep.Email,
    val email: String = "",
    val imapHost: String = "",
    val imapPort: String = "",
    val providers: Map<String, ProviderInfo> = emptyMap(),
    val loadingProviders: Boolean = false,
    /** 由邮箱域名识别出的服务商；域名还不完整时为 null。 */
    val target: AccountTarget? = null,
    /** 选中方式在 methods 里的下标；只有进入第 3 步之后才有意义。 */
    val selectedMethod: Int = 0,
    val secret: String = "",
    /** 正在发起请求（添加账户 / 取授权页 / 取设备码）。 */
    val submitting: Boolean = false,
    /** 浏览器 OAuth：已经打开授权页，正在轮询结果。 */
    val waitingBrowser: Boolean = false,
    /** 设备码登录的展示数据；null 表示不在设备码流程里。 */
    val deviceCode: DeviceCodeUi? = null,
    val error: String? = null,
    /** 成功后的一行结论：「已添加 A3 私有邮箱」。 */
    val addedLine: String? = null,
) {
    val methodRows: List<MethodRow> get() = methodRows(target?.info)

    /** 服务商的说明文字（unavailable / oauth_hint），原样显示。 */
    val notice: String get() = providerNotice(target?.info)

    val chosen: AuthMethod? get() = methodRows.getOrNull(selectedMethod)?.method
}

sealed interface AddAccountEvent {

    data class EmailChanged(val value: String) : AddAccountEvent

    data class HostChanged(val value: String) : AddAccountEvent

    data class PortChanged(val value: String) : AddAccountEvent

    /** 01 → 02：邮箱确认，进入选择方式。 */
    data object ContinueEmail : AddAccountEvent

    /** 02 → 03：选中一种方式并按方式开始执行。 */
    data class SelectMethod(val index: Int) : AddAccountEvent

    /** 回到第 1 步改邮箱。 */
    data object BackToEmail : AddAccountEvent

    data class SecretChanged(val value: String) : AddAccountEvent

    /** 提交授权码 / 密码：POST /api/accounts。 */
    data object SubmitSecret : AddAccountEvent

    /** 重新发起当前方式（重新打开授权页 / 重新取设备码）。 */
    data object Retry : AddAccountEvent

    /** 用系统浏览器打开当前方式的 setup_url。 */
    data object OpenSetup : AddAccountEvent

    /** 复制设备码。 */
    data object CopyUserCode : AddAccountEvent

    /** 打开设备码登录页。 */
    data object OpenVerification : AddAccountEvent

    /** 清空当前流程，从头再添加一个账户。 */
    data object StartOver : AddAccountEvent
}
