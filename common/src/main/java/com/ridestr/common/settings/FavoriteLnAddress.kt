package com.ridestr.common.settings

/**
 * A favorite lightning address with optional label.
 */
data class FavoriteLnAddress(
    val address: String,
    val label: String? = null,
    val lastUsed: Long = System.currentTimeMillis()
)
