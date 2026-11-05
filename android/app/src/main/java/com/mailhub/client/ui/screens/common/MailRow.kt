package com.mailhub.client.ui.screens.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.mailhub.client.data.model.Account
import com.mailhub.client.ui.swiss.GridRow
import com.mailhub.client.ui.swiss.HairlineRule
import com.mailhub.client.ui.swiss.LocalGridSpec
import com.mailhub.client.ui.swiss.LocalGridWidth
import com.mailhub.client.ui.swiss.LocalSwissColors
import com.mailhub.client.ui.swiss.SText
import com.mailhub.client.ui.swiss.SwissSpace
import com.mailhub.client.ui.swiss.SwissType
import com.mailhub.client.ui.swiss.baselineTopPadding
import com.mailhub.client.ui.swiss.cols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 一条邮件行的展示数据。收件箱与总览页共用同一种行结构：
 * 第 1 栏标记、第 2–4 栏时间、第 5–12 栏内容（主题、发件人、摘要、分类）。
 * 总览页不显示摘要和分类，对应字段留空即可。
 */
data class MailRowData(
    val id: Long,
    val subject: String,
    val sender: String,
    val dateTs: Double,
    val isUnread: Boolean,
    val snippet: String = "",
    /** 分类在 /api/meta 的 display_categories 里的序号（从 1 开始），0 表示没有编号。 */
    val categoryIndex: Int = 0,
    val categoryName: String = "",
)

/** 需要红色小方块的分类：重要、安全、可疑。文字本身仍是黑色。 */
val MarkedCategories: Set<String> = setOf("重要", "安全", "可疑")

/** 缺值时统一用这个占位符，不用破折号以外的字符。 */
private const val EmptyText = "—"

/**
 * 列表行用的「当前时间」（秒）。生产环境取设备时间；截图测试用 CompositionLocalProvider
 * 固定它，PNG 才不会因为「当天 / 其他日期」的判定随日期变化。
 */
val LocalMailNow = compositionLocalOf { System.currentTimeMillis() / 1000.0 }

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("MM-dd")

/** 时区名不可用时退回设备时区，不让整行失败。 */
private fun zoneOrSystem(zoneId: String): ZoneId =
    runCatching { ZoneId.of(zoneId) }.getOrElse { ZoneId.systemDefault() }

/**
 * 邮件时间：当天显示 HH:mm，其他日期显示 MM-dd，按服务器时区。
 *
 * @param nowTs 「现在」的秒数，由 [LocalMailNow] 下发，测试可以直接给定。
 */
fun mailStampText(dateTs: Double, zoneId: String, nowTs: Double): String {
    if (dateTs <= 0.0) return EmptyText
    val zone = zoneOrSystem(zoneId)
    val at = Instant.ofEpochSecond(dateTs.toLong()).atZone(zone)
    val now = Instant.ofEpochSecond(nowTs.toLong()).atZone(zone)
    val format = if (at.toLocalDate() == now.toLocalDate()) clockFormat else dayFormat
    return at.format(format)
}

/**
 * 分类编号：按 /api/meta 的 display_categories 顺序从 1 开始编号，找不到返回 0。
 * 收件箱的筛选行和邮件行里的分类都用同一套编号。
 */
fun displayCategoryNumber(categories: List<String>, name: String): Int {
    if (name.isBlank()) return 0
    val index = categories.indexOf(name)
    return if (index >= 0) index + 1 else 0
}

/** 分类标签，「编号 + 名称」，编号补齐两位；没有编号时只显示名称。 */
fun categoryLabel(index: Int, name: String): String = when {
    name.isBlank() -> ""
    index <= 0 -> name
    else -> "${index.toString().padStart(2, '0')} $name"
}

/** 该分类前面是否要加红色小方块。 */
fun categoryHasMark(name: String): Boolean = name in MarkedCategories

/** 账户按账户列表顺序编号：A1、A2 …；账户不在列表里时返回空串。 */
fun accountLabel(accounts: List<Account>, accountId: Int): String {
    val index = accounts.indexOfFirst { it.id == accountId }
    return if (index >= 0) "A${index + 1}" else ""
}

/**
 * 共享的邮件行。左边缘全部落在栏线上，只用前景色文字和红色小方块：
 *
 * - 第 1 栏标记栏：未读是 8dp 红色方块，已读留空；多选模式下换成选中方块（实心 / 描边）。
 * - 第 2–4 栏时间，与内容首行基线对齐。
 * - 第 5–12 栏内容：主题在第一行（未读加粗），发件人 + 分类在第二行，摘要最多两行。
 *
 * 有分类时，发件人默认只占第 5–8 栏；地址太长放不下时（V1 之后风险邮件显示真实地址，
 * 单行截断会把最要紧的域名藏起来），发件人独占第 5–12 栏一行、不截断，分类连同红色小方块
 * 挪到下一行，仍在原来的栏位。
 *
 * @param selected null 表示不在多选模式，true / false 表示多选模式下的勾选状态。
 */
