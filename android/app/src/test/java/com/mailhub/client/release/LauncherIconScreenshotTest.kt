package com.mailhub.client.release

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.mailhub.client.R
import com.mailhub.client.ui.PixelProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

/**
 * 启动器图标截图：把自适应图标的 background（纯色米白）和 foreground（方块 + 竖条）
 * 叠画到 432×432px 的方形画布上，录成 src/test/screenshots/icon_launcher_square.png。
 *
 * 这里只画方形，不画圆形：圆形 / 圆角方形裁切是启动器自己的行为，
 * 构图里出现圆就等于把裁切逻辑抄进了图标。
 * 画完再把 PNG 读回来验几个点，保证录下来的确实是「米白底 + 黑方块 + 红竖条」，
 * 而不是一张空图。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 144dp × xxhdpi（density 3）= 432px，整块窗口就是画布。
@Config(sdk = [36], qualifiers = "w144dp-h144dp-xxhdpi")
class LauncherIconScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 进版本库的那张：recordRoborazziDebug 时写盘，verifyRoborazziDebug 时逐像素比对。 */
    private val screenshot = File("src/test/screenshots/icon_launcher_square.png")

    /** 探针图：显式 Record 写到 build 目录，用来把刚画出来的像素读回来做断言。 */
    private val probe = File("build/icon-probe/icon_launcher_square.png")

    @Test
    fun iconLauncherSquare() {
        val context = RuntimeEnvironment.getApplication()
        val paper = Color(context.getColor(R.color.paper))
        val fills = mapOf(
            "@color/ink" to Color(context.getColor(R.color.ink)),
            "@color/red" to Color(context.getColor(R.color.red)),
        )
        val layers = LauncherIconGeometry.read("ic_launcher_foreground.xml").map { path ->
            fills.getValue(path.fillColor) to path.toShape()
        }

        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // background 层：自适应图标声明的是纯色 @color/paper
                    drawRect(color = paper)
                    // foreground 层：108dp 画布等比放大到画布边长
                    val scale = size.width / LauncherIconGeometry.CANVAS
                    withTransform({ scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero) }) {
                        layers.forEach { (color, path) -> drawPath(path = path, color = color) }
                    }
                }
            }
        }

        screenshot.parentFile?.mkdirs()
        composeRule.onRoot().captureRoboImage(screenshot.path)

        probe.parentFile?.mkdirs()
        composeRule.onRoot().captureRoboImage(
            probe.path,
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )

        val image = ImageIO.read(probe)
        requireNotNull(image) { "读取图标截图失败：${probe.path}" }
        assertEquals("截图必须是正方形 432×432px", 432, image.width)
        assertEquals("截图必须是正方形 432×432px", 432, image.height)

        // 画布 108dp → 432px，换算关系是 4px/dp
        val unit = image.width / LauncherIconGeometry.CANVAS
        fun pixelAt(xDp: Float, yDp: Float): Int =
            image.getRGB((xDp * unit).toInt(), (yDp * unit).toInt())

        assertTrue("(48, 54) 应该落在黑方块里", PixelProbe.isInk(pixelAt(48f, 54f)))
        assertTrue("(33, 33) 是方块的左上角，必须是直角而不是圆弧", PixelProbe.isInk(pixelAt(33f, 33f)))
        assertTrue("(30, 30) 在方块外，应该还是米白底", PixelProbe.isPaper(pixelAt(30f, 30f)))
        assertTrue("(66, 54) 是方块与竖条之间的空隙，应该是米白底", PixelProbe.isPaper(pixelAt(66f, 54f)))
        assertTrue("(74, 54) 应该落在红色竖条里", PixelProbe.isRed(pixelAt(74f, 54f)))
        assertTrue("(74, 30) 在竖条上方，红色只能是一条竖条，不能是圆斑", PixelProbe.isPaper(pixelAt(74f, 30f)))
        assertTrue("(8, 54) 是左边留白，应该是米白底", PixelProbe.isPaper(pixelAt(8f, 54f)))
    }

    /** 把解析出来的顶点按顺序连成一条闭合折线；安全区与直线命令由 LauncherIconVectorTest 保证。 */
    private fun IconPath.toShape(): Path = Path().apply {
        points.forEachIndexed { index, point ->
            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }
}
