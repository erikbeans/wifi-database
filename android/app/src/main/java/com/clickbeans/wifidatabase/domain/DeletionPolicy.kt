package com.clickbeans.wifidatabase.domain

import java.time.Duration
import java.time.Instant

object DeletionPolicy {
    const val WINDOW_ENDED = "The 15-minute deletion window has ended."
    const val ACCESS_POINT_RESTRICTION =
        "Access-point deletion requires all passwords to be yours and within their 15-minute windows."
    const val UNAVAILABLE = "Deletion is unavailable. Refresh to check your permissions."

    // Anchor to the request start, conservatively accounting for network time.
    // Never use the device wall clock, which the user can change.
    fun deadlineElapsedRealtime(
        canDelete: Boolean,
        serverTime: String?,
        deleteUntil: String?,
        requestStartedAt: Long,
    ): Long? {
        if (!canDelete || serverTime == null || deleteUntil == null) return null
        return try {
            val remaining = Duration.between(Instant.parse(serverTime), Instant.parse(deleteUntil)).toMillis()
            if (remaining <= 0) null else Math.addExact(requestStartedAt, remaining)
        } catch (_: Exception) {
            null
        }
    }

    fun canDelete(mine: Boolean, deadlineElapsedRealtime: Long?, now: Long): Boolean =
        mine && deadlineElapsedRealtime != null && now < deadlineElapsedRealtime
}
