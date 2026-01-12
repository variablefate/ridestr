package com.ridestr.common.routing

import ValhallaConfigBuilder
import android.content.Context
import android.util.Log
import com.valhalla.api.models.CostingModel
import com.valhalla.api.models.DirectionsOptions
import com.valhalla.api.models.RouteRequest
import com.valhalla.api.models.RoutingWaypoint
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaException
import com.valhalla.valhalla.ValhallaResponse
import com.valhalla.valhalla.files.ValhallaFile
import com.ridestr.common.settings.DistanceUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service for offline routing using Valhalla Mobile.
 *
 * This service wraps the Valhalla routing engine and provides a simple API
 * for calculating routes between two points.
 */
class ValhallaRoutingService(private val context: Context) {

    companion object {
        private const val TAG = "ValhallaRoutingService"
        private const val TILES_FILENAME = "valhalla_tiles.tar"
        private const val NEVADA_TILES_FILENAME = "nevada.tar"
    }

    private var valhalla: Valhalla? = null
    private var isInitialized = false
    private var currentTileFile: String? = null

    /**
     * Initialize the Valhalla routing engine with the bundled tile file.
     * This should be called before any routing operations.
     *
     * @param tileFilename Optional tile filename to use (default: Colorado demo tile)
     * @return true if initialization succeeded, false otherwise
     */
    suspend fun initialize(tileFilename: String = TILES_FILENAME): Boolean = withContext(Dispatchers.IO) {
        // If already initialized with the same tile, skip
        if (isInitialized && currentTileFile == tileFilename) {
            Log.d(TAG, "Valhalla already initialized with $tileFilename")
            return@withContext true
        }

        // If switching tiles, reset
        if (isInitialized && currentTileFile != tileFilename) {
            Log.d(TAG, "Switching tiles from $currentTileFile to $tileFilename")
            valhalla = null
            isInitialized = false
        }

        try {
            Log.d(TAG, "Initializing Valhalla with tiles: $tileFilename")

            // Copy tile file from assets to app storage
            val tarFile = ValhallaFile.usingAsset(context, tileFilename)
            Log.d(TAG, "Tile file path: ${tarFile.absolutePath()}")

            // Build config pointing to tiles
            val config = ValhallaConfigBuilder()
                .withTileExtract(tarFile.absolutePath())
                .build()

            // Initialize Valhalla
            valhalla = Valhalla(context, config)
            isInitialized = true
            currentTileFile = tileFilename

            Log.d(TAG, "Valhalla initialized successfully with $tileFilename")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Valhalla", e)
            false
        }
    }

    /**
     * Initialize with Nevada tiles for testing.
     */
    suspend fun initializeWithNevada(): Boolean = initialize(NEVADA_TILES_FILENAME)

    /**
     * Initialize the Valhalla routing engine with a downloaded tile file.
     * Use this for tiles downloaded from Nostr/Blossom (not from assets).
     *
     * @param tileFile The downloaded tile file
     * @return true if initialization succeeded, false otherwise
     */
    suspend fun initializeWithDownloadedTile(tileFile: File): Boolean = withContext(Dispatchers.IO) {
        val tilePath = tileFile.absolutePath

        // If already initialized with the same tile, skip
        if (isInitialized && currentTileFile == tilePath) {
            Log.d(TAG, "Valhalla already initialized with $tilePath")
            return@withContext true
        }

        // If switching tiles, reset
        if (isInitialized && currentTileFile != tilePath) {
            Log.d(TAG, "Switching tiles from $currentTileFile to $tilePath")
            valhalla = null
            isInitialized = false
        }

        if (!tileFile.exists()) {
            Log.e(TAG, "Tile file does not exist: $tilePath")
            return@withContext false
        }

        try {
            Log.d(TAG, "Initializing Valhalla with downloaded tile: $tilePath (size: ${tileFile.length()} bytes)")

            // Build config pointing directly to the downloaded tile file
            val config = ValhallaConfigBuilder()
                .withTileExtract(tilePath)
                .build()

            // Initialize Valhalla
            valhalla = Valhalla(context, config)
            isInitialized = true
            currentTileFile = tilePath

            Log.d(TAG, "Valhalla initialized successfully with downloaded tile: ${tileFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Valhalla with downloaded tile", e)
            false
        }
    }

