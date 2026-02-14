# Rider App Module

## Overview

The rider Android app allows users to request rides, track driver location in real-time, and manage payments via Cashu wallet. All state is centralized in `RiderViewModel`, which coordinates between NostrService for communication and WalletService for payments.

---

## Key Components

### ViewModels (`java/com/ridestr/rider/viewmodels/`)

| File | Purpose | Key State Fields |
|------|---------|------------------|
| `RiderViewModel.kt` | Central state machine (~2800 lines) | `rideStage`, `currentOffer`, `currentDriver`, `riderStateHistory` |
| `OnboardingViewModel.kt` | Key generation/import flow | `uiState`, `isLoggedIn` |
| `ProfileViewModel.kt` | Profile editing | `name`, `profilePicture` |

### Screens (`java/com/ridestr/rider/ui/screens/`)

| File | Purpose |
|------|---------|
| `RiderModeScreen.kt` | Main ride UI - map, pickup/dropoff selection, driver tracking |
| `WalletScreen.kt` | Balance display card - tap opens `WalletDetailScreen` |
| `HistoryScreen.kt` | Past rides list with manual backup button |
| `SettingsScreen.kt` | App settings, relay config, developer options |
| `OnboardingScreen.kt` | Login/signup flow |
| `ProfileSetupScreen.kt` | Profile name and picture setup |
| `KeyBackupScreen.kt` | nsec backup display |
| `TipScreen.kt` | Post-ride tipping UI |
| `RoadflareTab.kt` | RoadFlare tab - favorite drivers list, driver status badges, fare estimates, payment methods dialog |
| `AddDriverScreen.kt` | Add driver via QR scan (Quickie) or manual npub/hex entry |

### Services (`java/com/ridestr/rider/service/`)

| File | Purpose |
|------|---------|
| `RiderActiveService.kt` | Foreground service - keeps app alive during active rides |

### Entry Point

| File | Purpose |
|------|---------|
| `MainActivity.kt` | App entry, Compose navigation, dependency injection, sync adapter registration |

---

## State Machine (RiderViewModel)

```
IDLE
  │
  ├──▶ sendRideOffer() ─────────────▶ WAITING_FOR_ACCEPTANCE
  │                                        │
  │                                        ├──▶ timeout/cancel/driver unavailable → IDLE
  │                                        │
  │                                        ▼ driver accepts (Kind 3174)
  │                                   DRIVER_ACCEPTED
  │                                        │
  ├──▶ broadcastRideRequest() ──────▶ BROADCASTING_REQUEST
  │                                        │
  │                                        ├──▶ timeout/cancel → IDLE
  │                                        │
  │                                        ▼ driver accepts (Kind 3174)
  │                                   DRIVER_ACCEPTED
  │                                        │
  ▼                                        ▼ autoConfirmRide() (automatic)
                                      RIDE_CONFIRMED
                                           │
                                           ▼ driver state: ARRIVED
                                      DRIVER_ARRIVED
                                           │
                                           ▼ PIN verified, preimage shared
                                       IN_PROGRESS
                                           │
                                           ▼ driver state: COMPLETED
                                       COMPLETED
                                           │
                                           ▼ clearRide()
                                          IDLE

Any active state → cancelRide() or driverCancelled() → IDLE
```

### Key State Transitions

| Method | From State | To State | Nostr Event |
|--------|------------|----------|-------------|
| `sendRideOffer()` | IDLE | WAITING_FOR_ACCEPTANCE | Kind 3173 (direct) |
| `sendRoadflareOffer()` | IDLE | WAITING_FOR_ACCEPTANCE | Kind 3173 (RoadFlare, isRoadflare=true) |
| `sendRoadflareOfferWithAlternatePayment()` | IDLE | WAITING_FOR_ACCEPTANCE | Kind 3173 (RoadFlare, alternate payment, no HTLC) |
| `broadcastRideRequest()` | IDLE | BROADCASTING_REQUEST | Kind 3173 (broadcast) |
| `sendRoadflareToAll()` | IDLE | WAITING_FOR_ACCEPTANCE | Kind 3173 (batch to all followed drivers, sorted by proximity, batches of 3) |
| `autoConfirmRide()` | DRIVER_ACCEPTED | RIDE_CONFIRMED | Kind 3175 |
| `handleDriverRideState()` | Various | Various | Receives Kind 30180 |
| `cancelOffer()` | WAITING_FOR_ACCEPTANCE / BROADCASTING_REQUEST | IDLE | Cancels pending offer or broadcast request |
| `cancelRide()` | Any active | IDLE | Kind 3179 |
| `clearRiderStateHistory()` | - | - | **CRITICAL**: Must call when starting new ride |

