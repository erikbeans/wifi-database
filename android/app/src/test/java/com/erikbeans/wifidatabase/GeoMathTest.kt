package com.erikbeans.wifidatabase

import com.erikbeans.wifidatabase.domain.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun `point inside radius is unchanged`() {
        val (lat, lon) = GeoMath.clampToRadius(37.0, -122.0, 37.001, -122.001, 500.0)
        assertEquals(37.001, lat, 1e-9)
        assertEquals(-122.001, lon, 1e-9)
    }

    @Test
    fun `point far outside radius clamps to the limit`() {
        val fixLat = 37.0
        val fixLon = -122.0
        val (lat, lon) = GeoMath.clampToRadius(fixLat, fixLon, fixLat + 0.05, fixLon, 500.0)
        val distance = GeoMath.distanceMeters(fixLat, fixLon, lat, lon)
        assertTrue("distance $distance should be near 500", distance in 499.0..501.0)
    }

    @Test
    fun `identical point returns itself`() {
        val (lat, lon) = GeoMath.clampToRadius(37.0, -122.0, 37.0, -122.0, 500.0)
        assertEquals(37.0 to -122.0, lat to lon)
    }

    @Test
    fun `haversine distance matches known values`() {
        val seattleToSanFrancisco = GeoMath.distanceMeters(47.6062, -122.3321, 37.7749, -122.4194)
        assertTrue(seattleToSanFrancisco in 1_090_000.0..1_100_000.0)
    }
}
