# Driver App Module (Drivestr)

## Overview

The driver Android app allows users to go online, receive ride offers, navigate to pickups/dropoffs, and receive payments via Cashu wallet. All state is centralized in `DriverViewModel`, which coordinates between NostrService for communication and WalletService for payments.

---

## Key Components

### ViewModels (`java/com/drivestr/app/viewmodels/`)

| File | Purpose | Key State Fields |
|------|---------|------------------|
| `DriverViewModel.kt` | Central state machine (~2800 lines) | `driverStage`, `currentRide`, `currentOffer`, `driverStateHistory` |
| `OnboardingViewModel.kt` | Key generation/import flow | `uiState`, `isLoggedIn` |
| `ProfileViewModel.kt` | Profile editing | `name`, `profilePicture` |

### Screens (`java/com/drivestr/app/ui/screens/`)

| File | Purpose |
|------|---------|
| `DriverModeScreen.kt` | Main driver UI - map, offer cards, navigation overlay |
| `WalletScreen.kt` | Earnings display card - tap opens `WalletDetailScreen` |
| `EarningsScreen.kt` | Earnings history, stats, manual backup |
| `VehiclesScreen.kt` | Vehicle list with add/edit/delete |
| `VehicleSetupScreen.kt` | New vehicle form |
| `VehiclePickerDialog.kt` | Vehicle selection for going online |
| `SettingsScreen.kt` | App settings, relay config, developer options, removed followers list |
| `RoadflareTab.kt` | RoadFlare tab - QR code, pending/approved followers, accepted payment methods |
| `OnboardingScreen.kt` | Login/signup flow |
| `ProfileSetupScreen.kt` | Profile name and picture setup |
| `KeyBackupScreen.kt` | nsec backup display |

### Services (`java/com/drivestr/app/service/`)

| File | Purpose |
|------|---------|
| `DriverOnlineService.kt` | Foreground service - keeps driver online, refreshes availability |
| `RoadflareListenerService.kt` | Foreground service - background RoadFlare ride request alerts (Kind 3173 with roadflare tag) |

### Entry Point

| File | Purpose |
|------|---------|
| `MainActivity.kt` | App entry, Compose navigation, dependency injection, sync adapter registration |

---

## State Machine (DriverViewModel)

```
OFFLINE
  │
  ├──▶ goRoadflareOnly()
  │    ROADFLARE_ONLY (RoadFlare location + RoadFlare offers only)
  │    ├──▶ goOnline() → AVAILABLE
  │    └──▶ goOffline() → OFFLINE
  │
  ▼ goOnline() / broadcastAvailability()
AVAILABLE
  │
  ▼ acceptOffer() / acceptBroadcastRequest()
RIDE_ACCEPTED
  │
  ▼ startRouteToPickup()
EN_ROUTE_TO_PICKUP
  │
  ▼ arrivedAtPickup()
ARRIVED_AT_PICKUP
  │
  ▼ PIN verified, startRide()
IN_RIDE
  │
  ▼ arrivedAtDropoff()
RIDE_COMPLETED → finishAndGoOnline() → AVAILABLE

Any state → cancelCurrentRide() → CANCELLED → goOnline() → AVAILABLE
```

### Key State Transitions

| Method | From State | To State | Nostr Event |
|--------|------------|----------|-------------|
| `goOnline()` | OFFLINE/ROADFLARE_ONLY | AVAILABLE | Kind 30173 |
| `goRoadflareOnly()` | OFFLINE | ROADFLARE_ONLY | Kind 30014 (location broadcast) |
| `goOffline()` | AVAILABLE/ROADFLARE_ONLY | OFFLINE | Kind 30014 (offline status) |
| `acceptOffer()` | AVAILABLE/ROADFLARE_ONLY | RIDE_ACCEPTED | Kind 3174 |
| `acceptBroadcastRequest()` | AVAILABLE | RIDE_ACCEPTED | Kind 3174 |
| `updateDriverStatus()` | Various | Various | Kind 30180 |
| `cancelCurrentRide()` | Any | CANCELLED | Kind 3179 |
| `clearDriverStateHistory()` | - | - | **CRITICAL**: Must call when starting new ride |

---

## Connections Table

