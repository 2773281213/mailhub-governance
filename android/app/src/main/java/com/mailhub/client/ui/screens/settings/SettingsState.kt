package com.mailhub.client.ui.screens.settings

import com.mailhub.client.data.settings.AiScopeAll
import com.mailhub.client.data.settings.AiScopeOther
import com.mailhub.client.data.settings.AiScopeUncertain
import com.mailhub.client.data.settings.SettingsDto
import com.mailhub.client.data.settings.SettingsUpdate

/**
 * 设置页的五个区块，按页面顺序排列。
 * 编号按实际显示的顺序现算：「同步」被隐藏时，后面的区块顺延，不留空号。
 */
enum class SettingsSection(val title: String) {
    Ai("治理与 AI"),
    Push("推送"),
    Cleanup("清理与晨报"),
    Sync("同步"),
    Password("管理密码"),
}

/** 开关字段：属于哪一块、显示什么、怎么从设置里读、怎么写进请求体。 */
enum class SwitchField(val section: SettingsSection, val label: String) {
    AiEnabled(SettingsSection.Ai, "启用 AI 判定"),
    AiSendBody(SettingsSection.Ai, "发送正文给 AI"),
    NotifyImportant(SettingsSection.Push, "推送重要与安全邮件"),
    NotifyOtp(SettingsSection.Push, "推送验证码"),
    CleanServer(SettingsSection.Cleanup, "清理时同时删除邮箱服务器上的邮件"),
    SyncReadState(SettingsSection.Sync, "与邮箱服务器同步已读状态（双向）"),
    ;

    fun read(settings: SettingsDto): Boolean = when (this) {
        AiEnabled -> settings.aiEnabled
        AiSendBody -> settings.aiSendBody
        NotifyImportant -> settings.notifyImportant
        NotifyOtp -> settings.notifyOtp
        CleanServer -> settings.cleanServer
        SyncReadState -> settings.syncReadState ?: false
    }

    fun write(update: SettingsUpdate, value: Boolean): SettingsUpdate = when (this) {
        AiEnabled -> update.copy(aiEnabled = value)
        AiSendBody -> update.copy(aiSendBody = value)
        NotifyImportant -> update.copy(notifyImportant = value)
        NotifyOtp -> update.copy(notifyOtp = value)
        CleanServer -> update.copy(cleanServer = value)
        SyncReadState -> update.copy(syncReadState = value)
    }
}

/** 天数字段的上限：输入框最多 5 位数字，范围内的值后端都原样保存。 */
private const val MaxDays: Int = 99_999

/** 数字输入框最多几位。 */
const val MaxNumberDigits: Int = 5

/**
 * 数字字段。[range] 与后端的钳位范围一致：超出范围就不提交，
 * 免得后端悄悄改成别的值，页面上显示的和实际保存的不一样。
 */
enum class NumberField(
    val section: SettingsSection,
    val label: String,
    val range: IntRange,
    val errorText: String,
) {
    AiBackfillDays(SettingsSection.Ai, "历史回溯天数", 0..365, "请填写 0–365 的整数"),
    DigestHour(SettingsSection.Cleanup, "晨报时间（点）", 0..23, "请填写 0–23 的整数"),
    CleanOtpDays(SettingsSection.Cleanup, "验证码保留天数", 0..MaxDays, DaysErrorText),
    BodyKeepDays(SettingsSection.Cleanup, "正文保留天数", 0..MaxDays, DaysErrorText),
    CleanBillMinorDays(SettingsSection.Cleanup, "一般账单保留天数", 0..MaxDays, DaysErrorText),
    ;

    fun read(settings: SettingsDto): Int = when (this) {
        AiBackfillDays -> settings.aiBackfillDays
        DigestHour -> settings.digestHour
        CleanOtpDays -> settings.cleanOtpDays
        BodyKeepDays -> settings.bodyKeepDays
        CleanBillMinorDays -> settings.cleanBillMinorDays
    }

    fun write(update: SettingsUpdate, value: Int): SettingsUpdate = when (this) {
        AiBackfillDays -> update.copy(aiBackfillDays = value)
        DigestHour -> update.copy(digestHour = value)
        CleanOtpDays -> update.copy(cleanOtpDays = value)
        BodyKeepDays -> update.copy(bodyKeepDays = value)
        CleanBillMinorDays -> update.copy(cleanBillMinorDays = value)
    }

    /** 输入框里的文字转成要提交的值；空、非数字、超出范围都返回 null。 */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in range }
}

