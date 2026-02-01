package com.ridestr.common.util

import com.ridestr.common.nostr.events.UserProfile

/**
 * Helper utilities for building RideHistoryEntry objects.
 *
 * This extracts common calculations used across both DriverViewModel and RiderViewModel
 * when creating ride history entries. The actual RideHistoryEntry construction remains
 * in each ViewModel because drivers and riders store different data:
 * - Drivers: store geohashes only (privacy)
 * - Riders: store exact coordinates + addresses
 */
object RideHistoryBuilder {
    /**
     * Conversion factor: 1 km = 0.621371 miles
     */
    private const val KM_TO_MILES = 0.621371

    /**
     * Extract first name from profile for display in ride history.
     * Uses UserProfile.bestName() to get the display name, then takes the first word.
     *
     * @param profile The user profile, or null if not available
     * @return First name, or null if profile is null or has no name
     */
    fun extractCounterpartyFirstName(profile: UserProfile?): String? =
        profile?.bestName()?.split(" ")?.firstOrNull()

    /**
     * Convert kilometers to miles for ride history display.
     *
     * @param km Distance in kilometers, or null
     * @return Distance in miles (defaults to 0.0 if null)
     */
    fun toDistanceMiles(km: Double?): Double = (km ?: 0.0) * KM_TO_MILES

    /**
     * Current timestamp in seconds (Nostr convention).
     *
     * @return Unix timestamp in seconds
     */
    fun currentTimestampSeconds(): Long = System.currentTimeMillis() / 1000
}
