package com.erikbeans.wifidatabase

import com.erikbeans.wifidatabase.domain.NearbyMatcher
import com.erikbeans.wifidatabase.domain.model.NearbyAccessPoint
import com.erikbeans.wifidatabase.domain.model.ScannedNetwork
import com.erikbeans.wifidatabase.domain.model.SecurityTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyMatcherTest {

    private fun ap(
        id: String,
        ssid: String,
        bssid: String? = null,
        distanceM: Double? = null,
        bestScore: Int? = 0,
    ) = NearbyAccessPoint(
        id = id,
        ssid = ssid,
        bssid = bssid,
        security = "WPA2",
        latitude = 0.0,
        longitude = 0.0,
        distanceM = distanceM,
        passwordCount = 1,
        bestScore = bestScore,
    )

    @Test
    fun `bssid match takes precedence over ssid match`() {
        val scanned = listOf(ScannedNetwork("HomeWifi", "aa:bb:cc:11:22:33", "WPA2"))
        val database = listOf(
            ap("ap-1", "DifferentName", bssid = "aa:bb:cc:11:22:33", distanceM = 12.0, bestScore = 5),
            ap("ap-2", "HomeWifi", bssid = "11:22:33:aa:bb:cc", distanceM = 900.0),
        )
        val rows = NearbyMatcher.merge(scanned, database)
        assertEquals(2, rows.size)
        assertEquals("ap-1", rows[0].accessPoint?.id)
        assertTrue(rows[0].passwordAvailable)
        assertEquals(5, rows[0].bestScore)
        assertEquals("ap-2", rows[1].accessPoint?.id)
    }

    @Test
    fun `ssid fallback matches when bssid absent from scan`() {
        val scanned = listOf(ScannedNetwork("HomeWifi", null, "WPA2"))
        val database = listOf(ap("ap-1", "homewifi", bssid = "aa:bb:cc:11:22:33"))
        val rows = NearbyMatcher.merge(scanned, database)
        assertEquals(1, rows.size)
        assertEquals("ap-1", rows[0].accessPoint?.id)
    }

    @Test
    fun `scanned networks without a database entry are marked unavailable`() {
        val scanned = listOf(ScannedNetwork("GhostNet", "aa:bb:cc:11:22:33", "WPA3"))
        val rows = NearbyMatcher.merge(scanned, emptyList())
        assertEquals(1, rows.size)
        assertNull(rows[0].accessPoint)
        assertFalse(rows[0].passwordAvailable)
        assertEquals("GhostNet", rows[0].title)
    }

    @Test
    fun `database entries not in scan appear as extra rows sorted by distance`() {
        val scanned = listOf(ScannedNetwork("MyNet", "aa:bb:cc:11:22:33", "WPA2"))
        val database = listOf(
            ap("far", "FarCafe", distanceM = 500.0),
            ap("near", "NearCafe", distanceM = 50.0),
        )
        val rows = NearbyMatcher.merge(scanned, database)
        assertEquals(3, rows.size)
        assertEquals("near", rows[1].accessPoint?.id)
        assertEquals("far", rows[2].accessPoint?.id)
    }

    @Test
    fun `duplicate ssid in scan does not claim the same database entry twice`() {
        val scanned = listOf(
            ScannedNetwork("ChainWifi", "aa:aa:aa:aa:aa:01", "WPA2"),
            ScannedNetwork("ChainWifi", "aa:aa:aa:aa:aa:02", "WPA2"),
        )
        val database = listOf(ap("ap-1", "ChainWifi"))
        val rows = NearbyMatcher.merge(scanned, database)
        assertEquals(2, rows.size)
        val matched = rows.count { it.passwordAvailable }
        assertEquals(1, matched)
    }
}

class SecurityTypesTest {

    @Test
    fun `capabilities map to security labels`() {
        assertEquals("WPA3", SecurityTypes.fromCapabilities("[WPA2-PSK-CCMP][WPA3-PSK-SAE-CCMP][ESS]"))
        assertEquals("WPA2", SecurityTypes.fromCapabilities("[WPA2-PSK-CCMP][ESS]"))
        assertEquals("WPA", SecurityTypes.fromCapabilities("[WPA-PSK-TKIP][ESS]"))
        assertEquals("Open", SecurityTypes.fromCapabilities("[ESS]"))
    }
}