private const val DaysErrorText: String = "请填写 0 或正整数"

/** 输入框只收 ASCII 数字，最多 [MaxNumberDigits] 位。 */
fun digitsOnly(text: String): String = text.filter { it in '0'..'9' }.take(MaxNumberDigits)

/** AI 判定范围的三个选项，顺序就是页面上从左到右的顺序。 */
val AiScopeOptions: List<Pair<String, String>> = listOf(
    AiScopeAll to "全部邮件",
    AiScopeUncertain to "规则拿不准的",
    AiScopeOther to "仅规则未识别的",
)

/** 页面上正在编辑的值。数字保留成文字，方便输入到一半时校验。 */
data class SettingsDraft(
    val switches: Map<SwitchField, Boolean> = emptyMap(),
    val numbers: Map<NumberField, String> = emptyMap(),
    val aiScope: String = AiScopeAll,
) {
    fun switch(field: SwitchField): Boolean = switches[field] ?: false

    fun number(field: NumberField): String = numbers[field].orEmpty()
}

fun SettingsDto.toDraft(): SettingsDraft = SettingsDraft(
    switches = SwitchField.entries.associateWith { it.read(this) },
    numbers = NumberField.entries.associateWith { it.read(this).toString() },
    aiScope = aiScope,
)

/** 某一块的保存结论。 */
sealed interface SectionChange {

    /** 有数字填错了，不发请求。 */
    data class Invalid(val errors: Map<NumberField, String>) : SectionChange

    data object Unchanged : SectionChange

    /** 只含这一块里改动过的字段。 */
    data class Changed(val update: SettingsUpdate) : SectionChange
}

/** 对比这一块的编辑值与已保存的值，得出要提交的字段。其他区块的改动一律不带。 */
fun sectionChange(
    section: SettingsSection,
    saved: SettingsDto,
    draft: SettingsDraft,
): SectionChange {
    val parsed = NumberField.entries
        .filter { it.section == section }
        .associateWith { it.parse(draft.number(it)) }
    val errors = parsed.filterValues { it == null }.keys.associateWith { it.errorText }
    if (errors.isNotEmpty()) {
        return SectionChange.Invalid(errors)
    }
    var update = SettingsUpdate()
    SwitchField.entries.filter { it.section == section }.forEach { field ->
        val value = draft.switch(field)
        if (value != field.read(saved)) {
            update = field.write(update, value)
        }
    }
    parsed.forEach { (field, value) ->
        if (value != null && value != field.read(saved)) {
            update = field.write(update, value)
        }
    }
    if (section == SettingsSection.Ai && draft.aiScope != saved.aiScope) {
        update = update.copy(aiScope = draft.aiScope)
    }
    return if (update == SettingsUpdate()) SectionChange.Unchanged else SectionChange.Changed(update)
}

/**
 * 保存成功后把已提交的值并进本地的「已保存」。提交前已按后端的钳位范围校验过，
 * 后端存下的就是这些值，不必重新拉取（重新拉取会冲掉其他区块还没保存的编辑）。
 */
fun SettingsDto.applied(update: SettingsUpdate): SettingsDto = copy(
    aiEnabled = update.aiEnabled ?: aiEnabled,
    aiSendBody = update.aiSendBody ?: aiSendBody,
    aiScope = update.aiScope ?: aiScope,
    aiBackfillDays = update.aiBackfillDays ?: aiBackfillDays,
    cleanOtpDays = update.cleanOtpDays ?: cleanOtpDays,
    cleanServer = update.cleanServer ?: cleanServer,
    cleanBillMinorDays = update.cleanBillMinorDays ?: cleanBillMinorDays,
    syncReadState = update.syncReadState ?: syncReadState,
    digestHour = update.digestHour ?: digestHour,
    notifyImportant = update.notifyImportant ?: notifyImportant,
    notifyOtp = update.notifyOtp ?: notifyOtp,
    bodyKeepDays = update.bodyKeepDays ?: bodyKeepDays,
)

/** 改密码的三个输入框。 */
enum class PasswordField(val label: String) {
    Old("原密码"),
    New("新密码"),
    Confirm("确认新密码"),
}

/** 与后端 `len(new_password) < 8` 一致，按 Unicode 码点计数。 */
const val MinPasswordLength: Int = 8

