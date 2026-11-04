package com.mailhub.client.ui.screens.overview

import com.mailhub.client.data.model.GovernanceRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 治理状态的中文说明必须按后端 `governance_runs.status` 的真实取值映射。
 *
 * 真机验收时总览「本次结果」一栏显示过 `completed 3 封`：后端完成时写的是
 * `completed`（governance.py），而界面只认 `done` / `ok` / `success`，
 * 于是把状态码原样端了出来。这里把三个真实取值和一个未知值都钉住。
 */
class GovernanceStatusTest {

    @Test
    fun backendValuesMapToChinese() {
        assertEquals("已完成", governanceStatusText("completed"))
        assertEquals("进行中", governanceStatusText("running"))
        assertEquals("失败", governanceStatusText("failed"))
    }

    @Test
    fun resultLineUsesTheChineseStatus() {
        assertEquals(
            "已完成 3 封",
            governanceResultText(GovernanceRun(status = "completed", processedCount = 3)),
        )
        assertEquals(
            "进行中 12 封",
            governanceResultText(GovernanceRun(status = "running", processedCount = 12)),
        )
        assertEquals(
            "失败 0 封",
            governanceResultText(GovernanceRun(status = "failed", processedCount = 0)),
        )
    }

    @Test
    fun unknownValueShowsFinishedInsteadOfTheRawCode() {
        assertEquals("已结束", governanceStatusText("interrupted"))
        assertEquals("已结束", governanceStatusText(""))
        assertEquals("已结束", governanceStatusText("some-other-code"))

        // 任何情况下都不把状态码原样显示出来
        val text = governanceResultText(
            GovernanceRun(status = "interrupted", processedCount = 3),
        )
        assertEquals("已结束 3 封", text)
        assertFalse("结果里不能出现状态码", text.contains("interrupted"))
    }

    @Test
    fun missingRunSaysItNeverRan() {
        assertEquals("尚未运行", governanceResultText(null))
    }
}
