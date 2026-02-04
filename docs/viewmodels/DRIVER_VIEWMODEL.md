# DriverViewModel Reference

**File**: `drivestr/src/main/java/com/drivestr/app/viewmodels/DriverViewModel.kt`
**Last Updated**: 2026-02-02

This document provides a complete reference of all functions, state fields, and subscriptions in DriverViewModel.

---

## Overview

| Metric | Count |
|--------|-------|
| Lines of Code | ~3900 |
| Public Functions | ~35 |
| Private Functions | ~40 |
| State Fields | 35+ |
| Subscription Types | 8 |

---

## Public Functions

### Online/Offline Functions

| Function | Purpose | Triggers State Change |
|----------|---------|----------------------|
| `goOnline()` | Start accepting all ride requests | OFFLINE/ROADFLARE_ONLY -> AVAILABLE |
| `goRoadflareOnly()` | Start RoadFlare-only mode | OFFLINE -> ROADFLARE_ONLY |
| `goOffline()` | Stop accepting requests | AVAILABLE/ROADFLARE_ONLY -> OFFLINE |
| `toggleOnlineStatus()` | Toggle between online/offline | Depends on current state |

### Ride Flow Functions

| Function | Purpose | Triggers State Change |
|----------|---------|----------------------|
| `acceptBroadcastRequest(request)` | Accept a broadcast ride request | AVAILABLE -> RIDE_ACCEPTED |
| `acceptOffer(offer)` | Accept a direct ride offer | AVAILABLE -> RIDE_ACCEPTED |
| `arrivedAtPickup()` | Signal arrival at pickup location | EN_ROUTE_TO_PICKUP -> ARRIVED_AT_PICKUP |
| `submitPinForVerification(pin)` | Submit PIN for rider verification | Publishes PIN in state |
| `completeRide()` | Mark ride as complete | IN_RIDE -> RIDE_COMPLETED |
| `cancelRide(reason)` | Cancel current ride | Any -> AVAILABLE |
| `clearAcceptedOffer()` | Clear ride state after completion | RIDE_COMPLETED -> OFFLINE |

### Location Functions

| Function | Purpose |
|----------|---------|
| `updateLocation(location)` | Update driver's current location |
| `setCurrentLocationAsPickup()` | Use GPS location as pickup reference |

### Chat Functions

| Function | Purpose |
|----------|---------|
| `sendChatMessage(message)` | Send encrypted chat to rider |
| `getChatMessages()` | Get current chat history |

### Earnings Functions

| Function | Purpose |
|----------|---------|
| `getTodayEarnings()` | Get earnings for today |
| `getWeekEarnings()` | Get earnings for this week |
| `getMonthEarnings()` | Get earnings for this month |
| `setDisplayCurrency(currency)` | Switch between USD and SATS |

### Account Functions

| Function | Purpose |
|----------|---------|
| `logout()` | Clear keys and reset state |
| `isLoggedIn()` | Check if driver has valid keys |
| `getMyPubKey()` | Get driver's public key |

---

## Private Functions

### Subscription Management

| Function | Purpose | Creates Subscription |
|----------|---------|---------------------|
| `subscribeToRideOffers()` | Listen for direct ride offers | Kind 3173 (p-tag) |
| `subscribeToRoadflareOffers()` | Listen for RoadFlare-tagged offers only | Kind 3173 (roadflare tag) |
| `subscribeToBroadcastRequests()` | Listen for broadcast requests | Kind 3173 (g-tag) |
| `subscribeToRiderRideState(confId, riderPubKey)` | Listen for rider state updates | Kind 30181 |
| `subscribeToRideConfirmation(offerEventId)` | Listen for ride confirmation | Kind 3175 |
| `subscribeToCancellation(confId)` | Listen for rider cancellation | Kind 3179 |
| `subscribeToChat(confId, riderPubKey)` | Listen for chat messages | Kind 3178 |
| `closeAllRideSubscriptions()` | Close all ride-related subscriptions | N/A |

### State Publishing

| Function | Purpose | Publishes |
|----------|---------|-----------|
| `publishAvailability()` | Broadcast driver availability | Kind 30173 |
| `publishUnavailable()` | Broadcast unavailable status | Kind 30173 |
| `broadcastRoadflareOfflineStatus()` | Send final OFFLINE RoadFlare location | Kind 30014 |
| `publishDriverRideState()` | Publish consolidated ride state | Kind 30180 |
| `publishRideAcceptance(offer)` | Accept and publish acceptance | Kind 3174 |

### State Handlers

