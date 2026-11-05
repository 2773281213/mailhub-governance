package com.mailhub.client.ui.screens.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.captureRoboImage
import com.mailhub.client.ui.screens.accounts.AccountsBody
import com.mailhub.client.ui.screens.accounts.AccountsFixtureZone
import com.mailhub.client.ui.screens.accounts.AddAccountBody
import com.mailhub.client.ui.screens.accounts.accountsFixtureState
import com.mailhub.client.ui.screens.accounts.addDeviceFixtureState
import com.mailhub.client.ui.screens.common.LocalMailNow
import com.mailhub.client.ui.screens.common.MailRow
import com.mailhub.client.ui.screens.common.MailRowData
import com.mailhub.client.ui.screens.inbox.InboxFixtureMeta
import com.mailhub.client.ui.screens.inbox.InboxFixtureNowTs
import com.mailhub.client.ui.screens.security.SecurityBody
import com.mailhub.client.ui.screens.security.SecurityFixtureCategories
import com.mailhub.client.ui.screens.security.SecurityFixtureZone
import com.mailhub.client.ui.screens.security.securityFixtureState
import com.mailhub.client.ui.screens.settings.SettingsBody
import com.mailhub.client.ui.screens.settings.SettingsUiState
import com.mailhub.client.ui.screens.settings.settingsFixtureState
import com.mailhub.client.ui.swiss.PageTitle
import com.mailhub.client.ui.swiss.SwissScaffold
import com.mailhub.client.ui.swiss.SwissTabBar
import com.mailhub.client.ui.swiss.SwissTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 整页截图：用 SwissScaffold + 各页 Body（header 里放 PageTitle）拼出外壳里的实际页面，
 * 不经过 ShellHost，不访问网络。浅色 360dp，示例数据写死，「现在」固定。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ShellPageScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalMailNow provides InboxFixtureNowTs) {
                SwissTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h2600dp-xxhdpi")
    fun securityPageLightW360() = capture("page_security_light_w360") {
        val title = "安全"
        SwissScaffold(
            index = "04",
            section = "安全",
            meta = InboxFixtureMeta.line,
            actionLabel = IndexActionLabel,
            onAction = {},
            bottomBar = {
                SwissTabBar(items = AppTabs, selected = 3, onSelect = {})
            },
        ) {
            SecurityBody(
                state = securityFixtureState(),
                zoneId = SecurityFixtureZone,
                categories = SecurityFixtureCategories,
                onEvent = {},
                modifier = Modifier.fillMaxSize(),
                header = { PageTitle(text = title) },
            )
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    fun accountsPageLightW360() = capture("page_accounts_light_w360") {
        val title = "账户"
        SwissScaffold(
            index = "05",
            section = "账户",
            meta = InboxFixtureMeta.line,
            actionLabel = "← 返回",
            onAction = {},
        ) {
            AccountsBody(
                state = accountsFixtureState(),
                zoneId = AccountsFixtureZone,
                onEvent = {},
                modifier = Modifier.fillMaxSize(),
                header = { PageTitle(text = title) },
            )
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h2600dp-xxhdpi")
    fun settingsPageLightW360() = capture("page_settings_light_w360") {
        val title = "设置"
        SwissScaffold(
            index = "06",
            section = "设置",
            meta = InboxFixtureMeta.line,
            actionLabel = "← 返回",
            onAction = {},
        ) {
            SettingsBody(
                state = settingsFixtureState(),
                onEvent = {},
                modifier = Modifier.fillMaxSize(),
                header = { PageTitle(text = title) },
            )
        }
    }

    /** 加载中也要有大标题：页头先渲染，下面是加载条。 */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun settingsPageLoadingLightW360() = capture("page_settings_loading_light_w360") {
        val title = "设置"
        SwissScaffold(
            index = "06",
            section = "设置",
            meta = InboxFixtureMeta.line,
            actionLabel = "← 返回",
            onAction = {},
        ) {
            SettingsBody(
                state = SettingsUiState(loading = true),
                onEvent = {},
                modifier = Modifier.fillMaxSize(),
                header = { PageTitle(text = title) },
            )
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun addAccountDevicePageLightW360() = capture("page_add_account_device_light_w360") {
        val title = "添加账户"
        SwissScaffold(
            index = "05",
            section = "添加账户",
            meta = InboxFixtureMeta.line,
            actionLabel = "← 账户",
            onAction = {},
        ) {
            AddAccountBody(
                state = addDeviceFixtureState(),
                onEvent = {},
                modifier = Modifier.fillMaxSize(),
                header = { PageTitle(text = title) },
            )
        }
    }

    /** 设备码在 360dp 下必须排成一行，不能被裁掉。 */
    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun deviceCodeFitsOnOneLineAt360dp() {
        composeRule.setContent {
            SwissTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AddAccountBody(state = addDeviceFixtureState(), onEvent = {})
                }
            }
        }

        val node = composeRule.onNodeWithText("QZ7XK4JN9", useUnmergedTree = true)
            .fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val action = node.config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("拿不到设备码的文本布局结果", action != null && action.invoke(layouts))
        val layout = layouts.first()
        assertEquals("设备码应该只有一行", 1, layout.lineCount)
    }

    @Test
    @Config(qualifiers = "w360dp-h400dp-xxhdpi")
    fun mailRowLongSenderLightW360() = capture("mailrow_long_sender_light_w360") {
        Column(modifier = Modifier.fillMaxSize()) {
            MailRow(
                data = MailRowData(
                    id = 1,
                    subject = "账户存在异常登录，请立即验证你的身份",
                    sender = "notice@paypa1-secure.example",
                    dateTs = InboxFixtureNowTs - 1800,
                    isUnread = true,
                    snippet = "这是一段示例摘要，用来说明这封邮件的要点，最多显示两行。",
                    categoryIndex = 6,
                    categoryName = "可疑",
                ),
                zoneId = "Asia/Shanghai",
            )
        }
    }
}
