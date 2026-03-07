package com.roadflare.common.settings

/**
 * Typed domain model for all app settings.
 */
data class AppSettings(
    val displayCurrency: DisplayCurrency = DisplayCurrency.USD,
    val distanceUnit: DistanceUnit = DistanceUnit.MILES,
    val autoOpenNavigation: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val notificationSound: Boolean = true,
    val notificationVibration: Boolean = true,
    val relayUrls: List<String> = emptyList(),
    val fiatPaymentMethods: List<String> = listOf("fiat_cash"),
    val useGeocodingSearch: Boolean = true,
    val useManualDriverLocation: Boolean = false,
    val manualDriverLat: Double = 0.0,
    val manualDriverLon: Double = 0.0,
    val tilesSetupCompleted: Boolean = false
)
