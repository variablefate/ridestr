package com.ridestr.common.nostr.events

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AdminConfigEvent"

/**
 * Kind 30182: Admin Config Event (Parameterized Replaceable)
 *
 * Platform-wide configuration published by the official admin pubkey.
 * Contains fare rates, recommended mints, and app version info.
 *
 * Apps fetch this event once on startup after relay connection,
 * cache locally for offline use, and fall back to hardcoded defaults
 * if no event found.
 *
 * Security: Only events from ADMIN_PUBKEY should be trusted.
 */
object AdminConfigEvent {
    const val KIND = RideshareEventKinds.ADMIN_CONFIG
    const val D_TAG = "ridestr-admin-config"

    /**
     * Official Ridestr admin pubkey.
     * This is the same pubkey used for tile publishing.
     * Apps MUST verify event.pubKey matches this value.
     */
    const val ADMIN_PUBKEY = "da790ba18e63ae79b16e172907301906957a45f38ef0c9f219d0f016eaf16128"

    /**
     * Parse an admin config event into data class.
     * Returns null if parsing fails or pubkey doesn't match admin key.
     *
     * @param event The Nostr event to parse
     * @param verifyPubkey Whether to verify the event is from admin pubkey (default true)
     */
    fun parse(event: Event, verifyPubkey: Boolean = true): AdminConfig? {
        // Verify this is from the official admin
        if (verifyPubkey && event.pubKey != ADMIN_PUBKEY) {
            Log.w(TAG, "Ignoring admin config from untrusted pubkey: ${event.pubKey.take(16)}...")
            return null
        }

        // Verify kind
        if (event.kind != KIND) {
            Log.w(TAG, "Wrong event kind: ${event.kind}, expected $KIND")
            return null
        }

        return try {
            val json = JSONObject(event.content)

            // Parse fare settings
            val fareRateUsdPerMile = json.optDouble("fare_rate_usd_per_mile", AdminConfig.DEFAULT_FARE_RATE)
            val minimumFareUsd = json.optDouble("minimum_fare_usd", AdminConfig.DEFAULT_MINIMUM_FARE)
            val roadflareFareRate = json.optDouble("roadflare_fare_rate_usd_per_mile", AdminConfig.DEFAULT_ROADFLARE_FARE_RATE)
            val roadflareMinimumFare = json.optDouble("roadflare_minimum_fare_usd", AdminConfig.DEFAULT_ROADFLARE_MINIMUM_FARE)

            // Parse recommended mints
            val mints = mutableListOf<MintOption>()
            val mintsArray = json.optJSONArray("recommended_mints")
            if (mintsArray != null) {
                for (i in 0 until mintsArray.length()) {
                    val mintJson = mintsArray.getJSONObject(i)
                    mints.add(
                        MintOption(
                            name = mintJson.getString("name"),
                            url = mintJson.getString("url"),
                            description = mintJson.optString("description", ""),
                            recommended = mintJson.optBoolean("recommended", false)
                        )
                    )
                }
            }

            // Parse version info
            val versionInfo = json.optJSONObject("latest_version")?.let { versionJson ->
                val riderJson = versionJson.optJSONObject("rider")
                val driverJson = versionJson.optJSONObject("driver")

                VersionInfo(
                    rider = riderJson?.let {
                        AppVersion(
                            code = it.optInt("code", 0),
                            name = it.optString("name", ""),
                            sha256 = it.optString("sha256", "")
                        )
                    },
                    driver = driverJson?.let {
                        AppVersion(
                            code = it.optInt("code", 0),
                            name = it.optString("name", ""),
                            sha256 = it.optString("sha256", "")
                        )
                    }
                )
            }

            AdminConfig(
                fareRateUsdPerMile = fareRateUsdPerMile,
                minimumFareUsd = minimumFareUsd,
                roadflareFareRateUsdPerMile = roadflareFareRate,
                roadflareMinimumFareUsd = roadflareMinimumFare,
                recommendedMints = mints.ifEmpty { AdminConfig.DEFAULT_MINTS },
                latestVersion = versionInfo,
                eventId = event.id,
                createdAt = event.createdAt
            ).also {
                Log.d(TAG, "Parsed admin config: fare=$${it.fareRateUsdPerMile}/mi, roadflare=$${it.roadflareFareRateUsdPerMile}/mi, min=$${it.minimumFareUsd}, ${it.recommendedMints.size} mints")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse admin config event", e)
            null
        }
    }
}

/**
 * Admin configuration data.
 */
data class AdminConfig(
    val fareRateUsdPerMile: Double = DEFAULT_FARE_RATE,
    val minimumFareUsd: Double = DEFAULT_MINIMUM_FARE,
    val roadflareFareRateUsdPerMile: Double = DEFAULT_ROADFLARE_FARE_RATE,
    val roadflareMinimumFareUsd: Double = DEFAULT_ROADFLARE_MINIMUM_FARE,
    val recommendedMints: List<MintOption> = DEFAULT_MINTS,
    val latestVersion: VersionInfo? = null,
    val eventId: String? = null,
    val createdAt: Long = 0
) {
    companion object {
        const val DEFAULT_FARE_RATE = 1.85
        const val DEFAULT_MINIMUM_FARE = 5.0
        const val DEFAULT_ROADFLARE_FARE_RATE = 1.20
        const val DEFAULT_ROADFLARE_MINIMUM_FARE = 5.0

        val DEFAULT_MINTS = listOf(
            MintOption(
                name = "Minibits",
                url = "https://mint.minibits.cash/Bitcoin",
                description = "Popular, widely used (~1% fees)",
                recommended = true
            )
        )
    }
}

/**
 * A recommended Cashu mint option.
 */
data class MintOption(
    val name: String,
    val url: String,
    val description: String = "",
    val recommended: Boolean = false
)

/**
 * App version information for update checking.
 */
data class VersionInfo(
    val rider: AppVersion? = null,
    val driver: AppVersion? = null
)

/**
 * Version details for a specific app.
 */
data class AppVersion(
    val code: Int,
    val name: String,
    val sha256: String = ""
)
