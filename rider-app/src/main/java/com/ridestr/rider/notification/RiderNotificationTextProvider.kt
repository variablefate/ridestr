package com.ridestr.rider.notification

import com.ridestr.common.notification.AlertType
import com.ridestr.common.notification.NotificationTextProvider
import com.ridestr.rider.service.RiderStatus

/**
 * Rider-specific notification text generation.
 */
class RiderNotificationTextProvider : NotificationTextProvider<RiderStatus> {

    override fun getBaseStatusText(status: RiderStatus): Pair<String, String> {
        return when (status) {
            is RiderStatus.Searching -> "Looking for a ride" to "Searching for drivers..."
            is RiderStatus.DriverAccepted -> {
                val name = status.driverName ?: "A driver"
                "Driver found!" to "$name accepted your ride"
            }
            is RiderStatus.DriverEnRoute -> {
                val name = status.driverName ?: "Your driver"
                "Driver on the way" to "$name is heading to you"
            }
            is RiderStatus.DriverArrived -> {
                val name = status.driverName ?: "Your driver"
                "Driver has arrived!" to "$name is waiting at pickup"
            }
            is RiderStatus.RideInProgress -> "Ride in progress" to "On the way to destination"
            is RiderStatus.Cancelled -> "Ride cancelled" to "Your ride was cancelled"
        }
    }

    override fun formatAlert(alert: AlertType): String = when (alert) {
        is AlertType.Chat -> "Message: ${alert.preview.take(40)}"
        is AlertType.Arrived -> "${alert.participantName ?: "Your driver"} is waiting at pickup"
        is AlertType.EnRoute -> "${alert.participantName ?: "Your driver"} is heading to you"
        is AlertType.Accepted -> "${alert.participantName ?: "A driver"} accepted your ride"
        is AlertType.NewRideRequest -> ""  // Not used for rider
    }

    override fun getCombinedTitle(alerts: List<AlertType>): String {
        val hasChat = alerts.any { it is AlertType.Chat }
        val hasArrived = alerts.any { it is AlertType.Arrived }
        val hasEnRoute = alerts.any { it is AlertType.EnRoute }
        val hasAccepted = alerts.any { it is AlertType.Accepted }

        return when {
            hasChat && hasArrived -> "Driver arrived + Message"
            hasChat && hasEnRoute -> "Driver on the way + Message"
            hasChat && hasAccepted -> "Driver found + Message"
            hasArrived -> "Driver has arrived!"
            hasEnRoute -> "Driver on the way!"
            hasAccepted -> "Driver found!"
            hasChat -> "Message from Driver"
            else -> "Alert"
        }
    }

    override fun getChannel(status: RiderStatus, alerts: List<AlertType>): String? {
        // Rider uses isHighPriority only, no explicit channel routing
        return null
    }

    override fun isHighPriority(status: RiderStatus, alerts: List<AlertType>): Boolean {
        // High priority if alerts OR specific statuses (matches current behavior)
        return alerts.isNotEmpty() || when (status) {
            is RiderStatus.DriverAccepted,
            is RiderStatus.DriverArrived,
            is RiderStatus.Cancelled -> true
            else -> false
        }
    }
}
