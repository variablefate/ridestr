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
     */
    fun playRideRequestAlert(context: Context) {
        Log.d(TAG, "Playing ride request alert")
        playNotificationSoundIfAllowed(context)
        vibrateIfAllowed(context, VIBRATION_RIDE_REQUEST)
    }

    /**
     * Play notification sound and vibrate for ride confirmation.
     */
    fun playConfirmationAlert(context: Context) {
        Log.d(TAG, "Playing confirmation alert")
        playNotificationSoundIfAllowed(context)
        vibrateIfAllowed(context, VIBRATION_CONFIRMATION)
    }

    /**
     * Play notification sound and vibrate for ride cancellation.
     * Uses normal notification sound (not alarm).
     */
    fun playCancellationAlert(context: Context) {
        Log.d(TAG, "Playing cancellation alert")
        playNotificationSoundIfAllowed(context)
        vibrateIfAllowed(context, VIBRATION_ALERT)
    }

    /**
     * Play notification sound for a chat message.
     */
    fun playChatMessageAlert(context: Context) {
        Log.d(TAG, "Playing chat message alert")
        playNotificationSoundIfAllowed(context)
        vibrateIfAllowed(context, VIBRATION_MESSAGE)
    }

    /**
     * Play notification sound when driver arrives.
     */
    fun playDriverArrivedAlert(context: Context) {
        Log.d(TAG, "Playing driver arrived alert")
        playNotificationSoundIfAllowed(context)
        vibrateIfAllowed(context, VIBRATION_CONFIRMATION)
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
