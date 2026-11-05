package com.mailhub.client.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 启动器图标前景 vector 的两条硬性约束：
 *
 * 1. path 只用 M/H/V/L/Z 直线命令，不能出现弧线（A）和曲线（C/Q/S/T）；
 * 2. 每个顶点都落在 108dp 画布正中、直径 66dp 的安全区内。
 *    启动器会把图标裁成圆形或圆角方形，这是系统行为没法避免，
 *    所以图形必须自己躲进安全区，且构图里不许画圆。
 */
class LauncherIconVectorTest {

    /** 原始 pathData：命令字母的断言要看到未解析的文本，才不会先被解析器拦下。 */
    private val sources = LauncherIconGeometry.readSources("ic_launcher_foreground.xml")

    private val foreground = LauncherIconGeometry.read("ic_launcher_foreground.xml")

    @Test
    fun foregroundUsesStraightLineCommandsOnly() {
        assertTrue("前景 vector 至少要有方块和竖条两个 path，实际 ${sources.size} 个", sources.size >= 2)
        sources.forEach { source ->
            val commands = LauncherIconGeometry.commands(source.pathData)
            val illegal = commands.filterNot { it in LauncherIconGeometry.ALLOWED_COMMANDS }
            assertTrue(
                "path 里出现不允许的命令 $illegal，只允许大写绝对命令 M/H/V/L/Z：${source.pathData}",
                illegal.isEmpty(),
            )
            assertEquals("每个 path 都必须以 M 起始：${source.pathData}", 'M', commands.first())
            assertEquals("每个 path 都必须以 Z 闭合：${source.pathData}", 'Z', commands.last())
            assertEquals(
                "每个 path 只能有一个 M（测试按单条闭合折线重建路径）：${source.pathData}",
                1,
                commands.count { it == 'M' },
            )
        }
    }

    @Test
    fun foregroundStaysInsideSafeZone() {
        foreground.forEach { path ->
            assertTrue("path 没解析出任何顶点：${path.pathData}", path.points.isNotEmpty())
            path.points.forEach { point ->
                val distance = sqrt(point.distanceSquaredFromCenter())
                assertTrue(
                    "顶点 (${point.x}, ${point.y}) 到安全区圆心 (54, 54) 的距离是 $distance dp，" +
                        "超过半径 ${LauncherIconGeometry.SAFE_RADIUS} dp：${path.pathData}",
                    distance <= LauncherIconGeometry.SAFE_RADIUS,
                )
            }
        }
    }

    @Test
    fun monochromeSharesForegroundShape() {
        val monochrome = LauncherIconGeometry.read("ic_launcher_monochrome.xml")
        assertEquals(
            "monochrome 必须与前景同形，只是填充色不同",
            foreground.map { it.pathData },
            monochrome.map { it.pathData },
        )
    }
}
