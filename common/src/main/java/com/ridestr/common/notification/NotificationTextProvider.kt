package com.ridestr.common.notification

/**
 * Interface for app-specific notification text generation.
 * Implementations provide driver or rider specific wording.
 */
interface NotificationTextProvider<S> {
    /** Generate title/content for a status with no alerts. */
    fun getBaseStatusText(status: S): Pair<String, String>

    /** Format an alert for display in notification content. */
    fun formatAlert(alert: AlertType): String

    /** Generate combined title when alerts present. */
    fun getCombinedTitle(alerts: List<AlertType>): String

    /** Determine notification channel based on status and alerts. */
    fun getChannel(status: S, alerts: List<AlertType>): String?

    /**
     * Determine if notification should be high priority.
     * CRITICAL: Some statuses are high-priority even WITHOUT alerts.
     * - Driver: Cancelled
     * - Rider: DriverAccepted, DriverArrived, Cancelled
     */
    fun isHighPriority(status: S, alerts: List<AlertType>): Boolean
}
