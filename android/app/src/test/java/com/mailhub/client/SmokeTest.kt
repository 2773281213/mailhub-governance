package com.mailhub.client

import androidx.compose.ui.graphics.Color
import com.mailhub.client.ui.swiss.SwissPalette
import org.junit.Assert.assertEquals
import org.junit.Test

/** 骨架阶段的冒烟测试：保证设计系统的 3 个基础色没有被改错。 */
class SmokeTest {
    @Test
    fun paletteMatchesSwissDesign() {
        assertEquals(Color(0xFF000000).value, SwissPalette.Ink.value)
        assertEquals(Color(0xFFF2EDE4).value, SwissPalette.Paper.value)
        assertEquals(Color(0xFFDA291C).value, SwissPalette.Red.value)
    }
}
