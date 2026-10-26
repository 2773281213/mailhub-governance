package com.mailhub.client.ui.swiss

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 小号编号，例如 01、02。 */
@Composable
fun IndexLabel(
    index: String,
    modifier: Modifier = Modifier,
) {
    SText(
        text = index,
        style = SwissType.label(),
        modifier = modifier,
    )
}

/** 年份、地点、时间一类的小号信息，输出形如 2026 — 上海 — 16:54。 */
@Composable
fun MetaLine(
    year: Int,
    place: String,
    time: String? = null,
    modifier: Modifier = Modifier,
) {
    val text = buildString {
        append(year)
        append(" — ")
        append(place)
        if (time != null) {
            append(" — ")
            append(time)
        }
    }
    SText(
        text = text,
        style = SwissType.label(),
        modifier = modifier,
    )
}
