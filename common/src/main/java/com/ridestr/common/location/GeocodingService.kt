package com.ridestr.common.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Service for geocoding (address lookup) using Android's built-in Geocoder.
 * No external API keys required - uses device's geocoding service.
 *
 * Note: Requires Google Play Services on most devices.
 * For offline/F-Droid support, consider Osmunda in the future.
 */
class GeocodingService(private val context: Context) {

    companion object {
        private const val TAG = "GeocodingService"
    }

    private val geocoder: Geocoder? by lazy {
        if (Geocoder.isPresent()) {
            Geocoder(context, Locale.getDefault())
        } else {
            Log.w(TAG, "Geocoder not available on this device")
            null
        }
    }

    /**
     * Check if geocoding is available on this device.
     */
    fun isAvailable(): Boolean = Geocoder.isPresent()

    /**
     * Forward geocoding: Convert address string to coordinates.
     *
     * @param query Address or place name to search for
     * @param maxResults Maximum number of results to return (default 5)
     * @return List of matching locations, or empty list if none found
     */
    suspend fun searchAddress(query: String, maxResults: Int = 5): List<GeocodingResult> {
        val geo = geocoder ?: return emptyList()

        if (query.isBlank() || query.length < 3) {
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            try {
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ uses async callback
                    getAddressesAsync(geo, query, maxResults)
                } else {
                    // Older versions use blocking call
                    @Suppress("DEPRECATION")
                    geo.getFromLocationName(query, maxResults) ?: emptyList()
                }

                addresses.mapNotNull { address ->
                    if (address.hasLatitude() && address.hasLongitude()) {
                        GeocodingResult(
                            lat = address.latitude,
                            lon = address.longitude,
                            addressLine = address.getAddressLine(0) ?: buildAddressLine(address),
                            featureName = address.featureName,
                            locality = address.locality ?: address.subAdminArea ?: address.adminArea
                        )
                    } else null
                }.also {
                    Log.d(TAG, "Search '$query' returned ${it.size} results")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Geocoding failed for '$query'", e)
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during geocoding", e)
                emptyList()
            }
        }
    }

    /**
     * Reverse geocoding: Convert coordinates to address string.
     *
     * @param lat Latitude
     * @param lon Longitude
     * @return Address string, or null if not found
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? {
        val geo = geocoder ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getReverseAddressesAsync(geo, lat, lon, 1)
                } else {
                    @Suppress("DEPRECATION")
                    geo.getFromLocation(lat, lon, 1) ?: emptyList()
                }

                addresses.firstOrNull()?.let { address ->
                    address.getAddressLine(0) ?: buildAddressLine(address)
                }.also {
                    Log.d(TAG, "Reverse geocode ($lat, $lon) -> $it")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Reverse geocoding failed for ($lat, $lon)", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during reverse geocoding", e)
                null
            }
        }
    }

    /**
     * Get a short display name for coordinates (locality or short address).
     */
    suspend fun getShortName(lat: Double, lon: Double): String? {
        val geo = geocoder ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getReverseAddressesAsync(geo, lat, lon, 1)
                } else {
                    @Suppress("DEPRECATION")
                    geo.getFromLocation(lat, lon, 1) ?: emptyList()
                }

                addresses.firstOrNull()?.let { address ->
                    // Try to get a short name: feature name, locality, or admin area
                    address.featureName
                        ?: address.locality
                        ?: address.subAdminArea
                        ?: address.adminArea
                        ?: address.getAddressLine(0)?.take(30)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting short name for ($lat, $lon)", e)
                null
            }
        }
    }

    /**
     * Build an address line from address components when getAddressLine(0) is null.
     */
    private fun buildAddressLine(address: Address): String {
        return buildString {
            address.featureName?.let { append(it) }
            address.thoroughfare?.let {
                if (isNotEmpty()) append(", ")
                append(it)
            }
            address.locality?.let {
                if (isNotEmpty()) append(", ")
                append(it)
            }
            address.adminArea?.let {
                if (isNotEmpty()) append(", ")
                append(it)
            }
            address.postalCode?.let {
                if (isNotEmpty()) append(" ")
                append(it)
            }
        }.ifEmpty { "${address.latitude}, ${address.longitude}" }
    }

    /**
     * Android 13+ async geocoding for forward lookup.
     */
    private suspend fun getAddressesAsync(
        geocoder: Geocoder,
        query: String,
        maxResults: Int
    ): List<Address> = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocationName(query, maxResults, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    continuation.resume(addresses)
                }

                override fun onError(errorMessage: String?) {
                    Log.e(TAG, "Geocode error: $errorMessage")
                    continuation.resume(emptyList())
                }
            })
        } else {
            continuation.resume(emptyList())
        }
    }

    /**
     * Android 13+ async geocoding for reverse lookup.
     */
    private suspend fun getReverseAddressesAsync(
        geocoder: Geocoder,
        lat: Double,
        lon: Double,
        maxResults: Int
    ): List<Address> = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lon, maxResults, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    continuation.resume(addresses)
                }

                override fun onError(errorMessage: String?) {
                    Log.e(TAG, "Reverse geocode error: $errorMessage")
                    continuation.resume(emptyList())
                }
            })
        } else {
            continuation.resume(emptyList())
        }
    }
}

/**
 * Result from a geocoding search.
 */
data class GeocodingResult(
    val lat: Double,
    val lon: Double,
    val addressLine: String,      // Full address: "123 Main St, Denver, CO 80202"
    val featureName: String?,     // Place name if available: "Starbucks"
    val locality: String?         // City/town: "Denver"
) {
    /**
     * Get a display string suitable for UI (prefers shorter name if available).
     */
    fun getDisplayName(): String {
        return featureName?.let { "$it, ${locality ?: ""}" }?.trimEnd(',', ' ')
            ?: addressLine
    }
}
