# Issue #46: Reorderable Payment Method Priority List

## Status: PLAN (no code changes yet)

---

## 1. Current State Analysis

### Data Model
- `PaymentMethod` enum in `RideshareEventKinds.kt:229-254` defines 9 methods: CASHU, LIGHTNING, FIAT_CASH + 6 RoadFlare alternates (ZELLE, PAYPAL, CASH_APP, VENMO, CASH, STRIKE)
- `ROADFLARE_ALTERNATE_METHODS` companion list separates the 6 non-Bitcoin methods

### Storage (SettingsManager.kt)
- `paymentMethods: StateFlow<List<String>>` -- stored as comma-separated string in SharedPreferences key `"payment_methods"`, defaults to `["cashu"]`
- `defaultPaymentMethod: StateFlow<String>` -- single string, defaults to `"cashu"`
- `roadflarePaymentMethods: StateFlow<Set<String>>` -- separate Set for RoadFlare alternates, key `"roadflare_payment_methods"`
- Loading: `split(",")` preserves insertion order but **order has no semantic meaning today**
- Saving: `joinToString(",")` -- order IS preserved in storage, just not used

### Nostr Events
- **Kind 30173 (Driver Availability)**: `payment_methods` JSONArray in content. Order not used by consumers.
- **Kind 3173 (Ride Offer)**: Single `payment_method` string. Rider picks `defaultPaymentMethod` at offer time.
- **Kind 3174 (Ride Acceptance)**: Echoes the rider's `payment_method` as confirmation.
- **Kind 30177 (Profile Backup)**: `SettingsBackup.paymentMethods: List<String>` serialized as JSONArray. `SettingsBackup.roadflarePaymentMethods: List<String>` serialized separately. Order preserved through serialize/deserialize cycle.

### Matching Logic (Deleted)
- `isPaymentCompatible()` (RiderViewModel.kt:4756-4763, private, single call site) and its filtering block (lines 2985-3014) are **deleted by this issue**. Rider-side compatibility filtering is removed entirely. Compatibility checking moves to the **driver side**: the rider sends their full payment methods list in the offer event (Kind 3173 `payment_methods` field), and the driver's app compares locally against its own methods — showing a GREEN/AMBER/RED three-state indicator on the offer card and a warning popup on accept if AMBER- or RED-indicated. See Phase 3.7.

