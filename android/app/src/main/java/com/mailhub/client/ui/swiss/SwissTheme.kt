package com.mailhub.client.ui.swiss

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

/**
 * 主题入口：提供三色和 12 栏网格，背景铺满 background 色。
 * 深色模式只做黑白互换，红色不变。
 */
@Composable
fun SwissTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) SwissColors.Dark else SwissColors.Light
    CompositionLocalProvider(LocalSwissColors provides colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            ProvideSwissGrid(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}