### Pre-Confirmation Driver Monitoring (Issue #22)

When rider sends a direct offer to a specific driver, the rider app monitors that driver's availability:

- `subscribeToSelectedDriverAvailability()` - Starts monitoring Kind 30173 for selected driver
- If driver goes offline (availability status changes), shows "Driver Unavailable" dialog
- Auto-cancels offer and returns to IDLE state
- Only applies during WAITING_FOR_ACCEPTANCE stage (pre-confirmation)

This prevents riders from waiting indefinitely when their selected driver takes another ride or goes offline.

### Driver Availability State Stability (February 2026)

Fixes for driver status bouncing between offline/online:

| Fix | Description | Location |
|-----|-------------|----------|
| Selective clearing | `resubscribeToDrivers(clearExisting)` - only clears on geohash changes | Line ~798 |
| ReceivedAt tracking | `driverLastReceivedAt` map for accurate staleness vs publish time | Line ~179 |
| Out-of-order guard | `selectedDriverLastAvailabilityTimestamp` rejects old events | Line ~168 |
| Since filter | 10-min filter in NostrService subscription | NostrService:1275 |
| Ride stage check | Pause stale cleanup during active rides | Line ~2777 |

**Key methods:**
- `resubscribeToDrivers(clearExisting: Boolean)` - Pass `true` only for geohash changes
- `cleanupStaleDrivers()` - Uses `driverLastReceivedAt` for freshness, skips during active rides
- `closeDriverAvailabilitySubscription()` - Resets timestamp guard on close

---

## Connections Table

| From (this module) | To (common module) | How/Why | Example Call |
|--------------------|-------------------|---------|--------------|
| `RiderViewModel` | `NostrService` | Publish ride request | `nostrService.broadcastRideRequest()` |
| `RiderViewModel` | `NostrService` | Subscribe to acceptances | `nostrService.subscribeToAcceptances()` |
| `RiderViewModel` | `NostrService` | Publish rider state | `nostrService.publishRiderRideState()` |
| `RiderViewModel` | `WalletService` | Verify wallet before offer | `walletService.ensureWalletReady(fareWithBuffer)` |
| `RiderViewModel` | `WalletService` | Lock funds for ride (HTLC) | `walletService.lockForRide(fareSats)` |
| `RiderViewModel` | `WalletService` | Check balance | `walletService.hasSufficientFunds()` |
| `RiderViewModel` | `RideHistoryRepository` | Save completed ride | `rideHistoryRepo.addRide()` |
| `RiderViewModel` | `SavedLocationRepository` | Recent locations | `savedLocationRepo.addRecent()` |
| `MainActivity` | `ProfileSyncManager` | Register sync adapters | `profileSyncManager.registerSyncable()` |
| `MainActivity` | `Nip60WalletSync` | Wire to WalletService | `walletService.setNip60Sync()` |
| `MainActivity` | `LogoutManager` | Centralized logout cleanup | `LogoutManager.performFullCleanup(...)` |
| `MainActivity` | `RiderViewModel` | ViewModel logout cleanup | `viewModel.performLogoutCleanup()` |
| `MainActivity` | `NostrService` | Create service instance | Constructor injection |
| `WalletScreen` | `WalletService` | Display balance | `walletService.balance.value` |
| `HistoryScreen` | `RideHistoryRepository` | Display past rides | `rideHistoryRepo.rides.value` |
| `HistoryScreen` | `RideHistoryRepository` | Manual backup | `rideHistoryRepo.backupToNostr()` |
| `RiderViewModel` | `NostrService` | Send RoadFlare to all drivers | `sendRoadflareToAll()` |
| `MainActivity` | `FollowedDriversRepository` | Followed drivers for RoadFlare tab | `followedDriversRepo.drivers` |
| `MainActivity` | `NostrService` | Subscribe to key shares (Kind 3186) | `nostrService.subscribeToRoadflareKeyShares()` |

---

## MainActivity Initialization Flow

