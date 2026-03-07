package com.roadflare.common.nostr.events

/**
 * Geohash encoding/decoding utilities for location-based filtering.
 * Port from ridestr — full implementation preserved.
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int = 5): String {
        require(lat in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(lon in -180.0..180.0) { "Longitude must be between -180 and 180" }
        require(precision in 1..12) { "Precision must be between 1 and 12" }

        var minLat = -90.0; var maxLat = 90.0
        var minLon = -180.0; var maxLon = 180.0
        val result = StringBuilder()
        var bit = 0; var ch = 0; var isLon = true

        while (result.length < precision) {
            if (isLon) {
                val mid = (minLon + maxLon) / 2
                if (lon >= mid) { ch = ch or (1 shl (4 - bit)); minLon = mid } else { maxLon = mid }
            } else {
                val mid = (minLat + maxLat) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); minLat = mid } else { maxLat = mid }
            }
            isLon = !isLon
            if (bit < 4) { bit++ } else { result.append(BASE32[ch]); bit = 0; ch = 0 }
        }
        return result.toString()
    }

    fun decode(geohash: String): Pair<Double, Double> {
        require(geohash.isNotEmpty()) { "Geohash cannot be empty" }
        var minLat = -90.0; var maxLat = 90.0; var minLon = -180.0; var maxLon = 180.0; var isLon = true
        for (c in geohash.lowercase()) {
            val idx = BASE32.indexOf(c)
            require(idx >= 0) { "Invalid geohash character: $c" }
            for (bit in 4 downTo 0) {
                val bitValue = (idx shr bit) and 1
                if (isLon) { val mid = (minLon + maxLon) / 2; if (bitValue == 1) minLon = mid else maxLon = mid }
                else { val mid = (minLat + maxLat) / 2; if (bitValue == 1) minLat = mid else maxLat = mid }
                isLon = !isLon
            }
        }
        return Pair((minLat + maxLat) / 2, (minLon + maxLon) / 2)
    }

    fun getGeohashTags(lat: Double, lon: Double, minPrecision: Int = 4, maxPrecision: Int = 5): List<String> {
        val fullGeohash = encode(lat, lon, maxPrecision)
        return (minPrecision..maxPrecision).map { fullGeohash.take(it) }.distinct()
    }

    fun overlaps(geohash1: String, geohash2: String): Boolean =
        geohash1.startsWith(geohash2) || geohash2.startsWith(geohash1)

    fun neighbors(geohash: String): List<String> {
        if (geohash.isEmpty()) return listOf(geohash)
        val (centerLat, centerLon) = decode(geohash)
        val precision = geohash.length
        val latStep = 180.0 / Math.pow(8.0, precision.toDouble())
        val lonStep = 360.0 / Math.pow(4.0, precision.toDouble())
        val neighbors = mutableListOf<String>()
        for (dLat in -1..1) { for (dLon in -1..1) {
            val newLat = (centerLat + dLat * latStep).coerceIn(-90.0, 90.0)
            var newLon = centerLon + dLon * lonStep
            if (newLon > 180) newLon -= 360; if (newLon < -180) newLon += 360
            neighbors.add(encode(newLat, newLon, precision))
        }}
        return neighbors.distinct()
    }

    fun getSearchAreaGeohashes(lat: Double, lon: Double, expandSearch: Boolean = false): List<String> {
        val precision = if (expandSearch) 3 else 4
        return neighbors(encode(lat, lon, precision))
    }

    fun distanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMiles = 3958.8
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return earthRadiusMiles * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    data class BoundingBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
        val centerLat: Double get() = (minLat + maxLat) / 2
        val centerLon: Double get() = (minLon + maxLon) / 2
        fun contains(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon
    }
}

fun Location.geohash(precision: Int = 5): String = Geohash.encode(lat, lon, precision)
fun Location.geohashTags(minPrecision: Int = 4, maxPrecision: Int = 5): List<String> = Geohash.getGeohashTags(lat, lon, minPrecision, maxPrecision)
fun Location.searchAreaGeohashes(expandSearch: Boolean = false): List<String> = Geohash.getSearchAreaGeohashes(lat, lon, expandSearch)
fun Location.distanceMilesTo(other: Location): Double = Geohash.distanceMiles(lat, lon, other.lat, other.lon)