/** 改密码的输入。toString 不带密码。 */
data class PasswordDraft(
    val old: String = "",
    val new: String = "",
    val confirm: String = "",
) {
    fun value(field: PasswordField): String = when (field) {
        PasswordField.Old -> old
        PasswordField.New -> new
        PasswordField.Confirm -> confirm
    }

    fun with(field: PasswordField, text: String): PasswordDraft = when (field) {
        PasswordField.Old -> copy(old = text)
        PasswordField.New -> copy(new = text)
        PasswordField.Confirm -> copy(confirm = text)
    }

    override fun toString(): String = "PasswordDraft(***)"
}

/** 改密码前的本地校验；有任何一条不通过就不发请求。 */
fun passwordErrors(draft: PasswordDraft): Map<PasswordField, String> = buildMap {
    if (draft.old.isEmpty()) {
        put(PasswordField.Old, "请输入原密码")
    }
    if (draft.new.codePointCount(0, draft.new.length) < MinPasswordLength) {
        put(PasswordField.New, "新密码至少 $MinPasswordLength 位")
    }
    if (draft.confirm != draft.new) {
        put(PasswordField.Confirm, "两次输入的新密码不一致")
    }
}

const val SavedText: String = "已保存"
const val UnchangedText: String = "这一块没有改动"
const val PasswordChangedText: String = "密码已修改，其他设备已退出"
const val PasswordChangedReloginText: String = "密码已修改，请用新密码重新登录"

/** 打开 clean_server 前的确认面板。 */
const val CleanServerConfirmTitle: String = "同时删除服务器上的邮件？"
const val CleanServerConfirmMessage: String = "开启后，自动清理会同时删除邮箱服务器上的邮件，删除后无法恢复。"

/** 设置页的界面状态。 */
data class SettingsUiState(
    val loading: Boolean = true,
    /** 整页还没有内容时的失败说明；各区块的结果走 [feedback]。 */
    val error: String? = null,
    /** 后端已保存的值；null 表示还没加载成功。 */
    val saved: SettingsDto? = null,
    val draft: SettingsDraft = SettingsDraft(),
    /** 保存时没通过校验的数字字段，改动该字段后清除。 */
    val numberErrors: Map<NumberField, String> = emptyMap(),
    val password: PasswordDraft = PasswordDraft(),
    val passwordErrors: Map<PasswordField, String> = emptyMap(),
    /** 有请求在途的区块（保存或测试推送）；同一时间只允许一个。 */
    val pending: SettingsSection? = null,
    /** 各区块最近一次操作的结果说明，显示在该区块的按钮下面。 */
    val feedback: Map<SettingsSection, String> = emptyMap(),
    /** 正在等确认打开 clean_server。 */
    val confirmingCleanServer: Boolean = false,
) {
    /** 实际显示的区块：后端没返回 sync_read_state 时隐藏「同步」。 */
    val sections: List<SettingsSection>
        get() = SettingsSection.entries.filter {
            it != SettingsSection.Sync || saved?.syncReadState != null
        }

    /** 区块编号 01 02 …，按实际显示的顺序。 */
    fun indexOf(section: SettingsSection): String =
        (sections.indexOf(section) + 1).toString().padStart(2, '0')
}

sealed interface SettingsEvent {

    data object Retry : SettingsEvent

    /** clean_server 从关到开不会直接生效，先进确认面板。 */
    data class SwitchChanged(val field: SwitchField, val checked: Boolean) : SettingsEvent

    data class NumberChanged(val field: NumberField, val text: String) : SettingsEvent

    data class ScopeChanged(val scope: String) : SettingsEvent

    data object ConfirmCleanServer : SettingsEvent

    data object DismissCleanServer : SettingsEvent

    data class PasswordChanged(val field: PasswordField, val text: String) : SettingsEvent

    /** 「保存 →」：只提交这一块里改动过的字段；管理密码块是「修改密码 →」。 */
    data class Save(val section: SettingsSection) : SettingsEvent

    data object TestPush : SettingsEvent
}

/** 「已配置」「未配置」，有附加信息时接在后面：已配置 · bark.example.com。 */
fun configuredText(configured: Boolean, detail: String = ""): String = when {
    !configured -> "未配置"
    detail.isBlank() -> "已配置"
    else -> "已配置 · $detail"
}

/** Telegram 要令牌和 Chat ID 都填了才算能用，与后端 notify._any_channel 的判断一致。 */
fun telegramConfigured(settings: SettingsDto): Boolean =
    settings.notifyTgTokenSet && settings.notifyTgChat.isNotBlank()
