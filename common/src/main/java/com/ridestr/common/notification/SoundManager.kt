package com.ridestr.common.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.ridestr.common.settings.SettingsManager

private const val TAG = "SoundManager"

/**
 * Manages sound playback and vibration for ride notifications.
 * Uses system sounds (no custom audio files needed).
 * Respects the phone's ringer mode (normal/vibrate/silent).
 */
object SoundManager {

    // Vibration patterns (milliseconds)
    private val VIBRATION_RIDE_REQUEST = longArrayOf(0, 400, 150, 400)    // Two pulses
    private val VIBRATION_CONFIRMATION = longArrayOf(0, 250, 100, 250)   // Double pulse
    private val VIBRATION_ALERT = longArrayOf(0, 300, 100, 300, 100, 300) // Triple pulse for cancellation
    private val VIBRATION_MESSAGE = longArrayOf(0, 150)                   // Quick tap

    /**
     * Play notification sound and vibrate for a new ride request.
     * @param soundEnabled If false, skip playing sound (respects user settings)
     * @param vibrationEnabled If false, skip vibration (respects user settings)
     */
    fun playRideRequestAlert(
        context: Context,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true
    ) {
        Log.d(TAG, "Playing ride request alert (sound=$soundEnabled, vibration=$vibrationEnabled)")
        if (soundEnabled) playNotificationSoundIfAllowed(context)
        if (vibrationEnabled) vibrateIfAllowed(context, VIBRATION_RIDE_REQUEST)
    }

    /**
     * Play notification sound and vibrate for ride confirmation.
     * @param soundEnabled If false, skip playing sound (respects user settings)
     * @param vibrationEnabled If false, skip vibration (respects user settings)
     */
    fun playConfirmationAlert(
        context: Context,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true
    ) {
        Log.d(TAG, "Playing confirmation alert (sound=$soundEnabled, vibration=$vibrationEnabled)")
        if (soundEnabled) playNotificationSoundIfAllowed(context)
        if (vibrationEnabled) vibrateIfAllowed(context, VIBRATION_CONFIRMATION)
    }

    /**
     * Play notification sound and vibrate for ride cancellation.
     * Uses normal notification sound (not alarm).
     * @param soundEnabled If false, skip playing sound (respects user settings)
     * @param vibrationEnabled If false, skip vibration (respects user settings)
     */
    fun playCancellationAlert(
        context: Context,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true
    ) {
        Log.d(TAG, "Playing cancellation alert (sound=$soundEnabled, vibration=$vibrationEnabled)")
        if (soundEnabled) playNotificationSoundIfAllowed(context)
        if (vibrationEnabled) vibrateIfAllowed(context, VIBRATION_ALERT)
    }

    /**
     * Play notification sound for a chat message.
     * @param soundEnabled If false, skip playing sound (respects user settings)
     * @param vibrationEnabled If false, skip vibration (respects user settings)
     */
    fun playChatMessageAlert(
        context: Context,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true
    ) {
        Log.d(TAG, "Playing chat message alert (sound=$soundEnabled, vibration=$vibrationEnabled)")
        if (soundEnabled) playNotificationSoundIfAllowed(context)
        if (vibrationEnabled) vibrateIfAllowed(context, VIBRATION_MESSAGE)
    }

    /**
     * Play notification sound when driver arrives.
     * @param soundEnabled If false, skip playing sound (respects user settings)
     * @param vibrationEnabled If false, skip vibration (respects user settings)
     */
    fun playDriverArrivedAlert(
        context: Context,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true
    ) {
        Log.d(TAG, "Playing driver arrived alert (sound=$soundEnabled, vibration=$vibrationEnabled)")
        if (soundEnabled) playNotificationSoundIfAllowed(context)
        if (vibrationEnabled) vibrateIfAllowed(context, VIBRATION_CONFIRMATION)
    }

    // === CONVENIENCE OVERLOADS: Automatically read settings from SettingsManager ===

    /**
     * Play ride request alert using settings from SettingsManager.
     * Falls back to enabled if SettingsManager is null.
     */
    fun playRideRequestAlert(context: Context, settingsManager: SettingsManager?) {
        val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
        val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
        playRideRequestAlert(context, soundEnabled, vibrationEnabled)
    }

    /**
     * Play confirmation alert using settings from SettingsManager.
     * Falls back to enabled if SettingsManager is null.
     */
    fun playConfirmationAlert(context: Context, settingsManager: SettingsManager?) {
        val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
        val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
        playConfirmationAlert(context, soundEnabled, vibrationEnabled)
    }

    /**
     * Play cancellation alert using settings from SettingsManager.
     * Falls back to enabled if SettingsManager is null.
     */
    fun playCancellationAlert(context: Context, settingsManager: SettingsManager?) {
        val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
        val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
        playCancellationAlert(context, soundEnabled, vibrationEnabled)
    }

    /**
     * Play chat message alert using settings from SettingsManager.
     * Falls back to enabled if SettingsManager is null.
     */
    fun playChatMessageAlert(context: Context, settingsManager: SettingsManager?) {
        val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
        val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
        playChatMessageAlert(context, soundEnabled, vibrationEnabled)
    }

    /**
     * Play driver arrived alert using settings from SettingsManager.
     * Falls back to enabled if SettingsManager is null.
     */
    fun playDriverArrivedAlert(context: Context, settingsManager: SettingsManager?) {
        val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
        val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
        playDriverArrivedAlert(context, soundEnabled, vibrationEnabled)
    }

    /**
     * Get the current ringer mode.
     */
    private fun getRingerMode(context: Context): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    }

    /**
     * Play notification sound only if ringer mode is normal (not vibrate or silent).
     */
    private fun playNotificationSoundIfAllowed(context: Context) {
        val ringerMode = getRingerMode(context)

        if (ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            Log.d(TAG, "Skipping sound - ringer mode: $ringerMode")
            return
        }

        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, notification)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing notification sound", e)
        }
    }

    /**
     * Vibrate only if ringer mode allows it (normal or vibrate, not silent).
     */
    private fun vibrateIfAllowed(context: Context, pattern: LongArray) {
        val ringerMode = getRingerMode(context)

        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            Log.d(TAG, "Skipping vibration - ringer mode is silent")
            return
        }

        vibrate(context, pattern)
    }

    /**
     * Trigger device vibration with the specified pattern.
     */
    private fun vibrate(context: Context, pattern: LongArray) {
        try {
            val vibrator = getVibrator(context) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+: Use VibrationEffect
                val effect = VibrationEffect.createWaveform(pattern, -1) // -1 = don't repeat
                vibrator.vibrate(effect)
            } else {
                // Legacy vibration
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering vibration", e)
        }
    }

    /**
     * Single vibration pulse.
     */
    fun vibrateOnce(context: Context, durationMs: Long = 200) {
        val ringerMode = getRingerMode(context)
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return
        }

        try {
            val vibrator = getVibrator(context) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(
                    durationMs,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering single vibration", e)
        }
    }

    /**
     * Get the Vibrator service (handles API level differences).
     */
    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Check if the device has vibration capability.
     */
    fun hasVibrator(context: Context): Boolean {
        return getVibrator(context)?.hasVibrator() == true
    }
}
