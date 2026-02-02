package com.ridestr.common.util

import kotlinx.coroutines.*

/**
 * Utility class for managing periodic refresh jobs in ViewModels.
 *
 * This class encapsulates the common pattern of periodically executing a refresh callback
 * with proper coroutine lifecycle management. The delay happens BEFORE the first tick,
 * so callers should perform their initial subscription BEFORE starting this job.
 *
 * Usage:
 * ```
 * // Initial subscription (before job starts)
 * subscribeToChatMessages(confirmationEventId)
 *
 * // Start periodic refresh job
 * chatRefreshJob = PeriodicRefreshJob(
 *     scope = viewModelScope,
 *     intervalMs = 15_000L,
 *     onTick = {
 *         subscribeToChatMessages(confirmationEventId)
 *     }
 * ).also { it.start() }
 * ```
 *
 * @param scope The CoroutineScope for the job (typically viewModelScope)
 * @param intervalMs Interval between refresh ticks in milliseconds
 * @param onTick Suspend function called on each refresh tick
 */
class PeriodicRefreshJob(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val onTick: suspend () -> Unit
) {
    private var job: Job? = null

    /**
     * Start the periodic refresh job.
     * If already running, stops the previous job first.
     *
     * Note: The delay happens BEFORE the first tick. Callers should
     * perform their initial subscription BEFORE calling start().
     */
    fun start() {
        stop()
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                // CRITICAL: Check cancellation IMMEDIATELY after delay
                // Kotlin coroutine cancellation is cooperative - without this check,
                // a cancelled job could execute one more iteration after waking up.
                ensureActive()
                onTick()
            }
        }
    }

    /**
     * Stop the periodic refresh job.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Check if the job is currently running.
     */
    fun isRunning(): Boolean = job?.isActive == true
}
