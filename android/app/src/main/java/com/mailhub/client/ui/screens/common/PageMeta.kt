package com.mailhub.client.ui.screens.common

import com.mailhub.client.data.model.Meta

/**
 * 页头右侧的元信息：设备当前年份 + 由服务器时区换算出来的地点。
 * [zoneId] 是服务器时区原名（例如 Asia/Shanghai），用来按服务器时间格式化钟点。
 */
data class PageMeta(
    val year: Int,
    val place: String,
    val zoneId: String = "",
) {
    /** 形如 2026 — 上海。 */
    val line: String get() = "$year — $place"
}

/** meta 还没拿到时的兜底地点。 */
const val DefaultPlace: String = "MailHub"

/** 常见时区的中文地点，其余时区取时区名的最后一段。 */
private val cityByZone: Map<String, String> = mapOf(
    "Asia/Shanghai" to "上海",
    "Asia/Hong_Kong" to "香港",
    "Asia/Taipei" to "台北",
    "Asia/Tokyo" to "东京",
    "Asia/Singapore" to "新加坡",
    "Europe/London" to "伦敦",
    "Europe/Berlin" to "柏林",
    "America/New_York" to "纽约",
    "America/Los_Angeles" to "洛杉矶",
    "UTC" to "UTC",
)

/**
 * 时区换算成页头显示的地点名。
 * 表里有的用中文；其余取时区名最后一段，下划线换成空格（America/Argentina/Buenos_Aires → Buenos Aires）。
 * 空字符串按「meta 还没拿到」处理，给出兜底地点。不申请定位权限。
 */
fun cityForZone(zoneId: String): String {
    val zone = zoneId.trim()
    if (zone.isEmpty()) return DefaultPlace
    cityByZone[zone]?.let { return it }
    cityByZone.entries.firstOrNull { it.key.equals(zone, ignoreCase = true) }?.let { return it.value }
    val tail = zone.substringAfterLast('/').trim()
    if (tail.isEmpty()) return DefaultPlace
    return tail.replace('_', ' ')
}

/** meta 还没拿到（或没带时区）时显示兜底地点。 */
fun placeForMeta(meta: Meta?): String {
    val zone = meta?.tz ?: return DefaultPlace
    return cityForZone(zone)
}

/** 组装页头元信息：年份取设备当前年份，地点取服务器时区。 */
fun pageMetaOf(year: Int, meta: Meta?): PageMeta = PageMeta(
    year = year,
    place = placeForMeta(meta),
    zoneId = meta?.tz.orEmpty(),
)
