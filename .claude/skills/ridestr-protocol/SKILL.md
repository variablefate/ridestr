# Ridestr Protocol Expert Skill

Use this skill when working on Nostr event handling, ride state management, payment rails, or debugging data flow issues in the Ridestr rideshare platform.

---

## Core Debugging Philosophy

### The Dual-Origin Principle
**When debugging data corruption or unexpected state, ALWAYS investigate BOTH sides:**

1. **Data Origination** - Where is the data created/modified/sent?
2. **Data Receiver** - Where is the data received/processed/consumed?

**Case Study - Phantom Cancellation Bug:**
- Symptom: Rider sees "Driver cancelled" on ride #2 even though driver didn't cancel
- Initial (wrong) approach: Added deduplication in RiderViewModel (receiver)
- Why it failed: The data was already corrupted when sent
- Root cause: DriverViewModel (originator) didn't clear `driverStateHistory` between rides
- Fix: Add `clearDriverStateHistory()` in `acceptOffer()` and `acceptBroadcastRequest()`

---

## NIP-014173 Protocol Implementation

### Event Architecture

#### Parameterized Replaceable Events (Kind 30180/30181)
These events use **history accumulation** - each update ADDS to a history array:

```json
{
  "kind": 30180,
  "tags": [
    ["d", "ridestr-{confirmationEventId}"],
    ["e", "{confirmationEventId}"],
    ["p", "{otherPartyPubkey}"]
  ],
  "content": "{NIP-44 encrypted JSON}"
}
```

Decrypted content structure:
```json
{
  "history": [
    {"action": "status", "status": "en_route_pickup", "timestamp": 1234567890},
    {"action": "location_update", "lat": 40.7128, "lng": -74.0060, "timestamp": 1234567891},
    {"action": "pin_submit", "pin": "1234", "timestamp": 1234567892}
  ],
  "rider_pubkey": "npub...",
  "driver_pubkey": "npub...",
  "pickup": {"lat": 40.7128, "lng": -74.0060, "address": "123 Main St"},
  "dropoff": {"lat": 40.7580, "lng": -73.9855, "address": "456 Oak Ave"},
  "confirmationEventId": "abc123..."
}
```

#### History Actions
| Action | Fields | Published By |
|--------|--------|--------------|
| `status` | `status: String` | Both |
| `location_update` | `lat, lng, heading?, speed?` | Both |
| `pin_submit` | `pin: String` | Driver |
| `pin_verify` | `verified: Boolean` | Rider |
| `preimage_share` | `preimage: String` | Rider (post-PIN) |
| `settlement` | `settlement_proof: String` | Driver (post-dropoff) |

#### Status Values
**DriverStage:** `AVAILABLE`, `OFFERED`, `ACCEPTED`, `EN_ROUTE_PICKUP`, `AT_PICKUP`, `EN_ROUTE_DROPOFF`, `AT_DROPOFF`, `CANCELLED`, `COMPLETED`

**RiderStage:** `IDLE`, `SEEKING`, `OFFERED`, `ACCEPTED`, `PICKUP`, `IN_RIDE`, `DROPOFF`, `CANCELLED`, `COMPLETED`

### Subscription Patterns

```kotlin
// Subscribe to specific ride events using d-tag filter
val filter = JsonObject().apply {
    addProperty("kinds", 30180)
    add("d", JsonArray().apply { add("ridestr-$confirmationEventId") })
}
```

**Critical**: When processing events, compare `lastProcessedActionCount` to avoid reprocessing:
```kotlin
val newActions = history.drop(lastProcessedActionCount)
for (action in newActions) {
    processAction(action)
}
lastProcessedActionCount = history.size
```

---

## State Machine Transitions

### Driver Flow
```
AVAILABLE → acceptOffer() → ACCEPTED
ACCEPTED → startRouteToPickup() → EN_ROUTE_PICKUP
EN_ROUTE_PICKUP → arrivedAtPickup() → AT_PICKUP
AT_PICKUP → startRide() [after PIN verify] → EN_ROUTE_DROPOFF
EN_ROUTE_DROPOFF → arrivedAtDropoff() → AT_DROPOFF
AT_DROPOFF → completeRide() [geohash check] → COMPLETED

Any state → cancelRide() → CANCELLED → resetToAvailable() → AVAILABLE
```

### Rider Flow
```
IDLE → requestRide() → SEEKING
SEEKING → receiveOffer() → OFFERED
OFFERED → acceptOffer() → ACCEPTED
ACCEPTED → driverArrived() → PICKUP
PICKUP → rideStarted() [PIN verified] → IN_RIDE
IN_RIDE → arrivedAtDropoff() → DROPOFF
DROPOFF → rideCompleted() → COMPLETED

Any state → cancelRide() or driverCancelled() → CANCELLED → resetToIdle() → IDLE
```

### Critical State Cleanup Points
**ALWAYS clear history when:**
1. Starting a new ride (`acceptOffer`, `acceptBroadcastRequest`)
2. After cancellation cleanup (`resetToAvailable`, `resetToIdle`)
3. After ride completion

