package com.mailhub.client.release

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 启动器图标的几何数据来源：直接读 `src/main/res/drawable` 下的 vector XML。
 *
 * 截图测试和「只用直线命令 / 落在安全区内」的断言都读同一份资源，
 * 不在测试里另抄一份坐标，避免改了图标而测试照样通过。
 */
internal object LauncherIconGeometry {

    /** vector 的画布边长：自适应图标固定 108dp。 */
    const val CANVAS = 108f

    /** 安全区：108dp 画布正中、直径 66dp 的圆。 */
    const val SAFE_CENTER = 54f
    const val SAFE_RADIUS = 33f

    /** 允许的命令：直线 M/H/V/L 和闭合 Z；弧线 A、曲线 C/Q/S/T 一律不准出现。 */
    val ALLOWED_COMMANDS = setOf('M', 'H', 'V', 'L', 'Z')

    /** 命令字母或数字（支持负号和小数）。 */
    private val tokenPattern = Regex("[A-Za-z]|-?\\d+(?:\\.\\d+)?")

    private val drawableDir = File("src/main/res/drawable")

    /** 读出一个 vector 里的全部 path，保持 XML 里的顺序（已解析成顶点）。 */
    fun read(fileName: String): List<IconPath> =
        readSources(fileName).map { source -> parse(source.pathData, source.fillColor) }

    /**
     * 只读 XML 里的 pathData 和 fillColor，不做解析。
     *
     * 「命令字母」这类断言要用它：解析器遇到弧线会直接抛错，
     * 先拿到原始文本才能给出「哪条命令不合法」这种可读的断言信息。
     */
    fun readSources(fileName: String): List<IconSource> {
        val file = File(drawableDir, fileName)
        require(file.isFile) { "找不到图标资源：${file.path}" }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("path")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            val pathData = element.getAttribute("android:pathData")
            require(pathData.isNotBlank()) { "${file.name} 里第 ${index + 1} 个 path 没有 pathData" }
            IconSource(pathData = pathData, fillColor = element.getAttribute("android:fillColor"))
        }
    }

    /** pathData 里出现的命令字母，按出现顺序、原样返回（大小写都保留，由调用方判断）。 */
    fun commands(pathData: String): List<Char> =
        tokenPattern.findAll(pathData)
            .map { it.value }
            .filter { it.length == 1 && it[0].isLetter() }
            .map { it[0] }
            .toList()

    /**
     * 解析成绝对坐标的顶点序列。
     *
     * 只接受 M/H/V/L/Z 大写绝对命令；遇到相对命令或弧线/曲线直接失败，
     * 这样规则被绕过时测试会立刻报错，而不是悄悄算出一组错误坐标。
     */
    fun parse(pathData: String, fillColor: String = ""): IconPath {
        val tokens = tokenPattern.findAll(pathData).map { it.value }.toList()
        val commands = mutableListOf<Char>()
        val points = mutableListOf<IconPoint>()
        var cursor = IconPoint(0f, 0f)
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            require(token.length == 1 && token[0].isLetter()) { "pathData 里出现孤立的数字：$pathData" }
            require(token[0].isUpperCase()) { "只允许绝对坐标命令（大写），发现 ${token[0]}：$pathData" }
            val command = token[0]
            require(command in ALLOWED_COMMANDS) {
                "只允许 ${ALLOWED_COMMANDS.sorted().joinToString("/")} 直线命令，发现 $command：$pathData"
            }
            commands += command
            index++
            when (command) {
                'M', 'L' -> {
                    val x = number(tokens, index, pathData).also { index++ }
                    val y = number(tokens, index, pathData).also { index++ }
                    cursor = IconPoint(x, y)
                    points += cursor
                }

                'H' -> {
                    val x = number(tokens, index, pathData).also { index++ }
                    cursor = IconPoint(x, cursor.y)
                    points += cursor
                }

                'V' -> {
                    val y = number(tokens, index, pathData).also { index++ }
                    cursor = IconPoint(cursor.x, y)
                    points += cursor
                }

                // Z 不需要参数，收尾闭合。
                else -> Unit
            }
        }
        require(commands.isNotEmpty()) { "pathData 是空的：$pathData" }
        return IconPath(pathData = pathData, fillColor = fillColor, commands = commands, points = points)
    }

    private fun number(tokens: List<String>, index: Int, pathData: String): Float {
        require(index < tokens.size) { "命令缺少参数：$pathData" }
        val token = tokens[index]
        require(!token[0].isLetter()) { "命令缺少参数：$pathData" }
        return token.toFloat()
    }
}

/** 一个 path 的原始 pathData 和填充色引用，尚未解析。 */
internal data class IconSource(val pathData: String, val fillColor: String)

/** 一个顶点，坐标单位是 vector 画布的 dp。 */
internal data class IconPoint(val x: Float, val y: Float) {

    /** 到安全区圆心的距离平方；比较半径时避免开方误差。 */
    fun distanceSquaredFromCenter(): Float {
        val dx = x - LauncherIconGeometry.SAFE_CENTER
        val dy = y - LauncherIconGeometry.SAFE_CENTER
        return dx * dx + dy * dy
    }
}

/** 一条 path：原始 pathData、填充色引用和解析出来的顶点。 */
internal data class IconPath(
    val pathData: String,
    val fillColor: String,
    val commands: List<Char>,
    val points: List<IconPoint>,
) {

    /**
     * 落在安全区外的顶点。
     *
     * 安全区是圆，圆是凸集，折线每条边都是两端点的凸组合，
     * 所以顶点全在圆内就等价于整条折线都在圆内。
     */
    fun pointsOutsideSafeZone(): List<IconPoint> {
        val limit = LauncherIconGeometry.SAFE_RADIUS * LauncherIconGeometry.SAFE_RADIUS
        return points.filter { it.distanceSquaredFromCenter() > limit }
    }
}
