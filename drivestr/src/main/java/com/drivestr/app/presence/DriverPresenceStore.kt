package com.drivestr.app.presence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime-only store for the driver presence gate.
 * Not persisted — resets to null on process death.
 *
 * Writer: DriverOnlineService (sets gate on start/update/stop)
 * Reader: RoadflareListenerService (checks gate to suppress duplicate notifications)
 */
@Singleton
class DriverPresenceStore @Inject constructor() {
    private val _gate = MutableStateFlow<DriverPresenceGate?>(null)
    val gate: StateFlow<DriverPresenceGate?> = _gate.asStateFlow()

    fun setGate(gate: DriverPresenceGate?) {
        _gate.value = gate
    }
}
