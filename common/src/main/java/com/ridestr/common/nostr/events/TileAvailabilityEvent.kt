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
 * @param west Western longitude (minimum)
 * @param south Southern latitude (minimum)
 * @param east Eastern longitude (maximum)
 * @param north Northern latitude (maximum)
 */
data class BoundingBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
) {
    /**
     * Convert to tag value format: "west,south,east,north"
     */
    fun toTagValue(): String = "$west,$south,$east,$north"

    /**
     * Check if a point is within this bounding box.
     */
    fun contains(lat: Double, lon: Double): Boolean {
        return lat in south..north && lon in west..east
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
