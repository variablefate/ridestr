# Ridestr Nostr Event Protocol

**Version**: 1.0
**Last Updated**: 2026-01-15

This document defines all Nostr event kinds used in the Ridestr rideshare application.

---

## Event Kind Summary

| Kind | Name | Type | Author | Purpose |
|------|------|------|--------|---------|
| 30173 | Driver Availability | Parameterized Replaceable | Driver | Broadcast driver availability and location |
| 30180 | Driver Ride State | Parameterized Replaceable | Driver | Consolidated ride status + PIN submissions |
| 30181 | Rider Ride State | Parameterized Replaceable | Rider | Consolidated location reveals + PIN verifications |
| 30174 | Ride History | Parameterized Replaceable | Either | Encrypted backup of ride details |
| 3173 | Ride Offer | Regular | Rider | Request a ride (direct or broadcast) |
| 3174 | Ride Acceptance | Regular | Driver | Accept a ride offer |
| 3175 | Ride Confirmation | Regular | Rider | Confirm accepted ride with pickup details |
| 3178 | Chat Message | Regular | Either | Encrypted chat during ride |
| 3179 | Ride Cancellation | Regular | Either | Cancel an active ride |

---

## Event Definitions

### Kind 30173: Driver Availability

**Purpose**: Broadcasts driver's availability status and approximate location to nearby riders.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Driver

**d-tag**: Driver's public key hex

**Tags**:
```
["d", "<driver_pubkey>"]
["g", "<geohash_precision_5>"]  // ~5km area for discovery
["expiration", "<unix_timestamp>"]  // NIP-40, typically 10 minutes
```

**Content** (JSON):
```json
{
  "status": "available" | "unavailable",
  "geohash": "<precision_5_geohash>",
  "timestamp": <unix_timestamp>
}
```

**Lifecycle**:
1. Published when driver goes online (`goOnline()`)
2. Republished every 5 minutes while online
3. Replaced with `status: unavailable` when going offline
4. Auto-expires via NIP-40 after 10 minutes

---

### Kind 30180: Driver Ride State

**Purpose**: Consolidated event containing all driver actions during a ride. Replaces older separate PIN/status events.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Driver

**d-tag**: Confirmation event ID (from Kind 3175)

**Tags**:
```
["d", "<confirmation_event_id>"]
["p", "<rider_pubkey>"]
["expiration", "<unix_timestamp>"]  // 3 hours from creation
```

**Content** (JSON, encrypted with NIP-44 to rider):
```json
{
  "confirmationEventId": "<confirmation_event_id>",
  "driverPubKey": "<driver_pubkey>",
  "riderPubKey": "<rider_pubkey>",
  "currentStatus": "EN_ROUTE_PICKUP" | "ARRIVED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED",
  "driverLocation": {
    "lat": <latitude>,
    "lon": <longitude>,
    "geohash": "<geohash>"
  },
  "history": [
    {
      "type": "status",
      "status": "<status_type>",
      "timestamp": <unix_timestamp>,
      "location": { "lat": <lat>, "lon": <lon>, "geohash": "<geohash>" }
    },
    {
      "type": "pin_submit",
      "pin": "<4_digit_pin>",
      "timestamp": <unix_timestamp>
    }
  ]
}
```

**History Action Types**:
- `status`: Driver status change (EN_ROUTE_PICKUP, ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED)
- `pin_submit`: Driver submitting PIN for verification

**Lifecycle**:
1. Created when driver accepts ride
2. Updated (replaced) on each status change or PIN submission
3. History accumulates all actions chronologically
4. Expires 3 hours after creation

---

### Kind 30181: Rider Ride State

**Purpose**: Consolidated event containing rider's location reveals and PIN verifications.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Rider

**d-tag**: Confirmation event ID (from Kind 3175)

**Tags**:
```
["d", "<confirmation_event_id>"]
["p", "<driver_pubkey>"]
["expiration", "<unix_timestamp>"]  // 3 hours from creation
```

**Content** (JSON, encrypted with NIP-44 to driver):
```json
{
  "confirmationEventId": "<confirmation_event_id>",
  "riderPubKey": "<rider_pubkey>",
  "driverPubKey": "<driver_pubkey>",
  "currentPhase": "AWAITING_DRIVER" | "AWAITING_PIN" | "IN_RIDE" | "COMPLETED",
  "history": [
    {
      "type": "location_reveal",
      "locationType": "pickup" | "destination",
      "locationEncrypted": "<nip44_encrypted_location>",
      "timestamp": <unix_timestamp>
    },
    {
      "type": "pin_verify",
      "verified": true | false,
      "attempt": 1 | 2 | 3,
      "timestamp": <unix_timestamp>
    }
  ]
}
```

