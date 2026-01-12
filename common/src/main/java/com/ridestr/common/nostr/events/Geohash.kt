package com.ridestr.common.nostr.events

/**
 * Geohash encoding/decoding utilities for location-based filtering.
 *
 * Geohashes are a hierarchical spatial data structure that subdivides space into
 * buckets of grid shape. They're used in Nostr to filter events by geographic location.
 *
 * Precision levels:
 * - 1 char: ~5,000km x 5,000km (continent)
 * - 2 chars: ~1,250km x 625km (large country/state)
 * - 3 chars: ~156km x 156km (state/province)
 * - 4 chars: ~39km x 19.5km (regional/county)
 * - 5 chars: ~4.9km x 4.9km (city/neighborhood) - PRIMARY FOR RIDESHARE
 * - 6 chars: ~1.2km x 0.6km (small neighborhood)
 * - 7 chars: ~153m x 153m (city block)
 * - 8 chars: ~38m x 19m (building)
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /**
     * Encode latitude and longitude to a geohash string.
     *
     * @param lat Latitude (-90 to 90)
     * @param lon Longitude (-180 to 180)
     * @param precision Number of characters in the geohash (1-12, default 5)
     * @return Geohash string
     */
    fun encode(lat: Double, lon: Double, precision: Int = 5): String {
        require(lat in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(lon in -180.0..180.0) { "Longitude must be between -180 and 180" }
        require(precision in 1..12) { "Precision must be between 1 and 12" }

        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0

        val result = StringBuilder()
        var bit = 0
        var ch = 0
        var isLon = true

        while (result.length < precision) {
            if (isLon) {
                val mid = (minLon + maxLon) / 2
                if (lon >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    minLon = mid
                } else {
                    maxLon = mid
                }
            } else {
                val mid = (minLat + maxLat) / 2
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    minLat = mid
                } else {
                    maxLat = mid
                }
            }

            isLon = !isLon

            if (bit < 4) {
                bit++
            } else {
                result.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }

        return result.toString()
    }

    /**
     * Decode a geohash string to latitude and longitude.
     *
     * @param geohash The geohash string to decode
     * @return Pair of (latitude, longitude) at the center of the geohash cell
     */
    fun decode(geohash: String): Pair<Double, Double> {
        require(geohash.isNotEmpty()) { "Geohash cannot be empty" }

        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0
        var isLon = true

        for (c in geohash.lowercase()) {
            val idx = BASE32.indexOf(c)
            require(idx >= 0) { "Invalid geohash character: $c" }

            for (bit in 4 downTo 0) {
                val bitValue = (idx shr bit) and 1
                if (isLon) {
                    val mid = (minLon + maxLon) / 2
                    if (bitValue == 1) {
                        minLon = mid
                    } else {
                        maxLon = mid
                    }
                } else {
                    val mid = (minLat + maxLat) / 2
                    if (bitValue == 1) {
                        minLat = mid
                    } else {
                        maxLat = mid
                    }
                }
                isLon = !isLon
            }
        }

        return Pair((minLat + maxLat) / 2, (minLon + maxLon) / 2)
    }

    /**
     * Get all geohash prefixes for a location at different precision levels.
     * Used to generate multiple `g` tags for Nostr events.
     *
     * @param lat Latitude
     * @param lon Longitude
     * @param minPrecision Minimum precision (default 4 for ~39km)
     * @param maxPrecision Maximum precision (default 5 for ~5km)
     * @return List of geohash strings at different precision levels
     */
    fun getGeohashTags(
        lat: Double,
        lon: Double,
        minPrecision: Int = 4,
        maxPrecision: Int = 5
    ): List<String> {
        val fullGeohash = encode(lat, lon, maxPrecision)
        return (minPrecision..maxPrecision).map { precision ->
            fullGeohash.take(precision)
        }.distinct()
    }

    /**
     * Check if a geohash contains or is contained by another.
     * Useful for filtering events.
     *
     * @param geohash1 First geohash
     * @param geohash2 Second geohash
     * @return true if one is a prefix of the other
     */
    fun overlaps(geohash1: String, geohash2: String): Boolean {
        return geohash1.startsWith(geohash2) || geohash2.startsWith(geohash1)
    }

    /**
     * Get neighboring geohashes at the same precision level.
     * Useful for expanding search area.
     *
     * @param geohash The center geohash
     * @return List of 8 neighboring geohashes plus the original
     */
    fun neighbors(geohash: String): List<String> {
        if (geohash.isEmpty()) return listOf(geohash)

        val (centerLat, centerLon) = decode(geohash)
        val precision = geohash.length

        // Approximate cell size based on precision
        val latStep = 180.0 / Math.pow(8.0, precision.toDouble())
        val lonStep = 360.0 / Math.pow(4.0, precision.toDouble())

        val neighbors = mutableListOf<String>()

        for (dLat in -1..1) {
            for (dLon in -1..1) {
                val newLat = (centerLat + dLat * latStep).coerceIn(-90.0, 90.0)
                var newLon = centerLon + dLon * lonStep
                // Handle longitude wrap-around
                if (newLon > 180) newLon -= 360
                if (newLon < -180) newLon += 360

                neighbors.add(encode(newLat, newLon, precision))
            }
        }

        return neighbors.distinct()
    }

    /**
     * Get the bounding box for a geohash.
     *
     * @param geohash The geohash string
     * @return BoundingBox with min/max lat/lon
     */
    fun boundingBox(geohash: String): BoundingBox {
        require(geohash.isNotEmpty()) { "Geohash cannot be empty" }

        var minLat = -90.0
        var maxLat = 90.0
        var minLon = -180.0
        var maxLon = 180.0
        var isLon = true

        for (c in geohash.lowercase()) {
            val idx = BASE32.indexOf(c)
            require(idx >= 0) { "Invalid geohash character: $c" }

            for (bit in 4 downTo 0) {
                val bitValue = (idx shr bit) and 1
                if (isLon) {
                    val mid = (minLon + maxLon) / 2
                    if (bitValue == 1) {
                        minLon = mid
                    } else {
                        maxLon = mid
                    }
                } else {
                    val mid = (minLat + maxLat) / 2
                    if (bitValue == 1) {
                        minLat = mid
                    } else {
                        maxLat = mid
                    }
                }
                isLon = !isLon
            }
        }

        return BoundingBox(minLat, maxLat, minLon, maxLon)
    }

    /**
     * Check if a location is near the edge of its geohash cell.
     * Useful for determining when to include neighboring cells.
     *
     * @param lat Latitude
     * @param lon Longitude
     * @param precision Geohash precision level
     * @param threshold Percentage of cell size to consider "near edge" (0.0-0.5, default 0.2 = 20%)
     * @return true if within threshold of any edge
     */
    fun isNearEdge(lat: Double, lon: Double, precision: Int = 4, threshold: Double = 0.2): Boolean {
        val box = boundingBox(encode(lat, lon, precision))
        val latRange = box.maxLat - box.minLat
        val lonRange = box.maxLon - box.minLon

        // Calculate position within cell as 0.0-1.0
        val latPosition = (lat - box.minLat) / latRange
        val lonPosition = (lon - box.minLon) / lonRange

        // Near edge if within threshold of 0.0 or 1.0
        return latPosition < threshold || latPosition > (1.0 - threshold) ||
               lonPosition < threshold || lonPosition > (1.0 - threshold)
    }

    /**
     * Get geohashes for a search radius around a location.
     * Ensures minimum coverage of approximately 20 miles in all directions.
     *
     * Uses 4-char precision (~24mi x 12mi per cell) with neighbors
     * to guarantee 20+ mile coverage from any position.
     *
     * @param lat Latitude
     * @param lon Longitude
     * @param alwaysIncludeNeighbors If true, always include all 9 cells. If false, only include
     *                               neighbors when near edge (saves bandwidth but may miss edge cases)
     * @return List of geohashes that cover the search area
     */
    fun getSearchAreaGeohashes(
        lat: Double,
        lon: Double,
        expandSearch: Boolean = false
    ): List<String> {
        // Precision levels:
        // - Nearby (expandSearch=false): precision 4 + neighbors (~72mi x 36mi total)
        // - Expanded (expandSearch=true): precision 3 + neighbors (~300mi x 150mi total)
        val precision = if (expandSearch) 3 else 4
        val centerGeohash = encode(lat, lon, precision)

        // Always include neighbors to avoid edge cases where someone
        // is right across a cell boundary
        return neighbors(centerGeohash)  // 9 cells at chosen precision
    }

    /**
     * Calculate approximate distance in miles between two coordinates.
     * Uses Haversine formula.
     */
    fun distanceMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMiles = 3958.8

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

        return earthRadiusMiles * c
    }

    /**
     * Bounding box for a geohash cell.
     */
    data class BoundingBox(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    ) {
        val centerLat: Double get() = (minLat + maxLat) / 2
        val centerLon: Double get() = (minLon + maxLon) / 2

        fun contains(lat: Double, lon: Double): Boolean {
            return lat in minLat..maxLat && lon in minLon..maxLon
        }
    }
}

/**
 * Extension function to get geohash from a Location.
 */
fun Location.geohash(precision: Int = 5): String = Geohash.encode(lat, lon, precision)

/**
 * Extension function to get all geohash tags for a Location.
 */
fun Location.geohashTags(minPrecision: Int = 4, maxPrecision: Int = 5): List<String> =
    Geohash.getGeohashTags(lat, lon, minPrecision, maxPrecision)

/**
 * Extension function to get search area geohashes for a Location.
 * @param expandSearch If true, always include neighbors. If false, only when near edge.
 */
fun Location.searchAreaGeohashes(expandSearch: Boolean = false): List<String> =
    Geohash.getSearchAreaGeohashes(lat, lon, expandSearch)

/**
 * Extension function to check if location is near edge of its geohash cell.
 */
fun Location.isNearGeohashEdge(precision: Int = 4, threshold: Double = 0.2): Boolean =
    Geohash.isNearEdge(lat, lon, precision, threshold)

/**
 * Extension function to calculate distance in miles to another location.
 */
fun Location.distanceMilesTo(other: Location): Double =
    Geohash.distanceMiles(lat, lon, other.lat, other.lon)
