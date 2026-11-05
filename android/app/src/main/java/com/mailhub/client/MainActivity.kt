package com.mailhub.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mailhub.client.ui.screens.MailHubRoot
import com.mailhub.client.ui.screens.accounts.OAuthReturnBus
import com.mailhub.client.ui.screens.shell.OpenMessageBus
import com.mailhub.client.ui.swiss.SwissTheme
import com.mailhub.client.work.MessageIdExtra

/** 应用入口：先恢复会话，再按会话状态在恢复态 / 登录页 / 主界面之间切换。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 强制边到边显示：这里显式开启，让系统栏透明，
        // 状态栏和导航栏的位置由外壳与各页的安全区让出来。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleOauthReturn(intent)
        handleMessageOpen(intent)
        val container = (application as MailHubApp).container
        setContent {
            SwissTheme {
                MailHubRoot(container = container)
            }
        }
    }

    /** 从浏览器返回（singleTop，复用当前实例）时同样处理深链。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOauthReturn(intent)
        handleMessageOpen(intent)
    }

    /**
     * 处理 `mailhub://oauth/done?tx=…` 深链。
     *
     * 深链里只有事务 id：这里只把它交给 [OAuthReturnBus]，由等待中的添加账户流程判断
     * 是不是自己那条事务；不是自己的 id 会被总线直接丢弃。不接收、不打印任何令牌。
     */
    private fun handleOauthReturn(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals(OAuthReturnScheme, ignoreCase = true)) return
        if (!data.host.equals(OAuthReturnHost, ignoreCase = true)) return
        if (data.path != OAuthReturnPath) return
        val tx = data.getQueryParameter(OAuthReturnTxParam).orEmpty()
        if (tx.isBlank()) return
        OAuthReturnBus.shared.publish(tx)
    }

    /**
     * 处理点通知打开 App：intent extra [MessageIdExtra] 里是邮件 id，
     * 只有大于 0 才交给 [OpenMessageBus]（外壳在已登录时取走并跳到详情页）。
     *
     * 处理完就把 extra 摘掉：Activity 重建时不会拿同一个 id 再跳一次，
     * 保证「取走即清空，同一个 id 只处理一次」。除 id 外不读取、不打印任何内容。
     */
    private fun handleMessageOpen(intent: Intent?) {
        if (intent == null) return
        val messageId = intent.getLongExtra(MessageIdExtra, 0L)
        if (messageId <= 0L) return
        OpenMessageBus.shared.publish(messageId)
        intent.removeExtra(MessageIdExtra)
    }

    private companion object {
        /** 深链 scheme：mailhub。 */
        const val OAuthReturnScheme: String = "mailhub"

        /** 深链 host：oauth。 */
        const val OAuthReturnHost: String = "oauth"

        /** 深链 path：/done。 */
        const val OAuthReturnPath: String = "/done"

        /** 深链里承接事务 id 的参数名。 */
        const val OAuthReturnTxParam: String = "tx"
    }
}