| Function | Purpose | Handles |
|----------|---------|---------|
| `handleRiderRideState(state, confId, riderPubKey)` | Process rider state updates | Kind 30181 |
| `handleLocationReveal(action, confId)` | Process location reveals | Location actions |
| `handlePinVerification(action, confId)` | Process PIN verification results | PIN actions |
| `handleRideCancellation(cancellation)` | Full cleanup on rider cancel | Kind 3179 |
| `handleConfirmation(confirmation)` | Process ride confirmation | Kind 3175 |

### Ride Flow Helpers

| Function | Purpose |
|----------|---------|
| `transitionToEnRoute()` | Auto-transition to EN_ROUTE after confirmation |
| `addStatusAction(status, location)` | Add status to history and publish |
| `addPinSubmitAction(pin)` | Add PIN submission to history |

### Cleanup Functions

| Function | Purpose |
|----------|---------|
| `clearDriverStateHistory()` | Clear ride state history |
| `cleanupRideEventsInBackground(reason)` | NIP-09 delete ride events |

### Persistence Functions

| Function | Purpose |
|----------|---------|
| `saveRideState()` | Persist ride state to SharedPreferences |
| `restoreRideState()` | Restore ride state on app restart |
| `clearSavedRideState()` | Clear persisted ride state |

### RoadFlare Functions

| Function | Purpose |
|----------|---------|
| `goRoadflareOnly()` | Enter RoadFlare-only mode (Kind 30014 location, no Kind 30173) |
| `subscribeToRoadflareOffers()` | Listen for RoadFlare-tagged Kind 3173 offers |
| `processIncomingOffer(offer)` | Filter and display incoming RoadFlare/direct offers |
| `broadcastRoadflareOfflineStatus()` | Send final OFFLINE RoadFlare location (Kind 30014) |

### Utility Functions

| Function | Purpose |
|----------|---------|
| `formatEarnings(sats)` | Format earnings for display |
| `startAvailabilityRefresh()` | Periodic availability broadcast |
| `startChatRefreshJob()` | Periodic chat subscription refresh |

---

## State Fields (DriverUiState)

### Driver Status

| Field | Type | Purpose |
|-------|------|---------|
| `stage` | DriverStage | Current stage in driver flow |
| `isOnline` | Boolean | Whether driver is accepting rides |
| `currentLocation` | Location? | Driver's GPS location |

### Ride Identification

| Field | Type | Purpose |
|-------|------|---------|
| `confirmationEventId` | String? | Canonical ride identifier |
| `acceptedOffer` | RideOfferData? | Current accepted offer |
| `pendingOffers` | List<RideOfferData> | Incoming ride offers |
| `broadcastRequests` | List<RideBroadcastData> | Incoming broadcast requests |

### Rider Information

| Field | Type | Purpose |
|-------|------|---------|
| `riderPubKey` | String? | Current rider's public key |
| `riderProfile` | UserProfile? | Current rider's profile |

### Locations (Progressive Reveal)

| Field | Type | Purpose |
|-------|------|---------|
| `pickupGeohash` | String? | Approximate pickup area |
| `pickupLocation` | Location? | Precise pickup (after <1 mile) |
| `destinationGeohash` | String? | Approximate destination area |
| `destinationLocation` | Location? | Precise destination (after PIN) |

### Fare & Earnings

| Field | Type | Purpose |
|-------|------|---------|
| `fareEstimate` | Double | Fare in satoshis |
| `todayEarnings` | Double | Today's earnings |
| `weekEarnings` | Double | This week's earnings |
| `monthEarnings` | Double | This month's earnings |
| `displayCurrency` | DisplayCurrency | USD or SATS |

### PIN Verification

| Field | Type | Purpose |
|-------|------|---------|
| `submittedPin` | String? | PIN driver submitted |
| `pinVerified` | Boolean | Whether PIN was verified |
| `pinAttempts` | Int | Number of PIN attempts |

### Chat

| Field | Type | Purpose |
|-------|------|---------|
| `chatMessages` | List<RideshareChatData> | Chat history |
| `hasUnreadMessages` | Boolean | Unread message indicator |

### UI State

| Field | Type | Purpose |
|-------|------|---------|
| `isLoading` | Boolean | Loading indicator |
| `statusMessage` | String? | Status message to display |
| `errorMessage` | String? | Error message to display |

### User Info

| Field | Type | Purpose |
|-------|------|---------|
| `myPubKey` | String? | Driver's public key |
| `myProfile` | UserProfile? | Driver's profile |

---

## Subscription Variables