**History Action Types**:
- `location_reveal`: Progressive location sharing (pickup at 1 mile, destination after PIN)
- `pin_verify`: Result of PIN verification attempt

**Lifecycle**:
1. Created when rider confirms ride
2. Updated on location reveals and PIN verifications
3. Expires 3 hours after creation

---

### Kind 30174: Ride History Backup

**Purpose**: Encrypted backup of complete ride details for user records.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Either party (both create their own backup)

**d-tag**: Confirmation event ID

**Tags**:
```
["d", "<confirmation_event_id>"]
["expiration", "<unix_timestamp>"]  // 7 days
```

**Content** (JSON, self-encrypted with NIP-44):
```json
{
  "confirmationEventId": "<confirmation_event_id>",
  "riderPubKey": "<rider_pubkey>",
  "driverPubKey": "<driver_pubkey>",
  "pickupLocation": { "lat": <lat>, "lon": <lon>, "address": "<address>" },
  "destinationLocation": { "lat": <lat>, "lon": <lon>, "address": "<address>" },
  "fareEstimate": <fare_in_sats>,
  "completedAt": <unix_timestamp>,
  "status": "COMPLETED" | "CANCELLED"
}
```

---

### Kind 3173: Ride Offer

**Purpose**: Rider requests a ride from a specific driver (direct) or broadcasts to all nearby drivers.

**Type**: Regular Event

**Author**: Rider

**Tags** (Direct Offer):
```
["p", "<driver_pubkey>"]  // Target driver
["expiration", "<unix_timestamp>"]  // 15 seconds for direct, 2 minutes for broadcast
```

**Tags** (Broadcast):
```
["g", "<pickup_geohash_precision_5>"]  // For discovery by nearby drivers
["t", "ride-request"]  // Broadcast marker
["expiration", "<unix_timestamp>"]
```

**Content** (JSON, encrypted to driver for direct, plaintext for broadcast):
```json
{
  "pickupGeohash": "<geohash_precision_7>",  // ~150m area
  "destinationGeohash": "<geohash_precision_7>",
  "fareEstimateSats": <fare>,
  "riderPubKey": "<rider_pubkey>",
  "timestamp": <unix_timestamp>
}
```

**Note**: Precise coordinates are NOT shared in the offer. Only revealed progressively after confirmation.

---

### Kind 3174: Ride Acceptance

**Purpose**: Driver accepts a ride offer.

**Type**: Regular Event

**Author**: Driver

**Tags**:
```
["p", "<rider_pubkey>"]
["e", "<offer_event_id>"]  // Reference to Kind 3173
["expiration", "<unix_timestamp>"]  // 5 minutes
```

**Content** (JSON, encrypted to rider):
```json
{
  "driverPubKey": "<driver_pubkey>",
  "offerEventId": "<offer_event_id>",
  "estimatedArrivalMinutes": <minutes>,
  "driverLocation": {
    "lat": <latitude>,
    "lon": <longitude>,
    "geohash": "<geohash>"
  }
}
```

---

### Kind 3175: Ride Confirmation

**Purpose**: Rider confirms the accepted ride. This event ID becomes the canonical ride identifier.

**Type**: Regular Event

**Author**: Rider

**Tags**:
```
["p", "<driver_pubkey>"]
["e", "<acceptance_event_id>"]  // Reference to Kind 3174
["expiration", "<unix_timestamp>"]  // 3 hours
```

**Content** (JSON, encrypted to driver):
```json
{
  "riderPubKey": "<rider_pubkey>",
  "driverPubKey": "<driver_pubkey>",
  "acceptanceEventId": "<acceptance_event_id>",
  "pickupGeohash": "<geohash_precision_7>",
  "pin": "<4_digit_pin>",
  "timestamp": <unix_timestamp>
}
```

**IMPORTANT**: The confirmation event ID is used as the `d-tag` for all subsequent ride state events (30180, 30181, 30174).

---

### Kind 3178: Chat Message

**Purpose**: Encrypted chat messages between rider and driver during a ride.

**Type**: Regular Event

**Author**: Either party

**Tags**:
```
["p", "<recipient_pubkey>"]
["e", "<confirmation_event_id>"]  // Links to ride
["expiration", "<unix_timestamp>"]  // 24 hours
```

**Content** (Encrypted with NIP-44):
```json
{
  "message": "<chat_message_text>",
  "senderPubKey": "<sender_pubkey>",
  "timestamp": <unix_timestamp>
}
```

---

### Kind 3179: Ride Cancellation

**Purpose**: Either party cancels an active ride.

**Type**: Regular Event

**Author**: Either party

**Tags**:
```
["p", "<other_party_pubkey>"]
["e", "<confirmation_event_id>"]  // Which ride is being cancelled
["expiration", "<unix_timestamp>"]  // 1 hour
```

