package com.mailhub.client.ui.screens

import com.mailhub.client.data.model.Meta
import com.mailhub.client.ui.screens.common.DefaultPlace
import com.mailhub.client.ui.screens.common.cityForZone
import com.mailhub.client.ui.screens.common.pageMetaOf
import com.mailhub.client.ui.screens.common.placeForMeta
import org.junit.Assert.assertEquals
import org.junit.Test

/** 时区换算地点：表里全部时区 + 兜底规则 + meta 缺省。 */
class CityForZoneTest {

    @Test
    fun tableZonesUseChineseCities() {
        val expected = mapOf(
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
        expected.forEach { (zone, city) ->
            assertEquals("$zone 应该换算成 $city", city, cityForZone(zone))
        }
    }

    @Test
    fun unknownZoneTakesLastSegmentAndReplacesUnderscores() {
        assertEquals("Kathmandu", cityForZone("Asia/Kathmandu"))
        assertEquals("Buenos Aires", cityForZone("America/Argentina/Buenos_Aires"))
        assertEquals("Reykjavik", cityForZone("Atlantic/Reykjavik"))
    }

    @Test
    fun zoneWithoutSlashIsKeptAsIs() {
        assertEquals("CET", cityForZone("CET"))
    }

    @Test
    fun blankZoneFallsBackToMailHub() {
        assertEquals(DefaultPlace, cityForZone(""))
        assertEquals(DefaultPlace, cityForZone("   "))
    }

    @Test
    fun metaWithoutTimezoneFallsBackToMailHub() {
        assertEquals(DefaultPlace, placeForMeta(null))
        assertEquals(DefaultPlace, placeForMeta(Meta(tz = "")))
        assertEquals("东京", placeForMeta(Meta(tz = "Asia/Tokyo")))
    }

    @Test
    fun pageMetaKeepsYearPlaceAndZone() {
        val meta = pageMetaOf(year = 2026, meta = Meta(tz = "Asia/Shanghai"))

        assertEquals(2026, meta.year)
        assertEquals("上海", meta.place)
        assertEquals("Asia/Shanghai", meta.zoneId)
        assertEquals("2026 — 上海", meta.line)
    }
}
