package com.mailhub.client.ui.screens.settings

import com.mailhub.client.data.settings.AiScopeUncertain
import com.mailhub.client.data.settings.SettingsDto

/** 截图与状态机测试共用的示例设置：Bark 和 Onepush 已配置，Telegram 未配置。 */
val SettingsFixture: SettingsDto = SettingsDto(
    aiEnabled = true,
    aiBaseUrl = "https://ai.example.com/v1",
    aiKeySet = true,
    aiModel = "gemini-2.5-flash",
    aiSendBody = true,
    aiScope = AiScopeUncertain,
    aiBackfillDays = 30,
    cleanOtpDays = 7,
    cleanServer = false,
    syncReadState = false,
    cleanBillMinorDays = 0,
    digestHour = 8,
    notifyBarkSet = true,
    notifyBarkHost = "bark.example.com",
    notifyTgTokenSet = false,
    notifyTgChat = "",
    notifyOnepushSet = true,
    notifyOnepushProvider = "serverchanturbo",
    notifyImportant = true,
    notifyOtp = false,
    bodyKeepDays = 90,
)

/** 加载完成、还没编辑过的状态。 */
fun settingsFixtureState(settings: SettingsDto = SettingsFixture): SettingsUiState =
    SettingsUiState(loading = false, saved = settings, draft = settings.toDraft())