| From (this module) | To (common module) | How/Why | Example Call |
|--------------------|-------------------|---------|--------------|
| `DriverViewModel` | `NostrService` | Broadcast availability | `nostrService.broadcastAvailability()` |
| `DriverViewModel` | `NostrService` | Accept ride offer | `nostrService.acceptRide()` |
| `DriverViewModel` | `NostrService` | Subscribe to offers | `nostrService.subscribeToOffers()` |
| `DriverViewModel` | `NostrService` | Publish driver state | `nostrService.publishDriverRideState()` |
| `DriverViewModel` | `WalletService` | Claim HTLC payment | `walletService.claimHtlcPayment()` |
| `DriverViewModel` | `WalletService` | Check wallet connected | `walletService.isConnected` |
| `DriverViewModel` | `RideHistoryRepository` | Save completed ride | `rideHistoryRepo.addRide()` |
| `DriverViewModel` | `VehicleRepository` | Get primary vehicle | `vehicleRepo.primaryVehicle` |
| `MainActivity` | `ProfileSyncManager` | Register sync adapters | `profileSyncManager.registerSyncable()` |
| `MainActivity` | `Nip60WalletSync` | Wire to WalletService | `walletService.setNip60Sync()` |
| `MainActivity` | `VehicleRepository` | Vehicle data for UI | `vehicleRepository.vehicles.value` |
| `WalletScreen` | `WalletService` | Display earnings | `walletService.balance.value` |
| `DriverViewModel` | `RoadflareLocationBroadcaster` | Location broadcast lifecycle | `broadcaster.startBroadcasting()`, `setOnRide()` |
| `DriverViewModel` | `DriverRoadflareRepository` | RoadFlare follower/key state | `driverRoadflareRepository.state` |
| `RoadflareListenerService` | `NostrService` | Subscribe to RoadFlare offers | `nostrService.subscribeToOffers()` |
| `RoadflareListenerService` | `DriverRoadflareRepository` | Filter muted riders | `repo.getMutedPubkeys()` |
| `EarningsScreen` | `RideHistoryRepository` | Display past rides | `rideHistoryRepo.rides.value` |
| `VehiclesScreen` | `VehicleRepository` | CRUD operations | `vehicleRepo.addVehicle()` |
| `MainActivity` | `RoadflareKeyManager` | Follower approval/removal | `roadflareKeyManager.handleMuteFollower()` |
| `MainActivity` | `DriverRoadflareRepository` | RoadFlare state + removed followers | `driverRoadflareRepository.state` |
| `MainActivity` | `RoadflareLocationBroadcaster` | Location broadcast lifecycle | `broadcaster.startBroadcasting()` |
| `SettingsScreen` | `DriverRoadflareRepository` | Removed followers list | `roadflareState?.muted` |

---

## MainActivity Initialization Flow

```kotlin
// Line 131: NostrService with custom relays
val nostrService = NostrService(context, settingsManager.getEffectiveRelays())

// Line 148-149: WalletService initialization
val walletService = WalletService(context, walletKeyManager)

// Line 152-157: Nip60WalletSync wiring
val nip60Sync = Nip60WalletSync(...)
walletService.setNip60Sync(nip60Sync)

// Line 171-173: ProfileSyncManager singleton
val profileSyncManager = ProfileSyncManager.getInstance(context, relays)

// Line 176-180: Sync adapter registration (driver-specific)
profileSyncManager.registerSyncable(Nip60WalletSyncAdapter(nip60Sync))
profileSyncManager.registerSyncable(ProfileSyncAdapter(vehicleRepository, null, settingsManager, nostrService))  // Unified profile (vehicles + settings)
profileSyncManager.registerSyncable(RideHistorySyncAdapter(rideHistoryRepository, nostrService))

// Line 255-256: On login, trigger sync
profileSyncManager.onKeyImported()
```

---

## Navigation Structure

```
Screen.ONBOARDING → (login/generate key) → Screen.MAIN_MAP
Screen.MAIN_MAP → (tap wallet card) → Screen.WALLET_DETAIL
Screen.MAIN_MAP → (tap earnings) → Screen.EARNINGS
Screen.MAIN_MAP → (tap vehicles) → Screen.VEHICLES
Screen.MAIN_MAP → (tap profile) → AccountBottomSheet
Screen.VEHICLES → (add vehicle) → Screen.VEHICLE_SETUP
Screen.MAIN_MAP → (go online) → VehiclePickerDialog (if multiple vehicles)
```

---

## Important Implementation Notes

### Driver State History
- `driverStateHistory` is a synchronized list that accumulates actions
- **CRITICAL**: Call `clearDriverStateHistory()` in both:
  - `acceptOffer()` (line ~1029)
  - `acceptBroadcastRequest()` (line ~2798)
