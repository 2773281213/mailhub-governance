package com.mailhub.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mailhub.client.ui.swiss.SwissPalette

/** 本卡只放一个空白页面；T07 起在这里接设计系统，T08 起接业务页面。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SwissPalette.Paper),
            ) {
                BasicText(
                    text = "MailHub",
                    modifier = Modifier.padding(24.dp),
                    style = TextStyle(
                        color = SwissPalette.Ink,
                        fontSize = 32.sp,
                    ),
                )
            }
        }
    }
}
