package com.mailhub.client

import androidx.compose.ui.unit.dp
import com.mailhub.client.ui.swiss.gridSpecFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SwissGridTest {

    @Test
    fun specFor360Uses16And8() {
        val spec = gridSpecFor(360.dp)
        assertEquals(16.dp, spec.margin)
        assertEquals(8.dp, spec.gutter)
        assertEquals(20.dp, spec.columnWidth(360.dp))
        assertEquals(16.dp, spec.x(1, 360.dp))
        assertEquals(324.dp, spec.x(12, 360.dp))
        assertEquals(328.dp, spec.spanWidth(12, 360.dp))
    }

    @Test
    fun specFor411HasQuarterDpColumns() {
        val spec = gridSpecFor(411.dp)
        assertEquals(24.25.dp, spec.columnWidth(411.dp))
    }

    @Test
    fun specFor700Uses24And16() {
        val spec = gridSpecFor(700.dp)
        assertEquals(24.dp, spec.margin)
        assertEquals(16.dp, spec.gutter)
    }

    @Test
    fun specFor900Uses32And24() {
        val spec = gridSpecFor(900.dp)
        assertEquals(32.dp, spec.margin)
        assertEquals(24.dp, spec.gutter)
    }

    @Test
    fun placementBeyondTwelveColumnsThrows() {
        val spec = gridSpecFor(360.dp)
        assertThrows(IllegalArgumentException::class.java) {
            spec.requirePlacement(start = 11, span = 3)
        }
    }

    @Test
    fun placementAtTheEdgeIsAllowed() {
        val spec = gridSpecFor(360.dp)
        spec.requirePlacement(start = 10, span = 3)
    }
}
