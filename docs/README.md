# Ridestr Documentation

Ridestr is a decentralized rideshare platform built on Nostr with Cashu payments.

---

## Features

| Feature | Status |
|---------|--------|
| **Ride Matching** | ✅ Direct offers, broadcast requests, geohash filtering |
| **RoadFlare Network** | ✅ Personal driver network with encrypted location sharing |
| **Cashu Wallet** | ✅ Deposits, withdrawals, cross-device sync (NIP-60) |
| **HTLC Escrow** | ✅ Trustless ride payments with automatic settlement |
| **Profile Sync** | ✅ Vehicles, saved locations, settings backed up to Nostr |
| **Offline Routing** | ✅ Valhalla-based turn-by-turn directions |
| **Accessibility** | ✅ TalkBack support with semantic roles and labels |

---

## Documentation Index

### For Users
- **Getting Started** - Install from Zapstore or build from source
- **RoadFlare Guide** - Build your personal driver network

### Protocol
- [NOSTR_EVENTS.md](protocol/NOSTR_EVENTS.md) - All Nostr event kinds used by Ridestr
- [DEPRECATION.md](protocol/DEPRECATION.md) - Deprecated events and migration notes

### Architecture
- [OVERVIEW.md](architecture/OVERVIEW.md) - System design and data flow
- [STATE_MACHINES.md](architecture/STATE_MACHINES.md) - Ride state diagrams
- [PAYMENT_ARCHITECTURE.md](architecture/PAYMENT_ARCHITECTURE.md) - Cashu wallet and HTLC escrow
- [CONNECTIONS.md](CONNECTIONS.md) - Module dependency map

### Developer Guides
- [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) - **Start here** - Quick navigation, key files, patterns
- [DEBUGGING.md](guides/DEBUGGING.md) - Debugging principles and patterns
- [ADDING_NOSTR_SYNC.md](guides/ADDING_NOSTR_SYNC.md) - Adding new synced data types
- [PAYMENT_SAFETY.md](architecture/PAYMENT_SAFETY.md) - Payment code modification checklist

### Code Reference
- [RIDER_VIEWMODEL.md](viewmodels/RIDER_VIEWMODEL.md) - Rider app state management
- [DRIVER_VIEWMODEL.md](viewmodels/DRIVER_VIEWMODEL.md) - Driver app state management

---

## Project Structure

```
ridestr/
├── rider-app/              # Rider Android app (Ridestr)
│   ├── viewmodels/         # RiderViewModel - ride state machine
│   ├── ui/screens/         # RiderModeScreen, RoadflareTab, HistoryScreen
│   └── service/            # RiderActiveService (foreground)
│
├── drivestr/               # Driver Android app (Drivestr)
│   ├── viewmodels/         # DriverViewModel - driver state machine
│   ├── ui/screens/         # DriverModeScreen, RoadflareTab, EarningsScreen
│   └── service/            # DriverOnlineService, RoadflareListenerService
│
├── common/                 # Shared code (~65% of codebase)
│   ├── nostr/              # Nostr protocol
│   │   ├── events/         # 20+ event types (rides, RoadFlare, backup)
│   │   ├── relay/          # WebSocket connection management
│   │   └── keys/           # Secure key storage
│   ├── payment/            # Cashu wallet
│   │   └── cashu/          # Mint operations, NIP-60 sync
│   ├── roadflare/          # Personal driver network
│   ├── routing/            # Valhalla offline routing
│   ├── sync/               # Profile sync adapters
│   ├── state/              # Ride state machine
│   ├── data/               # Repositories
│   ├── settings/           # User preferences, remote config
│   ├── notification/       # Alerts and sounds
│   ├── location/           # Geocoding
│   ├── bitcoin/            # Price service
│   └── ui/                 # Shared screens and components
│
└── docs/                   # Documentation
```

---

## Building

```bash
./gradlew :rider-app:assembleDebug    # Build rider app
./gradlew :drivestr:assembleDebug     # Build driver app
```

---

## Links

- [GitHub Repository](https://github.com/variablefate/ridestr)
- [Zapstore](https://zapstore.dev) - Install pre-built APKs
