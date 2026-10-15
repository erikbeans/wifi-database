package com.clickbeans.wifidatabase

import com.clickbeans.wifidatabase.data.api.AccessPointDetailDto
import com.clickbeans.wifidatabase.domain.DeletionPolicy
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DeletionPolicyTest {
    private val serverTime = "2026-10-15T18:00:00Z"
    private val deleteUntil = "2026-10-15T18:15:00Z"

    @Test
    fun `server permission expires at exact deadline while screen stays open`() {
        val deadline = DeletionPolicy.deadlineElapsedRealtime(true, serverTime, deleteUntil, 5000)!!
        assertEquals(905000L, deadline)
        assertTrue(DeletionPolicy.canDelete(true, deadline, deadline - 1))
        assertFalse(DeletionPolicy.canDelete(true, deadline, deadline))
        assertFalse(DeletionPolicy.canDelete(true, deadline, deadline + 1))
        assertFalse(DeletionPolicy.canDelete(false, deadline, 5000))
    }

    @Test
    fun `missing or invalid metadata never grants deletion`() {
        assertNull(DeletionPolicy.deadlineElapsedRealtime(false, serverTime, deleteUntil, 0))
        assertNull(DeletionPolicy.deadlineElapsedRealtime(true, null, deleteUntil, 0))
        assertNull(DeletionPolicy.deadlineElapsedRealtime(true, serverTime, null, 0))
        assertNull(DeletionPolicy.deadlineElapsedRealtime(true, "invalid", deleteUntil, 0))
        assertNull(DeletionPolicy.deadlineElapsedRealtime(true, serverTime, "invalid", 0))
        assertNull(DeletionPolicy.deadlineElapsedRealtime(true, deleteUntil, deleteUntil, 0))
        assertNull(DeletionPolicy.deadlineElapsedRealtime(true, deleteUntil, serverTime, 0))
        assertFalse(DeletionPolicy.canDelete(true, null, 0))
    }

    @Test
    fun `deadline uses server offset and monotonic time regardless of device calendar`() {
        // No device wall-clock timestamp is accepted by the policy.
        val deadline = DeletionPolicy.deadlineElapsedRealtime(true, serverTime, deleteUntil, 1000)!!
        assertTrue(DeletionPolicy.canDelete(true, deadline, 2000))
        // Includes time spent receiving the response and time spent backgrounded.
        assertFalse(DeletionPolicy.canDelete(true, deadline, 1000 + 900000))
        assertEquals(deadline, DeletionPolicy.deadlineElapsedRealtime(
            true, "2000-01-01T01:00:00+01:00", "2000-01-01T00:15:00Z", 1000,
        ))
    }

    @Test
    fun `detail and password metadata deserialize safely including older servers`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """{"id":"ap","ssid":"Cafe","latitude":0.0,"longitude":0.0,"mine":true,
            "passwords":[{"id":"pw","password":"password123","mine":true}]}"""
        val old = json.decodeFromString(AccessPointDetailDto.serializer(), legacy)
        assertFalse(old.canDelete)
        assertNull(old.serverTime)
        assertFalse(old.passwords.single().canDelete)
        assertNull(old.passwords.single().deleteUntil)
        val current = legacy.dropLast(1) + """, "server_time":"$serverTime",
            "can_delete":true,"delete_until":"$deleteUntil"}"""
        val detail = json.decodeFromString(AccessPointDetailDto.serializer(), current)
        assertTrue(detail.canDelete)
        assertEquals(900000L, DeletionPolicy.deadlineElapsedRealtime(
            detail.canDelete, detail.serverTime, detail.deleteUntil, 0,
        ))
    }
}