---

## Payment Rails (HODL Invoice + Nut-14 HTLC)

### Escrow Flow Overview
```
Rider generates: preimage (32 bytes random) → payment_hash = SHA256(preimage)
                                ↓
Rider sends payment_hash + destination_geohash in Kind 3173/3174
                                ↓
Driver creates escrow invoice with rider's payment_hash:
  - Lightning (NWC): HODL invoice
  - Cashu: Nut-14 HTLC locked tokens
                                ↓
Rider pays into escrow (funds locked, non-custodial)
                                ↓
At pickup: PIN verification → preimage shared via Kind 30181 preimage_share
                                ↓
At dropoff: Geohash check (~400m) → unlock "Confirm Dropoff" button
                                ↓
Driver settles: Uses preimage to claim locked funds
  - Lightning: Settle HODL invoice
  - Cashu: Melt Nut-14 HTLC with preimage
```

### Interoperability Matrix
| Rider Wallet | Driver Wallet | Bridge Needed |
|--------------|---------------|---------------|
| Lightning (NWC) | Lightning (NWC) | None |
| Cashu | Cashu | None |
| Lightning | Cashu | Bankify/Minibits NWC |
| Cashu | Lightning | Mint melt to Lightning |

### Security Properties
- **Driver can't claim early**: Needs preimage (only shared after PIN at pickup)
- **Driver can't claim without arrival**: Geohash check gates settlement UI
- **Rider can't scam post-pickup**: Funds already locked in HTLC
- **Timeout protection**: HODL/Nut-14 expires → auto-refund to rider

### Melt Quote Validation (NUT-05)
Before ride starts, validate mint has liquidity:
```kotlin
// Driver requests melt quote for fare amount
val meltQuote = cashuMint.getMeltQuote(invoiceAmount)
// Publish quote as Nostr event for rider verification
// Rider validates before paying into escrow
```

---

## Common Bug Patterns

### 1. History Pollution (Phantom Actions)
**Symptom**: Actions from ride #1 appear in ride #2
**Cause**: History not cleared when starting new ride
**Fix**: Call `clearDriverStateHistory()` / `clearRiderStateHistory()` at ride start

### 2. Event Deduplication Failures
**Symptom**: Same action processed multiple times
**Cause**: `lastProcessedActionCount` not updated or event ID not tracked
**Fix**: Track processed event IDs AND action counts

### 3. Subscription Leaks
**Symptom**: Events from old rides trigger handlers
**Cause**: Subscriptions not closed when ride ends
**Fix**: Close subscriptions in `resetToAvailable()` / `resetToIdle()`

### 4. d-tag Mismatch
**Symptom**: Events ignored or processed for wrong ride
**Cause**: d-tag format inconsistency (`ridestr-{id}` vs just `{id}`)
**Fix**: Use consistent d-tag format: `"ridestr-$confirmationEventId"`

---

## Key File Locations

### ViewModels (State Management)
- `drivestr/src/main/java/com/drivestr/app/viewmodels/DriverViewModel.kt`
  - `clearDriverStateHistory()` - Line ~243
  - `acceptOffer()` - Line ~950
  - `acceptBroadcastRequest()` - Line ~2550
  - `updateDriverStatus()` - Adds to history
  - `publishDriverRideState()` - Publishes Kind 30180

- `rider-app/src/main/java/com/ridestr/rider/viewmodels/RiderViewModel.kt`
  - `clearRiderStateHistory()` - Line ~340
  - `handleDriverRideState()` - Processes Kind 30180
  - `handleDriverCancellation()` - Cancellation handler
  - `publishRiderRideState()` - Publishes Kind 30181

### Nostr Layer
- `common/src/main/java/com/ridestr/common/nostr/NostrService.kt`
  - Event publishing methods
  - Subscription management
  - NIP-44 encryption/decryption

### Event Models
- `common/src/main/java/com/ridestr/common/nostr/events/` - Event data classes

---

## Testing Checklist

### After Any State Management Change
1. [ ] Single ride: request → accept → pickup → dropoff → complete
2. [ ] Driver cancellation: mid-ride cancel → rider notified
3. [ ] Rider cancellation: mid-ride cancel → driver notified
4. [ ] **Sequential rides same parties**: ride #1 cancel → ride #2 works
5. [ ] **Sequential rides different parties**: ride #1 with A → ride #2 with B

### After Payment Rails Changes
1. [ ] Lightning-to-Lightning escrow flow
2. [ ] Cashu-to-Cashu escrow flow
3. [ ] Cross-wallet (Lightning ↔ Cashu) with bridge
4. [ ] Timeout/expiry refund path
5. [ ] Geohash gate prevents early settlement

---

## Design Document References

For detailed specifications, see:
- `~/.claude/plans/updated design documents/master plan.md` - Complete project overview
- `~/.claude/plans/updated design documents/NIP-014173.md` - Full NIP specification
- `~/.claude/plans/updated design documents/NIP-014173-design-rationale-and-history.md` - Design rationale
- `~/.claude/plans/updated design documents/paymentrails.md` - Payment flow details
