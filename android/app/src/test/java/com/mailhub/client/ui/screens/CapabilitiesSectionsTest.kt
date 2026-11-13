package com.mailhub.client.ui.screens

import com.mailhub.client.data.model.Capabilities
import com.mailhub.client.ui.screens.security.SecuritySection
import com.mailhub.client.ui.screens.security.SecurityUiState
import com.mailhub.client.ui.screens.settings.SettingsSection
import com.mailhub.client.ui.screens.settings.settingsFixtureState
import org.junit.Assert.assertEquals
import org.junit.Test

/** 两个页面按能力表取区块：关掉一项能力，只少对应的那一块。 */
class CapabilitiesSectionsTest {

    @Test
    fun settingsHidesOnlyTheSectionOfTheDisabledCapability() {
        val cases = listOf(
            SettingsSection.Ai to Capabilities.Server.copy(aiSettings = false),
            SettingsSection.Push to Capabilities.Server.copy(pushSettings = false),
            SettingsSection.Cleanup to Capabilities.Server.copy(cleanupSettings = false),
            SettingsSection.Password to Capabilities.Server.copy(adminPassword = false),
        )

        cases.forEach { (hidden, capabilities) ->
            val state = settingsFixtureState().copy(capabilities = capabilities)
            assertEquals(SettingsSection.entries.filterNot { it == hidden }, state.sections)
        }
    }

    @Test
    fun settingsRenumbersTheRemainingSections() {
        val state = settingsFixtureState()
            .copy(capabilities = Capabilities.Server.copy(pushSettings = false))

        assertEquals(
            listOf(
                SettingsSection.Ai,
                SettingsSection.Cleanup,
                SettingsSection.Sync,
                SettingsSection.Password,
            ),
            state.sections,
        )
        // 编号按实际显示顺序顺延，不留空号
        assertEquals("01", state.indexOf(SettingsSection.Ai))
        assertEquals("02", state.indexOf(SettingsSection.Cleanup))
        assertEquals("03", state.indexOf(SettingsSection.Sync))
        assertEquals("04", state.indexOf(SettingsSection.Password))
    }

    @Test
    fun securityHidesOnlyTheDevicesSection() {
        val state = SecurityUiState(capabilities = Capabilities.Server.copy(deviceSessions = false))

        assertEquals(
            listOf(SecuritySection.Review, SecuritySection.Trash, SecuritySection.Audit),
            state.sections,
        )
    }
}
