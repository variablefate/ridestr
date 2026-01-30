# Ridestr Nostr Event Protocol

**Version**: 1.6
**Last Updated**: 2026-01-30

This document defines all Nostr event kinds used in the Ridestr rideshare application.

---

## Event Kind Summary

### Ride Protocol Events
| Kind | Name | Type | Author | Purpose |
|------|------|------|--------|---------|
| [30173](#kind-30173-driver-availability) | Driver Availability | Parameterized Replaceable | Driver | Broadcast driver availability and location |
| [30180](#kind-30180-driver-ride-state) | Driver Ride State | Parameterized Replaceable | Driver | Consolidated ride status + PIN submissions |
| [30181](#kind-30181-rider-ride-state) | Rider Ride State | Parameterized Replaceable | Rider | Location reveals + PIN verify + preimage share |
| [3173](#kind-3173-ride-offer) | Ride Offer | Regular | Rider | Request a ride (direct or broadcast) |
| [3174](#kind-3174-ride-acceptance) | Ride Acceptance | Regular | Driver | Accept ride with wallet pubkey for escrow |
| [3175](#kind-3175-ride-confirmation) | Ride Confirmation | Regular | Rider | Confirm accepted ride with pickup details |
| [3178](#kind-3178-chat-message) | Chat Message | Regular | Either | Encrypted chat during ride |
| [3179](#kind-3179-ride-cancellation) | Ride Cancellation | Regular | Either | Cancel an active ride |

### Profile Backup Events (NIP-44 encrypted to self)
| Kind | Name | Type | d-tag | Purpose |
|------|------|------|-------|---------|
| [30174](#kind-30174-ride-history-backup) | Ride History | Parameterized Replaceable | `rideshare-history` | Encrypted backup of ride details |
| [30177](#kind-30177-unified-profile-backup) | Unified Profile | Parameterized Replaceable | `rideshare-profile` | Vehicles, locations, settings |

### Wallet Events (NIP-60)
| Kind | Name | Type | Purpose |
|------|------|------|---------|
| [7375](#kind-7375-wallet-proofs) | Wallet Proofs | Regular | Cashu proofs backup |
| [17375](#kind-17375-wallet-metadata) | Wallet Metadata | Replaceable | Wallet settings/mint URL |

### Admin Configuration Events
| Kind | Name | Type | Purpose |
|------|------|------|---------|
| [30182](#kind-30182-admin-config) | Admin Config | Parameterized Replaceable | Platform settings (fare rates, mints, versions) |

### Discovery Events
| Kind | Name | Type | Purpose |
|------|------|------|---------|
| [1063](#kind-1063-tile-availability-nip-94) | File Metadata | NIP-94 | Routing tile availability (from official pubkey) |

### RoadFlare Events
| Kind | Name | Type | Purpose |
|------|------|------|---------|
| [30011](#kind-30011-followed-drivers) | Followed Drivers | Parameterized Replaceable | Rider's favorite drivers + their RoadFlare keys (NIP-44 to self) |
| [30012](#kind-30012-driver-roadflare-state) | Driver RoadFlare State | Parameterized Replaceable | Driver's RoadFlare keypair, followers, muted list (NIP-44 to self). Public `key_updated_at` tag. |
| [30014](#kind-30014-roadflare-location) | RoadFlare Location | Parameterized Replaceable | Driver's real-time location, 2-min interval, 5-min expiry (NIP-44 to RoadFlare pubkey) |
| [3186](#kind-3186-roadflare-key-share) | RoadFlare Key Share | Regular | DM sharing RoadFlare private key with follower, 5-min expiry (NIP-44 to follower) |
| [3187](#kind-3187-roadflare-follow-notify) | Follow Notification | Regular | Real-time follow notification (short expiry); p-tag query is primary discovery |
| [3188](#kind-3188-roadflare-key-ack) | Key Acknowledgement | Regular | Rider confirms key receipt to driver, 5-min expiry (NIP-44 to driver) |

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
["expiration", "<unix_timestamp>"]  // NIP-40, 30 minutes
```

**Content** (JSON):
```json
{
  "status": "available" | "unavailable",
  "geohash": "<precision_5_geohash>",
  "timestamp": <unix_timestamp>,
  "mint_url": "<cashu_mint_url>",
  "payment_methods": ["cashu", "fiat_cash"]
}
```

**Multi-Mint Fields** (Issue #13):
- `mint_url`: Driver's Cashu mint URL for multi-mint payment routing
- `payment_methods`: Array of supported payment methods (`cashu`, `lightning`, `fiat_cash`)

**Lifecycle**:
1. Published when driver goes online (`goOnline()`)
2. Republished every 5 minutes while online
3. Replaced with `status: unavailable` when going offline
4. Auto-expires via NIP-40 after 30 minutes

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
["expiration", "<unix_timestamp>"]  // 8 hours from creation
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
    },
    {
      "type": "settlement",
      "status": "claimed" | "refunded",
      "timestamp": <unix_timestamp>
    }
  ]
}
```

**History Action Types**:
- `status`: Driver status change (en_route_pickup, arrived, in_progress, completed, cancelled)
- `pin_submit`: Driver submitting PIN for verification
- `settlement`: Escrow settlement result (claimed by driver or refunded to rider)

**Lifecycle**:
1. Created when driver accepts ride
2. Updated (replaced) on each status change or PIN submission
3. History accumulates all actions chronologically
4. **CRITICAL**: Call `clearDriverStateHistory()` when starting new ride to prevent phantom actions
5. Expires 8 hours after creation

---

### Kind 30181: Rider Ride State

**Purpose**: Consolidated event containing rider's location reveals, PIN verifications, and preimage sharing for payment.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Rider

**d-tag**: Confirmation event ID (from Kind 3175)

**Tags**:
```
["d", "<confirmation_event_id>"]
["p", "<driver_pubkey>"]
["expiration", "<unix_timestamp>"]  // 8 hours from creation
```

**Content** (JSON, encrypted with NIP-44 to driver):
```json
{
  "confirmationEventId": "<confirmation_event_id>",
  "riderPubKey": "<rider_pubkey>",
  "driverPubKey": "<driver_pubkey>",
  "currentPhase": "AWAITING_DRIVER" | "AWAITING_PIN" | "VERIFIED" | "IN_RIDE",
  "history": [
    {
      "action": "location_reveal",
      "location_type": "pickup" | "destination",
      "location_encrypted": "<nip44_encrypted_location>",
      "at": <unix_timestamp>
    },
    {
      "action": "pin_verify",
      "status": "verified" | "rejected",
      "attempt": 1 | 2 | 3,
      "at": <unix_timestamp>
    },
    {
      "action": "preimage_share",
      "preimage_encrypted": "<nip44_encrypted_preimage>",
      "escrow_token_encrypted": "<nip44_encrypted_htlc_token>",
      "at": <unix_timestamp>
    }
  ]
}
```

**History Action Types**:
- `location_reveal`: Progressive location sharing (pickup when driver en route, destination after PIN)
- `pin_verify`: Result of PIN verification attempt
- `preimage_share`: **Payment rails** - shares HTLC preimage and escrow token with driver after successful PIN verification

**Lifecycle**:
1. Created when rider confirms ride
2. Updated on location reveals and PIN verifications
3. **CRITICAL**: `preimage_share` action published after successful PIN verification enables driver to claim payment
4. Expires 8 hours after creation

---

### Kind 30174: Ride History Backup

**Purpose**: Encrypted backup of complete ride details for user records.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Either party (both create their own backup)

**d-tag**: `rideshare-history`

**Tags**:
```
["d", "rideshare-history"]
```

**Content** (JSON array, self-encrypted with NIP-44):
```json
[
  {
    "id": "<ride_id>",
    "confirmationEventId": "<confirmation_event_id>",
    "riderPubKey": "<rider_pubkey>",
    "driverPubKey": "<driver_pubkey>",
    "riderName": "<rider_display_name>",
    "driverName": "<driver_display_name>",
    "pickupLocation": { "lat": <lat>, "lon": <lon>, "address": "<address>" },
    "destinationLocation": { "lat": <lat>, "lon": <lon>, "address": "<address>" },
    "fareSats": <fare_in_sats>,
    "status": "completed" | "cancelled" | "cancelled_claimed",
    "timestamp": <unix_timestamp>
  }
]
```

**Note**: No expiration - profile backup data persists indefinitely.

---

### Kind 30177: Unified Profile Backup

**Purpose**: Encrypted backup of user profile data including vehicles (driver), saved locations (rider), and app settings.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Either party

**d-tag**: `rideshare-profile`

**Tags**:
```
["d", "rideshare-profile"]
["t", "rideshare"]
```

**Content** (JSON, self-encrypted with NIP-44):
```json
{
  "vehicles": [
    {
      "id": "<uuid>",
      "make": "Toyota",
      "model": "Camry",
      "year": 2022,
      "color": "Silver",
      "licensePlate": "ABC123",
      "isPrimary": true
    }
  ],
  "savedLocations": [
    {
      "id": "<uuid>",
      "label": "Home",
      "latitude": 36.1699,
      "longitude": -115.1398,
      "address": "123 Main St, City, State",
      "isDefault": true
    }
  ],
  "settings": {
    "displayCurrency": "SATS" | "USD",
    "distanceUnit": "MILES" | "KILOMETERS",
    "notificationSoundEnabled": true,
    "notificationVibrationEnabled": true,
    "autoOpenNavigation": true,
    "alwaysAskVehicle": false,
    "customRelays": ["wss://relay.example.com"],
    "paymentMethods": ["cashu", "fiat_cash"],
    "defaultPaymentMethod": "cashu",
    "mintUrl": "https://mint.example.com"
  },
  "updated_at": <unix_timestamp>
}
```

**Note**: Replaces deprecated Kind 30175 (vehicles) and Kind 30176 (locations). No expiration - profile backup data persists indefinitely.

---

### Kind 7375: Wallet Proofs

**Purpose**: Cashu ecash proofs backup (NIP-60 standard).

**Type**: Regular Event

**Author**: Wallet owner

**Content**: NIP-44 encrypted Cashu proofs for cross-device wallet restoration.

**Note**: See [NIP-60](https://github.com/nostr-protocol/nips/blob/master/60.md) for full specification.

---

### Kind 17375: Wallet Metadata

**Purpose**: Wallet settings and mint URL (NIP-60 standard).

**Type**: Replaceable Event

**Author**: Wallet owner

**Content**: NIP-44 encrypted wallet metadata including mint URLs.

**Note**: See [NIP-60](https://github.com/nostr-protocol/nips/blob/master/60.md) for full specification.

---

### Kind 3173: Ride Offer

**Purpose**: Rider requests a ride from a specific driver (direct) or broadcasts to all nearby drivers.

**Type**: Regular Event

**Author**: Rider

**Tags** (Direct Offer):
```
["e", "<driver_availability_event_id>"]
["p", "<driver_pubkey>"]
["t", "rideshare"]
["expiration", "<unix_timestamp>"]  // 15 minutes
```

**Tags** (Broadcast):
```
["g", "<pickup_geohash_precision_3>"]
["g", "<pickup_geohash_precision_4>"]
["g", "<pickup_geohash_precision_5>"]
["t", "rideshare"]
["t", "ride-request"]  // Broadcast marker
["expiration", "<unix_timestamp>"]
```

**Content** (JSON, encrypted to driver for direct, plaintext for broadcast):
```json
{
  "fare_estimate": <fare_sats>,
  "destination": { "lat": <lat>, "lon": <lon>, "geohash": "<geohash>" },
  "approx_pickup": { "lat": <lat>, "lon": <lon>, "geohash": "<geohash>" },
  "pickup_route_km": <distance>,
  "pickup_route_min": <duration>,
  "ride_route_km": <distance>,
  "ride_route_min": <duration>,
  "destination_geohash": "<geohash_for_settlement>",
  "mint_url": "<rider_cashu_mint_url>",
  "payment_method": "cashu"
}
```

**Note (January 2026)**: `payment_hash` was removed from offers and moved to confirmation (Kind 3175). This ensures HTLC is locked with the correct driver wallet key AFTER acceptance.

**Payment Fields** (implemented):
- `destination_geohash`: Used for settlement location verification

**Multi-Mint Fields** (Issue #13):
- `mint_url`: Rider's Cashu mint URL for multi-mint payment routing
- `payment_method`: Payment method for this ride (`cashu`, `lightning`, `fiat_cash`)

**Note**: Precise coordinates are NOT shared in the offer. Only revealed progressively after confirmation.

---

### Kind 3174: Ride Acceptance

**Purpose**: Driver accepts a ride offer.

**Type**: Regular Event

**Author**: Driver

**Tags**:
```
["e", "<offer_event_id>"]
["p", "<rider_pubkey>"]
["t", "rideshare"]
["expiration", "<unix_timestamp>"]  // 10 minutes
```

**Content** (JSON, encrypted to rider):
```json
{
  "status": "accepted",
  "wallet_pubkey": "<driver_wallet_pubkey_for_p2pk>",
  "escrow_type": "cashu_nut14",
  "escrow_invoice": "<htlc_token_if_provided>",
  "escrow_expiry": <unix_timestamp>,
  "mint_url": "<driver_cashu_mint_url>",
  "payment_method": "cashu"
}
```

**Payment Fields** (implemented):
- `wallet_pubkey`: Driver's **wallet key** (separate from Nostr key) for P2PK escrow claims. **CRITICAL**: Rider must use this key, not the driver's Nostr pubkey, when locking HTLC escrow.
- `escrow_type`: Currently `"cashu_nut14"` for Cashu NUT-14 HTLC
- `escrow_invoice`: HTLC token or BOLT11 invoice (if provided upfront)
- `escrow_expiry`: When escrow expires and can be refunded to rider

**Multi-Mint Fields** (Issue #13):
- `mint_url`: Driver's Cashu mint URL for multi-mint payment routing
- `payment_method`: Confirms accepted payment method (`cashu`, `lightning`, `fiat_cash`)

---

### Kind 3175: Ride Confirmation

**Purpose**: Rider confirms the accepted ride. This event ID becomes the canonical ride identifier.

**Type**: Regular Event

**Author**: Rider

**Tags**:
```
["e", "<acceptance_event_id>"]
["p", "<driver_pubkey>"]
["t", "rideshare"]
["expiration", "<unix_timestamp>"]  // 8 hours
```

**Content** (JSON, encrypted to driver):
```json
{
  "precise_pickup": { "lat": <lat>, "lon": <lon>, "geohash": "<geohash>" },
  "payment_hash": "<sha256_of_preimage>",
  "escrow_token": "<htlc_token_if_same_mint>"
}
```

**Payment Fields** (January 2026 migration):
- `payment_hash`: SHA256 hash of preimage for HTLC escrow verification (moved from offer)
- `escrow_token`: HTLC token for same-mint payment (null for cross-mint)

**IMPORTANT**: The confirmation event ID is used as the `d-tag` for all subsequent ride state events (30180, 30181).

---

### Kind 3178: Chat Message

**Purpose**: Encrypted chat messages between rider and driver during a ride.

**Type**: Regular Event

**Author**: Either party

**Tags**:
```
["p", "<recipient_pubkey>"]
["e", "<confirmation_event_id>"]  // Links to ride
["expiration", "<unix_timestamp>"]  // 8 hours
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
["expiration", "<unix_timestamp>"]  // 24 hours
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

**Payment Implications**:
- If rider cancels BEFORE PIN verification: Escrow refunded to rider
- If rider cancels AFTER PIN verification: Driver can still claim payment (has preimage)
- If driver cancels: Escrow refunded to rider regardless of PIN status

---

### Kind 30182: Admin Config

**Purpose**: Platform-wide configuration settings published by the official Ridestr admin pubkey. Apps fetch this once on startup to get current fare rates, recommended mints, and version information.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Official admin pubkey (`da790ba18e63ae79b16e172907301906957a45f38ef0c9f219d0f016eaf16128`)

**d-tag**: `ridestr-admin-config`

**Tags**:
```
["d", "ridestr-admin-config"]
["t", "ridestr-admin"]
```

**Content** (JSON, plaintext):
```json
{
  "fare_rate_usd_per_mile": 1.85,
  "minimum_fare_usd": 5.0,
  "recommended_mints": [
    {
      "name": "Minibits",
      "url": "https://mint.minibits.cash/Bitcoin",
      "description": "Popular, widely used (~1% fees)",
      "recommended": true
    }
  ],
  "latest_version": {
    "rider": { "code": 10, "name": "1.0.10", "sha256": "abc123..." },
    "driver": { "code": 10, "name": "1.0.10", "sha256": "def456..." }
  }
}
```

**Fields**:
- `fare_rate_usd_per_mile`: Default fare rate in USD per mile
- `minimum_fare_usd`: Minimum fare in USD
- `recommended_mints`: Array of Cashu mint recommendations with metadata
- `latest_version`: Current app version info with SHA256 hashes for integrity verification

**Lifecycle**:
1. Published by admin when platform settings change
2. Apps fetch once on startup after relay connection
3. Cached locally for offline use
4. Falls back to hardcoded defaults if no event found or fetch fails

**Security**:
- Only events from the official admin pubkey are trusted
- Apps MUST verify the event's `pubkey` matches the hardcoded admin key
- Version SHA256 hashes can be used to verify APK integrity

**Note**: Kind 30182 is in the 30000-40000 parameterized replaceable range. This number was verified to not conflict with documented NIPs as of January 2026.

---

### Kind 1063: Tile Availability (NIP-94)

**Purpose**: Routing tile file metadata published by official Ridestr pubkey.

**Type**: Regular Event (NIP-94 File Metadata)

**Author**: Official tile publisher (`da790ba18e63ae79b16e172907301906957a45f38ef0c9f219d0f016eaf16128`)

**Tags**:
```
["x", "<sha256_hash>"]
["size", "<file_size_bytes>"]
["m", "application/x-tar"]
["url", "<blossom_download_url>"]
["region", "<region_id>"]
["title", "<human_readable_name>"]
["bbox", "<west>,<south>,<east>,<north>"]
["chunk", "<index>", "<sha256>", "<size>", "<url>"]  // For chunked files
```

**Discovery**: Apps subscribe to Kind 1063 from the official pubkey to discover available routing tiles. Results are cached locally with pull-to-refresh support.

---

## Encryption Standards

### NIP-44 Usage
All sensitive data (locations, PINs, chat messages, payment data) uses NIP-44 encryption:
- Encrypts to recipient's public key
- Decryptable only by recipient's private key
- Used for: ride offers, acceptances, confirmations, chat, ride states, preimage sharing

### Self-Encryption
For profile backups (ride history, vehicles, saved locations), the author encrypts to their own public key for secure storage and cross-device restoration.

---

## Expiration (NIP-40)

All events include expiration tags to prevent relay bloat:

| Event Type | Expiration | Constant |
|------------|------------|----------|
| Driver Availability | 30 minutes | `DRIVER_AVAILABILITY_MINUTES` |
| Ride Offer | 15 minutes | `RIDE_OFFER_MINUTES` |
| Ride Acceptance | 10 minutes | `RIDE_ACCEPTANCE_MINUTES` |
| Ride Confirmation | 8 hours | `RIDE_CONFIRMATION_HOURS` |
| Driver/Rider Ride State | 8 hours | `DRIVER_RIDE_STATE_HOURS` |
| Chat Message | 8 hours | `RIDESHARE_CHAT_HOURS` |
| Ride Cancellation | 24 hours | `RIDE_CANCELLATION_HOURS` |
| Profile Backups | No expiration | Profile data persists |

---

## Geohash Precision

| Precision | Size | Usage |
|-----------|------|-------|
| 3 | ~100mi x 100mi | Wide area broadcast matching |
| 4 | ~24mi x 24mi | Regional matching |
| 5 | ~5km x 5km | Driver discovery, broadcast requests |
| 6 | ~1km x 1km | Area-level location |
| 7 | ~150m x 150m | Approximate pickup/destination |
| 8+ | <40m | Precise location (encrypted only) |

---

## NIP-44 Encryption Matrix

Who encrypts to whom for each content type:

| Content | Encrypted To | Decrypted By | Event Kind |
|---------|--------------|--------------|------------|
| Ride Offer details | Driver pubkey | Driver | 3173 |
| Acceptance with wallet pubkey | Rider pubkey | Rider | 3174 |
| PIN code | Rider pubkey | Rider | 30180 (pin_submit action) |
| Precise pickup location | Driver pubkey | Driver | 30181 (location_reveal action) |
| Precise destination | Driver pubkey | Driver | 30181 (location_reveal action) |
| Preimage + escrow token | Driver pubkey | Driver | 30181 (preimage_share action) |
| Chat messages | Recipient pubkey | Recipient | 3178 |
| Ride history backup | Self (author) | Author on new device | 30174 |
| Profile backup (vehicles, locations, settings) | Self (author) | Author on new device | 30177 |

---

## Payment Flow (Cashu NUT-14 HTLC)

### Overview
Ridestr uses Cashu NUT-14 Hash Time-Locked Contracts (HTLC) for trustless payment escrow:

1. **Rider generates preimage** - Random 32-byte secret, SHA256 hashed to create `payment_hash`
2. **Rider includes payment_hash in offer** - Driver knows what hash to expect
3. **Driver sends wallet_pubkey in acceptance** - **CRITICAL**: This is the P2PK claim key, NOT Nostr key
4. **Rider locks HTLC after acceptance** - Uses driver's `wallet_pubkey` for P2PK condition
5. **Rider shares preimage after PIN verification** - Via Kind 30181 `preimage_share` action
6. **Driver claims at dropoff** - Uses preimage + wallet key signature

### Key Separation
**Nostr Key ≠ Wallet Key** - For security isolation:
- **Nostr key**: User identity, event signing, NIP-44 encryption
- **Wallet key**: P2PK escrow claims, BIP-340 Schnorr signatures

The driver's `wallet_pubkey` in Kind 3174 acceptance ensures the rider locks funds to the correct key.

### HTLC Preimage Storage (January 2026)
The preimage is now stored in `PendingHtlc` when calling `lockForRide()`:
- Enables future-proof refunds if mints enforce hash verification
- `refundExpiredHtlc()` uses stored preimage when available
- Falls back to zeros placeholder for old HTLCs (mint compatibility workaround)
- Preimage stored in same encrypted SharedPreferences as wallet keys

### Escrow Token Flow
```
RIDER                                    DRIVER
  |                                         |
  |-- Kind 3173 (offer, no payment_hash) -->|
  |                                         |
  |<-- Kind 3174 (wallet_pubkey) -----------|
  |                                         |
  |  [lockForRide(wallet_pubkey)]           |
  |                                         |
  |-- Kind 3175 (payment_hash+escrowToken)->|
  |                                         |
  |<-- Kind 30180 (pin_submit) -------------|
  |                                         |
  |  [PIN verified]                         |
  |                                         |
  |-- Kind 30181 (preimage_share) --------->|
  |      preimage_encrypted                 |
  |      escrow_token_encrypted             |
  |                                         |
  |                    [claimHtlcPayment()] |
  |                                         |
```

**Note**: As of January 2026, `payment_hash` is sent in confirmation (Kind 3175), not offer (Kind 3173). This ensures HTLC is locked with the correct driver wallet key AFTER acceptance.

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
  |   (Ride Offer, no payment_hash)   |----------- Kind 3173 --------->|
  |                                   |                                |
  |                                   |<---------- Kind 3174 ----------|
  |<--------- Kind 3174 --------------|   (Acceptance + wallet_pubkey) |
  |                                   |                                |
  |  [lockForRide(wallet_pubkey)]     |                                |
  |                                   |                                |
  |---------- Kind 3175 ------------->|                                |
  |   (Confirm + payment_hash)        |----------- Kind 3175 --------->|
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
  |   (PIN verified + preimage_share) |---------- Kind 30181 --------->|
  |                                   |                                |
  |                                   |<---------- Kind 30180 ---------|
  |<-------- Kind 30180 --------------|   (Driver state: IN_PROGRESS)  |
  |                                   |                                |
  |--------- Kind 30181 ------------->|                                |
  |   (Reveal destination)            |---------- Kind 30181 --------->|
  |                                   |                                |
  |                                   |<---------- Kind 30180 ---------|
  |<-------- Kind 30180 --------------|   (Driver state: COMPLETED)    |
  |                                   |   (settlement: claimed)        |
  |                                   |                                |
  |--------- Kind 30174 ------------->|                                |
  |   (Ride history backup)           |                                |
  |                                   |<---------- Kind 30174 ---------|
  |                                   |   (Driver history backup)      |
```

---

## Source Code Reference

| Event Kind | Source File | Key Methods |
|------------|-------------|-------------|
| 30173 | `DriverAvailabilityEvent.kt` | `create()`, `parse()` |
| 30180 | `DriverRideStateEvent.kt` | `create()`, `parse()` |
| 30181 | `RiderRideStateEvent.kt` | `create()`, `parse()`, `createPreimageShareAction()` |
| 30174 | `RideHistoryEvent.kt` | `create()`, `parse()` |
| 30177 | `ProfileBackupEvent.kt` | `create()`, `parseAndDecrypt()` |
| 3173 | `RideOfferEvent.kt` | `create()`, `createBroadcast()`, `decrypt()` |
| 3174 | `RideAcceptanceEvent.kt` | `create()`, `parse()` |
| 3175 | `RideConfirmationEvent.kt` | `create()`, `parse()` |
| 3178 | `RideshareChatEvent.kt` | `create()`, `parse()` |
| 3179 | `RideCancellationEvent.kt` | `create()`, `parse()` |
| 30182 | `AdminConfigEvent.kt` | `create()`, `parse()` |
| Constants | `RideshareEventKinds.kt` | Kind constants, tags, expiration helpers |

---

## Protocol Interoperability (Issue #13)

See [GitHub Issue #13](https://github.com/variablefate/ridestr/issues/13) for the full interoperability roadmap.

### Implemented (Phase 1 - January 2026)

**1. Payment Method Fields** ✅
- `payment_methods` array in profile backup (Kind 30177 settings)
- `payment_method` field in ride offers (Kind 3173) and acceptances (Kind 3174)
- `mint_url` field in availability (Kind 30173), offers (Kind 3173), and acceptances (Kind 3174)
- Standardized values: `cashu`, `lightning`, `fiat_cash`
- `PaymentMethod` enum in `RideshareEventKinds.kt`

### Planned Enhancements

**2. Multi-Mint Payment Bridge** (Phase 2-3)
- Cross-mint payment via Lightning bridge at pickup
- Fee estimation and display
- Same-mint detection for zero-fee HTLC flow

**3. Protocol Versioning**
- Add `protocol_version` field to all backup events
- Format: `NIP-014173-1.0`
- Apps can gracefully handle older/newer versions

**4. Extension Fields Convention**
- `ext_` prefix marks optional app-specific fields
- Apps MUST ignore unknown `ext_*` fields
- Example: `ext_strictapp_drivers_license`, `ext_strictapp_insurance_verified`
- Allows stricter apps to add requirements without breaking protocol

**5. Public Profile (Proposed Kind 30178)**
- Optional public driver/rider profile for discoverability
- Fields: display_name, rating, rides_completed, payment_methods, verification_level
- Cross-app reputation sharing

### Implementation Priority

| Priority | Enhancement | Status |
|----------|-------------|--------|
| P0 | `payment_methods` in profile, `payment_method` in offers | ✅ COMPLETE |
| P0 | `mint_url` in availability, offers, acceptances | ✅ COMPLETE |
| P1 | Multi-mint Lightning bridge | 🚧 IN PROGRESS |
| P2 | `protocol_version`, `ext_*` convention documentation | Planned |
| P3 | Public profile event (Kind 30178) | Planned |

---

## Deprecated Events

The following event kinds were deprecated and replaced by Kind 30177 (Unified Profile):

| Kind | Name | Replaced By |
|------|------|-------------|
| 30175 | Vehicle Backup | Kind 30177 |
| 30176 | Saved Locations Backup | Kind 30177 |

New implementations should use Kind 30177 for all profile data.

---

## RoadFlare Event Definitions

RoadFlare enables riders to build a personal rideshare network from drivers they've favorited. Drivers broadcast their location encrypted so only followers can decrypt.

### Kind 30011: Followed Drivers

**Purpose**: Rider's personal list of favorite drivers, including their RoadFlare decryption keys.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Rider

**d-tag**: `roadflare-drivers`

**Tags**:
```
["d", "roadflare-drivers"]
["t", "roadflare"]
```

**Content** (NIP-44 encrypted to self):
```json
{
  "drivers": [
    {
      "pubkey": "driver_hex_pubkey",
      "name": "John",
      "addedAt": 1234567890,
      "note": "Toyota Camry, great driver",
      "roadflareKey": {
        "privateKey": "hex_private_key",
        "publicKey": "hex_public_key",
        "version": 3
      }
    }
  ],
  "updated_at": 1234567890
}
```

**Key Fields**:
- `roadflareKey`: The shared keypair received from driver via Kind 3186. Allows decryption of driver's location broadcasts (Kind 30014).

---

### Kind 30012: Driver RoadFlare State

**Purpose**: Driver's complete RoadFlare state - keypair, followers, muted list.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Driver

**d-tag**: `roadflare-state`

**Tags**:
```
["d", "roadflare-state"]
["t", "roadflare"]
["key_version", "3"]  // Quick staleness check without decryption
```

**Content** (NIP-44 encrypted to self):
```json
{
  "roadflareKey": {
    "privateKey": "hex_private_key",
    "publicKey": "hex_public_key",
    "version": 3,
    "createdAt": 1234567890
  },
  "followers": [
    {
      "pubkey": "rider_pubkey",
      "name": "Alice",
      "addedAt": 1234567890,
      "approved": true,
      "keyVersionSent": 3
    }
  ],
  "muted": [
    { "pubkey": "rider_pubkey", "mutedAt": 1234567890, "reason": "spam" }
  ],
  "lastBroadcastAt": 1234567890,
  "updated_at": 1234567890
}
```

**Follower Approval Flow**:
- `approved: false` = pending request, driver must approve
- `approved: true` = approved, key sent, receives location broadcasts
- Muted followers trigger key rotation (excluded from new key distribution)

---

### Kind 30014: RoadFlare Location

**Purpose**: Driver's real-time location, encrypted to the RoadFlare keypair so all followers can decrypt.

**Type**: Parameterized Replaceable Event (NIP-33)

**Author**: Driver

**d-tag**: `roadflare-location`

**Tags**:
```
["d", "roadflare-location"]
["t", "roadflare-location"]
["status", "online"]  // online | on_ride | offline
["key_version", "3"]
["expiration", "1234567890"]  // 5-minute TTL
```

**Content** (NIP-44 encrypted to driver's `roadflarePubKey`):
```json
{
  "lat": 40.7128,
  "lon": -74.0060,
  "timestamp": 1234567890,
  "status": "online",
  "onRide": false
}
```

**Encryption Model**:
- Driver encrypts: `nip44Encrypt(content, roadflarePubKey)`
- Followers decrypt: `nip44Decrypt(content, driverIdentityPubKey)` using stored `roadflarePrivateKey`
- ECDH is commutative: `ECDH(driver_priv, roadflare_pub) == ECDH(roadflare_priv, driver_pub)`

**Status Values**:
| Status | Meaning |
|--------|---------|
| `online` | Available for RoadFlare requests |
| `on_ride` | Currently giving a ride |
| `offline` | Driver has gone offline |

---

### Kind 3186: RoadFlare Key Share

**Purpose**: Ephemeral DM from driver to follower sharing the RoadFlare private key.

**Type**: Regular Event (with expiration)

**Author**: Driver

**Tags**:
```
["p", "follower_pubkey"]
["t", "roadflare-key"]
["expiration", "<unix_timestamp>"]  // 5 minutes
```

**Content** (NIP-44 encrypted to follower's identity pubkey):
```json
{
  "roadflareKey": {
    "privateKey": "hex_private_key",
    "publicKey": "hex_public_key",
    "version": 3,
    "keyUpdatedAt": 1706300000
  },
  "keyUpdatedAt": 1706300000,
  "driverPubKey": "driver_identity_pubkey"
}
```

**When Sent**:
1. Driver approves a new follower
2. Key rotation (sent to all remaining non-muted followers)

**Key Fields**:
- `keyUpdatedAt`: Timestamp when key was last rotated. Used by rider to detect stale keys.

---

### Kind 3187: RoadFlare Follow Notification

**Purpose**: Real-time notification to driver when a rider follows them. Used for immediate UX feedback.

**Type**: Regular Event (with short expiration)

**Design Note**: This event provides real-time notification for immediate driver feedback. The primary discovery mechanism is p-tag query on Kind 30011 (which persists). Kind 3187 is the "belt" to Kind 30011's "suspenders" - both ensure the driver sees new followers promptly.

**Author**: Rider

**Tags**:
```
["p", "driver_pubkey"]
["t", "roadflare-follow"]
```

**Content** (NIP-44 encrypted to driver):
```json
{
  "action": "follow",  // or "unfollow"
  "riderName": "Alice",
  "timestamp": 1234567890
}
```

---

### Kind 3188: RoadFlare Key Acknowledgement

**Purpose**: Ephemeral confirmation from rider to driver after receiving and storing the RoadFlare key.

**Type**: Regular Event (with expiration)

**Author**: Rider

**Tags**:
```
["p", "driver_pubkey"]
["t", "roadflare-key-ack"]
["expiration", "<unix_timestamp>"]  // 5 minutes
```

**Content** (NIP-44 encrypted to driver):
```json
{
  "keyVersion": 3,
  "keyUpdatedAt": 1706300000,
  "status": "received",
  "riderPubKey": "rider_pubkey"
}
```

**Status Values**:
| Status | Meaning | Driver Action |
|--------|---------|---------------|
| `received` | Key successfully stored | None (confirmation) |
| `stale` | Rider's stored key is outdated | Re-send current key via Kind 3186 |

**When Sent**:
- `status="received"`: Rider receives Kind 3186 → stores key → acknowledges receipt
- `status="stale"`: Rider detects outdated key → requests refresh (rate-limited: 1/hour/driver)

**Key Refresh Flow** (January 2026):
1. Rider subscribes to driver's Kind 30012, compares `key_updated_at` tag vs stored timestamp
2. If driver's timestamp > stored → key is stale
3. Rider sends Kind 3188 with `status="stale"` and their stored `keyUpdatedAt`
4. Driver verifies: pubkey matches claimed `riderPubKey`, follower is approved + not muted
5. If valid, driver re-sends current key via Kind 3186

---

## RoadFlare Flow Diagram

```
RIDER                              NOSTR RELAY                           DRIVER
  |                                      |                                   |
  |  [Add driver to favorites]           |                                   |
  |--------- Kind 30011 --------------->|  (Update followed list with p-tag) |
  |                                      |                                   |
  |                                      |    [Driver queries p-tags]        |
  |                                      |    [Sees new follower as pending] |
  |                                      |                                   |
  |                                      |    [Driver clicks "Accept"]       |
  |                                      |<---------- Kind 3186 -------------|
  |<-------- Kind 3186 -----------------|  (Key share, 5-min expiry)         |
  |                                      |                                   |
  |  [Store roadflareKey + keyUpdatedAt] |                                   |
  |--------- Kind 3188 ---------------->|  (Key acknowledgement)             |
  |                                      |----------- Kind 3188 ------------>|
  |                                      |    [Driver confirms key received] |
  |                                      |<---------- Kind 30012 ------------|
  |                                      |  (Update state + key_updated_at)  |
  |                                      |                                   |
  |  [Subscribe to driver location]      |                                   |
  |                                      |<---------- Kind 30014 ------------|
  |<-------- Kind 30014 ----------------|  (Location, 2-min interval)        |
  |  [Decrypt with roadflareKey]         |                                   |
  |  [Show real-time driver location]    |                                   |
  |                                      |                                   |
  |  [Check driver's key_updated_at]     |                                   |
  |  [If stale: show "Key Outdated"]     |                                   |
  |                                      |                                   |
  |  [Remove driver from favorites]      |                                   |
  |--------- Kind 30011 --------------->|  (Remove from followed list)       |
  |                                      |    [Driver queries p-tags]        |
  |                                      |    [Sees follower removed]        |
  |                                      |<---------- Kind 30012 ------------|
```

### Stale Key Detection

Riders detect stale keys by comparing:
- Stored `RoadflareKey.keyUpdatedAt` (from Kind 3186)
- Driver's public `key_updated_at` tag (from Kind 30012, no decryption needed)

If driver's timestamp > stored timestamp, the key is stale and the rider shows "Key Outdated" indicator. The driver will see this rider as pending again and can re-send the key.