| Variable | Type | Purpose | Lifetime |
|----------|------|---------|----------|
| `rideOfferSubscriptionId` | String? | Direct offers (3173) | While AVAILABLE |
| `roadflareOfferSubscriptionId` | String? | RoadFlare offers (3173) | While ROADFLARE_ONLY |
| `broadcastSubscriptionId` | String? | Broadcast requests (3173) | While AVAILABLE |
| `confirmationSubscriptionId` | String? | Confirmation (3175) | After acceptance |
| `riderRideStateSubscriptionId` | String? | Rider state (30181) | During active ride |
| `cancellationSubscriptionId` | String? | Cancellation (3179) | During active ride |
| `chatSubscriptionId` | String? | Chat messages (3178) | During active ride |

---

## History Tracking

| Variable | Type | Purpose |
|----------|------|---------|
| `driverStateHistory` | MutableList<DriverRideAction> | Actions for Kind 30180 |
| `lastProcessedRiderActionCount` | Int | Tracks processed rider actions |
| `currentDriverStatus` | DriverStatusType | Current status for 30180 |
| `myRideEventIds` | MutableList<String> | Events to delete on cleanup |

---

## Jobs

| Variable | Type | Purpose |
|----------|------|---------|
| `availabilityRefreshJob` | Job? | Periodic availability broadcast |
| `chatRefreshJob` | Job? | Periodic chat subscription refresh |
| `locationUpdateJob` | Job? | Periodic location updates |

---

## Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `AVAILABILITY_REFRESH_MS` | 5 min | How often to republish availability |
| `LOCATION_UPDATE_INTERVAL_MS` | 30 sec | How often to update location |
| `CHAT_REFRESH_INTERVAL_MS` | 15 sec | Chat refresh interval |
| `OFFER_EXPIRY_MS` | 30 sec | How long offers stay in list |

---

## Function Call Graph

### Going Online Flow
```
goOnline()
    -> publishAvailability()
    -> subscribeToRideOffers()
    -> subscribeToBroadcastRequests()
    -> startAvailabilityRefresh()
    -> _uiState.value = ... (AVAILABLE)
```

### Ride Acceptance Flow
```
acceptBroadcastRequest() OR acceptOffer()
    -> closeAllRideSubscriptions()
    -> publishRideAcceptance()
    -> subscribeToRideConfirmation()
    -> [on confirmation] handleConfirmation()
        -> subscribeToRiderRideState()
        -> subscribeToCancellation()
        -> subscribeToChat()
        -> transitionToEnRoute()
            -> addStatusAction(EN_ROUTE_PICKUP)
            -> publishDriverRideState()
```

### Arrival Flow
```
arrivedAtPickup()
    -> addStatusAction(ARRIVED)
    -> publishDriverRideState()
    -> _uiState.value = ... (ARRIVED_AT_PICKUP)
```

### PIN Submission Flow
```
submitPinForVerification(pin)
    -> addPinSubmitAction(pin)
    -> publishDriverRideState()
    -> [on rider response] handlePinVerification()
        -> if verified:
            -> addStatusAction(IN_PROGRESS)
            -> publishDriverRideState()
            -> _uiState.value = ... (IN_RIDE)
```

### Completion Flow
```
completeRide()
    -> addStatusAction(COMPLETED)
    -> publishDriverRideState()
    -> [correlation logging: RIDE xxxxxxxx] Claiming HTLC
    -> walletService.claimHtlcPayment()
    -> [correlation logging: RIDE xxxxxxxx] Claim SUCCESS/FAILED
    -> saveRideToHistory()
    -> _uiState.value = ... (RIDE_COMPLETED)
```

### Cancellation Flow
```
[Rider cancels OR cancelRide()]
    -> closeAllRideSubscriptions()
    -> clearDriverStateHistory()
    -> cleanupRideEventsInBackground()
    -> publishUnavailable()
    -> _uiState.value = ... (OFFLINE)
```

---

## Driver Status Types

| Status | Meaning | Published When |
|--------|---------|----------------|
| `EN_ROUTE_PICKUP` | Driving to pickup | After confirmation received |
| `ARRIVED` | At pickup location | Driver taps "Arrived" |
| `IN_PROGRESS` | Ride in progress | After PIN verified |
| `COMPLETED` | Ride finished | Driver taps "Complete" |
| `CANCELLED` | Ride cancelled | Driver or rider cancels |

---

## Location Reveal Timeline

| When | What Driver Sees |
|------|------------------|
| Offer/Broadcast | Pickup geohash (~150m area) |
| Acceptance | Pickup geohash (~150m area) |
| Within 1 mile of pickup | Precise pickup coordinates |
| After PIN verified | Precise destination coordinates |

This progressive reveal protects rider privacy while still allowing drivers to navigate.
