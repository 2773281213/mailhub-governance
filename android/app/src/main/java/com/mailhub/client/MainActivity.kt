package com.mailhub.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mailhub.client.ui.screens.MailHubRoot
import com.mailhub.client.ui.swiss.SwissTheme

/** 应用入口：先恢复会话，再按会话状态在恢复态 / 登录页 / 主界面之间切换。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 强制边到边显示：这里显式开启，让系统栏透明，
        // 状态栏和导航栏的位置由外壳与各页的安全区让出来。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MailHubApp).container
        setContent {
            SwissTheme {
                MailHubRoot(container = container)
            }
        }
    }
}
