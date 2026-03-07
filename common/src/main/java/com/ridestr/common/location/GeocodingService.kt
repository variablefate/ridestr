package com.ridestr.common.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Geocoding service using Photon (OpenStreetMap) as primary source
 * with Android's built-in Geocoder as fallback.
 *
 * Photon: Free, no API key, returns place names (POIs, businesses, landmarks).
 * Android Geocoder: Offline fallback, requires Google Play Services.
 */
class GeocodingService(private val context: Context) {

    companion object {
        private const val TAG = "GeocodingService"
        private const val PHOTON_BASE_URL = "https://photon.komoot.io"

        /** Haversine distance in km between two lat/lon points. */
        private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

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
    fun isAvailable(): Boolean = true // Photon is always available with network

    /**
     * Forward geocoding: Convert address string to coordinates.
     * Uses Photon (OpenStreetMap) as primary, Android Geocoder as fallback.
     *
     * @param biasLat Optional latitude to bias results toward (user's current location)
     * @param biasLon Optional longitude to bias results toward
     */
    suspend fun searchAddress(
        query: String,
        maxResults: Int = 5,
        biasLat: Double? = null,
        biasLon: Double? = null
    ): List<GeocodingResult> {
        if (query.isBlank() || query.length < 3) {
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            // Try Photon first
            val photonResults = searchPhoton(query, maxResults, biasLat, biasLon)
            if (photonResults.isNotEmpty()) {
                // Sort by distance from bias point if available
                if (biasLat != null && biasLon != null) {
                    return@withContext photonResults.sortedBy { result ->
                        distanceKm(biasLat, biasLon, result.lat, result.lon)
                    }
                }
                return@withContext photonResults
            }

            // Fall back to Android Geocoder
            Log.d(TAG, "Photon returned no results, falling back to Android Geocoder")
            searchAndroidGeocoder(query, maxResults)
        }
    }

    /**
     * Search using Photon (komoot) geocoding API.
     * Returns place names, addresses, and POIs from OpenStreetMap data.
     */
    private fun searchPhoton(
        query: String,
        maxResults: Int,
        biasLat: Double? = null,
        biasLon: Double? = null
    ): List<GeocodingResult> {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val lang = Locale.getDefault().language
            // Round to 2 decimal places (~1.1km precision) for privacy
            val biasPart = if (biasLat != null && biasLon != null) {
                val roundedLat = "%.2f".format(biasLat)
                val roundedLon = "%.2f".format(biasLon)
                "&lat=$roundedLat&lon=$roundedLon"
            } else ""
            val url = "$PHOTON_BASE_URL/api/?q=$encodedQuery&limit=$maxResults&lang=$lang$biasPart"

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Photon API returned ${response.code}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val features = json.getJSONArray("features")

            val results = mutableListOf<GeocodingResult>()
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val coords = geometry.getJSONArray("coordinates")
                val lon = coords.getDouble(0)
                val lat = coords.getDouble(1)

                val props = feature.getJSONObject("properties")
                val name = props.optString("name", null)
                val street = props.optString("street", null)
                val houseNumber = props.optString("housenumber", null)
                val city = props.optString("city", null)
                val state = props.optString("state", null)
                val postcode = props.optString("postcode", null)
                val country = props.optString("country", null)

                val streetAddr = when {
                    houseNumber != null && street != null -> "$houseNumber $street"
                    street != null -> street
                    else -> null
                }

                val addressLine = buildString {
                    (streetAddr ?: name)?.let { append(it) }
                    city?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                    state?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                    postcode?.let {
                        if (isNotEmpty()) append(" ")
                        append(it)
                    }
                    country?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                }.ifEmpty { "$lat, $lon" }

                // featureName = place name if it's different from the street address
                val featureName = name?.takeIf { it != streetAddr }

                results.add(
                    GeocodingResult(
                        lat = lat,
                        lon = lon,
                        addressLine = addressLine,
                        featureName = featureName,
                        locality = city ?: state
                    )
                )
            }

            Log.d(TAG, "Photon search '$query' returned ${results.size} results")
            return results
        } catch (e: IOException) {
            Log.w(TAG, "Photon network error for '$query'", e)
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Photon parsing error for '$query'", e)
            return emptyList()
        }
    }

    /**
     * Fallback: Search using Android's built-in Geocoder.
     */
    private suspend fun searchAndroidGeocoder(query: String, maxResults: Int): List<GeocodingResult> {
        val geo = geocoder ?: return emptyList()

        return try {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getAddressesAsync(geo, query, maxResults)
            } else {
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
                Log.d(TAG, "Android Geocoder '$query' returned ${it.size} results")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Geocoding failed for '$query'", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during geocoding", e)
            emptyList()
        }
    }

    /**
     * Reverse geocoding: Convert coordinates to address string.
     * Uses Photon first, Android Geocoder as fallback.
     *
     * @param localOnly If true, only uses Android's built-in Geocoder (no network
     *   call to Photon). Use for GPS "my location" to avoid sending exact coordinates
     *   to a third-party server.
     */
    suspend fun reverseGeocode(lat: Double, lon: Double, localOnly: Boolean = false): String? {
        return withContext(Dispatchers.IO) {
            if (localOnly) {
                reverseAndroidGeocoder(lat, lon)
            } else {
                reversePhoton(lat, lon) ?: reverseAndroidGeocoder(lat, lon)
            }
        }
    }

    /**
     * Reverse geocode using Photon API.
     */
    private fun reversePhoton(lat: Double, lon: Double): String? {
        try {
            val url = "$PHOTON_BASE_URL/reverse?lat=$lat&lon=$lon&limit=1"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val features = json.getJSONArray("features")
            if (features.length() == 0) return null

            val props = features.getJSONObject(0).getJSONObject("properties")
            val name = props.optString("name", null)
            val street = props.optString("street", null)
            val houseNumber = props.optString("housenumber", null)
            val city = props.optString("city", null)
            val state = props.optString("state", null)
            val postcode = props.optString("postcode", null)

            return buildString {
                val streetAddr = when {
                    houseNumber != null && street != null -> "$houseNumber $street"
                    street != null -> street
                    else -> null
                }
                (name ?: streetAddr)?.let { append(it) }
                city?.let {
                    if (isNotEmpty()) append(", ")
                    append(it)
                }
                state?.let {
                    if (isNotEmpty()) append(", ")
                    append(it)
                }
                postcode?.let {
                    if (isNotEmpty()) append(" ")
                    append(it)
                }
            }.ifEmpty { null }.also {
                Log.d(TAG, "Photon reverse ($lat, $lon) -> $it")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Photon reverse geocode failed for ($lat, $lon)", e)
            return null
        }
    }

    /**
     * Fallback: Reverse geocode using Android Geocoder.
     */
    private suspend fun reverseAndroidGeocoder(lat: Double, lon: Double): String? {
        val geo = geocoder ?: return null

        return try {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getReverseAddressesAsync(geo, lat, lon, 1)
            } else {
                @Suppress("DEPRECATION")
                geo.getFromLocation(lat, lon, 1) ?: emptyList()
            }

            addresses.firstOrNull()?.let { address ->
                address.getAddressLine(0) ?: buildAddressLine(address)
            }.also {
                Log.d(TAG, "Android reverse geocode ($lat, $lon) -> $it")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Reverse geocoding failed for ($lat, $lon)", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during reverse geocoding", e)
            null
        }
    }

    /**
     * Get a short display name for coordinates (locality or short address).
     */
    suspend fun getShortName(lat: Double, lon: Double): String? {
        return withContext(Dispatchers.IO) {
            // Try Photon reverse
            try {
                val url = "$PHOTON_BASE_URL/reverse?lat=$lat&lon=$lon&limit=1"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val json = JSONObject(body)
                        val features = json.getJSONArray("features")
                        if (features.length() > 0) {
                            val props = features.getJSONObject(0).getJSONObject("properties")
                            val name = props.optString("name", null)
                                ?: props.optString("city", null)
                                ?: props.optString("state", null)
                            if (name != null) return@withContext name
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Photon short name failed for ($lat, $lon)", e)
            }

            // Fallback to Android Geocoder
            val geo = geocoder ?: return@withContext null
            try {
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getReverseAddressesAsync(geo, lat, lon, 1)
                } else {
                    @Suppress("DEPRECATION")
                    geo.getFromLocation(lat, lon, 1) ?: emptyList()
                }

                addresses.firstOrNull()?.let { address ->
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
