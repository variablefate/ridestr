package com.drivestr.app.notification

import com.drivestr.app.service.DriverStatus
import com.ridestr.common.notification.AlertType
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.notification.NotificationTextProvider

/**
 * Driver-specific notification text generation.
 */
class DriverNotificationTextProvider : NotificationTextProvider<DriverStatus> {

    override fun getBaseStatusText(status: DriverStatus): Pair<String, String> {
        return when (status) {
            is DriverStatus.Available -> {
                val count = status.requestCount
                val content = if (count > 0) {
                    "$count ride request${if (count > 1) "s" else ""} waiting"
                } else {
                    "Waiting for ride requests"
                }
                "You are online" to content
            }
            is DriverStatus.NewRequest -> "New ride request!" to "${status.fare} - ${status.distance}"
            is DriverStatus.EnRouteToPickup -> {
                val name = status.riderName ?: "rider"
                "En route to pickup" to "Picking up $name"
            }
            is DriverStatus.ArrivedAtPickup -> {
                val name = status.riderName ?: "rider"
                "Arrived at pickup" to "Waiting for $name"
            }
            is DriverStatus.RideInProgress -> "Ride in progress" to "Heading to destination"
            is DriverStatus.Cancelled -> "Ride cancelled" to "The rider cancelled"
            is DriverStatus.RoadflareOnly -> "RoadFlare only" to "Available for trusted network only"
        }
    }

    override fun formatAlert(alert: AlertType): String = when (alert) {
        is AlertType.Chat -> "Message: ${alert.preview.take(40)}"
        is AlertType.NewRideRequest -> "${alert.fare} - ${alert.distance}"
        is AlertType.Arrived -> "${alert.participantName ?: "Rider"} is ready"
        is AlertType.EnRoute -> "${alert.participantName ?: "Rider"} is on the way"
        is AlertType.Accepted -> "${alert.participantName ?: "Rider"} confirmed"
    }

    override fun getCombinedTitle(alerts: List<AlertType>): String {
        val hasChat = alerts.any { it is AlertType.Chat }
        val hasRequest = alerts.any { it is AlertType.NewRideRequest }
        return when {
            hasChat && hasRequest -> "New request + Message"
            hasRequest -> "New ride request!"
            hasChat -> "Message from Rider"
            else -> "Alert"
        }
    }

    override fun getChannel(status: DriverStatus, alerts: List<AlertType>): String? {
        return when {
            alerts.isNotEmpty() -> NotificationHelper.CHANNEL_RIDE_REQUEST
            status is DriverStatus.Cancelled -> NotificationHelper.CHANNEL_RIDE_CANCELLED
            status is DriverStatus.EnRouteToPickup ||
            status is DriverStatus.ArrivedAtPickup ||
            status is DriverStatus.RideInProgress -> NotificationHelper.CHANNEL_RIDE_UPDATE
            else -> null  // Default channel
        }
    }

    override fun isHighPriority(status: DriverStatus, alerts: List<AlertType>): Boolean {
        // High priority if alerts OR specific statuses (matches current behavior)
        return alerts.isNotEmpty() || when (status) {
            is DriverStatus.NewRequest,
            is DriverStatus.Cancelled -> true
            else -> false
        }
    }
}
