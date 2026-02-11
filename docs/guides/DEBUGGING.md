# Ridestr Debugging Guide

**Last Updated**: 2026-01-17

This guide captures critical debugging principles and common patterns discovered during Ridestr development.

---

## The Dual-Origin Principle

**When debugging data issues, ALWAYS trace data at BOTH:**

1. **ORIGINATION** - Where data is created/sent
2. **RECEIVER** - Where data is processed

### Why This Matters

Many bugs appear to be in one location but are actually in another. The symptom manifests at the receiver, but the root cause is at the origin.

### Real Example: Phantom Cancellation Bug

**Symptom**: Rider saw "Driver cancelled" after starting a new ride, even though no cancellation occurred.

**Initial Investigation**: Focused on rider app's cancellation handling code.

**Actual Cause**: Driver app was publishing old cancellation events from a previous ride session because `driverStateHistory` wasn't being cleared when accepting a new ride.

**Fix Location**: `DriverViewModel.acceptOffer()` and `DriverViewModel.acceptBroadcastRequest()` - added `clearDriverStateHistory()` call.

### Application

When debugging:
1. First, verify the data looks correct AT THE SENDER
2. Then, verify the data looks correct AT THE RECEIVER
3. Check if stale data from previous sessions could be involved

---

## State History Accumulation Rule

**CRITICAL**: Kinds 30180 and 30181 use `history` arrays that ACCUMULATE actions.

### The Problem

If you don't clear the history when starting a new ride, old actions will be included in the new ride's state events. This causes:
- Phantom cancellations
- Duplicate PIN verifications
- Wrong location reveals
- State confusion

### The Solution

Always call these functions when starting a NEW ride:
- `DriverViewModel.clearDriverStateHistory()` (line ~243)
- `RiderViewModel.clearRiderStateHistory()` (line ~340)

### Verification Points

| Function | Must Clear History | Location |
|----------|-------------------|----------|
| `DriverViewModel.acceptOffer()` | ✅ YES | line 1025 |
| `DriverViewModel.acceptBroadcastRequest()` | ✅ YES | line 2798 |
| `RiderViewModel.autoConfirmRide()` | ✅ YES | Check implementation |

---

## Event Deduplication

### Why Deduplication Exists

Nostr relays may deliver the same event multiple times. Without deduplication, the app would process the same action repeatedly.

### Deduplication Sets

```kotlin
// In RiderViewModel
private val processedDriverStateEventIds = mutableSetOf<String>()
private val processedCancellationEventIds = mutableSetOf<String>()

// In DriverViewModel
private val processedRiderStateEventIds = mutableSetOf<String>()
private val processedCancellationEventIds = mutableSetOf<String>()
```

### CRITICAL: Clear Sets on Ride End

These sets must be cleared when a ride ends:
- In `clearRiderStateHistory()` / `clearDriverStateHistory()`
- Otherwise, events from a new ride with the same ID could be ignored

---

## Confirmation Event ID Validation

### The Pattern

Every ride-related event (30180, 30181, 3179) includes a `confirmationEventId`. This is the canonical ride identifier from the Kind 3175 confirmation event.

### Validation Rule

Before processing any ride event, verify:
```kotlin
if (event.confirmationEventId != activeConfirmationEventId) {
    // This event is for a different ride - IGNORE IT
    return
}
```

### Common Bug: Processing Old Events

If an event from a previous ride is received (e.g., from slow relay), the confirmation ID check prevents it from affecting the current ride.

---

## Subscription Lifecycle

### Subscription States by Ride Stage

**Rider App**:
| Stage | Subscriptions Active |
|-------|---------------------|
| IDLE | Driver availability (30173) |
| WAITING_FOR_ACCEPTANCE | + Acceptance (3174) |
| RIDE_CONFIRMED | + Driver state (30180), Chat (3178), Cancellation (3179) |
| COMPLETED | All closed except availability |

**Driver App**:
| Stage | Subscriptions Active |
|-------|---------------------|
| OFFLINE | None |
| AVAILABLE | Offers (3173), Broadcasts (3173 with g-tag) |
| EN_ROUTE | + Rider state (30181), Chat (3178), Cancellation (3179) |
| COMPLETED | All closed |

### Subscription Cleanup

Always close ride subscriptions when:
- Ride completes
- Ride is cancelled
- User logs out

Use `closeAllRideSubscriptionsAndJobs()` for ride-ending paths (completion, cancellation).
Use `closeAllRideSubscriptions()` only for mid-ride transitions (e.g., `confirmRide()`).

---

## Common Bug Patterns

