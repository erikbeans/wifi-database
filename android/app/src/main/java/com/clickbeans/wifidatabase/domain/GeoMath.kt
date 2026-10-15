package com.clickbeans.wifidatabase.domain

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    fun clampToRadius(
        centerLat: Double,
        centerLon: Double,
        lat: Double,
        lon: Double,
        maxMeters: Double,
    ): Pair<Double, Double> {
        val distance = distanceMeters(centerLat, centerLon, lat, lon)
        if (distance <= maxMeters) {
            return lat to lon
        }
        val scale = maxMeters / distance
        val clampedLat = centerLat + (lat - centerLat) * scale
        val clampedLon = centerLon + (lon - centerLon) * scale
        return clampedLat to clampedLon
    }

    fun degreesToRadians(degrees: Double): Double = degrees * PI / 180.0
}
