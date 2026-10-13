package com.erikbeans.wifidatabase.domain.model

data class ScannedNetwork(
    val ssid: String,
    val bssid: String?,
    val security: String?,
)

data class NearbyAccessPoint(
    val id: String,
    val ssid: String,
    val bssid: String?,
    val security: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceM: Double?,
    val passwordCount: Int,
    val bestScore: Int?,
)

data class PasswordCandidate(
    val id: String,
    val password: String,
    val upVotes: Int,
    val downVotes: Int,
    val score: Int,
    val myVote: Int?,
)

data class AccessPointDetail(
    val id: String,
    val ssid: String,
    val bssid: String?,
    val security: String?,
    val latitude: Double,
    val longitude: Double,
    val passwords: List<PasswordCandidate>,
)

data class NearbyRow(
    val scanned: ScannedNetwork?,
    val accessPoint: NearbyAccessPoint?,
    val title: String,
    val subtitle: String?,
    val passwordAvailable: Boolean,
    val bestScore: Int?,
    val distanceM: Double?,
)

object SecurityTypes {
    fun fromCapabilities(capabilities: String): String = when {
        "WPA3" in capabilities -> "WPA3"
        "WPA2" in capabilities -> "WPA2"
        "WPA" in capabilities -> "WPA"
        "WEP" in capabilities -> "WEP"
        else -> "Open"
    }
}

fun List<ScannedNetwork>.dedupeBySsid(): List<ScannedNetwork> {
    val securityRank = mapOf("WPA3" to 0, "WPA2" to 1, "WPA" to 2, "WEP" to 3, "Open" to 4)
    return this
        .sortedBy { securityRank[it.security] ?: 4 }
        .distinctBy { it.ssid.lowercase() }
        .sortedBy { it.ssid.lowercase() }
}
