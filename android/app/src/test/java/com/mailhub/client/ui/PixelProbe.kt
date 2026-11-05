package com.mailhub.client.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 像素扫描工具：把某个节点截成临时 PNG 再逐像素读回来。
 *
 * 抓图必须显式指定 taskType = Record，因为 `verifyRoborazziDebug` 会把整个测试进程
 * 设成 verify 模式，而这个工具要的是「先写一张临时图再读」。
 */
object PixelProbe {

    /** 截图输出目录（build 目录，不进版本库）。 */
    private fun target(name: String): File =
        File("build/swiss-probe/$name.png").also { it.parentFile?.mkdirs() }

    /** 截取节点并读回像素。 */
    fun capture(node: SemanticsNodeInteraction, name: String): BufferedImage {
        val file = target(name)
        node.captureRoboImage(
            file.path,
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        return requireNotNull(ImageIO.read(file)) { "读取截图失败：${file.path}" }
    }

    /** 是否接近米白（Paper）。 */
    fun isPaper(rgb: Int): Boolean {
        val red = (rgb shr 16 and 0xFF) / 255f
        val green = (rgb shr 8 and 0xFF) / 255f
        val blue = (rgb and 0xFF) / 255f
        return red > 0.85f && green > 0.85f && blue > 0.8f
    }

    /** 是否接近纯黑（Ink）。 */
    fun isInk(rgb: Int): Boolean {
        val red = (rgb shr 16 and 0xFF) / 255f
        val green = (rgb shr 8 and 0xFF) / 255f
        val blue = (rgb and 0xFF) / 255f
        return red < 0.05f && green < 0.05f && blue < 0.05f
    }

    /** 是否接近红（Red）。 */
    fun isRed(rgb: Int): Boolean {
        val red = (rgb shr 16 and 0xFF) / 255f
        val green = (rgb shr 8 and 0xFF) / 255f
        val blue = (rgb and 0xFF) / 255f
        return red > 0.7f && green < 0.4f && blue < 0.4f
    }

    /** 统计一行的像素分类。 */
    fun rowStats(image: BufferedImage, y: Int): RowStats {
        var paper = 0
        var ink = 0
        var red = 0
        for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            when {
                isInk(rgb) -> ink++
                isRed(rgb) -> red++
                isPaper(rgb) -> paper++
            }
        }
        return RowStats(paper = paper, ink = ink, red = red)
    }

    data class RowStats(val paper: Int, val ink: Int, val red: Int) {
        /** 这一行里被涂过的像素（不是背景色）。 */
        val painted: Int get() = ink + red
    }
}