```kotlin
// Line 131: NostrService with custom relays
val nostrService = NostrService(context, settingsManager.getEffectiveRelays())

// Line 153-154: WalletService initialization
val walletService = WalletService(context, walletKeyManager)

// Line 159-165: Nip60WalletSync wiring
val nip60Sync = Nip60WalletSync(...)
walletService.setNip60Sync(nip60Sync)

// Line 173-175: ProfileSyncManager singleton
val profileSyncManager = ProfileSyncManager.getInstance(context, relays)

// Line 182-186: Sync adapter registration
profileSyncManager.registerSyncable(Nip60WalletSyncAdapter(nip60Sync))
profileSyncManager.registerSyncable(ProfileSyncAdapter(null, savedLocationRepo, settingsManager, nostrService))  // Unified profile (locations + settings)
profileSyncManager.registerSyncable(RideHistorySyncAdapter(rideHistoryRepo, nostrService))

// Line 268-272: On login, trigger sync
profileSyncManager.onKeyImported()
```

---

## Navigation Structure

```
Screen.ONBOARDING → (login/generate key) → Screen.MAIN_MAP
Screen.MAIN_MAP → (tap wallet card) → Screen.WALLET_DETAIL
Screen.MAIN_MAP → (tap history) → Screen.HISTORY
Screen.MAIN_MAP → (tap profile) → AccountBottomSheet
Screen.MAIN_MAP → (ride in progress) → DriverTrackingOverlay
Screen.RIDE_DETAIL → (from history) → Full ride details
```

---

## Important Implementation Notes

### Ride State History
- `riderStateHistory` is a synchronized list that accumulates actions
- **CRITICAL**: Call `clearRiderStateHistory()` when starting a new ride
- History actions: `location_reveal`, `pin_verify`, `preimage_share`

### Preimage Sharing
- `sharePreimageWithDriver()` publishes preimage via Kind 30181
- Called after PIN verification at pickup
- Driver uses preimage to claim HTLC payment

### Subscriptions (Phase 4 SubscriptionManager)
All subscription IDs managed via `SubscriptionManager` instance (`subs`), replacing 7 scattered nullable variables.

**SubKeys:** `DRIVERS`, `DRIVER_PROFILES` (group), `ACCEPTANCE`, `CHAT`, `CANCELLATION`, `DRIVER_RIDE_STATE`, `SELECTED_DRIVER_AVAILABILITY`

**Key subscription methods:**
- `subscribeToDriverRideState()` - Watch driver's Kind 30180 events
- `subscribeToAcceptances()` - Watch for Kind 3174 responses
- `subscribeToSelectedDriverAvailability()` - Watch selected driver's Kind 30173 (Issue #22)

**Cleanup tiers:**
- `closeAllRideSubscriptions()` - Closes `SubKeys.RIDE_BASE` (4 subs: acceptance, cancellation, driver_ride_state, chat). Used by `confirmRide()` only — keeps `SELECTED_DRIVER_AVAILABILITY` open for Issue #22.
- `closeAllRideSubscriptionsAndJobs()` - Closes `SubKeys.RIDE_ALL` (5 subs, includes availability) + cancels all jobs. Used by all ride-ending paths.
- `closeDriverAvailabilitySubscription()` - Clean up driver monitoring on acceptance/cancel

### State Reset (Phase 1 + Phase 3 RideSession + Phase 4 SubscriptionManager)
- `RiderRideSession` data class holds ALL ride-scoped fields. Defined before `RiderUiState`.
- `resetRideUiState(stage, statusMessage, error?)` resets to `RiderRideSession()` defaults — new fields automatically included.
- `updateRideSession { ... }` helper uses atomic `StateFlow.update {}` for pure ride-session mutations.
- All 5 ride-ending paths use `resetRideUiState()`: `clearRide()`, `handleDriverCancellation()`, `handleRideCompletion()` (partial), PIN brute force, `cancelOffer()`
- Outer `RiderUiState` persists across rides: `availableDrivers`, `pickupLocation`, `destination`, `routeResult`, `fareEstimate`, `myPubKey`, `statusMessage`, `error`
- UI reads: `uiState.rideSession.fieldName` for ride-scoped fields, `uiState.fieldName` for persistent fields

