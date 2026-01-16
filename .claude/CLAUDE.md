# Ridestr - Nostr-based Rideshare Platform

## Project Structure
- `rider-app/` - Rider Android app (RiderViewModel.kt is main state)
- `drivestr/` - Driver Android app (DriverViewModel.kt is main state)
- `common/` - Shared code (NostrService.kt, event models, UI components)

## Build Commands
```bash
./gradlew :rider-app:assembleDebug    # Build rider app
./gradlew :drivestr:assembleDebug     # Build driver app
./gradlew build                        # Build all
```

## Nostr Event Kinds (NIP-014173)
| Kind | Type | Purpose |
|------|------|---------|
| 30173 | Addressable | Ride Request Broadcast (rider seeking drivers) |
| 30180 | Parameterized Replaceable | Driver Ride State (history-based) |
| 30181 | Parameterized Replaceable | Rider Ride State (history-based) |
| 3173 | Ephemeral | Ride Offer (driver to rider, NIP-44 encrypted) |
| 3174 | Ephemeral | Ride Acceptance (rider to driver) |
| 3175 | Ephemeral | Broadcast Ride Acceptance (driver accepts 30173) |
| 3176 | Ephemeral | Location Update (encrypted) |
| 3177 | Ephemeral | Cancellation |
| 3178 | Ephemeral | Ride Completed |
| 3179 | Ephemeral | Encrypted Chat |
| 30174 | Addressable | Ride History (backup/recovery) |

## Critical Debugging Insight
**When debugging data issues, ALWAYS trace data at BOTH:**
1. **ORIGINATION** (where data is created/sent)
2. **RECEIVER** (where data is processed)

Example: Phantom cancellation bug was in DRIVER app (origination) not RIDER app (receiver).

## State Management Rules
- Kind 30180/30181 use `history` arrays that ACCUMULATE actions
- **CRITICAL**: Clear history when starting new ride (`clearDriverStateHistory()`, `clearRiderStateHistory()`)
- d-tag format: `ridestr-{confirmationEventId}` for subscription filtering

## Key Files
- `DriverViewModel.kt:243` - `clearDriverStateHistory()` definition
- `DriverViewModel.kt:950` - `acceptOffer()` - MUST clear history
- `DriverViewModel.kt:2550` - `acceptBroadcastRequest()` - MUST clear history
- `RiderViewModel.kt:340` - `clearRiderStateHistory()` definition
- `NostrService.kt` - All event publishing/subscription logic
