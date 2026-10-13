package com.erikbeans.wifidatabase

import com.erikbeans.wifidatabase.domain.model.ScannedNetwork
import com.erikbeans.wifidatabase.domain.model.dedupeBySsid
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanDedupeTest {

    @Test
    fun `mesh access points with the same ssid collapse to one entry`() {
        val networks = listOf(
            ScannedNetwork("BeanieBitz", "aa:aa:aa:aa:aa:01", "WPA2"),
            ScannedNetwork("BeanieBitz", "aa:aa:aa:aa:aa:02", "WPA2"),
            ScannedNetwork("BeanieBitz", "aa:aa:aa:aa:aa:03", "WPA2"),
            ScannedNetwork("BeanieBitz", "aa:aa:aa:aa:aa:04", "WPA2"),
            ScannedNetwork("BeanieBitz", "aa:aa:aa:aa:aa:05", "Open"),
            ScannedNetwork("BeanieBitz", "aa:aa:aa:aa:aa:06", "Open"),
        )
        val deduped = networks.dedupeBySsid()
        assertEquals(1, deduped.size)
        assertEquals("BeanieBitz", deduped[0].ssid)
        assertEquals("WPA2", deduped[0].security)
    }

    @Test
    fun `different networks stay separate and sorted`() {
        val networks = listOf(
            ScannedNetwork("Zeta", "aa:aa:aa:aa:aa:01", "WPA2"),
            ScannedNetwork("Alpha", "aa:aa:aa:aa:aa:02", "Open"),
        )
        val deduped = networks.dedupeBySsid()
        assertEquals(listOf("Alpha", "Zeta"), deduped.map { it.ssid })
    }

    @Test
    fun `ssid comparison ignores case`() {
        val networks = listOf(
            ScannedNetwork("beaniebitz", "aa:aa:aa:aa:aa:01", "WPA2"),
            ScannedNetwork("BeanieBitz", "aa:aa:aa:aa:aa:02", "WPA3"),
        )
        val deduped = networks.dedupeBySsid()
        assertEquals(1, deduped.size)
        assertEquals("WPA3", deduped[0].security)
    }
}