@Composable
fun MailRow(
    data: MailRowData,
    zoneId: String,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = LocalSwissColors.current
    val nowTs = LocalMailNow.current
    val interactionSource = remember { MutableInteractionSource() }
    val subjectStyle = if (data.isUnread) {
        SwissType.body().copy(fontWeight = FontWeight.Bold)
    } else {
        SwissType.body()
    }
    val categoryText = categoryLabel(data.categoryIndex, data.categoryName)
    // 分类写满第 9–12 栏，发件人默认退到第 5–8 栏；没有分类时发件人独占第 5–12 栏
    val senderSpan = if (categoryText.isEmpty()) 8 else 4
    // 有分类时先量一次：第 5–8 栏一行放不下就换成「发件人独占一行 + 分类下一行」
    val senderFitsColumn = senderFitsInSpan(data.sender, span = 4)
    val senderOnOwnRow = categoryText.isNotEmpty() && !senderFitsColumn

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick == null && onLongClick == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                },
            ),
    ) {
        HairlineRule(color = colors.foreground)
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
        // 首行：标记 | 时间 | 主题
        val stampText = mailStampText(data.dateTs, zoneId, nowTs)
        GridRow {
            Box(modifier = Modifier.cols(1, 1)) {
                RowMark(isUnread = data.isUnread, selected = selected)
            }
            SText(
                text = stampText,
                style = SwissType.caption(),
                // 时间用 caption、主题用 body，按首行基线对齐要给时间补一点顶部内边距；
                // 两边都传真实文字，中文回退字体下的基线差才量得对
                modifier = Modifier
                    .cols(2, 3)
                    .padding(
                        top = baselineTopPadding(
                            fromStyle = SwissType.caption(),
                            fromText = stampText,
                            toStyle = subjectStyle,
                            toText = data.subject,
                        ),
                    ),
                color = colors.foreground,
                maxLines = 1,
            )
            SText(
                text = data.subject,
                style = subjectStyle,
                modifier = Modifier.cols(5, 8),
                color = colors.foreground,
                maxLines = 2,
            )
        }
        if (data.sender.isNotEmpty() || categoryText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(SwissSpace.Xxs))
            // 第二行：发件人 + 分类，分类的编号左缘落在第 10 栏栏线上；
            // 地址放不下时发件人独占第 5–12 栏一行，分类挪到下一行，仍在原来的栏位
            GridRow {
                SText(
                    text = data.sender,
                    style = SwissType.label(),
                    modifier = Modifier.cols(5, if (senderOnOwnRow) 8 else senderSpan),
                    color = colors.foreground,
                    maxLines = if (senderOnOwnRow) Int.MAX_VALUE else 1,
                )
                if (categoryText.isNotEmpty() && !senderOnOwnRow) {
                    CategoryMark(name = data.categoryName)
                    CategoryText(text = categoryText)
                }
            }
            if (senderOnOwnRow) {
                Spacer(modifier = Modifier.height(SwissSpace.Xxs))
                GridRow {
                    CategoryMark(name = data.categoryName)
                    CategoryText(text = categoryText)
                }
            }
        }
        if (data.snippet.isNotEmpty()) {
            Spacer(modifier = Modifier.height(SwissSpace.Xxs))
            GridRow {
                SText(
                    text = data.snippet,
                    style = SwissType.caption(),
                    modifier = Modifier.cols(5, 8),
                    color = colors.foreground,
                    maxLines = 2,
                )
            }
        }
        Spacer(modifier = Modifier.height(SwissSpace.Xs))
    }
}

/**
 * 发件人在第 [span] 栏里一行放不放得下：用 label 样式和真实文字量一次自然宽度，
 * 放不下就让发件人独占第 5–12 栏，避免把域名截掉。
 */
@Composable
private fun senderFitsInSpan(sender: String, span: Int): Boolean {
    val measurer = rememberTextMeasurer()
    val style = SwissType.label()
    val spec = LocalGridSpec.current
    val gridWidth = LocalGridWidth.current
    val density = LocalDensity.current
    return remember(measurer, sender, style, spec, gridWidth, density, span) {
        val available = with(density) { spec.spanWidth(span, gridWidth).roundToPx() }
        measurer.measure(
            text = AnnotatedString(sender),
            style = style,
            maxLines = 1,
            softWrap = false,
        ).size.width <= available
    }
}

/** 分类前面第 9 栏的红色小方块；不需要标记时仍占住栏位，行高不变。 */
@Composable
private fun CategoryMark(name: String) {
    val colors = LocalSwissColors.current
    Box(modifier = Modifier.cols(9, 1)) {
        if (categoryHasMark(name)) {
            Box(
                modifier = Modifier
                    .size(SwissSpace.Xs)
                    .background(colors.accent),
            )
        }
    }
}

/** 分类「编号 + 名称」，占第 10–12 栏。 */
@Composable
private fun CategoryText(text: String) {
    SText(
        text = text,
        style = SwissType.label(),
        modifier = Modifier.cols(10, 3),
        color = LocalSwissColors.current.foreground,
        maxLines = 1,
    )
}

/**
 * 第 1 栏的标记：未读是一个 8dp 红色方块；多选模式下换成选中方块，
 * 选中为实心，未选为 1dp 描边（线宽取当前密度，正好 1dp）。
 */
@Composable
private fun RowMark(isUnread: Boolean, selected: Boolean?) {
    val colors = LocalSwissColors.current
    if (selected == null) {
        if (isUnread) {
            Box(
                modifier = Modifier
                    .size(SwissSpace.Xs)
                    .background(colors.accent),
            )
        }
        return
    }
    Box(modifier = Modifier.size(SwissSpace.S)) {
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.foreground),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = colors.foreground,
                    topLeft = Offset(density / 2, density / 2),
                    size = androidx.compose.ui.geometry.Size(
                        width = size.width - density,
                        height = size.height - density,
                    ),
                    style = Stroke(width = density),
                )
            }
        }
    }
}
