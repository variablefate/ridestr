# Deprecated Event Kinds - NIP-014173

**Last Updated**: 2026-01-22
**Original Location**: `common/src/main/java/com/ridestr/common/nostr/events/deprecation.md`

This document describes event kinds that have been deprecated in the rideshare protocol.

---

## Deprecation Date: 2026-01-14

## Summary

The following event kinds were deprecated and consolidated into two new parameterized replaceable events:
- **Kind 30180: Driver Ride State** - consolidates driver status and PIN submission
- **Kind 30181: Rider Ride State** - consolidates rider verification and location reveals

---

## Deprecated Kinds

### Kind 3176: PIN Submission (DEPRECATED)
**Reason**: Merged into Kind 30180 (Driver Ride State)

The PIN submission was previously a separate regular event. Now it's embedded as an action in the driver's ride state history. This reduces the number of events per ride and improves relay efficiency through parameterized replaceable semantics.

**Previous file**: `PinSubmissionEvent.kt`

### Kind 3177: Pickup Verification (DEPRECATED)
**Reason**: Merged into Kind 30181 (Rider Ride State)

PIN verification results are now embedded as actions in the rider's ride state history. The verification state machine (attempt counts, verification status) is preserved in the history array.

**Previous file**: `PickupVerificationEvent.kt`

### Kind 3180: Driver Status (DEPRECATED)
**Reason**: Merged into Kind 30180 (Driver Ride State)

Driver status updates (en_route_pickup, arrived, in_progress, completed, cancelled) are now tracked as actions in the driver's ride state. The parameterized replaceable semantics ensure relays only keep the latest state.

**Previous file**: `DriverStatusEvent.kt`

### Kind 3181: Precise Location Reveal (DEPRECATED)
**Reason**: Merged into Kind 30181 (Rider Ride State)

Location reveals (pickup and destination) are now embedded as actions in the rider's ride state history. The NIP-44 encryption of location data is preserved.

**Previous file**: `PreciseLocationRevealEvent.kt`

### Kind 20173: Ephemeral Availability (DEPRECATED)
**Reason**: Never implemented; replaced by Kind 30173

Ephemeral driver availability was considered but never implemented. Kind 30173 (parameterized replaceable) was chosen instead for better reliability.

---

## Design Rationale

### Why Consolidate?

1. **Relay Efficiency**: Parameterized replaceable events (Kind 30xxx) with d-tags allow relays to keep only the latest state per ride, reducing storage and bandwidth.

2. **Atomic State**: Instead of multiple separate events that could become inconsistent, all ride actions are embedded in a single event with a complete history.

3. **Notification Simplicity**: Applications can detect new actions by comparing history array lengths, triggering appropriate notifications for each new action.

4. **Backward Compatibility**: Not required as the app was never publicly released.

### Why Keep Handshake Events Separate?

The handshake events (Kinds 3173, 3174, 3175) remain separate regular events because:

1. **Notification Reliability**: Each event triggers immediate notification delivery
2. **Different Lifecycle**: Handshake events are one-time per ride, while ride state events update continuously
3. **Event References**: The confirmation event ID becomes the d-tag for state events

---

## Current Event Kind Summary

| Kind | Name | Type | Phase |
|------|------|------|-------|
| 30173 | Driver Availability | Param Replaceable | Discovery |
| 3173 | Ride Offer | Regular | Handshake |
| 3174 | Ride Acceptance | Regular | Handshake |
| 3175 | Ride Confirmation | Regular | Handshake |
| **30180** | **Driver Ride State** | **Param Replaceable** | **Ride** |
| **30181** | **Rider Ride State** | **Param Replaceable** | **Ride** |
| 3178 | Rideshare Chat | Regular | Ride |
| 3179 | Ride Cancellation | Regular | Any |
| 30174 | Ride History Backup | Param Replaceable | Post |
| **30177** | **Unified Profile** | **Param Replaceable** | **Profile** |

**Total: 10 active event kinds** (8 ride + 2 profile sync)

**Note**: Kind 30175 (vehicles) and Kind 30176 (saved locations) were consolidated into Kind 30177 (unified profile) in January 2026.

---

## Migration Notes

If old events from deprecated kinds exist on relays, they will be ignored by the current implementation. Use the Account Safety screen to clean up old events.

---

## Files Deleted (Historical)

- `DriverStatusEvent.kt`
- `PinSubmissionEvent.kt`
- `PickupVerificationEvent.kt`
- `PreciseLocationRevealEvent.kt`

## Files Added

- `DriverRideStateEvent.kt` (Kind 30180)
- `RiderRideStateEvent.kt` (Kind 30181)

---

## Related Documentation

- [NOSTR_EVENTS.md](NOSTR_EVENTS.md) - Current event definitions
- [STATE_MACHINES.md](../architecture/STATE_MACHINES.md) - State transitions using new events
