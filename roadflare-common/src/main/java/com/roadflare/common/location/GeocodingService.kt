package com.roadflare.common.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Geocoding service using Android's built-in Geocoder API.
 * Port from ridestr with @Inject constructor for Hilt DI.
 */
@Singleton
class GeocodingService @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val TAG = "GeocodingService"
    }

    private val geocoder: Geocoder? by lazy {
        if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
    }

    fun isAvailable(): Boolean = Geocoder.isPresent()

    suspend fun searchAddress(query: String, maxResults: Int = 5): List<GeocodingResult> {
        val geo = geocoder ?: return emptyList()
        if (query.isBlank() || query.length < 3) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    getAddressesAsync(geo, query, maxResults)
                } else {
                    @Suppress("DEPRECATION")
                    geo.getFromLocationName(query, maxResults) ?: emptyList()
                }

                addresses.mapNotNull { address ->
                    if (address.hasLatitude() && address.hasLongitude()) {
                        GeocodingResult(
                            lat = address.latitude, lon = address.longitude,
                            addressLine = address.getAddressLine(0) ?: buildAddressLine(address),
                            featureName = address.featureName,
                            locality = address.locality ?: address.subAdminArea ?: address.adminArea
                        )
                    } else null
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
                addresses.firstOrNull()?.let { it.getAddressLine(0) ?: buildAddressLine(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Reverse geocoding failed for ($lat, $lon)", e)
                null
            }
        }
    }

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
                    address.featureName ?: address.locality ?: address.subAdminArea
                        ?: address.adminArea ?: address.getAddressLine(0)?.take(30)
                }
            } catch (e: Exception) { null }
        }
    }

    private fun buildAddressLine(address: Address): String = buildString {
        address.featureName?.let { append(it) }
        address.thoroughfare?.let { if (isNotEmpty()) append(", "); append(it) }
        address.locality?.let { if (isNotEmpty()) append(", "); append(it) }
        address.adminArea?.let { if (isNotEmpty()) append(", "); append(it) }
        address.postalCode?.let { if (isNotEmpty()) append(" "); append(it) }
    }.ifEmpty { "${address.latitude}, ${address.longitude}" }

    private suspend fun getAddressesAsync(geocoder: Geocoder, query: String, maxResults: Int): List<Address> =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocationName(query, maxResults, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { continuation.resume(addresses) }
                    override fun onError(errorMessage: String?) { continuation.resume(emptyList()) }
                })
            } else { continuation.resume(emptyList()) }
        }

    private suspend fun getReverseAddressesAsync(geocoder: Geocoder, lat: Double, lon: Double, maxResults: Int): List<Address> =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(lat, lon, maxResults, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { continuation.resume(addresses) }
                    override fun onError(errorMessage: String?) { continuation.resume(emptyList()) }
                })
            } else { continuation.resume(emptyList()) }
        }
}

data class GeocodingResult(
    val lat: Double,
    val lon: Double,
    val addressLine: String,
    val featureName: String?,
    val locality: String?
) {
    fun getDisplayName(): String =
        featureName?.let { "$it, ${locality ?: ""}" }?.trimEnd(',', ' ') ?: addressLine
}
