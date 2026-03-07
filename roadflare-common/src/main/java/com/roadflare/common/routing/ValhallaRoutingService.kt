package com.roadflare.common.routing

import ValhallaConfigBuilder
import android.content.Context
import android.util.Log
import com.roadflare.common.settings.DistanceUnit
import com.valhalla.api.models.CostingModel
import com.valhalla.api.models.DirectionsOptions
import com.valhalla.api.models.RouteRequest
import com.valhalla.api.models.RoutingWaypoint
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaException
import com.valhalla.valhalla.ValhallaResponse
import com.valhalla.valhalla.files.ValhallaFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for offline routing using Valhalla Mobile.
 *
 * Wraps the Valhalla routing engine and provides a simple API
 * for calculating routes between two points.
 */
@Singleton
class ValhallaRoutingService @Inject constructor(
    private val tileManager: TileManager,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ValhallaRoutingService"
    }

    private var valhalla: Valhalla? = null
    private var isInitialized = false
    private var currentTileFile: String? = null

    suspend fun initialize(tileFilename: String): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized && currentTileFile == tileFilename) return@withContext true

        if (isInitialized && currentTileFile != tileFilename) {
            valhalla = null
            isInitialized = false
        }

        try {
            Log.d(TAG, "Initializing Valhalla with tiles: $tileFilename")
            val tarFile = ValhallaFile.usingAsset(context, tileFilename)
            val config = ValhallaConfigBuilder()
                .withTileExtract(tarFile.absolutePath())
                .build()
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

    suspend fun initializeWithDownloadedTile(tileFile: File): Boolean = withContext(Dispatchers.IO) {
        val tilePath = tileFile.absolutePath

        if (isInitialized && currentTileFile == tilePath) return@withContext true

        if (isInitialized && currentTileFile != tilePath) {
            valhalla = null
            isInitialized = false
        }

        if (!tileFile.exists()) {
            Log.e(TAG, "Tile file does not exist: $tilePath")
            return@withContext false
        }

        try {
            val config = ValhallaConfigBuilder()
                .withTileExtract(tilePath)
                .build()
            valhalla = Valhalla(context, config)
            isInitialized = true
            currentTileFile = tilePath
            Log.d(TAG, "Valhalla initialized with downloaded tile: ${tileFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Valhalla with downloaded tile", e)
            false
        }
    }

    suspend fun initializeWithTileSource(tileSource: TileSource): Boolean {
        return when (tileSource) {
            is TileSource.Bundled -> initialize(tileSource.assetName)
            is TileSource.Downloaded -> initializeWithDownloadedTile(tileSource.file)
        }
    }

    suspend fun calculateRoute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        costingModel: CostingModel = CostingModel.auto
    ): ValhallaRouteResult? = withContext(Dispatchers.IO) {
        val valhallaInstance = valhalla
        if (valhallaInstance == null) {
            Log.e(TAG, "Valhalla not initialized. Call initialize() first.")
            return@withContext null
        }

        try {
            val request = RouteRequest(
                locations = listOf(
                    RoutingWaypoint(lat = originLat, lon = originLon),
                    RoutingWaypoint(lat = destLat, lon = destLon)
                ),
                costing = costingModel,
                directionsOptions = DirectionsOptions(format = DirectionsOptions.Format.json)
            )

            when (val response = valhallaInstance.route(request)) {
                is ValhallaResponse.Json -> {
                    val trip = response.jsonResponse.trip
                    val summary = trip?.summary
                    val legs = trip?.legs

                    if (summary != null) {
                        ValhallaRouteResult(
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
                    } else null
                }
                is ValhallaResponse.Osrm -> null
            }
        } catch (e: ValhallaException) {
            Log.e(TAG, "Valhalla routing error: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during routing", e)
            null
        }
    }

    fun isReady(): Boolean = isInitialized && valhalla != null
}

/**
 * Result of a Valhalla route calculation.
 * Named ValhallaRouteResult to avoid conflict with FareCoordinator's RouteResult.
 */
data class ValhallaRouteResult(
    val distanceKm: Double,
    val durationSeconds: Double,
    val encodedPolyline: String?,
    val maneuvers: List<Maneuver>
) {
    fun getFormattedDuration(): String {
        val minutes = (durationSeconds / 60).toInt()
        val seconds = (durationSeconds % 60).toInt()
        return "${minutes}m ${seconds}s"
    }

    fun getFormattedDistance(unit: DistanceUnit = DistanceUnit.KILOMETERS): String {
        return when (unit) {
            DistanceUnit.KILOMETERS -> {
                if (distanceKm < 1) "${(distanceKm * 1000).toInt()} m"
                else String.format("%.1f km", distanceKm)
            }
            DistanceUnit.MILES -> {
                val miles = distanceKm * 0.621371
                if (miles < 0.1) "${(miles * 5280).toInt()} ft"
                else String.format("%.1f mi", miles)
            }
        }
    }
}

data class Maneuver(
    val instruction: String,
    val distanceKm: Double,
    val durationSeconds: Double
)
