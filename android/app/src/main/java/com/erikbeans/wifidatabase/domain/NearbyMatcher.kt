package com.erikbeans.wifidatabase.domain

import com.erikbeans.wifidatabase.domain.model.NearbyAccessPoint
import com.erikbeans.wifidatabase.domain.model.NearbyRow
import com.erikbeans.wifidatabase.domain.model.ScannedNetwork

object NearbyMatcher {

    fun merge(scanned: List<ScannedNetwork>, database: List<NearbyAccessPoint>): List<NearbyRow> {
        val byBssid = database.filter { !it.bssid.isNullOrBlank() }.associateBy { it.bssid!!.lowercase() }
        val bySsid = database.groupBy { it.ssid.lowercase() }
        val matched = mutableSetOf<String>()

        val scanRows = scanned.map { network ->
            val ap = byBssid[network.bssid?.lowercase()]
                ?: bySsid[network.ssid.lowercase()]?.firstOrNull { it.id !in matched }
            if (ap != null) {
                matched.add(ap.id)
            }
            NearbyRow(
                scanned = network,
                accessPoint = ap,
                title = network.ssid,
                subtitle = network.security,
                passwordAvailable = ap != null,
                bestScore = ap?.bestScore,
                distanceM = ap?.distanceM,
            )
        }

        val databaseRows = database.filter { it.id !in matched }
            .sortedBy { it.distanceM ?: Double.MAX_VALUE }
            .map { ap ->
                NearbyRow(
                    scanned = null,
                    accessPoint = ap,
                    title = ap.ssid,
                    subtitle = ap.security,
                    passwordAvailable = true,
                    bestScore = ap.bestScore,
                    distanceM = ap.distanceM,
                )
            }

        return scanRows + databaseRows
    }
}
