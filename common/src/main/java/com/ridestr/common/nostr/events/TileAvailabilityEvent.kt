package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30078: Tile Availability Event (Parameterized Replaceable)
 *
 * Announces availability of Valhalla routing tiles on Blossom servers.
 * Uses Application Specific Data kind (30078) with d-tag for deduplication.
 *
 * Tags:
 * - d: "ridestr-tile:{region-id}" (parameterized replaceable identifier)
 * - region: Region identifier (e.g., "us-nevada")
 * - name: Human-readable region name (e.g., "Nevada, USA")
 * - bbox: Bounding box as "west,south,east,north"
 * - size: File size in bytes
 * - x: SHA256 hash of the tile file
 * - url: Blossom server URL(s) where tile can be downloaded
 * - updated: Unix timestamp of last tile data update
 *
 * Content is empty (all data in tags for easy filtering).
 */
object TileAvailabilityEvent {

    const val KIND = 30078

    /**
     * Create a tile availability event.
     *
     * @param signer NostrSigner to sign the event
     * @param regionId Unique region identifier (e.g., "us-nevada")
     * @param regionName Human-readable name (e.g., "Nevada, USA")
     * @param boundingBox Geographic bounds [west, south, east, north]
     * @param sha256 SHA256 hash of the tile file
     * @param sizeBytes File size in bytes
     * @param blossomUrls List of Blossom server URLs hosting this tile
     * @param updatedAt Unix timestamp when tile data was generated
     */
    suspend fun create(
        signer: NostrSigner,
        regionId: String,
        regionName: String,
        boundingBox: BoundingBox,
        sha256: String,
        sizeBytes: Long,
        blossomUrls: List<String>,
        updatedAt: Long = System.currentTimeMillis() / 1000
    ): Event {
        val tagsList = mutableListOf<Array<String>>()

        // d-tag for parameterized replaceable (only one event per region per pubkey)
        tagsList.add(arrayOf("d", "ridestr-tile:$regionId"))

        // Region metadata
        tagsList.add(arrayOf("region", regionId))
        tagsList.add(arrayOf("name", regionName))
        tagsList.add(arrayOf("bbox", boundingBox.toTagValue()))
        tagsList.add(arrayOf("size", sizeBytes.toString()))
        tagsList.add(arrayOf("x", sha256))
        tagsList.add(arrayOf("updated", updatedAt.toString()))

        // Add all Blossom URLs
        blossomUrls.forEach { url ->
            tagsList.add(arrayOf("url", url))
        }

        // Add rideshare tag for filtering
        tagsList.add(arrayOf("t", "ridestr-tiles"))

        val tags = tagsList.toTypedArray()

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND,
            tags = tags,
            content = "" // All data in tags
        )
    }

    /**
     * Parse a tile availability event into structured data.
     *
     * @param event The Nostr event to parse
     * @return TileAvailabilityData if valid, null otherwise
     */
    fun parse(event: Event): TileAvailabilityData? {
        if (event.kind != KIND) return null

        return try {
            var regionId: String? = null
            var regionName: String? = null
            var bbox: BoundingBox? = null
            var sha256: String? = null
            var sizeBytes: Long? = null
            var updatedAt: Long? = null
            val blossomUrls = mutableListOf<String>()

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    "region" -> regionId = tag.getOrNull(1)
                    "name" -> regionName = tag.getOrNull(1)
                    "bbox" -> bbox = BoundingBox.fromTagValue(tag.getOrNull(1) ?: "")
                    "x" -> sha256 = tag.getOrNull(1)
                    "size" -> sizeBytes = tag.getOrNull(1)?.toLongOrNull()
                    "updated" -> updatedAt = tag.getOrNull(1)?.toLongOrNull()
                    "url" -> tag.getOrNull(1)?.let { blossomUrls.add(it) }
                }
            }

            // Validate required fields
            if (regionId == null || regionName == null || bbox == null ||
                sha256 == null || sizeBytes == null || blossomUrls.isEmpty()) {
                return null
            }

            TileAvailabilityData(
                eventId = event.id,
                publisherPubKey = event.pubKey,
                regionId = regionId,
                regionName = regionName,
                boundingBox = bbox,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                blossomUrls = blossomUrls,
                updatedAt = updatedAt ?: event.createdAt,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Data class for parsed tile availability information.
 */
data class TileAvailabilityData(
    val eventId: String,
    val publisherPubKey: String,
    val regionId: String,
    val regionName: String,
    val boundingBox: BoundingBox,
    val sha256: String,
    val sizeBytes: Long,
    val blossomUrls: List<String>,
    val updatedAt: Long,
    val createdAt: Long
)

/**
 * Geographic bounding box for tile region.
 *
 * Handles International Date Line crossing properly. When a region spans the date line
 * (e.g., Alaska's Aleutian Islands), west will be > east in terms of longitude values.
 * For example: west=172 (eastern hemisphere), east=-130 (western hemisphere).
 *
 * @param west Western longitude boundary
 * @param south Southern latitude (minimum)
 * @param east Eastern longitude boundary
 * @param north Northern latitude (maximum)
 */
data class BoundingBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
) {
    /**
     * Whether this bounding box crosses the International Date Line.
     * True date-line crossing occurs when:
     * - west is positive (eastern hemisphere, e.g., 172°)
     * - east is negative (western hemisphere, e.g., -130°)
     *
     * Note: If both are the same sign but west > east, that's likely a data error
     * (swapped coordinates), not a date-line crossing.
     */
    val crossesDateLine: Boolean = west > 0 && east < 0

    /**
     * Whether this bounding box appears to have swapped west/east coordinates.
     * This is likely a data error that should be corrected in the source.
     */
    val hasSwappedCoordinates: Boolean = west > east && !crossesDateLine

    /**
     * Convert to tag value format: "west,south,east,north"
     */
    fun toTagValue(): String = "$west,$south,$east,$north"

    /**
     * Check if a point is within this bounding box.
     *
     * Handles three cases:
     * 1. Normal boxes (west <= east): Standard range check
     * 2. Date-line-crossing boxes (west > 0, east < 0): Check if in eastern OR western part
     * 3. Swapped coordinates (west > east, same sign): Auto-correct by swapping
     */
    fun contains(lat: Double, lon: Double): Boolean {
        // Latitude check is always the same
        val latInRange = lat in south..north
        if (!latInRange) return false

        // Longitude check depends on the bbox type
        val lonInRange = when {
            crossesDateLine -> {
                // Date line crossing: region wraps from west (positive) through ±180 to east (negative)
                // A point is inside if it's >= west (in eastern part) OR <= east (in western part)
                lon >= west || lon <= east
            }
            hasSwappedCoordinates -> {
                // Swapped coordinates - auto-correct by treating east as west and vice versa
                // This handles misconfigured data gracefully
                lon in east..west
            }
            else -> {
                // Normal case: simple range check
                lon in west..east
            }
        }

        return lonInRange
    }

    companion object {
        /**
         * Parse from tag value format: "west,south,east,north"
         */
        fun fromTagValue(value: String): BoundingBox? {
            return try {
                val parts = value.split(",")
                if (parts.size != 4) return null

                BoundingBox(
                    west = parts[0].toDouble(),
                    south = parts[1].toDouble(),
                    east = parts[2].toDouble(),
                    north = parts[3].toDouble()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
