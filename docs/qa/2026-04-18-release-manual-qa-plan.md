# Ridestr Release Manual QA Plan — 2026-04-18

**Target release window:** v0.2.7 (next cut after `v0.2.6`, tagged 2026-02-17 at commit `853744d`).
**Head under test:** `main` @ `1139022` (refactor: extract RideTab state-binding components, #73).
**Commits since last tag:** ~68 non-merge commits across ~2 months (not 1 — double-check versioning).
**Apps in scope:** `rider-app`, `drivestr` (two production Android clients).
**Out of scope:** `roadflare-rider` (broken WIP per product call, not shipping), roadflare-ios sibling app (PRs #58/#59/#61 on that tree are not built from this repo). RoadFlare protocol is still in scope from the **driver-side** (Kind 30014 publishing, follower lifecycle on drivestr) and from **rider-app's** integration (RoadflareRiderCoordinator + RoadflareTab UI).

**Estimated effort:** 3h 15m minimum, 4h 45m if every section is exercised thoroughly. Freed budget from dropping roadflare-rider reinvested into deeper rider-app + drivestr coverage (chat, notifications, extra HTLC edges).

| Section | Min | Max | Stop-ship? |
|---|---|---|---|
| 1. Pre-flight | 15m | 20m | n/a |
| 2. Critical-path smoke | 30m | 45m | **YES — any fail = stop** |
| 3. Per-app regression | 90m | 130m | Coordinator-level fails = stop |
| 4. Feature coverage since v0.2.6 | 40m | 55m | Payment/protocol fails = stop |
| 5. Cross-app / multi-device | 35m | 45m | Payment fails = stop |
| 6. Release gates (summary) | 5m | 10m | n/a |

**Skipped deliberately:** relay-side infra, iOS, backend admin tooling, load/perf beyond visual smoothness, automated unit tests (they already ran in CI for #69/#70). This plan is the on-device bake-in.

---

## 1. Pre-flight (15–20 min)

**Devices:** minimum 2 Android devices (physical preferred) — one "rider" (rider-app), one "driver" (drivestr). A third device or emulator with a second driver key is useful for the broadcast race test (§5.2) and the multi-device follower lifecycle test (§3.2) but is not strictly required.

**Builds to install:**

```bash
./gradlew :rider-app:assembleDebug
./gradlew :drivestr:assembleDebug
```

APKs land under `*/build/outputs/apk/debug/`. `roadflare-rider` is not being QA'd or shipped in this release — skip its build.

- [ ] Both devices have Google Play Services (for FCM if used) and location permission granted
- [ ] Mock-location disabled on physical devices (breaks broadcast geohash tests)
- [ ] Known-good relay set reachable — confirm from one device that a ping event round-trips before starting
- [ ] Two fresh Nostr keys generated (rider, driver); rider key funded with enough sats to cover 3–4 test rides + fiat-rail ride
- [ ] Driver Cashu wallet has a non-zero balance AND a declared `wallet_pubkey` in acceptance handshake (otherwise HTLC tests will silently degrade)
- [ ] Admin config (Kind 30182) reachable — driver app should show current fare rates at startup; if blank, investigate before continuing

**Upgrade-path seeding (required for the migration tests in §3):**

- [ ] Before installing today's builds, install the last tagged release `v0.2.6` APKs on one rider device AND one driver device, sign in, create 1 vehicle (driver), save 2 locations (rider), send one completed test ride so `Kind 30177` and ride history exist. Leave app data intact.
- [ ] Then install today's build OVER the `v0.2.6` install (do NOT wipe data). This is the real-world upgrade path and is the only way to catch `SettingsManager → SettingsRepository + DataStore` migration regressions from commit `9ebb5f3` (Issue #52).

**Feature flags / toggles:**
- No runtime flags are expected to change behaviour between v0.2.6 and HEAD; if any are added, note them here and retest §4.

---

## 2. Critical-path smoke (30–45 min, must all pass)

If **any** item in this section fails, stop and fix before continuing.

### 2.1 Rider fresh install + onboarding
- [ ] **Given** a clean rider device, **When** the app is launched and a new key is generated through onboarding, **Then** profile sync completes (Wallet → Profile → RideHistory order), home screen loads without errors, and both Kind 17375 + Kind 30177 are visible in a relay inspector.

### 2.2 Driver fresh install + go online
- [ ] **Given** a clean drivestr install with a key imported, **When** "Go Online" is tapped, **Then** Kind 30173 publishes within ~5 s, the status pill shows ONLINE, and a foreground service notification appears.

### 2.3 End-to-end broadcast ride, Cashu SAME_MINT
- [ ] **Given** driver is ONLINE within ~1 km and rider has funds at the same mint, **When** rider broadcasts a ride, driver accepts, rider auto-confirms, PIN verifies at pickup, and ride completes, **Then** HTLC is claimed by driver (balance moves), both apps land on the completion screen, Kind 3175 carries a `paymentHash`, and the driver's 30173 was NIP-09 deleted at accept time.

### 2.4 Direct offer to a specific driver
- [ ] **Given** driver is ONLINE, **When** rider sends a direct offer via the driver list, **Then** driver sees the offer (NIP-44 encrypted 3173 payload), accept → confirm → HTLC lock → PIN → complete works, and pre-confirmation driver-availability monitoring (`subscribeToSelectedDriverAvailability`) shuts down cleanly after acceptance (no spurious "Driver Unavailable" dialog — guards PR #70 review finding #4).

### 2.5 RoadFlare driver-side follower lifecycle
- [ ] **Given** drivestr is signed in with ROADFLARE_ONLY mode on, **When** rider-app adds the driver as a follower (publishes Kind 30011 reference + follow notification), **Then** drivestr receives the follow, user approves it, Kind 3186 key share publishes, Kind 30014 encrypted location broadcasts start, and rider-app's `RoadflareRiderCoordinator` stores the key + decrypts at least one location update. (This is the RoadFlare integration surface rider-app carries — the dedicated RoadflareTab + AddDriverScreen in `rider-app/ui/screens/`.)

### 2.6 Fiat-rail ride (non-Cashu, ADR-0008)
- [ ] **Given** driver advertises Zelle (or other fiat) in `fiat_payment_methods`, **When** rider sends an offer with a fiat payment method, **Then** Kind 3173 carries `fare_fiat_amount` + `fare_fiat_currency`, driver's UI shows the authoritative USD figure (no sats→USD drift), and completion does NOT attempt HTLC claim.

### 2.7 Authoritative fare display (PR #61)
- [ ] **Given** an offer with both fiat fields present, **When** driver inspects the offer, **Then** the displayed fare matches the rider's UI exactly (to the cent). If the offer is boosted, fiat fields drop and driver falls back to sats→USD — that drift is expected, confirm it only appears after boost.

### 2.8 Escrow-bypass block (rider-side)
- [ ] **Given** SAME_MINT Cashu ride, **When** `lockForRide()` is forced to fail (e.g., airplane-mode toggle during lock), **Then** rider app shows the retry/cancel dialog with a 15-second deadline and does NOT proceed to confirm. Driver's "Complete Anyway" button is hidden for SAME_MINT.

### 2.9 Kind 3179 ride cancellation
- [ ] **Given** an active ride in MATCHED stage, **When** either party cancels, **Then** Kind 3179 publishes, counterparty lands on a clean cancel screen, both apps return to IDLE/ONLINE, and `history` arrays are cleared (no stale driver/rider state leaking into next ride).

### 2.10 Logout on both apps
- [ ] **Given** a signed-in app, **When** user logs out, **Then** `LogoutManager` runs all 16 cleanup operations (subscriptions close, jobs cancel, SharedPrefs for backup-exclusion keys remain cleared, wallet state unbinds). Verify by signing back in on both apps and seeing the onboarding screen, not a stale session.

---

## 3. Per-app regression (90–130 min)

Structured by the coordinator/component each refactor PR produced. Every sub-section tests **specific behaviours** that moved across a module boundary today.

### 3.1 rider-app — PR #70 (OfferCoordinator, PaymentCoordinator, RoadflareRiderCoordinator) — 40–60 min

Each item corresponds to a real extraction surface or a review-pass regression the PR's reviewer already caught and fixed. Regression-locking these is the whole point.

**OfferCoordinator (`common/.../coordinator/OfferCoordinator.kt`):**
- [ ] `sendRideOffer` (direct): offer publishes with NIP-44 encrypted payload, driver-side parsing succeeds.
- [ ] `broadcastRideRequest`: privacy dialog shows first, then plaintext 3173 goes out at ~1 km geohash approximation.
- [ ] `sendRoadflareOffer` (single): p-tag + roadflare tag both present; only the targeted follower driver decrypts it.
- [ ] `sendRoadflareToAll`: batch proximity-ordered; multiple 3173 events publish; rate-limited client-side (confirm no relay "rate limit" bounce).
- [ ] Batch cancellation: after `sendRoadflareToAll`, rider cancels — Kind 5 deletions publish for each outstanding offer; drivers see them disappear.
- [ ] Pre-confirmation driver monitoring (Issue #22): with direct offer outstanding, driver goes offline → rider shows "Driver Unavailable" dialog → auto-cancels → returns to IDLE. **Then** verify the dialog does NOT appear after acceptance is already received (PR #70 review finding #4 — `onAcceptanceHandled()` must be called).
- [ ] `pingDriver()` (Kind 3189): rider pings a followed driver who is OFFLINE → driver receives a silent heads-up notification; second ping within 30 s is deduped (DriverPingRateLimiter).

**PaymentCoordinator (`common/.../coordinator/PaymentCoordinator.kt`):**
- [ ] HTLC lock on auto-confirm: `lockForRide()` fires with driver's `wallet_pubkey` from Kind 3174 (not rider's Nostr key — verify by inspecting mint swap request if possible, or by confirming driver can claim).
- [ ] Kind 3175 publishes with `paymentHash` AFTER acceptance, not before (architectural rule — paymentHash is in 3175, NOT 3173).
- [ ] Escrow-bypass gating: SAME_MINT ride where `lockForRide()` fails → retry dialog shows, cancel is available, 15 s deadline enforced. See §2.8 as smoke.
- [ ] Payment-path persistence across process death: start a SAME_MINT ride, force-kill the rider app between acceptance and PIN, relaunch → `paymentPath` is still SAME_MINT (not UNKNOWN), "Complete Anyway" on driver side is still hidden.
- [ ] Completion flow — claim success: HTLC is claimed by driver, rider sees completion screen, `markHtlcClaimedByPaymentHash()` fires, balance refreshes.
- [ ] Completion flow — **claimSuccess=null branch** (PR #70 review finding #3): if driver claim result is null on a SAME_MINT ride, HTLC stays LOCKED (do NOT call `clearHtlcRideProtected()`). Verify by forcing the null branch (e.g., driver has no wallet) and confirming rider sees the HTLC still listed as active in wallet diagnostics.
- [ ] Cancellation refund: driver cancels a confirmed ride with active HTLC → rider's wallet refreshes, expired HTLC refunds back to rider (check for auto-refund guard from commit `ae77e25`).
- [ ] Same-ride cancel guard (double-confirmation race): with two relays, confirm the acceptance reaches rider twice → only one `autoConfirmRide()` actually publishes 3175 (AtomicBoolean `confirmationInFlight` CAS).
- [ ] Retry-escrow CAS: during the retry dialog, double-tap "Retry" → only one retry fires.
- [ ] **Insufficient-funds dialog** (PR #70 review finding #5): send an offer where fare > balance → dialog shows with the correct amount, pending driver is queued until deposit clears. Prior bug: event fired but no dialog state was set.
- [ ] **Coordinator state restored after process death** (PR #70 review finding #6): start a ride, kill rider app post-acceptance, relaunch → `paymentCoordinator.restoreRideState()` runs (driver event dedup sets repopulate, PIN attempt counter preserved, no re-processing of already-handled driver events).
- [ ] **Double destination reveal removed** (PR #70 review finding #2): rider enters correct PIN → Kind 30181 publishes with DESTINATION action exactly ONCE (not twice). Verify on a relay inspector.
- [ ] Post-confirm ack timeout: after 3175 publishes, if driver never publishes 30180 within 60 s, rider sees timeout handling (not indefinite spinner).
- [ ] CROSS_MINT bridge at pickup: rider and driver on different mints → on PIN verify, rider melts at own mint, Lightning invoice pays driver's mint, driver's wallet balance goes up after the bridge clears.

**RoadflareRiderCoordinator (`common/.../coordinator/RoadflareRiderCoordinator.kt`):**
- [ ] Kind 3186 listener: driver sends fresh key → rider app stores it and can decrypt new Kind 30014 events immediately.
- [ ] Kind 3188 key-ack: rider sends `status="received"` on new key intake; rider sends `status="stale"` after decrypt failure and driver re-sends within a few seconds.
- [ ] Kind 3189 ping send path: rider pings driver — HMAC is computed with `roadflare-ping` key, 30-minute TTL expire tag present.
- [ ] **viewModelScope not cancelled** (PR #70 review finding #1): trigger whatever flow calls `coordinator.destroy()` (navigation out, likely) — app does NOT freeze, other coroutines (location, nostr subs) keep running. Previously `scope.cancel()` nuked the VM scope.

**Rider-app UI regression (PR #68 extractions):**
- [ ] HistoryScreen: `HistoryList`, `HistoryFilterBar`, `HistoryEntryCard`, `HistoryStatsCard` all render; filter by role (rider/driver), by date, verify stats sum correctly.
- [ ] RiderModeScreen: `RideRequestPanel` address search works (Photon geocoding from commit `aa8c161`, with privacy rounding), `ActiveRidePanel` shows driver info + chat, `RiderPinCard` shows correct PIN for pickup, `CompletionPanel` renders fare + rating, `CancelDialogStack` opens the right confirmation variant per stage.
- [ ] RoadflareTab (`rider-app/.../ui/screens/RoadflareTab.kt`): followed-drivers list renders with live presence badges (ONLINE/ROADFLARE_ONLY/ON_RIDE/OFFLINE); tap a driver → live map location appears within a few seconds of a fresh Kind 30014 event.
- [ ] AddDriverScreen (`rider-app/.../ui/screens/AddDriverScreen.kt`): paste an npub or scan a QR → follow request publishes Kind 30011 reference; driver receives follow notification.
- [ ] Stale-key detection in RoadflareTab: with a key marked stale (force by clearing or corrupting the stored key), the tab triggers Kind 3188 `status="stale"` and the refreshed key from the driver lands in `MainActivity`'s Kind 3188 handler → decrypt resumes.

**Rider-app notifications + chat:**
- [ ] Kind 3178 encrypted chat: rider sends a chat from `ActiveRidePanel` during MATCHED → driver receives it, replies → rider sees the reply. Verify NIP-44 encryption (no plaintext on relay).
- [ ] Foreground service notification during active ride: starts on MATCHED, updates on stage transitions, dismisses on COMPLETED/CANCELLED. No orphan service after `clearRide()`.
- [ ] Process death during chat: force-kill mid-chat → relaunch → scrollback preserved (or re-fetched from relay) without duplication.

### 3.2 drivestr — PR #69 (AvailabilityCoordinator, AcceptanceCoordinator, RoadflareDriverCoordinator) — 30–45 min

**AvailabilityCoordinator:**
- [ ] Periodic Kind 30173 loop: while online, replaceable event publishes every throttle interval; time + distance guards both respected (walk around the room — no spam publishes on tiny moves).
- [ ] `publishAvailability(track = true)`: the published event ID is tracked; subsequent `deleteAllAvailabilityEvents()` on go-offline issues NIP-09 for it. Verify by sniffing the Kind 5.
- [ ] `publishAvailability(track = false)`: ROADFLARE_ONLY presence event is NOT tracked (prior review caught an orphan). When toggling ROADFLARE_ONLY → OFFLINE, no orphan NIP-09 target.
- [ ] Go-offline NIP-09 batch delete: after 3+ periodic broadcasts, tap Go Offline → a single NIP-09 event with multiple `e` tags is published.
- [ ] NIP-09 on ride-accept: accepting an offer triggers deletion of current availability event (since driver is now busy); no new availability publishes until ride completion.
- [ ] AVAILABLE vs ROADFLARE_ONLY vs OFFLINE: toggle all three modes — Kind 30173 presence and Kind 30014 presence match the table in CLAUDE.md.
- [ ] `clearBroadcastState()` resets all three fields (last-time, last-geohash, last-id).

**AcceptanceCoordinator:**
- [ ] Direct offer accept: Kind 3174 includes `wallet_pubkey`, `mint_url`, `payment_method`; rider side sees these and uses wallet_pubkey for HTLC lock.
- [ ] Broadcast first-acceptance-wins: two drivers accept the same broadcast within seconds → only the FIRST acceptance's 3174 wins; the second returns `AcceptBroadcastOutcome.DuplicateBlocked`. Verify with a second device: the "losing" driver sees a clean "offer taken" state, not a crash, not a stuck spinner.
- [ ] `AcceptBroadcastOutcome.PublishFailed` path: simulate publish failure (airplane mode just before accept) → distinct error state, CAS gate resets so retry is possible.
- [ ] PaymentPath derivation in `AcceptanceCoordinator`: confirm the three paths (SAME_MINT, CROSS_MINT, FIAT_CASH) each resolve correctly based on mint_url + payment_method cross-check.
- [ ] `CancellationException` during acceptance (back button mid-flight): broadcast gate reset happens, coroutine rethrows cleanly, app is usable after.
- [ ] **PaymentStatus consolidation** (`drivestr/PaymentStatus` merged into `common/payment/PaymentModels.PaymentStatus`): walk through every driver-side UI state that reads PaymentStatus — lock status on active ride card, payment indicator on completion, error banners. All should render the correct copy.
- [ ] `handleConfirmationTimeout` with `currentLocation == null`: force this edge (disable location on driver) → `resetBroadcastGate()` fires so next broadcast offer can be accepted.

**RoadflareDriverCoordinator:**
- [ ] Union-merge Kind 30012 state sync: approve follower on device A, on device B (same driver key) the follower appears as APPROVED without loss of other device's pending list (union merge, not overwrite).
- [ ] `mergeFollowerLists`: approved logical-OR, `keyVersionSent` max-with-clamp, `addedAt` min. Hard to isolate end-to-end, but exercise by: approve on A, mute on B, sync → follower is muted (not unapproved, never auto-unmute).
- [ ] `mergeMutedLists` never auto-unmutes.
- [ ] Kind 30014 location broadcast lifecycle: `setOnRide()` publishes ON_RIDE status, stopping normal location broadcasts; on ride end, resumes broadcast.
- [ ] Final OFFLINE Kind 30014: on Go Offline, a final event with status=OFFLINE publishes (so followers see the driver drop off the map immediately).
- [ ] Kind 3189 driver-ping receiver (Issue #4, commit `a5758aa`): approved follower pings driver → silent notification appears; unapproved pubkey pings → rejected (HMAC mismatch); second ping within 30 s is deduped; >2 pings in 10 min globally → throttled.

**drivestr UI regression (PR #68 extractions):**
- [ ] DriverModeScreen: `AvailabilityControls` toggles the three modes correctly, `OfferInbox` lists incoming offers, `RideOfferCard` renders fare + route + driver/rider names + the payment-method compatibility indicator (Issue #46), `BroadcastRideRequestCard` renders broadcast-specific context, `NoCommonPaymentMethodDialog` appears when driver and rider share no payment method, `RoadflareFollowerList` shows PENDING/APPROVED/KEY_SENT badges.
- [ ] Offer card smoothness under load: with 3+ incoming offers, scrolling is smooth; no jank on new offer arrival (watch for the #71 perf concern even if it's a follow-up PR).
- [ ] Fare formatting helpers render both sats and USD without truncation; zero-fare rides no longer show "$0" bug (fix from commit `6e85f1d`).

**drivestr notifications + chat:**
- [ ] Kind 3178 chat (driver side): receive chat during MATCHED, reply goes out encrypted, driver sees delivery feedback.
- [ ] Foreground service notification copy changes with PresenceMode transitions (OFFLINE → AVAILABLE → ON_RIDE → ROADFLARE_ONLY). No duplicate notifications. Follow-notification silent channel (commit `80a6cf6`) vs active-ride channel are visually distinct.
- [ ] Driver-ping silent notification (Kind 3189, commit `a5758aa`): appears on its own channel, does not override active-ride notification.

---

## 4. Feature / protocol coverage since v0.2.6 (40–55 min)

Clustered from `git log v0.2.6..HEAD`. Each cluster's items must still work — the refactors are expected to preserve behavior, but these are the unreleased features that have accumulated.

### 4.1 Payment method priority (Issue #46, commits `db69c2f`, `ee6692d`, `ffb2a1e`, `f539d7a`) — 8 min
- [ ] Rider: drag-to-reorder payment methods in Settings → order persists to Kind 30177 `settings.paymentMethods`.
- [ ] Rider: default payment method applied to new offers (`settings.defaultPaymentMethod`).
- [ ] Driver: `fiat_payment_methods` on Kind 30173 matches the order driver set in their settings.
- [ ] Compatibility indicator: driver sees ✅/⚠️ per offer based on overlap of rider+driver fiat methods; case-insensitive match (Zelle == zelle).
- [ ] No common payment method: `NoCommonPaymentMethodDialog` blocks acceptance cleanly.

### 4.2 Fiat fare (ADR-0008, commits `648a1e4`, `0d6ac24`, #61, #63) — 6 min
- [ ] Offer with fiat method: 3173 has both `fare_fiat_amount` AND `fare_fiat_currency` (both-or-neither rule).
- [ ] Driver display: authoritative USD shown with cent precision, no sats→USD drift.
- [ ] Boost-fare: `boostFare()` clears fiat fields, driver falls back to sats→USD for that offer only.
- [ ] Interop: if testing against an iOS device, verify round-trip compatibility.

### 4.3 RideOfferSpec sealed class (commits `a194fd0`, `89daf3c`) — 3 min
- [ ] Three variants (`Direct`, `Broadcast`, `RoadFlare`) all publish correctly via `sendOffer(spec)`.
- [ ] Broadcast privacy: location approximation (~1 km) happens inside `RideOfferSpec.Broadcast`, UI shows privacy warning first.

### 4.4 SettingsRepository + Hilt + DataStore migration (Issue #52, commit `9ebb5f3`) — 8 min
- [ ] **Upgrade-path test (critical):** with v0.2.6 seeded data (§1), install HEAD → vehicles list still populated, saved locations still present, payment-method priority preserved, mint URL preserved, default payment method preserved. Any loss = stop-ship.
- [ ] Fresh install: defaults load correctly, settings persist across app restart.
- [ ] Settings changes on one device + sync → show up on a second device signed into the same key (via Kind 30177).

### 4.5 DriverPresenceMapper / presence unification (Issue #58, commits `938b406`, `b316004`, `617c91c`, `16fc30d`, `ce202d1`, `ede5952`) — 8 min
- [ ] Driver transitions: OFFLINE → AVAILABLE → ON_RIDE → ROADFLARE_ONLY → OFFLINE — foreground service notification text updates each time, Kind 30014 status matches, Kind 30173 presence matches.
- [ ] No stale ON_RIDE broadcasts (commit `b316004`): after completing a ride, next Kind 30173 does NOT carry stale on-ride flag.
- [ ] Rider presence mapper (`RiderPresenceMapper`) similarly: rider's online/offline status in chat updates correctly.
- [ ] Unavailable race (Issue #58): rapid toggle of availability → no race where Kind 30014 says ON_RIDE but Kind 30173 says AVAILABLE.

### 4.6 NIP-60 wallet + HTLC hardening (commits `ae77e25`, `f96e7e9`) — 10 min
- [ ] HTLC payment loss on driver process death (commit `ae77e25`): driver force-kills app mid-claim → on relaunch, HTLC is still claimable, funds are NOT lost, auto-refund guard kicks in if claim window expired.
- [ ] `rideProtected` race (commit `f96e7e9`): run the refund path on a ride where protection flag was set mid-cancel — HTLC is NOT prematurely refunded while ride still protected.
- [ ] PIN brute-force protection: enter wrong PIN 3+ times → attempt counter locks input, does not reset on process death (the coordinator restore test in §3.1 covers this).
- [ ] Proof conservation: after a completed SAME_MINT ride, verify no proofs were lost (wallet diagnostics: sum of NIP-60 proofs = starting balance − fare, within mint fee tolerance).
- [ ] `proofs spent` verification (NUT-07): when a stale proof is encountered, the stale NIP-60 event is deleted and retry succeeds.

### 4.7 Offer subscription lifecycle fixes (commits `9256ebe`, `0a307c8`, `945b751`, `10f3ea1`) — 6 min
- [ ] Re-arm offer subs after stale cleanup: trigger a relay switch mid-session → offers still deliver on the new relay.
- [ ] False "driver not available" alarms on direct + RoadFlare offers no longer trigger spuriously; availability monitor only reports after a real offline signal.
- [ ] Offline Kind 30173 grace period: toggling online → offline → online rapidly does not cause observers to miss the next online event.
- [ ] Stale relay delivery (Issue #51, commit `10f3ea1`): if a relay replays an older 3174 after a newer one has arrived, the newer one wins.

### 4.8 Geocoding + privacy (commits `aa8c161`, `98307c0`, `1b0a9d2`) — 4 min
- [ ] Photon API geocoding returns results with location bias from current position.
- [ ] Privacy rounding applied to geocoder input (no raw GPS to external service).
- [ ] Street-number-only results filtered out (heuristic from commit `98307c0`).
- [ ] Dropdown visibility under keyboard — no clipping.

### 4.9 RoadFlare driver-side key share: 12h expiry + auto-sync (commits `3b067fb`, `97205e2`, `19fdb22`) — 4 min
- [ ] drivestr auto-syncs follower lists on app launch (commit `97205e2`) — approvals from a second device show up without user action.
- [ ] Kind 3186 12-hour expiry: drivestr republishes the key share with the correct expiry tag; rider-app consumers can decrypt for ~12h after issuance.
- [ ] Post-expiry path: after the key window passes, drivestr accepts a Kind 3188 `status="stale"` and republishes a fresh Kind 3186 via the MainActivity handler.

### 4.10 Broadcast RoadFlare rename (commit `9ed2262`) — 1 min
- [ ] drivestr UI copy says "Broadcast RoadFlare" (not "Send to All"). No stale copy anywhere.

### 4.11 PR #64 — `broadcastIfReady()` no longer gated on active followers — 3 min
- [ ] drivestr driver with 0 approved followers: broadcasting still publishes Kind 30014 (so followers can be added while the broadcast is live without needing a restart). Prior behaviour silently dropped the broadcast.

---

## 5. Cross-app / multi-device tests (35–45 min)

Require 2 devices (or device + emulator) with coordinated actions. All tests here are rider-app ↔ drivestr only.

### 5.1 Double-confirmation race — 8 min
- [ ] Configure rider with 3+ relays. Driver accepts a direct offer → multi-relay replication delivers Kind 3174 to rider on multiple threads. Verify in logs: `confirmationInFlight.compareAndSet(false, true)` succeeds exactly once. Exactly one Kind 3175 publishes.

### 5.2 Broadcast race — 5 min
- [ ] Rider broadcasts a ride. Two drivers (device A and emulator B) both hit Accept within ~1 s. Expected: only one `AcceptBroadcastOutcome.Success`, the other gets `DuplicateBlocked`. No stuck spinners, no duplicate 3174s observed by rider.

### 5.3 Driver process death during active ride — 6 min
- [ ] Driver is mid-ride (MATCHED, location broadcasting, HTLC pending claim). Force-kill drivestr via Android settings. Relaunch. Expected: state restores to MATCHED, location broadcast resumes, HTLC claim still possible at ride completion. Saved ride state in `DriverRideSession` survives by construction (Phase 3 RideSession pattern).

### 5.4 Rider process death during confirmation window — 6 min
- [ ] Rider has sent 3173, received 3174, and is in the ~60 s window before 3175 publishes. Force-kill the rider app. Relaunch. Expected: `restorePostAcceptanceState()` re-runs `paymentCoordinator.restoreRideState()` AND re-subscribes; `paymentPath` is still correct (SAME_MINT/CROSS_MINT/FIAT_CASH from the persisted snapshot); HTLC lock completes or the retry dialog resumes where it left off. (PR #70 review finding #6 regression lock.)

### 5.5 Network loss mid-HTLC-lock — 5 min
- [ ] Enable airplane mode immediately after tapping Accept on driver side. Expected on rider: retry dialog; re-enable network → retry succeeds OR cancel returns rider cleanly to IDLE. No partial state.

### 5.6 RoadFlare key rotation during active follower session — 6 min
- [ ] rider-app is actively decrypting drivestr's Kind 30014 broadcasts via `RoadflareRiderCoordinator`. drivestr rotates key (remove follower → re-approve, or explicit rotate from `RoadflareKeyManager.rotateKey()`). Expected: rider-app gets Kind 3186 with new key, subsequent Kind 30014s decrypt with the new key, RoadflareTab does NOT flash "driver unavailable". Any decrypt failures on old key should trigger the Kind 3188 `status="stale"` refresh path within a few seconds (MainActivity handler).

### 5.7 Driver cancellation with active HTLC — 5 min
- [ ] Rider has locked HTLC, driver is MATCHED, driver cancels. Expected: rider sees cancel, wallet refreshes (commit `ae77e25` auto-refund guard), HTLC eventually refunds to rider on expiry. Confirm in wallet diagnostics that the pending HTLC moves to refunded state.

### 5.8 Logout while mid-ride — 3 min
- [ ] Active confirmed ride, driver hits Logout. `LogoutManager` runs — subscriptions close, jobs cancel. Rider sees ride stall / driver offline path. No crash, no orphan services.

### 5.9 Rider cancellation with HTLC lock just placed — 3 min
- [ ] Rider cancels the instant after HTLC lock succeeds but before driver acknowledges (short race window). Expected: same-ride cancel guard prevents orphan confirmation, HTLC either refunds immediately or auto-refunds on expiry, no stuck "escrow held" state in the wallet.

### 5.10 Relay flap mid-ride — 3 min
- [ ] Disable one of the 3+ configured relays mid-ride for ~30 s then re-enable. Expected: offer/availability subs re-arm (commit `9256ebe`), no missed state transitions on either side, chat messages eventually converge.

---

## 6. Release gates (5–10 min)

Tally every checkbox above. Minimum bar for shipping v0.2.7:

- [ ] All of §2 passed (10/10)
- [ ] All coordinator sub-sections in §3.1 and §3.2 passed — no coordinator-level regression
- [ ] §4.2, §4.4, §4.6 passed — these gate payment/protocol/migration safety
- [ ] §5.1, §5.2, §5.3, §5.4, §5.7 passed — the race and resilience guarantees

**Stop-ship triggers (any one of these = do NOT ship):**
- Any §2 critical-path test fails.
- Any payment test in §3.1 PaymentCoordinator or §4.6 fails.
- Upgrade-path settings migration (§4.4 first bullet) loses data.
- Double-confirmation race (§5.1) publishes >1 Kind 3175.
- Escrow bypass (§2.8) proceeds without retry dialog.

**Ship-despite triggers (can release with follow-up issue filed):**
- UI cosmetic drift in the PR #68 / #73 extractions (file an issue, ship).
- Non-payment subscription edge cases in §4.7 if reproducible only under adversarial relay conditions.
- Geocoding edge cases in §4.8 for obscure addresses.

**Bottom line:**
- ✅ All gates pass → tag `v0.2.7`, push, cut release.
- ⚠️ Non-stop-ship items fail → file follow-ups, ship with known issues documented.
- ❌ Stop-ship fails → fix on a branch off `main`, retest affected sections only, re-evaluate gates.

---

## Appendix — Commits since v0.2.6 (reference)

For readers of this plan who want to trace any item back to its origin commit, the full `git log v0.2.6..HEAD --no-merges` window is the ~68-commit list starting from `5a70347 Persist onboarding sync state, typed profile fetch, and RoadFlare rider wiring` up to the four refactor PRs merged today (#70, #69, #68, #73).

PR-level highlights the plan leans on:
- **#70** — rider-app coordinators + 7 review-pass bugs fixed in commit `17674a7`
- **#69** — drivestr coordinators + 5 review passes, 15 fixes, 33 new unit tests
- **#68** — Compose decomposition in rider-app + drivestr (roadflare-rider portion also landed here but is out of scope for this release)
- **#73** — RideTab state-binding extractions in roadflare-rider (out of scope — mentioned for completeness only)
- **#61** — authoritative fiat fare display from iOS offer events (ADR-0008)
- **#64** — remove active-followers gate from `broadcastIfReady()`
- **#60** — Kind 3189 driver ping receiver (Issue #4)
