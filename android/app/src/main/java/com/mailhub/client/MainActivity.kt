package com.mailhub.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mailhub.client.ui.swiss.SwissSpecimen
import com.mailhub.client.ui.swiss.SwissTheme

/** T07 起用设计系统样张占位；T08 起接业务页面。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SwissTheme {
                SwissSpecimen()
            }
        }
    }
}