    /**
     * Initialize from a TileSource (either bundled asset or downloaded file).
     * This is the preferred method for initializing with location-based tiles.
     *
     * @param tileSource The tile source to use
     * @return true if initialization succeeded, false otherwise
     */
    suspend fun initializeWithTileSource(tileSource: TileSource): Boolean {
        return when (tileSource) {
            is TileSource.Bundled -> initialize(tileSource.assetName)
            is TileSource.Downloaded -> initializeWithDownloadedTile(tileSource.file)
        }
    }

    /**
     * Calculate a route between two points.
     *
     * @param originLat Origin latitude
     * @param originLon Origin longitude
     * @param destLat Destination latitude
     * @param destLon Destination longitude
     * @param costingModel The costing model to use (auto, bicycle, pedestrian, etc.)
     * @return RouteResult with route details, or null if routing failed
     */
    suspend fun calculateRoute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        costingModel: CostingModel = CostingModel.auto
    ): RouteResult? = withContext(Dispatchers.IO) {
        val valhallaInstance = valhalla
        if (valhallaInstance == null) {
            Log.e(TAG, "Valhalla not initialized. Call initialize() first.")
            return@withContext null
        }

        try {
            Log.d(TAG, "Calculating route from ($originLat, $originLon) to ($destLat, $destLon)")

            val request = RouteRequest(
                locations = listOf(
                    RoutingWaypoint(lat = originLat, lon = originLon),
                    RoutingWaypoint(lat = destLat, lon = destLon)
                ),
                costing = costingModel,
                directionsOptions = DirectionsOptions(
                    format = DirectionsOptions.Format.json
                )
            )

            when (val response = valhallaInstance.route(request)) {
                is ValhallaResponse.Json -> {
                    val trip = response.jsonResponse.trip
                    val summary = trip?.summary
                    val legs = trip?.legs

                    if (summary != null) {
                        val result = RouteResult(
                            distanceKm = summary.length ?: 0.0,
                            durationSeconds = summary.time ?: 0.0,
                            encodedPolyline = legs?.firstOrNull()?.shape,
                            maneuvers = legs?.firstOrNull()?.maneuvers?.map { maneuver ->
                                Maneuver(
                                    instruction = maneuver.instruction ?: "",
                                    distanceKm = maneuver.length ?: 0.0,
                                    durationSeconds = maneuver.time ?: 0.0
                                )
                            } ?: emptyList()
                        )
                        Log.d(TAG, "Route calculated: ${result.distanceKm} km, ${result.durationSeconds} seconds")
                        result
                    } else {
                        Log.e(TAG, "No summary in route response")
                        null
                    }
                }
                is ValhallaResponse.Osrm -> {
                    Log.e(TAG, "Unexpected OSRM response format")
                    null
                }
            }
        } catch (e: ValhallaException) {
            Log.e(TAG, "Valhalla routing error: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during routing", e)
            null
        }
    }

    /**
     * Check if the routing service is ready to calculate routes.
     */
    fun isReady(): Boolean = isInitialized && valhalla != null
}

/**
 * Result of a route calculation.
 */
data class RouteResult(
    val distanceKm: Double,
    val durationSeconds: Double,
    val encodedPolyline: String?,
    val maneuvers: List<Maneuver>
) {
    /**
     * Get the duration formatted as minutes:seconds
     */
    fun getFormattedDuration(): String {
        val minutes = (durationSeconds / 60).toInt()
        val seconds = (durationSeconds % 60).toInt()
        return "${minutes}m ${seconds}s"
    }

    /**
     * Get the distance formatted with appropriate units (defaults to kilometers)
     */
    fun getFormattedDistance(): String {
        return getFormattedDistance(DistanceUnit.KILOMETERS)
    }

    /**
     * Get the distance formatted with the specified unit preference.
     */
    fun getFormattedDistance(unit: DistanceUnit): String {
        return when (unit) {
            DistanceUnit.KILOMETERS -> {
                if (distanceKm < 1) {
                    "${(distanceKm * 1000).toInt()} m"
                } else {
                    String.format("%.1f km", distanceKm)
                }
            }
            DistanceUnit.MILES -> {
                val miles = distanceKm * 0.621371
                if (miles < 0.1) {
                    val feet = (miles * 5280).toInt()
                    "$feet ft"
                } else {
                    String.format("%.1f mi", miles)
                }
            }
        }
    }
}

/**
 * A single maneuver/instruction in the route.
 */
data class Maneuver(
    val instruction: String,
    val distanceKm: Double,
    val durationSeconds: Double
)