### Offer Creation (RiderViewModel.kt)
- Direct offer (line 1461): `paymentMethod = settingsManager.defaultPaymentMethod.value`
- RoadFlare offer (line 1529): Same -- `settingsManager.defaultPaymentMethod.value`
- Broadcast (line 2338): Same
- **All paths use the single `defaultPaymentMethod`**, not a priority-ordered list.
- **Modified by this issue**: Each **direct** offer (including RoadFlare) now also includes a `payment_methods` list (rider's full merged payment methods — standard + RoadFlare alternates) alongside the existing singular `payment_method`. The singular field is unchanged for backward compatibility; the list enables driver-side compatibility checking. **Broadcast offers do NOT include `payment_methods`** — broadcast content is plaintext (not NIP-44 encrypted), so exposing the rider's full payment methods list would be a privacy leak. See Phase 3.7.

### Current UI
- **No payment method settings UI** in either Settings screen (rider or driver)
- `PaymentMethodsCard()` in WalletScreen.kt:349 exists but is **commented out** (line 90-93)
- `RoadflarePaymentMethodsDialog` in RoadflareTab.kt:857-930 -- checkboxes for RoadFlare alternate methods only (no ordering, no drag)
- `PaymentMethodsContent` in driver RoadflareTab.kt (line 790) -- checkboxes for RoadFlare alternate methods
- No drag-and-drop or reorderable list library in dependencies

---

## 2. Design Clarification: Standard vs Alternate Methods

### Key Design Principle

**Standard payment methods** (Cashu, Lightning, Fiat Cash) are **always enabled as selectable options** and are **NOT** part of the reorderable list. However, the runtime currently **only guarantees Cashu routing** — Lightning and Fiat Cash are listed in the `PaymentMethod` enum but do not have implemented payment routing. Using them as `defaultPaymentMethod` would cause semantic inconsistency (method field says one thing, execution runs Cashu). Adding routing for Lightning and Fiat Cash is a **separate future issue**. Cashu is the default/primary payment method and cannot be disabled.

**The reorderable list applies ONLY to RoadFlare alternate methods**: Zelle, Venmo, Cash App, PayPal, Strike, Cash. These are the methods a rider/driver can add, remove, and reorder in the payment methods settings.

### Method Categories (Immutable)

| Category | Methods | Behavior |
|----------|---------|----------|
| **Standard** | Cashu, Lightning, Fiat Cash | Always enabled. Not in reorderable list. Used via `defaultPaymentMethod` for all offer types. Cannot be disabled. Phase 1.4 adds a restore-time migration in `setDefaultPaymentMethod()`: non-cashu values (including `"lightning"`, `"fiat_cash"`) are coerced to `"cashu"` with a log warning, because the runtime cannot route them — all standard offer paths unconditionally run Cashu balance gating + HTLC generation. When Lightning/fiat-cash routing is implemented, this migration guard is relaxed. Current offer code (`sendRideOffer`, `sendRoadflareOffer`, `broadcastRideRequest`) unconditionally runs Cashu wallet balance gating + HTLC preimage generation. Only RoadFlare batch (`sendRoadflareToAll` / `sendRoadflareOfferSilent`) gates wallet/HTLC on `paymentMethod == CASHU`. Adding Lightning/fiat-cash routing is a **separate future issue** — this plan does not change that behavior. |
| **RoadFlare Alternates** | Zelle, PayPal, Cash App, Venmo, Cash, Strike | User-managed ordered list. Enabled by adding to list, disabled by removing. Order = priority for alternate selection (fallback dialogs, display order). |

### What This Simplifies (vs Prior Plan Revisions)

| Aspect | Old Plan (all 9 methods) | New Plan (alternates only) |
|--------|--------------------------|----------------------------|
| `defaultPaymentMethod` | Deprecated, replaced by list[0] | **Kept as single source of truth** — Phase 1.4 adds restore-time migration: non-cashu values coerced to `"cashu"` with log warning (runtime cannot route Lightning or fiat-cash) |
| `PaymentMethodResolver` for standard methods | New class resolving all 9 methods | **Not needed** - standard offers use `defaultPaymentMethod` directly |
| Reorderable UI | All 9 methods with enable/disable | 6 RoadFlare alternates only |
| Standard offer creation | Priority-resolved per-driver | **Unchanged** - still uses `defaultPaymentMethod` |
| `isPaymentCompatible()` | Modified | **Deleted** — function removed (private, single call site). Filtering block (lines 2985-3014) removed. Compatibility checking moves to **driver side**: driver parses rider's `payment_methods` list from offer event, compares locally, shows GREEN/AMBER/RED three-state indicator. See Phase 3.7. |
| `paymentMethods` (standard list) | Reorderable, deprecates default | **Unchanged** - not reorderable |
| Unified vs separate list debate | 3 options, blocking decision | **Resolved** - lists stay separate by design |
| Backup migration | Complex unified merge + deprecation bridge | Simple: Set → ordered List for alternates only |

---

## 3. Intended Behavior

### Core Concept
RoadFlare alternate payment methods become an **ordered priority list** instead of an unordered set. The order determines which alternate method is preferred when the rider needs a fallback (e.g., insufficient Cashu balance for RoadFlare ride) or when displaying alternate options in the UI.

### Where Priority Order Applies
1. **RoadFlare batch retry dialog** (`retryBatchWithAlternatePayment`): Pre-select the rider's top-priority alternate method instead of alphabetical first
2. **RoadFlare alternate display** (`RiderModeScreen.kt` lines 217, 273): Show alternates in priority order instead of `.sorted()` alphabetical
3. **Profile backup** (Kind 30177): Persist order for cross-device sync

### What Does NOT Change
- Standard offers (direct, broadcast, RoadFlare primary) still use `settingsManager.defaultPaymentMethod.value`
- **Standard offer wallet/HTLC gating is unchanged** — `sendRideOffer()`, `sendRoadflareOffer()`, and `broadcastRideRequest()` unconditionally call `verifyWalletBalance()` and generate HTLC preimage/hash. Phase 1.4 migrates non-cashu `defaultPaymentMethod` values to `"cashu"` at restore time — Lightning and fiat-cash routing is not implemented (separate future issue). There is no UI to change `defaultPaymentMethod`.
- `isPaymentCompatible()` and its filtering block are **deleted** — see Phase 3.7 for driver-side replacement
- `defaultPaymentMethod` is NOT deprecated - it remains the single source of truth for standard method selection
- `paymentMethods` (standard methods list) is not reorderable
- No new `PaymentMethodResolver` class for standard methods

### Event Wire Changes
- **Kind 3173 (Ride Offer — direct/RoadFlare only)**: New `payment_methods` JSONArray field added to **direct** offer content (NIP-44 encrypted). Contains the rider's full merged payment methods list (standard + RoadFlare alternates, deduped). Existing singular `payment_method` field is unchanged for backward compatibility. The driver parses `payment_methods` and falls back to `[payment_method]` for old rider apps. **Not added to broadcast offers** — broadcast content is plaintext, so the full payment methods list would be a privacy leak. See Phase 3.7.
- **Kind 30173 (Driver Availability)**: **No code change.** Existing `payment_methods` field is unchanged. Compatibility checking does not use this event — it uses the rider's `payment_methods` list in the offer (Kind 3173). See Phase 3.7.
- **Kind 30177**: `roadflarePaymentMethods` list in backup becomes ordered. No schema change (already serialized as JSONArray).

### Backwards Compatibility
- Old clients that ignore array order are unaffected (RoadFlare alternates are displayed, not resolved against)
- Old backups with unordered `roadflarePaymentMethods` arrays are loaded as-is (insertion order preserved)
- `defaultPaymentMethod` remains present and functional in all backup events

---

## 4. Edge Cases & Behavioral Questions

### Q1: What happens to `defaultPaymentMethod`?
**Answer**: Phase 1.4 adds a **restore-time migration**: any non-`"cashu"` value (including `"lightning"`, `"fiat_cash"`, or corrupt/future strings) is coerced to `"cashu"` with a log warning, because the runtime cannot route non-Cashu payments (all standard offer paths unconditionally run Cashu balance gating + HTLC generation). Outside of this migration guard, `defaultPaymentMethod` is unchanged — there is no UI to set it, and its only callers are `restoreFromBackup()` and `clearAllData()`. Standard offers (direct, broadcast, RoadFlare primary) continue using `defaultPaymentMethod`. The reorderable list is for RoadFlare alternates only.

### Q2: Empty alternate methods list?
**Answer**: Same as today - `roadflarePaymentMethods` defaults to empty list. No alternates shown. Rider can only use standard methods for RoadFlare rides. The batch "insufficient funds" dialog shows no alternate options (user must deposit funds).

### Q3: Haptic feedback during drag?
Use `HapticFeedbackType.LongPress` on drag start and `HapticFeedbackType.TextHandleMove` during reorder. Standard Android Compose pattern.

### Q4: `offerPaymentMethod` persistence for boost after app restart?
**Issue**: If the `offerPaymentMethod` field is added to `RiderRideSession` (Phase 2), it must be persisted in `saveRideState()`/`restoreRideState()` (lines 633-827). Currently, NO payment method is persisted in ride state JSON. Without persistence, a boost after app restart would fall back to `defaultPaymentMethod`, which could differ if the user changed settings.
**Resolution**: Phase 2 includes explicit persistence steps. Old saved states missing the field gracefully degrade via `optString()` returning `null`. Additionally, `saveRideState()` is restructured (Phase 2.2) to persist ride-session fields even when `acceptance == null`, since boost happens pre-acceptance in `WAITING_FOR_ACCEPTANCE` stage.

### Q5: Empty-list fallback consistency? — RESOLVED (N/A)
**Resolution**: `isPaymentCompatible()` is deleted (Phase 3.7). Compatibility checking moves entirely to the driver side: the driver compares the rider's `payment_methods` from the offer against its own methods. Empty alternate list simply means "no alternates available" — rider uses standard methods only. The `paymentMethods` list (standard methods) is never empty in practice — `SettingsManager` enforces `listOf("cashu")` fallback in both `loadPaymentMethods()` (line 490) and `setPaymentMethods()` (line 501).

### Q6: Driver-side alternate priority?
Drivers also have a `roadflarePaymentMethods` set (driver RoadflareTab.kt). Should drivers have reorderable alternates too?
**Answer**: Yes, for consistency. The same `RoadflarePaymentMethodPriorityList` composable is shared by both apps. Driver's alternate order is stored in their settings locally. **Kind 30173 is NOT modified** — driver alternates are not broadcast in availability events (see "Event Wire Changes" section). The driver's alternate order is used locally for the compatibility indicator (Phase 3.7). Future mutual matching would require a protocol change to Kind 30173 or Kind 30012 (out of scope).

### Q7: App restart during pre-acceptance stage — what runtime behavior is restored?
**Issue**: `restoreRideState()` currently only re-establishes subscriptions and foreground service for post-confirmation stages (lines 817-841). Pre-acceptance stages (`WAITING_FOR_ACCEPTANCE`, `BROADCASTING_REQUEST`) require acceptance subscriptions, timeouts, and foreground service notification — all started by `setupOfferSubscriptions()` in the live flow but lost on app restart.
**Resolution**: Phase 2.2 specifies a stage-branched behavioral rehydration block in `restoreRideState()`. For `WAITING_FOR_ACCEPTANCE`: re-subscribe to acceptance + driver availability, restart timeout with elapsed-time adjustment (fires immediately if expired). For `BROADCASTING_REQUEST`: re-subscribe to broadcast acceptances, restart broadcast timeout with elapsed-time adjustment. Both stages restart the "Searching" foreground service notification. New helper methods (`startAcceptanceTimeoutWithDelay()`, `startBroadcastTimeoutWithDelay()`) enable elapsed-time-aware timeout restart. Batch offer subscriptions beyond the first driver are NOT restored (acceptable degradation — see Phase 2.2 batch limitation note).

---

## 5. Implementation Plan

### Phase 1: SettingsManager Changes (common module)

**File**: `common/.../settings/SettingsManager.kt`

1. **Convert `roadflarePaymentMethods` from `Set<String>` to `List<String>`**:
   - Change `_roadflarePaymentMethods` type from `MutableStateFlow<Set<String>>` to `MutableStateFlow<List<String>>`
   - Change `roadflarePaymentMethods` public type from `StateFlow<Set<String>>` to `StateFlow<List<String>>`
   - Update `loadRoadflarePaymentMethods()` to return `List<String>` (already uses `split(",")` which returns a list - remove any `.toSet()` conversion)
   - Update `setRoadflarePaymentMethods()` to accept `List<String>` and preserve order
   - **Add input validation/normalization** in `setRoadflarePaymentMethods()`:
     - **Forward-compat invariant**: Unknown method string **values** from future app versions are preserved through all list operations (toggle, move, reorder, restore) — they are never dropped or lost, but they ARE normalized to lowercase for consistency (see validation tier below). Only truly invalid/corrupt entries (empty or blank strings) are dropped. Their original **interleaving positions** relative to known methods are NOT preserved: after any reorder operation, unknown methods are collected and appended at the end of the list as a stable tail (see Phase 1.2 unknown-method preservation caveat). This ensures backup round-trips (new app → export → old app → import → re-export) never silently lose methods the old app doesn't recognize, though their positions within the list may be normalized.
     - **Unknown Method Display Policy** (applies to ALL UI surfaces): Unknown methods are **hidden in settings/configuration UI** (which only renders the 6 known `ROADFLARE_ALTERNATE_METHODS` as checkboxes — unknowns persist in storage until the user upgrades to a version that recognizes them) but **shown in offer flow UI** (batch payment dialog at line 217, alternate fallback at line 273). In offer flow, unknown methods render as raw strings via `PaymentMethod.fromString(method)?.displayName ?: method` and are selectable by the user (they may be recognized by the driver's newer app version). Unknown methods are never **auto-selected** as fallback — only explicitly selectable (Phase 3 line 273 guardrail: `firstOrNull { it in knownAlternateMethods }`).
     - **Single validation tier** (applied to all callers): normalize strings (`map { it.trim().lowercase() }`), filter empty/blank strings (`filter { it.isNotBlank() }`), deduplicate preserving first occurrence order (`distinct()`). This replaces the implicit dedup that `.toSet()` provided in the old restore path (line 734). No strict validation against `ROADFLARE_ALTERNATE_METHODS` values — this would break forward-compatible restore and is unnecessary since the only method-adding caller (`toggleRoadflarePaymentMethod()`) only adds UI-presented known methods by construction (the UI shows only the 6 `ROADFLARE_ALTERNATE_METHODS` as checkboxes).

     > **Preservation vs Normalization — three distinct dimensions:**
     >
     > | Dimension | What it means | Example |
     > |-----------|--------------|---------|
     > | **Value preservation** | Unknown values are **never removed** from the list by any operation (set, move, toggle-on, restore, backup round-trip). Only explicit user action (toggle-off) or truly invalid entries (blank strings) cause removal. | `"FUTURE_METHOD"` from a newer app → stored as `"future_method"` — the value survives indefinitely. |
     > | **Value normalization** | Unknown values are **canonicalized** to lowercase with trimmed whitespace (`trim().lowercase()`). The semantic identity survives; only the surface form changes. This prevents casing/whitespace mismatch bugs from external data (Nostr events from other implementations, manually edited backups). All enum `.value` strings are already lowercase with no whitespace, so normalization is a no-op for well-formed data. | `"FUTURE_METHOD"` → `"future_method"`, `" zelle "` → `"zelle"`. |
     > | **Position normalization** | Unknown values' **interleaving positions** relative to known methods are NOT preserved after reorder operations. They are collected and appended to a stable tail (after all known methods). Their order relative to each other is preserved. Position normalization occurs because the settings UI only renders known methods — it cannot express interleaved positioning of hidden items. | Before reorder: `["future_method", "zelle", "venmo"]` → after `move("venmo", 0)`: `["venmo", "zelle", "future_method"]` — unknown moved to tail. |
     >
     > **Key rule of thumb**: Unknown values are **kept** (never lost) but **cleaned up** (normalized form + tail position). Truly invalid entries (empty/blank strings) are the only things dropped entirely.
     - **Normalization also applied to `setPaymentMethods()`**: Same `map { it.trim().lowercase() }` pipeline for the standard payment methods list, for consistency. And to `PaymentMethod.fromString()`: add `s.trim().lowercase()` before matching to tolerate non-normalized input from event parsing.
     - **Implementation**: Single `setRoadflarePaymentMethods(methods: List<String>)` with the validation above. No `strictValidation` parameter needed — all callers (`toggleRoadflarePaymentMethod()`, `moveRoadflarePaymentMethod()`, `restoreFromBackup()`, `loadRoadflarePaymentMethods()`) use the same validation path.

   **Unknown value preservation & normalization — concrete examples**:

   | Input | Operation | Output | Dimension | What happened |
   |-------|-----------|--------|-----------|---------------|
   | `["zelle", "FUTURE_METHOD", "venmo"]` | `setRoadflarePaymentMethods()` | `["zelle", "future_method", "venmo"]` | Value norm | Value preserved, casing normalized to lowercase |
   | `["zelle", "future_method", "venmo"]` | `moveRoadflarePaymentMethod("venmo", 0)` | `["venmo", "zelle", "future_method"]` | Position norm | Known items reordered; unknown appended to tail |
   | `["future_method", "zelle"]` | `toggleRoadflarePaymentMethod("venmo", true)` | `["future_method", "zelle", "venmo"]` | Preservation | Unknown preserved in place; new known added to end |
   | `["future_method", "zelle"]` | `toggleRoadflarePaymentMethod("future_method", false)` | `["zelle"]` | Removal | Unknown explicitly removed by user action (toggle off) |
   | `["", "future_method", " ", "zelle"]` | `setRoadflarePaymentMethods()` | `["future_method", "zelle"]` | Both | Blank/empty dropped (invalid); unknown value kept (preserved + normalized) |
   | `[" FUTURE_METHOD ", "zelle"]` | `setRoadflarePaymentMethods()` | `["future_method", "zelle"]` | Value norm | Whitespace trimmed + lowercased — value identity preserved, surface form cleaned |
   | `["zelle", "future_method"]` | Backup export → old app import → re-export | `["zelle", "future_method"]` | Preservation | Round-trip preserves unknown value (old app ignores it but doesn't drop it) |
   | `["zelle", "future_method"]` | Auto-default selection (line 273) | `"zelle"` | Preservation | Unknown skipped for auto-select; `firstOrNull { it in knownAlternateMethods }` picks first known |

   **Key invariant**: Unknown values survive all list operations (set, move, toggle-on, restore, backup round-trip). They are only removed by explicit user action (toggle-off) or if they are blank/empty strings. Reorder operations normalize their **position** to a stable tail (after all known methods) and their **form** to `trim().lowercase()`, but never drop the values themselves.

   **Complete Set→List migration checklist** (all locations in `SettingsManager.kt`):

   | Line | Current Code | Required Change |
   |------|-------------|-----------------|
   | 524 | `val roadflarePaymentMethods: StateFlow<Set<String>>` | Change type to `StateFlow<List<String>>` |
   | 523 | `MutableStateFlow(loadRoadflarePaymentMethods())` | Type inferred — changes automatically once load return type changes |
   | 526 | `private fun loadRoadflarePaymentMethods(): Set<String>` | Change return type to `List<String>` |
   | 528 | `emptySet()` (empty fallback in load) | Change to `emptyList()` |
   | 529 | `.toSet()` (conversion after split) | Remove — `split(",")` already returns `List<String>` |
   | 532 | `fun setRoadflarePaymentMethods(methods: Set<String>)` | Change parameter type to `List<String>` |
   | 705 | `_roadflarePaymentMethods.value.toList()` (backup export) | Remove `.toList()` — already `List<String>` after migration |
   | 734 | `backup.roadflarePaymentMethods.toSet()` (backup restore) | Remove `.toSet()` — pass `List<String>` directly (Phase 4.3) |
   | 765 | `_roadflarePaymentMethods.value = emptySet()` (clearAllData) | Change to `emptyList()` |

   All 9 sites must be updated atomically to avoid compile errors. The `ProfileBackupEvent.kt` `SettingsBackup` data class already uses `List<String>` (line 183) — no change needed there.

2. **Add `moveRoadflarePaymentMethod(method: String, toIndex: Int)`**:
   - Reorder within the alternate methods list using **method-string key** (not raw index) to identify the item being moved
   - `toIndex` is the target position within the **visible (known) methods sublist**, not the full stored list
   - Save to SharedPreferences via `joinToString(",")`
   - Emit new ordered list via StateFlow

   **Unknown-method index mapping strategy**: The stored `roadflarePaymentMethods` list may contain unknown future method strings (forward-compat invariant from Phase 1.1). The settings UI only renders the 6 known `ROADFLARE_ALTERNATE_METHODS` as draggable items — **unknown methods are not visible in the UI reorder and are appended to a stable tail** after any reorder operation. This creates an index mismatch: the UI's drag indexes (0..N-1 over known-only items) don't correspond to stored list indexes (which may interleave unknown entries). The move API uses method-string keys (`method: String, toVisibleIndex: Int`) to eliminate this mismatch entirely.

   **Implementation**: `moveRoadflarePaymentMethod()` uses method-string identification (not source index) to avoid index mapping bugs:
   ```kotlin
   fun moveRoadflarePaymentMethod(method: String, toVisibleIndex: Int) {
       val current = _roadflarePaymentMethods.value.toMutableList()
       // Separate known (visible in UI) from unknown (hidden, preserved)
       val knownMethods = PaymentMethod.ROADFLARE_ALTERNATE_METHODS.map { it.value }.toSet()
       val visibleItems = current.filter { it in knownMethods }.toMutableList()
       val unknownItems = current.filter { it !in knownMethods }
       // Guard: if method is not currently visible (disabled or unknown), no-op
       if (method !in visibleItems) return
       // Reorder within visible items only
       visibleItems.remove(method)
       visibleItems.add(toVisibleIndex.coerceIn(0, visibleItems.size), method)
       // Reconstruct: visible items in new order, then unknown items appended (stable tail)
       val reordered = visibleItems + unknownItems
       setRoadflarePaymentMethods(reordered)
   }
   ```
   **Unknown-method preservation caveat**: Unknown method **values** are always preserved — they are never lost or silently dropped by any list operation (toggle, move, reorder, restore). However, their original **interleaving positions** relative to known methods are NOT preserved: after any reorder operation, all unknown methods are collected and appended at the end of the list as a stable tail group. The original interleaving cannot be reconstructed because the settings UI only renders known methods — it has no way to express interleaved positioning of hidden items. This is an acceptable trade-off: values survive any number of round-trips intact; only their relative positions within the list are normalized. This ensures:
   - Drag gestures in the UI only affect known items (correct visual feedback)
   - Unknown method values are never lost or silently dropped
   - Round-trip safety: unknown items survive any number of reorder operations (values preserved, positions normalized to tail)
   - The UI's `LazyColumn` indexes map 1:1 to `visibleItems` indexes (no parallel array needed)

   The `RoadflarePaymentMethodPriorityList` composable (Phase 5) filters `roadflarePaymentMethods` to known methods for display, so the UI index naturally matches the `visibleItems` sublist. The composable calls `moveRoadflarePaymentMethod(method.value, targetIndex)` on drop — passing the method string, not a raw stored-list index.

3. **Add `toggleRoadflarePaymentMethod(method: String, enabled: Boolean)`**:
   - `enabled=true`: Add to end of list (lowest priority)
   - `enabled=false`: Remove from list
   - Save and emit

4. **Add input validation to `setDefaultPaymentMethod()`** (line 513):
   - Currently accepts arbitrary strings with no validation (`SettingsManager.kt:517-520`). The only callers are `restoreFromBackup()` (line 731) and `clearAllData()` (line 763). There is no UI to set this value directly.
   - **Cashu-only migration**: Coerce any non-`"cashu"` value to `"cashu"` with a log warning. While `"lightning"` and `"fiat_cash"` are valid `PaymentMethod` enum values, the runtime cannot route them — all offer paths (`sendRideOffer`, `sendRoadflareOffer`, `broadcastRideRequest`) unconditionally run Cashu balance gating and HTLC generation. Non-cashu values can appear in old backups or future app versions; coercing them prevents semantic inconsistency (method field says one thing, execution does another). Lightning/fiat-cash routing is a **separate future issue** — when implemented, this migration guard should be relaxed to accept `listOf("cashu", "lightning", "fiat_cash")`.
   - **Forward-compat note**: Unlike `roadflarePaymentMethods` (which preserves unknown strings for forward compatibility), `defaultPaymentMethod` is a single active value used directly in offer creation. An unrecognized or unroutable default would cause every offer to use an unsupported payment method — a breaking UX issue, not a graceful degradation. Falling back to `"cashu"` is the safe behavior.
   - `restoreFromBackup()` already passes the raw string from Kind 30177 — the validation in the setter handles non-cashu values (including `"lightning"`, `"fiat_cash"`, corrupt, or future values) transparently by falling back to `"cashu"`.
   - `clearAllData()` passes `"cashu"` — always valid, unaffected by this change.

5. **No changes to `paymentMethods` (standard methods list)** - not reorderable.

6. **Add `_roadflarePaymentMethods` to `syncableSettingsHash`** - currently the hash (lines 663-681) includes `_paymentMethods` and `_defaultPaymentMethod` but NOT `_roadflarePaymentMethods`. Without this, reorder/toggle changes won't trigger auto-backup via the `LaunchedEffect(settingsHash)` in either MainActivity. Add `_roadflarePaymentMethods` to the second `combine()` block (line 672-678) so its `joinToString` output participates in hash computation. Order changes produce different `joinToString` output → different hash → backup triggered.

7. **Add basic input validation to `setPaymentMethods()`** (line 498):
   - Currently accepts arbitrary strings with no validation. The only caller is `restoreFromBackup()` (line 730), which passes deserialized JSON array contents without filtering.
   - Add common-tier validation: filter empty/blank strings (`filter { it.isNotBlank() }`), deduplicate (`distinct()`). This is the same common tier applied to `setRoadflarePaymentMethods()`.
   - **Rationale**: Basic validation prevents edge-case pollution from malformed backup events. The only caller is `restoreFromBackup()`, which reads from the user's own NIP-44-encrypted Kind 30177 event (not externally writable). Strict validation against `PaymentMethod` enum values is NOT applied — forward-compatible restore semantics match `roadflarePaymentMethods` (common tier only). The practical guard is layered: (a) filter blank + dedup prevents trivial corruption, (b) the empty-list fallback ensures Cashu is always present as minimum.
   - The existing `if (methods.isEmpty())` fallback to `listOf("cashu")` is unchanged — it correctly ensures Cashu is always the default.
   - **Standard methods "always enabled" guarantee**: The "always enabled" claim in Section 2 is enforced by the empty-list fallback in both `loadPaymentMethods()` (line 490, defaults to `listOf("cashu")`) and `setPaymentMethods()` (line 501, falls back to `listOf("cashu")` when empty). Non-strict validation (filter blank, dedup) does not weaken this — if filtering removes all entries, the empty-list fallback ensures Cashu remains. Strict enum validation is intentionally omitted to allow forward-compatible restore of unknown future standard method strings.

### Phase 2: offerPaymentMethod in RiderRideSession (rider-app)

**File**: `rider-app/.../viewmodels/RiderViewModel.kt`

> **Risk Note**: This phase introduces significant ride-state persistence and rehydration logic (save/restore restructuring, pre-acceptance behavioral rehydration, timeout rewind). While critical for boost-after-restart functionality, these changes touch core state management. **Phase 2 should be implemented as a separate PR** from Phases 1/3-7 to isolate regression risk. The reorderable payment list (Phases 1, 3-7) is a self-contained UI/settings change; Phase 2's save/restore surgery is orthogonal and benefits from independent review and testing.

> **Regression Test Checklist (must pass before Phase 2 PR merges)**:
> - [ ] **Offer → Accept → Complete**: Direct offer, RoadFlare offer, broadcast offer — full happy path with Cashu payment
> - [ ] **Offer → Accept → Cancel**: Driver-initiated and rider-initiated cancellation from each stage
> - [ ] **Boost cycle**: Send offer → timeout → boost → accept — verify payment method preserved (not drifted)
> - [ ] **App restart during WAITING_FOR_ACCEPTANCE**: Kill app → reopen → verify offer state restored, acceptance subscription re-established, timeout fires at correct remaining time
> - [ ] **App restart during BROADCASTING_REQUEST**: Kill app → reopen → verify broadcast state restored, timeout fires correctly
> - [ ] **App restart post-acceptance**: Kill app → reopen → verify full ride state restored (existing behavior, regression check)
> - [ ] **Old saved state migration**: Manually create saved state JSON missing `hasAcceptance` and `offerPaymentMethod` fields → restore → verify no crash, graceful fallback
> - [ ] **Batch offer first-driver save**: Start batch → verify `saveRideState()` fires after first-offer state transition
> - [ ] **Alternate method boost**: Send RoadFlare offer with PayPal → boost → verify boost uses PayPal (not Cashu)
> - [ ] **Timeout edge cases**: Set `acceptanceTimeoutStartMs` to past (>timeout ago) → restore → verify timeout fires immediately
> - [ ] **Boost + restart (direct)**: Send offer → boost → kill app → reopen → verify boosted offer context restored (new event ID, boosted fare, correct remaining timeout)
> - [ ] **Boost + restart (broadcast)**: Broadcast → boost fare → kill app → reopen → verify boosted broadcast context restored (new event ID, boosted fare, correct remaining timeout)

This phase adds a field to track the payment method used for the current ride's offer, ensuring boost consistency and enabling future per-method logic.

1. **Add `offerPaymentMethod: String? = null` to `RiderRideSession`** (after line 5032, in the HTLC section):
   - Set it in `sendRideOffer()`, `sendRoadflareOffer()`, `sendRoadflareOfferWithAlternatePayment()`, `broadcastRideRequest()`, and `sendRoadflareToAll()` immediately after determining the payment method: `updateRideSession { copy(offerPaymentMethod = resolvedMethod) }`.
   - **Critical**: `sendRoadflareOfferWithAlternatePayment()` (lines 1552-1588) accepts a `paymentMethod: String` parameter for an alternate method. This MUST be captured - without it, `boostDirectOffer()` (line 2260) drifts back to `settingsManager.defaultPaymentMethod.value`, silently switching from e.g. PayPal to Cashu on boost.
   - `boostDirectOffer()` reads `rideSession.offerPaymentMethod` instead of re-resolving from `settingsManager.defaultPaymentMethod.value`. This ensures HTLC/method consistency across boost cycles. **Additionally**, `boostDirectOffer()` targeting chain is updated from `selectedDriver?.driverPubKey ?: roadflareTargetDriverPubKey` to `selectedDriver?.driverPubKey ?: offerTargetDriverPubKey ?: roadflareTargetDriverPubKey`. After app restart, `selectedDriver` is always `null` (not persisted in `saveRideState()`), so `offerTargetDriverPubKey` (restored from saved JSON) provides the target pubkey for regular direct offers. Without this wiring, `boostDirectOffer()` silently returns early (`?: return`) for non-RoadFlare direct offers after restart.
   - If `rideSession.offerPaymentMethod` is null (shouldn't happen but defensive), fall back to `settingsManager.defaultPaymentMethod.value`.
   - Field resets to `null` automatically via `resetRideUiState()` (RideSession default construction).
   - **Rationale**: The original payment method is currently not persisted anywhere in the ride session. Without this field, a boost after a settings change silently switches the payment method while reusing the same HTLC context, creating an inconsistency.

   **Add `offerTargetDriverPubKey: String? = null` to `RiderRideSession`** (alongside `offerPaymentMethod`):
   - Stores the target driver's pubkey for ALL direct offer types (regular direct, RoadFlare single, RoadFlare batch first-offer). Set alongside `offerPaymentMethod` in the same `updateRideSession { copy() }` calls at each offer site.
   - For broadcast offers (`broadcastRideRequest()`): remains `null` — broadcasts target all drivers, not a specific one.
   - **Purpose**: Enables (a) pre-acceptance restore to re-subscribe to acceptance events for the specific target driver, and (b) `boostDirectOffer()` targeting after app restart when `selectedDriver` is not reconstructed. Without this field, regular direct offers would lose both their subscription target AND boost target on app restart (`selectedDriver` is not persisted; `roadflareTargetDriverPubKey` only covers RoadFlare offers).
   - Field resets to `null` automatically via `resetRideUiState()` (RideSession default construction).

2. **Persist `offerPaymentMethod` in `saveRideState()`/`restoreRideState()`** (lines 633-827):

   **Save side** (lines 633-703):
   - Add `json.put("offerPaymentMethod", rideSession.offerPaymentMethod ?: "")` to the JSON serialization block.
   - **Persistence gate restructure**: `saveRideState()` returns early when `acceptance == null` (line 647), but boost happens in `WAITING_FOR_ACCEPTANCE` stage (pre-acceptance). Restructure `saveRideState()` so it persists ride-session fields (stage, offer, payment method) regardless of acceptance, and only gates acceptance-dependent fields (driver name, pickup ETA, etc.) on `acceptance != null`. Add a `json.put("hasAcceptance", acceptance != null)` flag to distinguish pre-acceptance vs post-acceptance saved states.

   **Restore side** (lines 708-827) - CRITICAL, must match save restructure:
   - `restoreRideState()` currently hard-requires acceptance fields via `getString()` (lines 733-738), which **throws** on missing keys. If `saveRideState()` is relaxed to save pre-acceptance states, restore will throw and the catch block (line 864-867) will silently clear all saved state via `clearSavedRideState()`.
   - **Fix**: Read the `hasAcceptance` flag first: `val hasAcceptance = data.optBoolean("hasAcceptance", true)`. When `hasAcceptance == false`, skip `RideAcceptanceData` construction entirely (set `acceptance = null`) and only restore the pre-acceptance fields (`offerPaymentMethod`, stage, offer context). When `hasAcceptance == true`, restore the full state including acceptance fields.
   - **Migrate acceptance field reads** from `getString()` (throws) to `optString()` with defaults for defense-in-depth, even in the `hasAcceptance == true` path. If any acceptance field is missing or empty, treat it as a corrupt save and call `clearSavedRideState()` with a log warning. This prevents silent data loss from partial saves.
   - Read `offerPaymentMethod`: `val offerPaymentMethod = data.optString("offerPaymentMethod", "").ifEmpty { null }` - safe in both pre-acceptance and post-acceptance paths.
   - **Null-safe downstream acceptance handling**: When `acceptance = null` (pre-acceptance restore), three downstream code blocks in `restoreRideState()` unconditionally access `acceptance.driverPubKey` and will NPE without guards:
     - **Line 820**: `subscribeToDriverRideState(confirmationEventId, acceptance.driverPubKey)` — inside a `confirmationEventId != null` guard, but no `acceptance` null-check. **Fix**: wrap the subscription block (lines 817-823) in `if (acceptance != null)` — pre-acceptance state has no confirmation ID anyway, but defense-in-depth.
     - **Line 828**: `_uiState.value.driverProfiles[acceptance.driverPubKey]?.bestName()` — unconditional access for `driverName` construction. **Fix**: guard with `val driverName = if (acceptance != null) { ... } else null`. Pre-acceptance state has no driver name to display.
     - **Line 859**: `startBridgePendingPoll(pendingBridge.id, confirmationEventId, acceptance.driverPubKey)` — inside a `pendingBridge != null` guard but no `acceptance` null-check. **Fix**: add `&& acceptance != null` to the `pendingBridge` guard condition. Pre-acceptance state cannot have a pending bridge payment.
   - **Pattern**: All three sites are gated on conditions (`confirmationEventId`, `driverProfiles`, `pendingBridge`) that are inherently absent in pre-acceptance state, so the `acceptance != null` guards are pure defense-in-depth. But without them, a corrupted or unexpected pre-acceptance save will crash on restore.
   - **Backward compat**: Old saved states missing `hasAcceptance` → `optBoolean("hasAcceptance", true)` returns `true` → full restore path used → acceptance fields read via `optString()` with fallback → safe. Old saved states missing `offerPaymentMethod` → `optString()` returns `""` → `ifEmpty { null }` → `null` → boost falls back to `defaultPaymentMethod.value`. Safe degradation in both cases.
   - **Pre-acceptance restore-time behavioral rehydration**: Restoring field values alone is insufficient for pre-acceptance stages (`WAITING_FOR_ACCEPTANCE`, `BROADCASTING_REQUEST`). The live offer flow starts runtime behavior (subscriptions, timeouts, foreground service) via `setupOfferSubscriptions()` — these are lost on app restart and must be explicitly re-established. Add a stage-branched behavioral block after the state update (`_uiState.update {}` at line 793), parallel to the existing post-confirmation block (lines 817-823):

     ```kotlin
     // Pre-acceptance behavioral rehydration (NEW — parallel to post-confirmation block)
     if (!hasAcceptance && pendingOfferEventId != null) {
         when (stage) {
             RideStage.WAITING_FOR_ACCEPTANCE -> {
                 // 1. Re-subscribe to acceptance for the pending offer
                 //    driverPubKey comes from saved offerTargetDriverPubKey (set for ALL direct offers in Phase 2.1)
                 val targetDriverPubKey = data.optString("offerTargetDriverPubKey", "")
                 if (targetDriverPubKey.isNotEmpty()) {
                     subscribeToAcceptance(pendingOfferEventId, targetDriverPubKey)
                     subscribeToSelectedDriverAvailability(targetDriverPubKey)
                 }
                 // 2. Rehydrate timeout with elapsed-time adjustment
                 //    acceptanceTimeoutStartMs is restored in session fields above.
                 //    Compute remaining time; if already expired, fire immediately.
                 val timeoutStart = data.optLong("acceptanceTimeoutStartMs", 0L)
                 val elapsed = System.currentTimeMillis() - timeoutStart
                 val remaining = (ACCEPTANCE_TIMEOUT_MS - elapsed).coerceAtLeast(0L)
                 startAcceptanceTimeoutWithDelay(remaining)
                 // 3. Foreground service
                 RiderActiveService.startSearching(getApplication())
             }
             RideStage.BROADCASTING_REQUEST -> {
                 // 1. Re-subscribe to broadcast acceptances
                 subscribeToAcceptancesForBroadcast(pendingOfferEventId)
                 hasAcceptedDriver.set(false)
                 // 2. Rehydrate broadcast timeout with elapsed-time adjustment
                 val timeoutStart = data.optLong("broadcastStartTimeMs", 0L)
                 val elapsed = System.currentTimeMillis() - timeoutStart
                 val remaining = (BROADCAST_TIMEOUT_MS - elapsed).coerceAtLeast(0L)
                 startBroadcastTimeoutWithDelay(remaining)
                 // 3. Foreground service
                 RiderActiveService.startSearching(getApplication())
             }
             else -> { /* Post-acceptance stages handled by existing block */ }
         }
     }
     ```

     **New helper methods** required for elapsed-time timeout rehydration:
     - `startAcceptanceTimeoutWithDelay(delayMs: Long)`: Same as `startAcceptanceTimeout()` but uses `delay(delayMs)` instead of `delay(ACCEPTANCE_TIMEOUT_MS)`. When `delayMs == 0L`, fires `handleAcceptanceTimeout()` immediately (offer expired while app was closed). Extract from existing `startAcceptanceTimeout()` — both call the same `handleAcceptanceTimeout()`.
     - `startBroadcastTimeoutWithDelay(delayMs: Long)`: Same pattern for `startBroadcastTimeout()` / `handleBroadcastTimeout()`.

     **Save side additions** for timeout rehydration — add to `saveRideState()` JSON:
     - `json.put("acceptanceTimeoutStartMs", rideSession.acceptanceTimeoutStartMs ?: 0L)`
     - `json.put("broadcastStartTimeMs", rideSession.broadcastStartTimeMs ?: 0L)`
     - `json.put("offerTargetDriverPubKey", rideSession.offerTargetDriverPubKey ?: "")`
     The timeout fields are already in `RiderRideSession` (lines 5004, 5009) but are not currently persisted. `offerTargetDriverPubKey` is a new field added in Phase 2.1.

     **Direct offer coverage**: All direct offer types (regular direct, RoadFlare single, RoadFlare batch first-offer) persist `offerTargetDriverPubKey`, so acceptance subscriptions and driver availability monitoring are fully restored on app restart. No degradation for any direct offer path.

     **Batch offer limitation**: Batch flow (`sendRoadflareToAll()`) stores per-driver subscriptions in `contactedDrivers` (in-memory `Map<String, String>`) and `SubKeys.BATCH_ACCEPTANCE` group subs. These are NOT persisted. On restore, only the first offer's subscription (via `pendingOfferEventId` + `offerTargetDriverPubKey`) is re-established. This is acceptable: batch is a short-lived operation (15s intervals between batches), and the acceptance timeout (15s) will fire and show the boost/retry dialog, giving the user a recovery path. Persisting the full `contactedDrivers` map would add significant save/restore complexity for a narrow window.

3. **Add `saveRideState()` calls at offer initiation sites**:

   Phase 2.2 restructures `saveRideState()` to support pre-acceptance persistence (`acceptance == null`), but without explicit call sites at offer initiation, the restructured function is never invoked during `WAITING_FOR_ACCEPTANCE`. The existing 3 call sites (lines 2662, 3494, 3686+) all fire post-acceptance. This step adds the missing call sites, including boost paths that create replacement offers with updated state.

   **A. Batch offers — `sendRoadflareOfferSilent()` (function at line 1898)**:

   Batch flow (`sendRoadflareToAll()` → `sendRoadflareOfferSilent()`) does **NOT** use `applyOfferSuccessState()`. The first state transition to `WAITING_FOR_ACCEPTANCE` happens inline at line 1978, inside the first-offer `if` branch (lines 1965-1993). Add `saveRideState()` immediately after the `_uiState.update {}` block completes (after line 1993), still inside the `if` branch — before the `else` at line 1994:

   ```kotlin
   // In sendRoadflareOfferSilent(), first-offer branch:
   if (state.rideSession.pendingOfferEventId == null) {
       subscribeToAcceptance(eventId, driverPubKey)          // line 1966
       startAcceptanceTimeout()                               // line 1967
       clearRiderStateHistory()                               // line 1968
       // ...
       _uiState.update { current ->                           // lines 1971-1993
           current.copy(
               rideSession = current.rideSession.copy(
                   rideStage = RideStage.WAITING_FOR_ACCEPTANCE,  // line 1978
                   // ... other session fields
               )
           )
       }
       saveRideState()  // ← NEW: persist pre-acceptance state (batch first-offer)
   } else {
       // Subsequent offers in batch — only add subscriptions, no state change, NO save
   }
   ```

   Subsequent batch offers (the `else` branch, lines 1994-2000) only add subscriptions and do not change the ride session — no additional persistence needed.

   **B. Direct/single offers — `sendRideOffer()`, `sendRoadflareOffer()`, `sendRoadflareOfferWithAlternatePayment()`, `broadcastRideRequest()`**:

   These flows use `applyOfferSuccessState()` to set `WAITING_FOR_ACCEPTANCE` and populate the ride session. Call `saveRideState()` immediately after `applyOfferSuccessState()` returns, where the session is fully populated with offer context and `offerPaymentMethod`.

   **Placement summary (all 7 sites)**:

   | Method | Placement | Anchor |
   |--------|-----------|--------|
   | `sendRideOffer()` | After `applyOfferSuccessState()` | Uses `applyOfferSuccessState()` |
   | `sendRoadflareOffer()` | After `applyOfferSuccessState()` | Uses `applyOfferSuccessState()` |
   | `sendRoadflareOfferWithAlternatePayment()` | After `applyOfferSuccessState()` | Uses `applyOfferSuccessState()` |
   | `broadcastRideRequest()` | After `applyOfferSuccessState()` | Uses `applyOfferSuccessState()` |
   | `sendRoadflareToAll()` → `sendRoadflareOfferSilent()` | After `_uiState.update {}` (line 1993), inside first-offer `if` branch | Does **NOT** use `applyOfferSuccessState()` |
   | `boostDirectOffer()` (line 2172) | After the new offer is sent, acceptance re-subscribed, and timeout restarted (after line ~2271) | Creates new offer event + resets timeout — updated `pendingOfferEventId`, `directOfferBoostSats`, and restarted `acceptanceTimeoutStartMs` must survive restart |
   | `boostFare()` (line 2380) | After `broadcastRideRequest()` returns **successfully** (non-null), end of method (after line ~2445). Gated on success — if rebroadcast fails, skip save to avoid persisting corrupted state (stale/null `pendingOfferEventId` + updated boost amounts). | Creates new broadcast offer + resets timeout — updated `pendingOfferEventId`, `totalBoostSats`, and restarted `broadcastStartTimeMs` must survive restart |

   **C. Boost paths — `boostDirectOffer()` (line 2172) and `boostFare()` (line 2380)**:

   Both boost methods create replacement offers (new Nostr events with updated fare) and reset timers, but the existing plan omitted `saveRideState()` calls for them. Without persistence, an app restart during the boosted `WAITING_FOR_ACCEPTANCE` window loses the updated `pendingOfferEventId`, boost amount, and restarted timeout — the rider would restore to the pre-boost state and re-subscribe to the old (deleted) offer event, which will never receive an acceptance.

   - **`boostDirectOffer()`** (lines 2172-2286): Deletes the old offer, sends a new offer with boosted fare, re-subscribes to acceptance for the new event ID, and restarts the acceptance timeout. Add `saveRideState()` after the acceptance subscription and timeout restart (after line ~2271). The saved state captures the new `pendingOfferEventId`, updated `directOfferBoostSats`, and fresh `acceptanceTimeoutStartMs`.

   - **`boostFare()`** (lines 2380-2447): Deletes the old broadcast offer, updates fare state, and calls `broadcastRideRequest()` which creates a new broadcast and restarts the broadcast timeout. Add `saveRideState()` at the end of the method, **gated on successful rebroadcast** (after line ~2445). The saved state captures the new `pendingOfferEventId`, updated `totalBoostSats`, and fresh `broadcastStartTimeMs`.

     **Success gate**: `broadcastRideRequest()` returns `String?` — `null` on failure. If the rebroadcast fails, the old offer has already been deleted but no new offer exists. Persisting partial state (updated boost amounts but stale/null `pendingOfferEventId`) would cause a corrupted restore: the rider would restart into `WAITING_FOR_ACCEPTANCE` with no valid offer on relays and no subscription target. Gate `saveRideState()` on `newOfferEventId != null`:
     ```kotlin
     val newOfferEventId = broadcastRideRequest(...)
     if (newOfferEventId != null) {
         saveRideState()
     } else {
         // Rebroadcast failed — ride state is inconsistent (old offer deleted, no new offer).
         // Do NOT persist this state. Log error and let user manually retry via the boost/retry
         // dialog (the existing timeout will fire and show it), or cancel manually.
         // On app restart, the pre-boost saved state (from the earlier broadcastRideRequest()
         // save site in section B) is used — the old pendingOfferEventId is stale but the
         // timeout-expired restore path handles it gracefully.
         Log.e(TAG, "boostFare: rebroadcast failed, skipping state persistence — user must retry manually")
     }
     ```

   **Rollback on `boostFare()` failure**: If `broadcastRideRequest()` returns `null` (rebroadcast failed), `saveRideState()` is skipped to avoid persisting corrupted state. The failure is logged at ERROR level. The user's recovery path is: (1) the existing broadcast timeout fires and shows the boost/retry dialog, allowing manual retry, or (2) the user cancels the ride. No automatic rollback of the deleted old offer is attempted — the pre-boost saved state remains valid for app restart recovery.

   **Note**: `boostFare()` delegates to `broadcastRideRequest()`, which is already listed in section B as a `saveRideState()` site. However, the `broadcastRideRequest()` call within `boostFare()` happens after `boostFare()` updates boost-specific state (`totalBoostSats`, fare reset). If `broadcastRideRequest()`'s own `saveRideState()` fires before `boostFare()` updates state, the boost amounts are lost. To avoid this ordering dependency, place `saveRideState()` at the END of `boostFare()` (gated on success) — this captures both the boost state updates AND the new offer from `broadcastRideRequest()`. The `saveRideState()` inside `broadcastRideRequest()` (section B) is harmless — it fires first with partial boost state, then `boostFare()`'s call overwrites with complete state. Both calls are idempotent (last-writer-wins on the same SharedPreferences key).

4. **Gate `boostDirectOffer()` balance check on payment method** (line 2200):
   - Current code unconditionally calls `walletService?.getBalance()` to check Cashu balance before boosting.
   - Add guard: `if (rideSession.offerPaymentMethod == PaymentMethod.CASHU.value || rideSession.offerPaymentMethod == null) { /* existing balance check */ }`
   - Non-Cashu boosts (e.g., RoadFlare alternate method) skip the balance check entirely.
   - Uses stored `offerPaymentMethod` - never re-resolves from `defaultPaymentMethod`.

### Phase 3: Priority-Ordered Alternate Display (rider-app)

**File**: `rider-app/.../ui/screens/RiderModeScreen.kt`

Update 6 usages of `roadflarePaymentMethods` to use priority order instead of alphabetical sort:

1. **Line 124**: State collection - `Set<String>` type annotation becomes `List<String>`. The `collectAsState()` call itself is unchanged.
2. **Line 217**: Replace `.sorted().forEach` with `.forEach` - list is already priority-ordered from SettingsManager.
3. **Line 234**: `hasAlternateMethods` guard (`isNotEmpty()`) - works identically on `List` as on `Set`. No logic change needed.
4. **Line 273**: Replace `.sorted().firstOrNull()` with `.firstOrNull { it in PaymentMethod.ROADFLARE_ALTERNATE_METHODS.map { m -> m.value } }` — auto-default prefers the first **known** alternate method in priority order, per the Unknown Method Display Policy (Phase 1.1): unknown methods are shown and selectable by the user but never auto-selected. If no known method exists, returns `null` (same as empty-list behavior — user must deposit funds).
5. **Line 325**: Remove duplicate `settingsManager.roadflarePaymentMethods.collectAsState()` - use the screen-level state already collected at line 124.
6. **Line 610**: `isEmpty()` gate for batch send - works identically on `List`. No logic change.

**Key behavioral change**: Lines 217 and 273 currently sort alphabetically, making "cash" the implicit default alternate. After this change, the user's actual #1 priority alternate is the default.

**Phase 3.7: Driver-Side Payment Compatibility Indicator + Incompatibility Warning**

When the driver receives a ride offer (Kind 3173), their app parses the rider's `payment_methods` list from the offer event, compares it against the driver's own merged payment methods (`paymentMethods` + `roadflarePaymentMethods`), and shows a compatibility indicator on the offer card. **Incompatible offers are still shown** — the GREEN/AMBER/RED indicator plus a warning popup on accept of AMBER- or RED-indicated offers is the sole compatibility UX (rider-side filtering is deleted).

**Direct offers and broadcast requests are treated differently for privacy.** `RideOfferCard` (direct/RoadFlare offers via `RideOfferData`) shows the green/amber/red payment compatibility indicator and triggers the "No Common Payment Method" warning popup on accept — because direct offer content is NIP-44 encrypted and carries the full `payment_methods` list. `BroadcastRideRequestCard` (broadcast requests via `BroadcastRideOfferData`) does **NOT** show a payment compatibility indicator and does **NOT** carry `payment_methods` — because broadcast content is plaintext and exposing the rider's full payment methods list would be a privacy leak. The driver sees only the singular `payment_method` from the broadcast offer. The incompatibility popup is only shown for direct offers.

**Event model and parser changes (`RideOfferEvent.kt`)**:

The rider already sends a single `payment_method` string in Kind 3173 offers (both direct and broadcast). This is a natural extension of that existing pattern: the offer event is extended to also carry a `payment_methods` JSONArray — the rider's full merged payment methods list (standard + RoadFlare alternates, deduped). The driver parses this list directly from the offer event it receives (no additional subscriptions or event kinds needed). The existing singular `payment_method` field is unchanged for backward compatibility; the new list field is additive.

**Data class changes**:
- Add `paymentMethods: List<String> = listOf(paymentMethod)` to `RideOfferData` only. Default derives from the singular field so old-format events work without special handling.
- **`BroadcastRideOfferData` is NOT modified** — no `paymentMethods` field is added. Broadcast content is plaintext; adding the rider's full payment methods list would be a privacy leak.

**Parser changes** (in `RideOfferEvent.kt` parse methods):
- After parsing the singular `payment_method` field, parse `payment_methods` as a JSONArray from the same content JSON. If the array is present and non-empty, use it. If absent or empty (old rider app), fall back to `listOf(paymentMethod)` (the singular field wrapped in a list). This fallback is the data class default — the parser only needs to set the field when the array IS present.

**Creation changes** (end-to-end: `NostrService.kt` → `RideshareDomainService.kt` → `RideOfferEvent.kt`):
- When building the Kind 3173 content JSON for **direct/RoadFlare offers only** (`sendRideOffer()`), include a `payment_methods` JSONArray containing the rider's full merged payment methods list from `settingsManager.paymentMethods.value + settingsManager.roadflarePaymentMethods.value` (deduped, preserving priority order). The singular `payment_method` field continues to be set to `defaultPaymentMethod` as today.
- **`broadcastRideRequest()` is NOT modified** — broadcast content is plaintext, so the `payment_methods` list is not included. Broadcasts continue to carry only the singular `payment_method` field.
- The `sendRideOffer()` creation method already accepts `paymentMethod: String` — add an additional `paymentMethods: List<String>` parameter (with default `listOf(paymentMethod)` for backward compatibility with any internal callers). The caller in `RiderViewModel.kt` passes the merged list.
- **NostrService.kt facade threading**: `NostrService.sendRideOffer()` (line 761) is a pure pass-through facade that delegates to `RideshareDomainService`. It must add `paymentMethods: List<String> = listOf(paymentMethod ?: "cashu")` to its signature and forward the parameter. Updated signature:
  ```kotlin
  // NostrService.sendRideOffer() — add after existing paymentMethod parameter:
  suspend fun sendRideOffer(
      ...,
      paymentMethod: String? = "cashu",
      paymentMethods: List<String> = listOf(paymentMethod ?: "cashu"),  // NEW
      isRoadflare: Boolean = false
  ): String?
  ```
  `NostrService.broadcastRideRequest()` signature is **unchanged** — no `paymentMethods` parameter added.

**RideshareEventKinds.kt KDoc update**: Update the KDoc comment for `RIDE_OFFER = 3173` (line 33-38) to document that Kind 3173 offer content now carries both the singular `payment_method` string (existing) and the new `payment_methods` JSONArray (rider's full merged payment methods list — standard + RoadFlare alternates, deduped). The singular field is preserved for backward compatibility; the array enables driver-side compatibility checking. No new constant is needed — the existing `RIDE_OFFER` constant covers both fields. Updated KDoc:
```kotlin
/**
 * Kind 3173: Ride Offer Event (Regular)
 * Sent by riders to request a ride from a specific driver.
 * References the driver's availability event.
 *
 * Transport variants:
 * - Direct/RoadFlare (NIP-44 encrypted): Contains singular `payment_method`
 *   (rider's default) AND `payment_methods` JSONArray (rider's full merged
 *   list for driver-side compatibility checking).
 * - Broadcast (plaintext): Contains only singular `payment_method`.
 *   The `payment_methods` array is intentionally omitted to avoid leaking
 *   the rider's full payment preferences in unencrypted content.
 */
const val RIDE_OFFER = 3173
```

**Driver-side parsing**: The driver's `subscribeToOffers()` callback already receives `RideOfferData`. Once the data class carries the `paymentMethods` field, the driver ViewModel receives the list automatically through the existing callback chain — no callback signature changes needed. `subscribeToBroadcastRideRequests()` is unchanged — `BroadcastRideOfferData` does not carry `paymentMethods`.

**Rider-side change (deletion only)**: Delete `isPaymentCompatible()` (lines 4756-4763, private, single call site) and the filtering block (lines 2985-3014) from `RiderViewModel.kt`. All drivers are shown to the rider regardless of payment compatibility — no indicators, no popup on the rider side. The rider sends the offer; the driver decides.

**Code to delete (lines 2985-3014)**: The `isPaymentCompatible()` filtering block and the `isPaymentCompatible()` function itself (lines 4756-4763). The block has 5 side effects (removes tracking, closes profile subscription, removes from `availableDrivers`, clears profile, resets `selectedDriver`) — none are needed when all drivers are shown.

**Driver-side offer card indicator** (new logic in driver app — applies to `RideOfferCard` only, NOT `BroadcastRideRequestCard`):
- Read `paymentMethods: List<String>` from the parsed `RideOfferData` (already populated by the parser — see model changes above). The parser handles the fallback to `listOf(paymentMethod)` for old rider apps.
- Read the singular `paymentMethod` field — this is the method the ride will actually execute with (the rider's `defaultPaymentMethod`, typically `"cashu"`).
- Compute the intersection of rider's `paymentMethods` list and the driver's own merged methods (standard `paymentMethods` + `roadflarePaymentMethods`).
- **THREE indicator states** (evaluated in order):
  1. **GREEN** (`Color(0xFF2E7D32)`): The singular `paymentMethod` (the executed method) is in the driver's merged methods. Display the method name. Example: rider sends `payment_method="cashu"`, `payment_methods=["cashu","zelle"]`, driver has `["cashu","venmo"]` → `"cashu"` is in driver's list → show "Bitcoin (Cashu)" in green.
  2. **AMBER** (`Color(0xFFF57F17)`): The singular `paymentMethod` is NOT in the driver's methods, but there IS overlap on alternate methods. Display "Alt: {best common alternate}" in amber. This warns the driver that the default payment path (Cashu HTLC) may fail, but an alternate method exists for out-of-band settlement. Example: rider sends `payment_method="cashu"`, `payment_methods=["cashu","zelle"]`, driver has `["zelle","venmo"]` → `"cashu"` not in driver's list, but `"zelle"` overlaps → show "Alt: Zelle" in amber.
  3. **RED** (`MaterialTheme.colorScheme.error`): No common methods at all. Display "No common payment method" in red. The offer card is still shown (not hidden or auto-declined).
- **Rationale for three states:** GREEN means the automated payment path works. RED means no overlap at all. AMBER is the critical new state: it prevents the false-green where alternate-only overlap misleads the driver into thinking the automated Cashu HTLC will succeed, when in reality the parties will need to negotiate via chat (Kind 3178) or alternate payment rails.

**Broadcast request coverage**: `BroadcastRideRequestCard` does **NOT** show a payment compatibility indicator. Broadcast content is plaintext — adding `payment_methods` would leak the rider's full payment method preferences publicly. The driver sees only the singular `payment_method` from the broadcast. No incompatibility popup is shown when accepting broadcast requests. If the driver's payment methods are incompatible with the rider's default method, this is discovered post-acceptance (the HTLC escrow fails or the parties negotiate via chat).

**Accept-time incompatibility popup** (shown when driver taps Accept on a red-indicated **direct** offer only):
- **Title**: "No Common Payment Method"
- **Body**: Displays the rider's full payment methods list in human-readable format (display names via `PaymentMethod.fromString()?.displayName ?: rawString`). Example: "Rider accepts: Bitcoin (Cashu), Lightning, Cash".
- **Actions**: "Accept Anyway" and "Decline" (dismisses popup, does not accept the ride).
- **"Accept Anyway" behavior**: Proceeds with normal ride acceptance. The acceptance event (Kind 3174) writes the offer's original `payment_method` (the rider's `defaultPaymentMethod`, typically `"cashu"`) — unchanged from the normal acceptance flow. Both rider and driver understand that payment will be attempted via the rider's default method (Cashu HTLC escrow). If it fails (e.g., driver doesn't support the rider's mint or payment method), they negotiate payment out-of-band via chat (Kind 3178). The existing `escrowToken=null` fallback applies (see "Known TODOs: Escrow Bypass" in CLAUDE.md).
- **Implementation**: Add `showIncompatiblePaymentDialog` state to the driver's ViewModel or UI state. The state holds a reference to the `RideOfferData` being accepted. The `AlertDialog` composable lives alongside existing driver offer UI. "Accept Anyway" routes to `acceptOffer()`.
- **Rationale**: The driver may still want to accept if they can negotiate payment through other means (e.g., the rider may have physical cash). The popup ensures informed consent without blocking the transaction entirely.
- **Not applicable to broadcast offers**: Broadcast requests do not carry `payment_methods` (privacy — plaintext content), so no compatibility indicator or popup is shown for broadcasts.

**Accept Anyway — acceptance event `payment_method` behavior**: When the driver taps "Accept Anyway" on an incompatible offer, the acceptance event (Kind 3174) writes the **offer's original `payment_method`** (the rider's `defaultPaymentMethod`, typically `"cashu"`). This is unchanged from the normal acceptance flow — `RideAcceptanceEvent.create()` (line 48) echoes the offer's `paymentMethod` regardless of compatibility. **Rationale**: The `payment_method` field in Kind 3174 records the rider's *requested* method, not a negotiated agreement. The rider's app uses this field to determine the payment path (HTLC escrow, cross-mint bridge, etc.). Writing a different value or omitting it would break the rider's payment flow logic. Both rider and driver proceed with the standard Cashu HTLC escrow attempt. If escrow setup fails (e.g., driver doesn't support the rider's mint, or payment method mismatch), the existing `escrowToken=null` fallback applies (see "Known TODOs: Escrow Bypass" in CLAUDE.md) and the parties negotiate payment out-of-band via chat (Kind 3178) — the protocol events don't model out-of-band payment agreements.

**When driver taps Accept on a green-indicated offer**: Normal acceptance flow proceeds without any popup (direct offers only — broadcasts have no compatibility indicator).

**When driver taps Accept on an amber-indicated offer**: The same "No Common Payment Method" warning popup is shown (the automated payment path won't work). "Accept Anyway" proceeds normally — the HTLC attempt may fail, falling back to out-of-band settlement via the alternate method shown in amber.

**What is NOT on the rider side (design simplification)**:
- No `driverPaymentMethods: Map<String, List<String>>` state in `RiderUiState` — the rider does not need to know the driver's payment methods for compatibility checking.
- No Kind 30173 subscription for payment method data on the rider side — compatibility is checked entirely on the driver side.
- No green/amber/red indicators on `SwipeableDriverCard` or `RoadflareDriverCard` — the rider's driver cards show no payment compatibility information.
- No incompatibility warning popup on the rider side — the rider sends the offer regardless; the driver decides whether to accept.

**Scope**: This phase has three parts: (1) **Event model**: Add `paymentMethods: List<String>` to `RideOfferData` only (NOT `BroadcastRideOfferData` — broadcast content is plaintext, privacy risk), update `RideOfferEvent.kt` parser/creation to handle `payment_methods` JSONArray for direct offers. (2) **Rider deletion**: Delete `isPaymentCompatible()` (private function, single call site) and its filtering block from the rider ViewModel — all drivers shown regardless of compatibility. (3) **Driver-side UI**: Parse `paymentMethods` from direct offers only, show three-state indicator (green/amber/red) on `RideOfferCard` only, show "Accept Anyway" / "Decline" warning popup on accept of amber- or red-indicated direct offers (`acceptOffer()` path only). Broadcast requests show no payment compatibility indicator.

### Phase 4: Backup/Restore (common module)

**Files**: `common/.../settings/SettingsManager.kt` (actionable change) + `common/.../nostr/events/ProfileBackupEvent.kt` (verification only)

The `SettingsBackup` data class (`ProfileBackupEvent.kt` line 171) has `roadflarePaymentMethods: List<String>`. It is serialized in `toJson()` (lines 208-212) as a JSONArray and deserialized in `fromJson()` (lines 264-268). **Both `toJson()` and `fromJson()` already handle ordered lists correctly — no changes needed in `ProfileBackupEvent.kt`.**

The actionable code change is in **`SettingsManager.kt`**:

1. **`ProfileBackupEvent.toJson()`**: Already serializes `roadflarePaymentMethods` as ordered JSONArray. Order now becomes semantically meaningful. **No code change needed — verify only.**

2. **`ProfileBackupEvent.fromJson()`**: Already reads as ordered list from JSONArray. **No code change needed — verify only.**

3. **`SettingsManager.restoreFromBackup()`** (SettingsManager.kt line 734): Currently calls `setRoadflarePaymentMethods(backup.roadflarePaymentMethods.toSet())` — the `.toSet()` provided implicit deduplication. Replace with `setRoadflarePaymentMethods(backup.roadflarePaymentMethods)` passing `List<String>` directly. Dedup is handled inside the setter (Phase 1.1 single-tier validation: filter blank, dedup). Unknown future methods from backup are preserved per the forward-compat invariant. The `.toSet()` is no longer needed and would destroy order. This is also listed in the Phase 1 migration checklist (line 734).

4. **`SettingsManager.toBackupData()`** (SettingsManager.kt line 705): Currently calls `_roadflarePaymentMethods.value.toList()`. After Phase 1 migration, `_roadflarePaymentMethods` is already `List<String>`, so the `.toList()` becomes a no-op and can be removed for clarity.

5. **No changes to `defaultPaymentMethod` in backup** — it stays in `SettingsBackup` as-is.

6. **No changes to `paymentMethods` in backup** — standard methods list backup is unchanged.

**Backward compatibility**: Old backups with unordered `roadflarePaymentMethods` arrays are loaded in insertion order (JSONArray preserves order). New backups with ordered arrays are loaded correctly by old code (old code converts to Set, losing order - acceptable degradation since old code didn't use order).

### Phase 5: Reorderable UI (common module)

**New file**: `common/.../ui/components/RoadflarePaymentMethodPriorityList.kt`

Shared composable for both rider and driver Settings screens.

1. **Data structure for UI state**:
   ```
   data class AlternatePaymentMethodItem(
       val method: PaymentMethod,
       val enabled: Boolean,  // true if in user's roadflarePaymentMethods list
       val priorityIndex: Int  // position in priority list (-1 if disabled)
   )
   ```

2. **Composable: `RoadflarePaymentMethodPriorityList`**:
   - Takes `settingsManager: SettingsManager`
   - Observes `roadflarePaymentMethods` StateFlow
   - Shows all 6 `ROADFLARE_ALTERNATE_METHODS` in two sections:
     - **Enabled** (top): Checked, draggable, shown in priority order
     - **Available** (bottom): Unchecked, static, greyed out
   - Each enabled row: drag handle (left), checkbox (checked), method display name
   - Each available row: no drag handle, checkbox (unchecked), method display name (greyed)
   - Drag gestures: `detectDragGesturesAfterLongPress()` on handle icon
   - Visual feedback: elevated card + scale during drag
   - Haptic: `LongPress` on start, subtle tick on reorder
   - Toggle: checking adds to bottom of enabled section, unchecking moves to available

3. **Drag-and-drop implementation** (manual `LazyColumn` gesture tracking, no external library):
   - Use `LazyColumn` with `rememberLazyListState()`
   - Track drag state: `draggingItemIndex: Int?`, `dragOffset: Float`, `targetIndex: Int?`
   - Drag handle uses `Modifier.pointerInput` with `detectDragGesturesAfterLongPress()` — only the handle icon initiates drag, not the full row
   - On drag: calculate target index from accumulated offset vs item heights, visually swap items in local state
   - On drop: commit reorder via `settingsManager.moveRoadflarePaymentMethod(method.value, targetIndex)` — uses method string key, not raw list index (see Phase 1.2 index mapping strategy)
   - **Not using** Compose Foundation `Modifier.dragAndDropSource/Target` — that API targets inter-component/inter-app drag-and-drop (clipboard MIME types), not intra-list reordering

### Phase 6: Settings Screen Integration

**File**: `rider-app/.../ui/screens/SettingsScreen.kt`

1. Add `SettingsNavigationRow` for "RoadFlare Payment Methods":
   ```
   SettingsNavigationRow(
       title = "RoadFlare Payment Methods",
       description = "Set alternate payment method priority",
       icon = Icons.Default.Payment,
       onClick = onOpenRoadflarePaymentMethods
   )
   ```
2. Add `onOpenRoadflarePaymentMethods: () -> Unit` parameter
3. Wire navigation in rider MainActivity

**File**: `drivestr/.../ui/screens/SettingsScreen.kt`

4. Same navigation row addition for driver app
5. Wire navigation in driver MainActivity

**New file**: `common/.../ui/RoadflarePaymentMethodsScreen.kt`

6. Full-screen composable wrapping `RoadflarePaymentMethodPriorityList` with TopAppBar

### Phase 7: Replace Existing Alternate Method UIs

1. **Replace `RoadflarePaymentMethodsDialog`** in rider `RoadflareTab.kt` (lines 857-930) with navigation to the new `RoadflarePaymentMethodsScreen`. The dialog currently shows unordered checkboxes - the new screen adds reordering.

2. **Replace `PaymentMethodsContent`** in driver `RoadflareTab.kt` (line 790) with navigation to the new screen. Currently uses `settingsManager.roadflarePaymentMethods.collectAsState()` and renders checkboxes for `ROADFLARE_ALTERNATE_METHODS`.

3. **Update payment FAB** on RoadFlare tab - redirect to new settings screen instead of opening the dialog.

4. **`PaymentMethodsCard` in WalletScreen.kt** (lines 90-93, commented out) - leave commented. This was intended for standard methods, which are not part of the reorderable list.

5. **Update batch alternate display order** in the insufficient funds dialog UI (`RiderModeScreen.kt` lines 273-279) and initial batch payment selection dialog (`RiderModeScreen.kt` lines 204-229): Display alternate payment options in priority order from `roadflarePaymentMethods` instead of alphabetical/arbitrary order. Auto-default uses `roadflarePaymentMethods.firstOrNull { it in knownAlternateMethods }` to prefer known `ROADFLARE_ALTERNATE_METHODS` entries — unknown forward-compat strings are displayed but never auto-selected (see Phase 3 line 273 guardrail). Note: `retryBatchWithAlternatePayment()` (RiderViewModel.kt line 2861) is the ViewModel handler called BY the UI - it has no dialog of its own.

6. **Replace inline "Set Alternative Payment" dialog in `RiderModeScreen.kt`** (lines 323-382): The `showAlternatePaymentSetupDialog` dialog uses unordered checkboxes to toggle `roadflarePaymentMethods` - inconsistent with the new reorderable UI. Replace the inline `AlertDialog` with navigation to `RoadflarePaymentMethodsScreen`. Update the trigger path (`viewModel.showAlternatePaymentSetup()` and `viewModel.dismissAlternatePaymentSetup()`) to use navigation instead of dialog state. Remove the `showAlternatePaymentSetupDialog` field from rider `UiState` if no other consumers exist.

---

## 6. Files Changed Summary

| File | Change | Module |
|------|--------|--------|
| `SettingsManager.kt` | Convert `roadflarePaymentMethods` from `Set<String>` to `List<String>` (9-site migration: lines 523, 524, 526, 528, 529, 532, 705, 734, 765). Add `moveRoadflarePaymentMethod(method, toVisibleIndex)` (method-string key API with known/unknown index mapping — see Phase 1.2), `toggleRoadflarePaymentMethod()`. Add input validation/dedup to setter (single tier: filter blank, dedup — no strict validation, preserves unknown future methods). Add basic validation to `setPaymentMethods()` (filter blank, dedup). Add cashu-only migration to `setDefaultPaymentMethod()` (coerce non-cashu values including `"lightning"`/`"fiat_cash"` to "cashu" — runtime cannot route non-cashu methods). Add to `syncableSettingsHash`. | common |
| `RiderViewModel.kt` | Add `offerPaymentMethod` to `RiderRideSession`. Restructure `saveRideState()`/`restoreRideState()` for pre-acceptance persistence (`hasAcceptance` flag, `optString()` migration, persist `acceptanceTimeoutStartMs`/`broadcastStartTimeMs`/`offerTargetDriverPubKey`). Add pre-acceptance restore behavioral rehydration (stage-branched re-subscription + elapsed-time timeout restart via new `startAcceptanceTimeoutWithDelay()`/`startBroadcastTimeoutWithDelay()` helpers). Add `saveRideState()` calls at 7 sites: 5 offer initiation sites + `boostDirectOffer()` + `boostFare()` (boost paths create replacement offers with updated state that must survive restart). Set at offer creation, read by boost. Gate boost balance check on Cashu. Delete `isPaymentCompatible()` (lines 4756-4763) and filtering block (lines 2985-3014) — compatibility checking moved to driver side (Phase 3.7). | rider-app |
| `RiderModeScreen.kt` | Update 6 `roadflarePaymentMethods` usages (lines 124, 217, 234, 273, 325, 610): replace `.sorted()` with priority order, fix duplicate state collection, update type from Set to List. Line 273 auto-default uses `firstOrNull { it in knownAlternateMethods }` guardrail (skip unknown forward-compat strings). Replace inline alternate payment setup dialog (lines 323-382) with navigation to `RoadflarePaymentMethodsScreen` (Phase 7.6). No payment compatibility indicators or popups on the rider side — all compatibility UX moved to the driver side (Phase 3.7). | rider-app |
| `ProfileBackupEvent.kt` | **No code changes** — `SettingsBackup`, `toJson()`, `fromJson()` already handle ordered `List<String>`. Verify only. The actionable restore fix (`.toSet()` removal) is in `SettingsManager.kt` line 734. | common |
| **NEW** `RoadflarePaymentMethodPriorityList.kt` | Reorderable checklist for 6 RoadFlare alternate methods | common |
| **NEW** `RoadflarePaymentMethodsScreen.kt` | Full-screen wrapper with TopAppBar | common |
| `SettingsScreen.kt` (rider) | Add navigation row for RoadFlare payment methods | rider-app |
| `SettingsScreen.kt` (driver) | Add navigation row for RoadFlare payment methods | drivestr |
| `MainActivity.kt` (rider) | Add navigation route | rider-app |
| `MainActivity.kt` (driver) | Add navigation route | drivestr |
| `RoadflareTab.kt` (rider) | Replace `RoadflarePaymentMethodsDialog` with navigation to new screen | rider-app |
| `RoadflareTab.kt` (driver) | Replace `PaymentMethodsContent` with navigation to new screen | drivestr |
| `RideOfferEvent.kt` | Add `paymentMethods: List<String>` to `RideOfferData` only (NOT `BroadcastRideOfferData` — broadcast content is plaintext, privacy risk). Update parser to read `payment_methods` JSONArray with fallback to singular field for direct offers. Update `sendRideOffer()` creation to include `payment_methods` JSONArray in Kind 3173 content. Phase 3.7. | common |
| `NostrService.kt` | Add `paymentMethods: List<String>` parameter to `sendRideOffer()` (line 761) only. Pure pass-through to `RideshareDomainService`. `broadcastRideRequest()` unchanged (no `paymentMethods` — plaintext privacy). Phase 3.7. | common |
| `RideshareDomainService.kt` | Pass rider's merged payment methods list to `sendRideOffer()` only. Add `paymentMethods: List<String>` parameter alongside existing `paymentMethod: String`. `broadcastRideRequest()` unchanged. Phase 3.7. | common |
| `RideshareEventKinds.kt` | **KDoc update only**: Update `RIDE_OFFER` (Kind 3173) KDoc to document that direct offer content now carries both singular `payment_method` string and new `payment_methods` JSONArray (rider's full merged payment methods list). Broadcast offers carry singular field only. No constant value changes, no enum changes. Phase 3.7. | common |
| `DriverViewModel.kt` | Read `paymentMethods` from parsed `RideOfferData` (direct offers only — `BroadcastRideOfferData` has no `paymentMethods` field). Compute intersection with driver's merged methods (`paymentMethods` + `roadflarePaymentMethods`). Expose GREEN/AMBER/RED compatibility state and highest-priority common method for direct offer card indicator. Add `showIncompatiblePaymentDialog` state for accept-time warning popup (AMBER- or RED-indicated direct offers only). Phase 3.7. | drivestr |
| Driver offer card UI (composable) | Add green/amber/red payment compatibility indicator to `RideOfferCard` only (NOT `BroadcastRideRequestCard` — broadcasts lack `payment_methods` for privacy). Add "No Common Payment Method" warning popup (`AlertDialog`) with rider's methods and "Accept Anyway" / "Decline" actions — triggers on accept of amber- or red-indicated direct offers only. Phase 3.7. | drivestr |

**Files NOT changed** (explicitly unchanged by design):
| File | Reason |
|------|--------|
| `RideshareEventKinds.kt` | `PaymentMethod` enum and `ROADFLARE_ALTERNATE_METHODS` unchanged (KDoc-only update — see Files Changed) |
| `DriverAvailabilityEvent.kt` | Kind 30173 broadcast unchanged — no merge of RoadFlare alternates |
| `WalletScreen.kt` | Commented `PaymentMethodsCard` left as-is (for standard methods) |

---

## 7. Testing Strategy

1. **Unit tests for `SettingsManager` ordering** (Robolectric for SharedPreferences):
   - `moveRoadflarePaymentMethod("zelle", 2)` reorders correctly (method-string key, not raw index)
   - `toggleRoadflarePaymentMethod("zelle", true)` adds to end of list
   - `toggleRoadflarePaymentMethod("zelle", false)` removes from list
   - Round-trip: set → save → reload → verify order preserved
   - `setRoadflarePaymentMethods(emptyList())` → defaults to empty list (no fallback needed for alternates)
   - `setRoadflarePaymentMethods(listOf(“zelle”, “zelle”, “venmo”))` → deduplicates to `[“zelle”, “venmo”]` preserving first occurrence
   - `setRoadflarePaymentMethods(listOf(“”, “future_method”, “zelle”))` → filters blank entries, preserves unrecognized `”future_method”` for forward compatibility (`[“future_method”, “zelle”]`)
   - `toggleRoadflarePaymentMethod(“venmo”, true)` on a list containing unknown `”future_method”` → adds venmo, unknown entry preserved (validation only filters blanks/dupes)
   - `toggleRoadflarePaymentMethod(“future_method”, false)` → removes the specific entry, other entries preserved
   - `moveRoadflarePaymentMethod("zelle", 1)` on stored list `["future_method", "zelle", "venmo"]` → known items reordered to `["venmo", "zelle"]`, unknown `"future_method"` appended as stable tail → result `["venmo", "zelle", "future_method"]`
   - `moveRoadflarePaymentMethod("venmo", 0)` on stored list `["zelle", "future_method", "venmo"]` → known items reordered to `["venmo", "zelle"]`, unknown appended → result `["venmo", "zelle", "future_method"]`
   - `setPaymentMethods(listOf(“cashu”, “”, “cashu”, “  “))` → filters blank, deduplicates to `[“cashu”]`
   - `syncableSettingsHash` changes when `roadflarePaymentMethods` order changes (backup trigger)

2. **Unit tests for `offerPaymentMethod` persistence and pre-acceptance save/restore**:
   - `saveRideState()` includes `offerPaymentMethod` in JSON output
   - `restoreRideState()` reconstructs `offerPaymentMethod` from JSON
   - Boost after restore uses stored `offerPaymentMethod`, not current `defaultPaymentMethod`
   - Old saved state missing `offerPaymentMethod` → field is `null` → boost falls back to `defaultPaymentMethod.value`
   - `offerPaymentMethod` set to non-Cashu value → `boostDirectOffer()` skips balance check
   - `offerPaymentMethod` set to Cashu → `boostDirectOffer()` performs balance check
   - `sendRoadflareOfferWithAlternatePayment("paypal")` → `offerPaymentMethod` is `"paypal"` → boost reuses `"paypal"` (no drift to Cashu)
   - Pre-acceptance `saveRideState()` persists `offerPaymentMethod` even when `acceptance == null` (restructured save gate)
   - Pre-acceptance save → restore: `hasAcceptance=false` → acceptance is `null`, `offerPaymentMethod` restored correctly, no crash
   - Post-acceptance save → restore: `hasAcceptance=true` → full acceptance + `offerPaymentMethod` restored correctly
   - Old saved state (missing `hasAcceptance`) → `optBoolean("hasAcceptance", true)` defaults to `true` → full restore path, backward compatible
   - Corrupt pre-acceptance save (missing required pre-acceptance fields) → logs warning, calls `clearSavedRideState()` gracefully
   - Pre-acceptance restore in `WAITING_FOR_ACCEPTANCE` stage: `subscribeToAcceptance()` called with restored `pendingOfferEventId` and `offerTargetDriverPubKey`, `subscribeToSelectedDriverAvailability()` called, timeout restarted with correct remaining time
   - Pre-acceptance restore in `BROADCASTING_REQUEST` stage: `subscribeToAcceptancesForBroadcast()` called with restored `pendingOfferEventId`, `hasAcceptedDriver` reset to `false`, broadcast timeout restarted with correct remaining time
   - Pre-acceptance restore with expired timeout (elapsed > timeout duration): timeout fires immediately via `coerceAtLeast(0L)`, shows boost/retry dialog
   - Pre-acceptance restore: `RiderActiveService.startSearching()` called for both `WAITING_FOR_ACCEPTANCE` and `BROADCASTING_REQUEST` stages
   - Save/restore round-trip preserves `acceptanceTimeoutStartMs`, `broadcastStartTimeMs`, `offerTargetDriverPubKey`
   - Regular direct offer → save → restore → `boostDirectOffer()` uses restored `offerTargetDriverPubKey` (not null) when `selectedDriver` is null — boost succeeds
   - Broadcast offer → save → restore → `boostDirectOffer()` returns early (`offerTargetDriverPubKey` is null for broadcasts, expected — broadcast boost uses different path)
   - **Boost persistence**: `boostDirectOffer()` → `saveRideState()` called → saved JSON contains updated `pendingOfferEventId` (new offer), updated `directOfferBoostSats`, and restarted `acceptanceTimeoutStartMs`
   - **Boost persistence**: `boostFare()` → `saveRideState()` called → saved JSON contains updated `pendingOfferEventId` (new broadcast), updated `totalBoostSats`, and restarted `broadcastStartTimeMs`
   - **Boost + restart**: `boostDirectOffer()` → save → kill app → restore → verify boosted offer context restored (new event ID, boosted fare, correct remaining timeout)
   - **Boost + restart**: `boostFare()` → save → kill app → restore → verify boosted broadcast context restored (new event ID, boosted fare, correct remaining timeout)

3. **UI tests**:
   - Drag reorder changes persisted order
   - Toggle method on adds to bottom of enabled section
   - Toggle method off removes from enabled section
   - Disabled methods not draggable
   - Only 6 RoadFlare alternates shown (no standard methods in list)
   - Alternate display in RiderModeScreen uses priority order (not alphabetical)
   - Auto-default alternate selection skips unknown methods: list `["future_method", "zelle"]` → auto-selects `"zelle"` (first known method), not `"future_method"`
   - Auto-default with only unknown methods: list `["future_method"]` → returns `null` (no auto-selection, same as empty list behavior)
   - **All drivers shown to rider**: Rider's available driver list shows all drivers regardless of payment compatibility (no filtering, no indicators, no popup on rider side)
   - **Driver offer card indicator (green, direct only)**: Rider sends direct offer with `payment_methods = ["cashu", "zelle"]`, driver has `["cashu", "venmo"]` → singular `"cashu"` in driver's list → driver sees "Bitcoin (Cashu)" in green. Accept proceeds normally (no popup).
   - **Driver offer card indicator (amber, direct only)**: Rider sends direct offer with `payment_method = "cashu"`, `payment_methods = ["cashu", "zelle"]`, driver has `["zelle", "venmo"]` → singular `"cashu"` NOT in driver's list, but `"zelle"` overlaps → driver sees "Alt: Zelle" in amber. Accept shows warning popup ("No Common Payment Method" dialog with "Accept Anyway" / "Decline").
   - **Driver offer card indicator (red, direct only)**: Rider sends direct offer with `payment_methods = ["lightning", "zelle"]`, driver has `["cashu", "venmo"]` → no common methods → driver sees "No common payment method" in red. Accept shows warning popup.
   - **Driver offer card indicator (fallback, direct only)**: Old rider app sends direct offer without `payment_methods` field, only `payment_method = "cashu"` → driver falls back to `["cashu"]` → compatibility check proceeds normally.
   - **Driver incompatibility popup (direct only, amber)**: On accept of amber-indicated direct offer → shows "No Common Payment Method" warning popup with rider's methods in display-name format and "Accept Anyway" / "Decline" actions. Same popup as red — amber warns that the automated Cashu HTLC path won't work despite alternate overlap.
   - **Driver incompatibility popup (direct only, red)**: On accept of red-indicated direct offer → shows "No Common Payment Method" dialog with rider's methods in display-name format (e.g., "Lightning, Zelle" not "lightning, zelle").
   - **Driver incompatibility popup — Accept Anyway**: Proceeds with normal ride acceptance flow. The acceptance event (Kind 3174) echoes the rider's default `payment_method` (typically `"cashu"`). Both parties attempt Cashu HTLC escrow. If escrow fails (e.g., driver doesn't support the rider's mint), they negotiate payment out-of-band via chat (Kind 3178). The existing `escrowToken=null` fallback applies.
   - **Driver incompatibility popup — Decline**: Dismisses popup, does not accept the ride.
   - **Driver priority order in amber indicator**: Rider sends direct offer with `payment_method = "cashu"`, `payment_methods = ["cashu", "zelle", "venmo"]`, driver has `["venmo", "zelle"]` (no cashu) → singular `"cashu"` NOT in driver's list → AMBER. Common alternates: `"zelle"` and `"venmo"`. Highest-priority common (driver order) = `"venmo"` → driver sees "Alt: Venmo" in amber.
   - **Broadcast request card — no payment indicator**: Broadcast content is plaintext; `BroadcastRideOfferData` does NOT carry `payment_methods`. No GREEN/AMBER/RED indicator shown. No incompatibility popup on accept. Payment compatibility is discovered post-acceptance (HTLC escrow attempt or out-of-band negotiation).

   **AMBER indicator edge cases** (the critical middle state — automated payment path likely fails but alternate overlap exists):
   - **AMBER with single alternate overlap**: Rider `payment_method = "cashu"`, `payment_methods = ["cashu", "zelle"]`, driver has `["zelle"]` (no cashu, no standard overlap) → singular `"cashu"` NOT in driver's list → AMBER. One common alternate `"zelle"` → show "Alt: Zelle" in amber.
   - **AMBER with multiple alternate overlaps (driver priority order)**: Rider `payment_method = "cashu"`, `payment_methods = ["cashu", "zelle", "venmo", "cash_app"]`, driver has `["cash_app", "venmo"]` (no cashu) → AMBER. Common alternates: `"venmo"` and `"cash_app"`. Highest-priority common (by **driver's** `roadflarePaymentMethods` order) = `"cash_app"` → show "Alt: Cash App" in amber.
   - **AMBER with rider's default not in rider's own list (defensive)**: Rider `payment_method = "lightning"`, `payment_methods = ["cashu", "zelle"]`, driver has `["zelle"]` → singular `"lightning"` NOT in driver's list → check alternates → `"zelle"` overlaps → AMBER, show "Alt: Zelle". (Singular `payment_method` may diverge from list contents due to old app or misconfig.)
   - **AMBER accept → popup → Accept Anyway**: Same "No Common Payment Method" popup as RED. Driver sees rider's full method list in display-name format. "Accept Anyway" proceeds with standard Cashu HTLC escrow attempt using the rider's `payment_method` (typically `"cashu"`). If escrow fails, parties negotiate via the amber-indicated alternate method through chat (Kind 3178).
   - **AMBER accept → popup → Decline**: Popup dismissed, offer not accepted. Offer stays visible in the pending list.
   - **AMBER vs GREEN boundary**: Rider `payment_method = "cashu"`, `payment_methods = ["cashu", "zelle"]`, driver has `["cashu"]` → singular `"cashu"` IS in driver's list → **GREEN** (not AMBER). The alternate overlap on `"zelle"` is irrelevant — GREEN takes priority because the automated path works.
   - **AMBER vs RED boundary**: Rider `payment_method = "cashu"`, `payment_methods = ["cashu"]`, driver has `["zelle"]` → singular `"cashu"` NOT in driver's list → check alternates → `"cashu"` (standard) vs `"zelle"` (alternate) — no overlap on alternates either → **RED** (not AMBER). AMBER requires at least one common non-standard method.
   - **AMBER with unknown future method overlap**: Rider `payment_method = "cashu"`, `payment_methods = ["cashu", "future_method"]`, driver has `["future_method", "venmo"]` → singular `"cashu"` NOT in driver's list → check alternates → `"future_method"` overlaps → AMBER, show "Alt: future_method" (raw string, no display name mapping for unknown methods).

   - `setDefaultPaymentMethod("cashu")` → accepted (only valid value currently)
   - `setDefaultPaymentMethod("lightning")` → coerced to "cashu" with log warning (Lightning routing not implemented — cashu-only migration, Phase 1.4)
   - `setDefaultPaymentMethod("fiat_cash")` → coerced to "cashu" with log warning (fiat-cash routing not implemented — cashu-only migration, Phase 1.4)
   - `setDefaultPaymentMethod("zelle")` → coerced to "cashu" with log warning (alternate methods cannot be the default — cashu-only migration, Phase 1.4)
   - `setDefaultPaymentMethod("future_method")` → coerced to "cashu" with log warning (unknown strings not routable — cashu-only migration, Phase 1.4)

4. **Backup/restore round-trip**:
   - Order preserved through Kind 30177 backup and restore
   - **Old backup (unordered)** → new code: loaded as List in insertion order. Order is arbitrary but stable - acceptable for first launch.
   - **New backup (ordered)** → old code: old code reads as List then converts to Set, losing order. Methods preserved - acceptable degradation since old code didn't use order.
   - `defaultPaymentMethod` unaffected by any changes - present and functional in both old and new backups.

---

## 8. Open Questions

### Open Question 1: Batch Retry Default Selection - RESOLVED

The insufficient funds dialog UI lives in `RiderModeScreen.kt` (lines 273-279 for the alternate selection, lines 204-229 for initial batch payment selection). `retryBatchWithAlternatePayment()` (RiderViewModel.kt line 2861) is the ViewModel handler called by the UI - it has no dialog of its own.

**Resolution**: Yes, use priority order. When the dialog shows alternate options, display them in `roadflarePaymentMethods` order (not alphabetical). Auto-default uses `firstOrNull { it in knownAlternateMethods }` to skip unknown forward-compat strings. Addressed in Phase 7.5.

### Open Question 2: Driver-side Alternate Priority - RESOLVED

**Resolution**: Yes, for consistency. Already answered in Q6 (Section 4). The same `RoadflarePaymentMethodPriorityList` composable is shared by both apps. Driver's alternate order is stored in settings and used locally for the driver-side compatibility indicator (Phase 3.7 — highest-priority common method display). Implementation cost is near-zero since the composable is shared. Addressed in Phase 6 (steps 4-5) and Phase 7.2.

---

## Review Notes

> **ALL ROUNDS ARCHIVED — OBSOLETE.** 30 codex review rounds (Rounds 1-30, 2026-02-21 to 2026-02-22) were conducted. All findings have been verified against the codebase and integrated into the current specification (Phases 1-7 above). The review rounds are superseded; **do not reference historical round text for implementation** — Phases 1-7 are the single source of truth.
>
> **Key resolutions across all rounds:**
> - **Cashu-only `defaultPaymentMethod` migration** (Phase 1.4): All non-cashu values coerced to `"cashu"` at restore time with log warning. Runtime cannot route Lightning or fiat-cash — all standard offer paths unconditionally run Cashu balance gating + HTLC generation. Non-cashu values can appear in old backups or future app versions; coercion prevents semantic inconsistency. Migration guard relaxed when Lightning/fiat-cash routing is implemented (separate future issue).
> - **`isPaymentCompatible()` deletion** (Phase 3.7): Private function (single call site) deleted along with filtering block (lines 2985-3014). Compatibility checking moved entirely to **driver side**: driver parses rider's `payment_methods` from offer, shows green/amber/red indicator on offer card, warning popup on accept if amber- or red-indicated. No rider-side indicators or popup.
> - **Single-tier validation** (Phase 1.1): Forward-compatible — normalize (`trim().lowercase()`), filter blank, dedup. No strict enum validation. Unknown future method string values are preserved (never removed) but normalized to lowercase for consistency; only truly invalid/corrupt entries (blank strings) are dropped. Positions normalized to tail after reorder (see Phase 1.2 caveat).
> - **Pre-acceptance state persistence** (Phase 2.2): `saveRideState()`/`restoreRideState()` restructured with `hasAcceptance` flag, `optString()` migration, behavioral rehydration (stage-branched re-subscription + elapsed-time timeout restart), and 3 `acceptance != null` defense-in-depth guards.
> - **Kind 30173 unchanged**: No code change. Compatibility checking uses rider's `payment_methods` from offer (Kind 3173, driver-side parsing), not Kind 30173 broadcast.
> - **Batch persistence** (Phase 2.3): `saveRideState()` call placed after `_uiState.update {}` in `sendRoadflareOfferSilent()` first-offer branch, with per-method summary table.
> - **Phase 2 isolation** (Round 29): Mandated as separate PR from Phases 1/3-7 due to save/restore blast radius. 12-item regression test checklist included.
> - **Boost path persistence** (Round 30): `boostDirectOffer()` and `boostFare()` added to Phase 2.3 save-call table (7 total sites). Both create replacement offers with updated state that must survive restart.
> - **Driver-side compatibility** (Phase 3.7): Compatibility indicator and incompatibility popup moved entirely to driver side. Rider sends `payment_methods` in offer; driver compares locally against own merged methods. No `driverPaymentMethods` map on rider side, no Kind 30173 subscription for compatibility.
> - **Index mapping for unknown methods** (Round 30): `moveRoadflarePaymentMethod()` API changed from `(fromIndex, toIndex)` to `(method: String, toVisibleIndex: Int)`. Known methods reordered independently; unknown methods appended as stable tail. Eliminates UI-to-stored index mismatch.
> - **End-to-end `payment_methods` wiring** (Round 30 HIGH, resolved → **revised Round 33**): `RideOfferEvent.kt` moved to "Files Changed" — `paymentMethods: List<String>` added to `RideOfferData` only (NOT `BroadcastRideOfferData` — broadcast content is plaintext, privacy risk). Parser reads `payment_methods` JSONArray with singular fallback for direct offers. `RideshareDomainService.kt` passes merged list to `sendRideOffer()` only. `broadcastRideRequest()` unchanged.
> - **Broadcast compatibility coverage** (Round 30 MEDIUM, resolved → **superseded Round 33**): Broadcast offers do NOT carry `payment_methods` — broadcast content is plaintext and exposing the full list would be a privacy leak. `BroadcastRideRequestCard` shows no payment compatibility indicator. No incompatibility popup on broadcast accept. Compatibility for broadcasts is discovered post-acceptance.
> - **Unknown-method stable tail** (Round 30 LOW, resolved): Already documented in Phase 1.2 — move API uses `(method: String, toVisibleIndex: Int)`. Unknown methods are hidden in settings UI, appended to stable tail on reorder, never lost. `toVisibleIndex` maps 1:1 to known-methods sublist rendered by `LazyColumn`.
>
> **Round 31 refinements (2026-02-22):**
> - **NostrService API threading** (HIGH, resolved → **revised Round 33**): `NostrService.kt` added to Phase 3.7 creation changes and Files Changed Summary. Only `sendRideOffer()` (line 761) receives `paymentMethods: List<String>` parameter — `broadcastRideRequest()` is unchanged (broadcast content is plaintext, no `payment_methods`). End-to-end threading for direct offers: `RiderViewModel → NostrService → RideshareDomainService → RideOfferEvent`.
> - **Accept Anyway acceptance event behavior** (MEDIUM, resolved → **expanded Round 33**): Phase 3.7 clarified — "Accept Anyway" writes the offer's original `payment_method` (rider's `defaultPaymentMethod`, typically `"cashu"`) into Kind 3174 unchanged. Both rider and driver proceed with standard Cashu HTLC escrow attempt. If escrow fails (e.g., driver doesn't support the rider's mint), the `escrowToken=null` fallback applies and the parties negotiate payment out-of-band via chat (Kind 3178). The protocol events don't model out-of-band payment agreements.
> - **`boostFare()` persistence success gate** (LOW, resolved): Phase 2.3C updated — `saveRideState()` at end of `boostFare()` is gated on `broadcastRideRequest()` returning non-null. If rebroadcast fails, skip save to avoid persisting corrupted state (stale/null `pendingOfferEventId` + updated boost amounts). The pre-boost saved state from `broadcastRideRequest()`'s own save site (section B) remains valid as fallback.
>
> **Round 32 refinements (2026-02-22):**
> - **RideshareEventKinds.kt KDoc** (HIGH, resolved): `RIDE_OFFER` KDoc updated to document that Kind 3173 now carries both singular `payment_method` and new `payment_methods` JSONArray. `RideshareEventKinds.kt` moved from "Files NOT changed" to "Files Changed" (KDoc-only update). No new constant needed — existing `RIDE_OFFER = 3173` covers both fields.
> - **`isPaymentCompatible()` text cleanup** (MEDIUM, resolved): Removed code listing and filtering behavior descriptions from Section 1 "Matching Logic", Q5, and Phase 3.7 paragraphs. All remaining mentions are deletion-only references. No text implies the function is still active or still filtering.
> - **`boostFare()` failure rollback note** (LOW, resolved): Phase 2.3C updated — explicit rollback note added. On rebroadcast failure: log at ERROR level, skip `saveRideState()`, user retries manually via boost/retry dialog or cancels. No automatic rollback of deleted old offer.
>
> **Round 33 refinements (2026-02-22):**
> - **Broadcast privacy leak** (HIGH, resolved): Removed `payment_methods` from broadcast offers entirely. Broadcast content is plaintext (not NIP-44 encrypted) — exposing the rider's full payment methods list would be a privacy leak. `BroadcastRideOfferData` is NOT modified; `BroadcastRideRequestCard` shows no payment compatibility indicator; no incompatibility popup on broadcast accept. Payment compatibility for broadcasts is discovered post-acceptance (HTLC escrow attempt or out-of-band negotiation). Updated: Section 1 (Offer Creation), Section 3 (Event Wire Changes), Phase 3.7 (data classes, creation, parsing, driver UI, scope), Files Changed Summary, and all test cases referencing broadcast indicators.
> - **Accept Anyway payment state** (MEDIUM, resolved): Clarified that Accept Anyway proceeds with the standard Cashu HTLC escrow attempt. The acceptance event echoes the rider's `defaultPaymentMethod` (typically `"cashu"`). If escrow fails, parties negotiate out-of-band via chat (Kind 3178). The existing `escrowToken=null` fallback applies. The protocol events don't model out-of-band payment agreements. Updated: Phase 3.7 popup description, test cases, resolved decisions.
> - **Standard methods runtime caveat** (LOW, resolved): Section 2 "Key Design Principle" clarified that standard methods are always enabled as selectable options, but the runtime currently only guarantees Cashu routing. Lightning and fiat-cash methods are listed but may not work due to unimplemented routing — this is a separate future issue.

> **Round 34 refinements (2026-02-22):**
> - **Compatibility false-green on direct offers** (HIGH, resolved): Phase 3.7 indicator changed from two-state (GREEN/RED) to three-state (GREEN/AMBER/RED). GREEN = singular `paymentMethod` in driver's list (automated path works). AMBER = singular `paymentMethod` NOT in driver's list but alternate-method overlap exists (warns HTLC may fail). RED = no overlap. AMBER-indicated offers show the same warning popup as RED. Prevents false confidence from alternate-only overlap.
> - **Kind 30173 contradiction cleanup** (MEDIUM, resolved): Q6 answer corrected — Kind 30173 is NOT modified by this feature. Removed false claim about alternate-order inclusion in 30173 payload. Compatibility uses rider's `payment_methods` from Kind 3173 offer (driver-side parsing), not Kind 30173 broadcast.
> - **Method normalization policy** (MEDIUM, resolved): Phase 1.1 validation tier updated — `trim().lowercase()` applied at storage boundaries (`setPaymentMethods()`, `setRoadflarePaymentMethods()`) and in `PaymentMethod.fromString()`. Prevents casing/whitespace mismatch between stored values and enum comparison.
> - **moveRoadflarePaymentMethod() guard** (LOW, resolved): Early-return guard added to Phase 1.2 pseudocode — `if (method !in visibleItems) return`. Prevents accidental insertion of disabled or unknown methods into the visible reorder list.
> - **KDoc specificity for 3173** (LOW, resolved): Phase 3.7 KDoc updated to document transport variants explicitly: direct/RoadFlare (encrypted) carries both `payment_method` and `payment_methods` array; broadcast (plaintext) carries only singular `payment_method`. Array intentionally omitted from broadcasts to avoid privacy leak.