### Confirmation Flow Protection (Phase 6 — February 2026)
Thread-safe race condition fix prevents duplicate confirmation from multi-relay delivery:
- `confirmationInFlight` AtomicBoolean — `compareAndSet(false, true)` as very first operation in both `autoConfirmRide()` and `confirmRide()`. Only one IO thread wins.
- `hasAcceptedDriver` AtomicBoolean — `compareAndSet` for broadcast first-acceptance-wins
- Callback stage checks (`subscribeToAcceptance`, `handleBatchAcceptance`, `subscribeToAcceptancesForBroadcast`) use `StateFlow.update {}` CAS with `shouldConfirm` re-derived from `current` at lambda top
- Post-suspension guards check acceptance identity FIRST (cross-ride), then stage (same-ride cancel)
- try/catch with `CancellationException` rethrow wraps confirmation coroutines; catch guards reset with identity check
- `isConfirmingRide` remains as UI spinner state; `confirmationInFlight` is the thread-safe mutex
- **Without this**: Multi-relay delivery causes double HTLC lock, two Kind 3175 events, subscription mismatches

### Auto-Confirm UI (January 2026)
- When driver accepts, rider UI shows ride summary with "Confirming ride..." spinner
- No manual confirm button - confirmation happens automatically via `autoConfirmRide()`
- Driver has 30-second timeout waiting for confirmation
- Only "Cancel Ride" button available during confirmation

### PaymentHash in Confirmation (January 2026)
paymentHash is now sent in confirmation (Kind 3175), not offer (Kind 3173):
- `autoConfirmRide()` at line 2940 passes `paymentHash` and `escrowToken` to `confirmRide()`
- This ensures HTLC is locked with the correct driver wallet key AFTER acceptance
- Fixes boost bug where resending offer overwrote driver's paymentHash with null
- Driver extracts paymentHash from `confirmation.paymentHash` in `subscribeToConfirmation()`

### Cancel After PIN Verification (January 2026)
If rider cancels after preimage was shared with driver:
- `attemptCancelRide()` checks `preimageShared || pinVerified` (line 1547)
- Warning dialog shows that payment was already authorized
- Driver can still claim the fare even after cancellation
- `preimageShared` set to `true` after successful `sharePreimageWithDriver()` (line 343)

### Deposit/Withdraw
- Tap wallet card in `WalletScreen.kt` → navigates to `WalletDetailScreen` (common)
- Deposit and withdraw are **fully functional**
- Only LN address resolution is broken (must paste BOLT11 directly)

### RoadFlare Alternate Payment Methods (January 2026)
For personal RoadFlare drivers, riders can offer non-bitcoin payment methods:
- Payment methods configured in RoadflareTab via `RoadflarePaymentMethodsDialog` (Zelle, PayPal, Cash App, Venmo, Cash, Strike)
- Stored in `SettingsManager.roadflarePaymentMethods` and backed up to Kind 30177
- When insufficient bitcoin funds during a RoadFlare offer, modified dialog offers "Continue with Alternate Payment"
- `sendRoadflareOfferWithAlternatePayment()` skips balance check and sends offer with alternate `paymentMethod`
- UI state tracks `insufficientFundsIsRoadflare`, `pendingRoadflareDriverPubKey`, `pendingRoadflareDriverLocation`

### RoadFlare Offer Unification (January 2026)
RoadFlare offers use the same `RideOfferEvent.create()` as normal offers with `isRoadflare=true`:
- `driverAvailabilityEventId` is optional (RoadFlare offers have no `e` tag)
- `subscribeToSelectedDriverAvailability()` called for RoadFlare offers (cancellation compatibility)
- Minimum fare ($5.00 USD) enforced for RoadFlare rides

### Wallet Refresh on Ride End (January 2026)
Both ride completion and cancellation now trigger automatic wallet refresh:

**`handleRideCompletion()`** (line ~2886):
1. `markHtlcClaimedByPaymentHash()` - Clears `pendingSats` immediately (synchronous)
2. `refreshBalance()` - Full NIP-60 sync + `updateDiagnostics()` (in coroutine)

**`handleDriverCancellation()`** (line ~2982):
1. `refreshBalance()` - Syncs wallet, checks for expired HTLCs to refund

This ensures:
- Rider's balance is accurate after ride ends
- Diagnostics icon shows green (NIP-60 synced)
- Expired HTLCs are auto-refunded on cancellation
