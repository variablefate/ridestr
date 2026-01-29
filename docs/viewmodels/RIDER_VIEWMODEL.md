# RiderViewModel Reference

**File**: `rider-app/src/main/java/com/ridestr/rider/viewmodels/RiderViewModel.kt`
**Last Updated**: 2026-01-15

This document provides a complete reference of all functions, state fields, and subscriptions in RiderViewModel.

---

## Overview

| Metric | Count |
|--------|-------|
| Lines of Code | ~2800 |
| Public Functions | ~40 |
| Private Functions | ~45 |
| State Fields | 30+ |
| Subscription Types | 6 |

---

## Public Functions

### Ride Flow Functions

| Function | Purpose | Triggers State Change |
|----------|---------|----------------------|
| `broadcastRideRequest()` | Send ride request to all nearby drivers | IDLE -> BROADCASTING_REQUEST |
| `sendRideOffer(driver)` | Send direct ride offer to specific driver | IDLE -> WAITING_FOR_ACCEPTANCE |
| `clearRide()` | Cancel current ride and reset state | Any -> IDLE |
| `boostFare()` | Increase fare offer by $1 or 1000 sats | Updates `fareEstimate` |
| `retryRideRequest()` | Retry after timeout with same parameters | IDLE -> WAITING/BROADCASTING |
| `acceptCompletedRide()` | Acknowledge completed ride | COMPLETED -> IDLE |

### Location Functions

| Function | Purpose |
|----------|---------|
| `setPickupLocation(location)` | Set pickup location for ride |
| `setDestinationLocation(location)` | Set destination for ride |
| `clearLocations()` | Clear both pickup and destination |
| `searchPickupLocation(query)` | Geocode search for pickup address |
| `searchDestinationLocation(query)` | Geocode search for destination address |
| `selectPickupFromSearch(result)` | Select pickup from search results |
| `selectDestinationFromSearch(result)` | Select destination from search results |
| `setDemoLocations()` | Set demo locations for testing |

### Driver Selection Functions

| Function | Purpose |
|----------|---------|
| `selectDriver(driver)` | Select a driver for direct offer |
| `clearSelectedDriver()` | Deselect current driver |
| `loadDriverProfile(pubKey)` | Fetch driver's Nostr profile |

### Chat Functions

| Function | Purpose |
|----------|---------|
| `sendChatMessage(message)` | Send encrypted chat to driver |
| `getChatMessages()` | Get current chat history |

### Settings Functions

| Function | Purpose |
|----------|---------|
| `setDisplayCurrency(currency)` | Switch between USD and SATS display |
| `setDebugDropoffEnabled(enabled)` | Enable/disable debug dropoff button |

### Saved Locations Functions

| Function | Purpose |
|----------|---------|
| `getSavedLocations()` | Get user's saved locations |
| `saveCurrentPickup(name)` | Save current pickup as favorite |
| `saveCurrentDestination(name)` | Save current destination as favorite |
| `deleteSavedLocation(id)` | Delete a saved location |

### Account Functions

| Function | Purpose |
|----------|---------|
| `logout()` | Clear keys and reset state |
| `isLoggedIn()` | Check if user has valid keys |
| `getMyPubKey()` | Get user's public key |

---

## Private Functions

### Subscription Management

| Function | Purpose | Creates Subscription |
|----------|---------|---------------------|
| `subscribeToDrivers()` | Subscribe to driver availability in area | Kind 30173 |
| `resubscribeToDrivers()` | Refresh driver subscription with new geohash | Kind 30173 |
| `subscribeToAcceptance(offerEventId, expectedDriverPubKey)` | Listen for ride acceptance (direct offers only) | Kind 3174 |
| `subscribeToDriverRideState(confirmationId, driverPubKey)` | Listen for driver status updates | Kind 30180 |
| `subscribeToCancellation(confirmationId)` | Listen for driver cancellation | Kind 3179 |
| `subscribeToChat(confirmationId, driverPubKey)` | Listen for chat messages | Kind 3178 |
| `closeAllRideSubscriptions()` | Close all ride-related subscriptions | N/A |