### 1. Stale Events from Previous Ride

**Symptom**: Actions happening that user didn't trigger

**Debug**: Check if `confirmationEventId` matches, check deduplication sets

**Fix**: Clear history and deduplication sets at ride start

### 2. Missing Location Reveal

**Symptom**: Driver doesn't see precise location

**Debug**: Check rider's `riderStateHistory` for location_reveal action

**Fix**: Verify `revealPrecisePickup()` is called when driver is <1 mile

### 3. PIN Verification Loop

**Symptom**: PIN keeps being requested even after verification

**Debug**: Check both apps' state history for pin_verify action

**Fix**: Ensure rider publishes verification result, driver processes it

### 4. Subscription Not Receiving Events

**Symptom**: State updates not appearing

**Debug**: Check filter in subscription, verify relay connection

**Fix**: Ensure subscription filter matches event kind, d-tag, p-tag

---

## Key File Locations

### State Management

| Purpose | File | Key Functions |
|---------|------|---------------|
| Rider state | `RiderViewModel.kt` | `clearRiderStateHistory()`:340, `handleDriverRideState()` |
| Driver state | `DriverViewModel.kt` | `clearDriverStateHistory()`:243, `handleRiderRideState()` |
| State publishing | `RideshareDomainService.kt` | `publishDriverRideState()`, `publishRiderRideState()` |

### Event Handling

| Purpose | File | Location |
|---------|------|----------|
| Driver state event | `DriverRideStateEvent.kt` | Parse/create functions |
| Rider state event | `RiderRideStateEvent.kt` | Parse/create functions |
| Cancellation event | `RideCancellationEvent.kt` | Parse/create functions |

### Subscription Management

| Purpose | File | Functions |
|---------|------|-----------|
| Create subscriptions | `RideshareDomainService.kt` | `subscribeToDriverRideState()`, `subscribeToRiderRideState()` |
| Close subscriptions | ViewModels | `closeAllRideSubscriptionsAndJobs()` (ride-ending), `closeAllRideSubscriptions()` (mid-ride) |

---

## Debugging Checklist

When debugging ride flow issues:

- [ ] Is the correct `confirmationEventId` being used?
- [ ] Was history cleared at the start of this ride?
- [ ] Are deduplication sets being checked and cleared properly?
- [ ] Is the event being published to relays? (Check NostrService logs)
- [ ] Is the subscription receiving events? (Check subscription logs)
- [ ] Does the filter match the event's tags?
- [ ] Are we looking at origination OR receiver? (Check both!)

---

## Logging Tips

### Enable Verbose Logging

Key tags to watch:
- `NostrService` / `RideshareDomainSvc` - Event publishing and subscription
- `RiderViewModel` - Rider state changes
- `DriverViewModel` - Driver state changes
- `RelayManager` - Connection status

### Log Patterns to Search

```
// Event published
"Publishing Kind 30180"
"Publishing Kind 30181"

// Event received
"Received driver ride state"
"Received rider ride state"

// State changes
"Stage changed to"
"History cleared"
```

---

## Broadcast vs Direct Offers

The codebase supports two offer patterns using Kind 3173:

| Pattern | Tag | Filter | Use Case |
|---------|-----|--------|----------|
| **Broadcast** | `g` (geohash) | `subscribeToBroadcastRideRequests()` | Open auction - multiple drivers see |
| **Direct** | `p` (driver pubkey) | `subscribeToOffers()` | Retry specific driver |

### Broadcast Flow (Primary)
1. Rider publishes Kind 3173 with g-tag (geohash)
2. All online drivers in area receive via geohash subscription
3. Drivers compete to accept first
4. First acceptance wins (`hasAcceptedDriver` flag prevents duplicates)

### Direct Flow (Legacy/Retry)
1. Rider publishes Kind 3173 with p-tag (specific driver pubkey)
2. Only targeted driver receives
3. Used for: retry after cancellation, request specific driver

### Debugging Offer Issues

If driver doesn't receive offers:
1. Check g-tag geohash precision matches subscription filter
2. Verify driver is subscribed to correct geohash area
3. Check NIP-40 expiration hasn't passed

---

## Related Documentation

- [STATE_MACHINES.md](../architecture/STATE_MACHINES.md) - Valid state transitions
- [NOSTR_EVENTS.md](../protocol/NOSTR_EVENTS.md) - Event structures
- [RIDER_VIEWMODEL.md](../viewmodels/RIDER_VIEWMODEL.md) - Rider function reference
- [DRIVER_VIEWMODEL.md](../viewmodels/DRIVER_VIEWMODEL.md) - Driver function reference
