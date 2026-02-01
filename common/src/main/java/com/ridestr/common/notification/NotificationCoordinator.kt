package com.ridestr.common.notification

/**
 * Centralized notification logic coordinator.
 * Generic over status type S (DriverStatus or RiderStatus).
 */
class NotificationCoordinator<S>(
    private val textProvider: NotificationTextProvider<S>
) {
    private val _alertStack = mutableListOf<AlertType>()
    val alertStack: List<AlertType> get() = _alertStack.toList()

    private var currentStatus: S? = null

    /**
     * Update the base status. Does NOT clear alerts.
     */
    fun updateStatus(status: S) {
        currentStatus = status
    }

    /**
     * Add an alert to the stack.
     */
    fun addAlert(alert: AlertType) {
        when (alert) {
            is AlertType.Arrived -> {
                _alertStack.removeAll { it is AlertType.EnRoute }
            }
            is AlertType.EnRoute -> {
                _alertStack.removeAll { it is AlertType.Accepted }
            }
            else -> { /* allow duplicates for Chat, etc. */ }
        }

        // Avoid duplicate status alerts
        if (alert is AlertType.RideStatusAlert) {
            if (_alertStack.none { it::class == alert::class }) {
                _alertStack.add(alert)
            }
        } else {
            _alertStack.add(alert)
        }
    }

    /**
     * Clear all alerts (called when app comes to foreground).
     */
    fun clearAlerts() {
        _alertStack.clear()
    }

    /**
     * Clear specific alert types (e.g., request alerts on acceptance).
     */
    fun clearAlertsOfType(alertClass: Class<out AlertType>) {
        _alertStack.removeAll { alertClass.isInstance(it) }
    }

    /**
     * Generate notification data based on current status and alerts.
     * Returns null if no status has been set yet (service startup).
     */
    fun buildNotificationData(): NotificationData? {
        val status = currentStatus ?: return null
        val alerts = _alertStack.toList()

        return if (alerts.isEmpty()) {
            val (title, content) = textProvider.getBaseStatusText(status)
            NotificationData(
                title = title,
                content = content,
                isHighPriority = textProvider.isHighPriority(status, alerts),
                channel = textProvider.getChannel(status, alerts)
            )
        } else {
            val sortedAlerts = alerts.sortedBy { it.sortPriority }
            val title = textProvider.getCombinedTitle(sortedAlerts)
            val content = sortedAlerts.joinToString("\n") { textProvider.formatAlert(it) }

            NotificationData(
                title = title,
                content = content,
                isHighPriority = textProvider.isHighPriority(status, alerts),
                channel = textProvider.getChannel(status, alerts)
            )
        }
    }
}