**Content** (JSON, encrypted to other party):
```json
{
  "confirmationEventId": "<confirmation_event_id>",
  "cancelledByPubKey": "<canceller_pubkey>",
  "reason": "<optional_reason_text>",
  "timestamp": <unix_timestamp>
}
```

---

## Encryption Standards

### NIP-44 Usage
All sensitive data (locations, PINs, chat messages) uses NIP-44 encryption:
- Encrypts to recipient's public key
- Decryptable only by recipient's private key
- Used for: ride offers, acceptances, confirmations, chat, ride states

### Self-Encryption
For ride history backups, the author encrypts to their own public key for secure storage.

---

## Expiration (NIP-40)

All events include expiration tags to prevent relay bloat:

| Event Type | Expiration |
|------------|------------|
| Driver Availability | 10 minutes |
| Ride Offer (Direct) | 15 seconds |
| Ride Offer (Broadcast) | 2 minutes |
| Ride Acceptance | 5 minutes |
| Ride Confirmation | 3 hours |
| Driver/Rider Ride State | 3 hours |
| Chat Message | 24 hours |
| Ride Cancellation | 1 hour |
| Ride History Backup | 7 days |

---

## Geohash Precision

| Precision | Size | Usage |
|-----------|------|-------|
| 5 | ~5km x 5km | Driver discovery, broadcast requests |
| 6 | ~1km x 1km | Area-level location |
| 7 | ~150m x 150m | Approximate pickup/destination |
| 8+ | <40m | Precise location (encrypted only) |

---

## Event Flow Diagram

```
RIDER                           NOSTR RELAY                         DRIVER
  |                                   |                                |
  |  [Browse Available Drivers]       |                                |
  |<----------- Kind 30173 -----------|                                |
  |                                   |<---------- Kind 30173 ---------|
  |                                   |   (Driver publishes availability)
  |                                   |                                |
  |---------- Kind 3173 ------------->|                                |
  |   (Ride Offer)                    |----------- Kind 3173 --------->|
  |                                   |                                |
  |                                   |<---------- Kind 3174 ----------|
  |<--------- Kind 3174 --------------|   (Ride Acceptance)            |
  |                                   |                                |
  |---------- Kind 3175 ------------->|                                |
  |   (Confirmation + PIN)            |----------- Kind 3175 --------->|
  |                                   |                                |
  |                                   |<---------- Kind 30180 ---------|
  |<-------- Kind 30180 --------------|   (Driver state: EN_ROUTE)     |
  |                                   |                                |
  |--------- Kind 30181 ------------->|                                |
  |   (Reveal precise pickup)         |---------- Kind 30181 --------->|
  |                                   |                                |
  |                                   |<---------- Kind 30180 ---------|
  |<-------- Kind 30180 --------------|   (Driver state: ARRIVED)      |
  |                                   |                                |
  |                                   |<---------- Kind 30180 ---------|
  |<-------- Kind 30180 --------------|   (PIN submit in state)        |
  |                                   |                                |
  |--------- Kind 30181 ------------->|                                |
  |   (PIN verified)                  |---------- Kind 30181 --------->|
  |                                   |                                |
  |                                   |<---------- Kind 30180 ---------|
  |<-------- Kind 30180 --------------|   (Driver state: IN_PROGRESS)  |
  |                                   |                                |
  |--------- Kind 30181 ------------->|                                |
  |   (Reveal destination)            |---------- Kind 30181 --------->|
  |                                   |                                |
  |                                   |<---------- Kind 30180 ---------|
  |<-------- Kind 30180 --------------|   (Driver state: COMPLETED)    |
  |                                   |                                |
  |--------- Kind 30174 ------------->|                                |
  |   (Ride history backup)           |                                |
  |                                   |<---------- Kind 30174 ---------|
  |                                   |   (Driver history backup)      |
```

---

## Future: Payment Rails Integration

When payment rails are added, the following fields will be added:

### Kind 3173 (Ride Offer) - Additional Fields:
```json
{
  "paymentHash": "<sha256_of_preimage>",
  "walletType": "lightning" | "cashu"
}
```

### Kind 3174 (Ride Acceptance) - Additional Fields:
```json
{
  "hodlInvoice": "<bolt11_invoice>",  // For Lightning
  "nut14Proof": "<nut14_htlc_proof>"   // For Cashu
}
```

### Kind 30181 (Rider Ride State) - New Action Type:
```json
{
  "type": "preimage_share",
  "preimage": "<32_byte_preimage>",
  "timestamp": <unix_timestamp>
}
```

This preimage will be shared encrypted to the driver upon successful PIN verification, enabling trustless escrow settlement.