- History actions: `status`, `location_update`, `pin_submit`, `settlement`

### Phantom Cancellation Bug Prevention
The phantom cancellation bug was caused by not clearing history between rides.
- Old cancellation actions from ride #1 would appear in ride #2's events
- Fix: Always call `clearDriverStateHistory()` at the START of a new ride

### Payment Claiming
- `claimHtlcPayment()` called at line 2220 after ride completion
- Driver receives preimage from rider's Kind 30181 event
- **Note**: P2PK witness signatures not implemented - empty array passed

### PaymentHash from Confirmation (January 2026)
paymentHash is now extracted from confirmation (Kind 3175), not offer (Kind 3173):
- `acceptOffer()` at line 1331 sets `activePaymentHash = null`
- `subscribeToConfirmation()` at line 2302 extracts `confirmation.paymentHash`
- Line 2315 stores it: `activePaymentHash = paymentHash`
- This ensures driver receives paymentHash AFTER rider has locked HTLC with correct wallet key
- All existing null checks (lines 1914, 2127, 2238) remain safe during transition window

### Wallet Refresh on HTLC Claim (January 2026)
`WalletService.claimHtlcPayment()` now performs automatic NIP-60 refresh after successful claim:
1. Publishes received proofs to NIP-60
2. Clears NIP-60 cache and fetches fresh proofs
3. Updates displayed balance from NIP-60 data
4. Calls `updateDiagnostics()` to ensure green status

This ensures driver's balance is accurate and diagnostics show synced after claiming payment.

### Vehicle Selection
- Driver must have at least one vehicle to go online
- `VehiclePickerDialog` shown if multiple vehicles exist
- Primary vehicle selected by default

### Broadcast vs Direct Offers
| Pattern | Tag | Subscription | Use Case |
|---------|-----|--------------|----------|
| Broadcast | `g` (geohash) | `subscribeToBroadcastRideRequests()` | Open auction |
| Direct | `p` (driver pubkey) | `subscribeToOffers()` | Retry specific driver |

### Confirmation Timeout
- Driver waits 30 seconds for rider confirmation after accepting (constant `CONFIRMATION_TIMEOUT_MS` at line 73)
- UI countdown circle uses `confirmationWaitDurationMs` from UI state (line 3145)
- If no confirmation received, ride is auto-cancelled
- Both timeout values must match (30 seconds)

### Claim Payment After Rider Cancellation (January 2026)
If rider cancels after PIN verification (driver has preimage):
- `handleRideCancellation()` checks `canSettleEscrow` (line 2063)
- If driver can claim, shows dialog with fare amount
- `claimPaymentAfterCancellation()` calls `walletService.claimHtlcPayment()` (line 2089)
- Ride saved to history with status `cancelled_claimed` or `cancelled`
- Escrow state preserved until driver decides (cleanup deferred to `performCancellationCleanup()`)

### Availability Broadcast Loop (January 2026)
Driver broadcasts availability every 5 minutes (`AVAILABILITY_BROADCAST_INTERVAL_MS` at line 66):
- `startBroadcasting()` at line 2389 launches coroutine loop
- Each broadcast updates `lastBroadcastTime` in UI state (line 2429)
- Timer display in `AvailableContent` uses ticker for live updates (lines 638-645)
- **CRITICAL**: When going back online after ride, must reset broadcast state:
  - `publishedAvailabilityEventIds.clear()` - prevents deleting already-deleted events
  - `lastBroadcastLocation = null` - ensures fresh throttle tracking
  - Done in both `finishAndGoOnline()` (line 970) and `performCancellationCleanup()` (line 2204)

### Deposit/Withdraw
- Tap wallet card in `WalletScreen.kt` → navigates to `WalletDetailScreen` (common)
- Deposit and withdraw are **fully functional**
- Only LN address resolution is broken (must paste BOLT11 directly)

### RoadFlare Accepted Payment Methods (January 2026)
Drivers can configure which alternate payment methods they accept from RoadFlare riders:
- `AcceptedPaymentMethodsCard` in `RoadflareTab.kt` with checkboxes (Zelle, PayPal, Cash App, Venmo, Cash, Strike)
- Stored in `SettingsManager.roadflarePaymentMethods` and backed up to Kind 30177
- RoadFlare offer cards in `DriverModeScreen.kt` display the rider's chosen payment method (e.g., "Payment: Zelle") for non-cashu methods
