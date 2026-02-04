package com.ridestr.common.notification

/**
 * Platform-agnostic notification model.
 * Services convert this to Android Notification.
 */
data class NotificationData(
    val title: String,
    val content: String,
    val isHighPriority: Boolean,
    val channel: String? = null  // null = use default based on isHighPriority
)