### State Handlers

| Function | Purpose | Handles |
|----------|---------|---------|
| `handleDriverRideState(state, confId, driverPubKey)` | Process driver state updates | Kind 30180 |
| `handleDriverStatusAction(action, state, confId)` | Handle driver status changes | Status actions |
| `handlePinSubmission(action, confId, driverPubKey)` | Validate driver's PIN entry | PIN actions |
| `handleDriverCancellation(reason)` | Full cleanup on driver cancel | Kind 3179 / status |
| `handleRideCompletion(driverState)` | Process ride completion | COMPLETED status |

### Ride Flow Helpers

| Function | Purpose |
|----------|---------|
| `autoConfirmRide()` | Automatically confirm after acceptance |
| `publishRideConfirmation(acceptance)` | Publish Kind 3175 confirmation |
| `generatePickupPin()` | Create random 4-digit PIN |
| `calculateFare(routeResult)` | Calculate fare from route distance |

### Location Reveal (Progressive)

| Function | Purpose |
|----------|---------|
| `revealLocation(confId, driverPubKey, type, location)` | Encrypt and publish location reveal |
| `revealPrecisePickup(confId)` | Share precise pickup when driver <1 mile |
| `revealDestination(confId, driverPubKey)` | Share destination after PIN verified |
| `checkAndRevealPrecisePickup(confId, driverLocation)` | Check distance and reveal if close |

### Cleanup Functions

| Function | Purpose |
|----------|---------|
| `clearRiderStateHistory()` | Clear ride state and deduplication sets |
| `cleanupRideEventsInBackground(reason)` | NIP-09 delete ride events |
| `cancelAcceptanceTimeout()` | Cancel direct offer timeout |
| `cancelBroadcastTimeout()` | Cancel broadcast timeout |

### Persistence Functions

| Function | Purpose |
|----------|---------|
| `saveRideState()` | Persist ride state to SharedPreferences |
| `restoreRideState()` | Restore ride state on app restart |
| `clearSavedRideState()` | Clear persisted ride state |

### Utility Functions

| Function | Purpose |
|----------|---------|
| `formatFare(sats)` | Format fare for display (USD or SATS) |
| `startChatRefreshJob()` | Periodic chat subscription refresh |
| `startStaleDriverCleanup()` | Periodic cleanup of stale drivers |

---

## State Fields (RiderUiState)

### Ride Identification

| Field | Type | Purpose |
|-------|------|---------|
| `rideStage` | RideStage | Current stage in ride flow |
| `confirmationEventId` | String? | Canonical ride identifier |
| `pendingOfferEventId` | String? | Event ID of pending offer |
| `acceptance` | RideAcceptanceData? | Driver's acceptance data |

### Driver Information

| Field | Type | Purpose |
|-------|------|---------|
| `availableDrivers` | List<DriverAvailabilityData> | Nearby available drivers |
| `selectedDriver` | DriverAvailabilityData? | Currently selected driver |
| `driverProfiles` | Map<String, UserProfile> | Cached driver profiles |

### Locations

| Field | Type | Purpose |
|-------|------|---------|
| `pickupLocation` | Location? | Pickup coordinates |
| `destinationLocation` | Location? | Destination coordinates |
| `pickupAddress` | String? | Geocoded pickup address |
| `destinationAddress` | String? | Geocoded destination address |
| `precisePickupShared` | Boolean | Whether precise pickup sent |
| `destinationShared` | Boolean | Whether destination sent |

### Route & Fare

| Field | Type | Purpose |
|-------|------|---------|
| `routeResult` | RouteResult? | Calculated route |
| `fareEstimate` | Double | Fare in satoshis |
| `displayCurrency` | DisplayCurrency | USD or SATS |

### PIN Verification

