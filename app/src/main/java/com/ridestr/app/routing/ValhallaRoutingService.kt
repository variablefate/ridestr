package com.ridestr.app.routing

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    }

    private var valhalla: Valhalla? = null
    private var isInitialized = false

    /**
     * Initialize the Valhalla routing engine with the bundled tile file.
     * This should be called before any routing operations.
     *
     * @return true if initialization succeeded, false otherwise
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.d(TAG, "Valhalla already initialized")
            return@withContext true
        }

        try {
            Log.d(TAG, "Initializing Valhalla with tiles: $TILES_FILENAME")

            // Copy tile file from assets to app storage
            val tarFile = ValhallaFile.usingAsset(context, TILES_FILENAME)
            Log.d(TAG, "Tile file path: ${tarFile.absolutePath()}")

            // Build config pointing to tiles
            val config = ValhallaConfigBuilder()
                .withTileExtract(tarFile.absolutePath())
                .build()

            // Initialize Valhalla
            valhalla = Valhalla(context, config)
            isInitialized = true

            Log.d(TAG, "Valhalla initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Valhalla", e)
            false
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
     * Get the distance formatted with appropriate units
     */
    fun getFormattedDistance(): String {
        return if (distanceKm < 1) {
            "${(distanceKm * 1000).toInt()} m"
        } else {
            String.format("%.1f km", distanceKm)
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