| Field | Type | Purpose |
|-------|------|---------|
| `pickupPin` | String? | 4-digit verification PIN |
| `pinFailedAttempts` | Int | Failed verification count |
| `pinVerified` | Boolean | Whether PIN verified |

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
| `directOfferTimedOut` | Boolean | Show retry options |
| `isRoutingReady` | Boolean | Routing engine ready |

### User Info

| Field | Type | Purpose |
|-------|------|---------|
| `myPubKey` | String? | User's public key |
| `myProfile` | UserProfile? | User's profile |

---

## Subscription Variables

| Variable | Type | Purpose | Lifetime |
|----------|------|---------|----------|
| `driverSubscriptionId` | String? | Driver availability (30173) | While in IDLE |
| `acceptanceSubscriptionId` | String? | Ride acceptance (3174) | During offer wait |
| `driverRideStateSubscriptionId` | String? | Driver state (30180) | During active ride |
| `cancellationSubscriptionId` | String? | Cancellation (3179) | During active ride |
| `chatSubscriptionId` | String? | Chat messages (3178) | During active ride |
| `profileSubscriptionIds` | Map<String, String> | Profile lookups | Per driver |

---

## Event Deduplication

Added to fix phantom cancellation bug:

| Variable | Type | Purpose |
|----------|------|---------|
| `processedDriverStateEventIds` | MutableSet<String> | Tracks processed Kind 30180 events |
| `processedCancellationEventIds` | MutableSet<String> | Tracks processed Kind 3179 events |

Cleared in `clearRiderStateHistory()` when ride ends.

---

## History Tracking

| Variable | Type | Purpose |
|----------|------|---------|
| `riderStateHistory` | MutableList<RiderRideAction> | Actions for Kind 30181 |
| `lastProcessedDriverActionCount` | Int | Tracks processed driver actions |
| `currentRiderPhase` | Phase | Current phase for 30181 |
| `myRideEventIds` | MutableList<String> | Events to delete on cleanup |

---

## Jobs

| Variable | Type | Purpose |
|----------|------|---------|
| `staleDriverCleanupJob` | Job? | Periodic stale driver removal |
| `chatRefreshJob` | Job? | Periodic chat subscription refresh |
| `acceptanceTimeoutJob` | Job? | Direct offer timeout |
| `broadcastTimeoutJob` | Job? | Broadcast request timeout |

---

## Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `FARE_USD_PER_MILE` | 1.85 | Base fare calculation |
| `MINIMUM_FARE_USD` | 5.0 | Minimum fare |
| `FARE_BOOST_USD` | 1.0 | Fare boost amount (USD) |
| `FARE_BOOST_SATS` | 1000 | Fare boost amount (sats) |
| `STALE_DRIVER_TIMEOUT_MS` | 10 min | Driver expiry time |
| `CHAT_REFRESH_INTERVAL_MS` | 15 sec | Chat refresh interval |
| `MAX_PIN_ATTEMPTS` | 3 | Max PIN failures |
| `ACCEPTANCE_TIMEOUT_MS` | 15 sec | Direct offer timeout |
| `BROADCAST_TIMEOUT_MS` | 2 min | Broadcast timeout |

---

## Function Call Graph

### Ride Request Flow
```
broadcastRideRequest() OR sendRideOffer()
    -> closeAllRideSubscriptions()
    -> nostrService.publishRideOffer()
    -> subscribeToAcceptance()
    -> [on acceptance] handleAcceptance()
        -> autoConfirmRide()
            -> publishRideConfirmation()
            -> generatePickupPin()
            -> subscribeToDriverRideState()
            -> subscribeToCancellation()
            -> subscribeToChat()
```

### Driver State Processing Flow
```
[Kind 30180 received]
    -> handleDriverRideState()
        -> [deduplication check]
        -> [confirmation ID validation]
        -> handleDriverStatusAction() OR handlePinSubmission()
```

### Cancellation Flow
```
[Driver cancels OR handleDriverCancellation()]
    -> closeAllRideSubscriptions()
    -> clearRiderStateHistory()
    -> cleanupRideEventsInBackground()
    -> _uiState.value = ... (IDLE)
    -> resubscribeToDrivers()
```
