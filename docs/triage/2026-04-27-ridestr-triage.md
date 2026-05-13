# Ridestr Triage Update — 2026-04-27

> Follow-up to [`2026-04-18-ridestr-triage.md`](./2026-04-18-ridestr-triage.md). `main` at `87706d0`, GitNexus reindexed (10,698 nodes / 27,893 edges, 336 files).

## What changed since the 04-18 report

- **All P0 + P1 recommendations shipped.** #49, #52, #58 closed 04-18 as completed; #59, #71, #72 closed 04-19 via PR #75 (`87706d0`, "chore: post-refactor housekeeping + P1 fixes").
- **One new tracking issue**: #74 ("Unify Kind 30173 availabilityStatus into DriverPresenceMapper") — explicit follow-up to the carved-out third channel from #58. Decision-required, not a bug.
- **Issue #13 was rescoped** from a sprawling "protocol interoperability" tracking issue to a focused one: "Protocol versioning + `ext_*` extension-field convention". Matches the 04-18 recommendation.
- **Open issues: 21** (down from 26).
- **GitNexus reindex now reflects post-refactor reality**: coordinator caller counts visible (PaymentCoordinator 33, OfferCoordinator 24, AvailabilityCoordinator 22, AcceptanceCoordinator 14, RoadflareDriverCoordinator 18, RoadflareRiderCoordinator 5, DriverPresenceMapper 26). RiderViewModel callers dropped 96 → 87, DriverViewModel 80 → 78 — modest but the coordinators are absorbing real load. `SettingsRepository` (167 callers) and `NostrService` facade (155 callers) remain the two biggest seams.

## Triage table (21 open issues)

| Tier | # | Title | Cat | Effort | Rationale |
|------|---|-------|-----|:------:|-----------|
| **P1** | [#74](https://github.com/variablefate/ridestr/issues/74) | Unify Kind 30173 `availabilityStatus` into DriverPresenceMapper | refactor | S | Decide-or-do, not a bug. Either extend mapper with `availabilityStatus(stage, context)` or close as won't-do with KDoc rationale. Cheap to resolve either way. |
| **P2** | [#41](https://github.com/variablefate/ridestr/issues/41) | [HIGH] Audit notification layer across all app states | bug | L | Labeled HIGH, broad scope, no concrete repro. **Spike first** — enumerate `notify()` call sites into a state-matrix before sizing. |
| **P2** | [#19](https://github.com/variablefate/ridestr/issues/19) | Backup payment methods settings (Zelle/Venmo/Cash App/etc.) | enh | M | Extends the now-shipped fiat path. Touches `SettingsRepository` (167 callers — well-shaped seam), event schemas, Kind 30177 backup. |
| **P2** | [#26](https://github.com/variablefate/ridestr/issues/26) | Progressive Location Disclosure (proximity pickup reveal) | enh | M | Privacy upgrade. Dead-code wiring already exists in RiderViewModel — coordinate event-schema changes with iOS port. |
| **P2** | [#6](https://github.com/variablefate/ridestr/issues/6) | More detailed geocoder search results | enh | S | Small UX win, scoped to `GeocodingService.kt` rendering. |
| **P3** | [#43](https://github.com/variablefate/ridestr/issues/43) | ViewModel test harness for rider/driver flows | test | L–XL | Strategically high-leverage SDK prep — locks down RiderVM/DriverVM behavior before phase 2. Promote to P2 if pulling SDK work forward. |
| **P3** | [#13](https://github.com/variablefate/ridestr/issues/13) | Protocol versioning + `ext_*` extension convention | proto | M | Rescoped per prior triage. Now atomic and ready. Coordinate with iOS. |
| **P3** | [#42](https://github.com/variablefate/ridestr/issues/42) | Extract hardcoded strings to `strings.xml` | enh | L | Mechanical, blocks future localization. One screen at a time. |
| **P3** | [#44](https://github.com/variablefate/ridestr/issues/44) | Withdrawal calculator-style flow redesign | UX | M | Self-contained UX rewrite, no protocol deps. |
| **P3** | [#11](https://github.com/variablefate/ridestr/issues/11) | Proper app icons + notification icons | design | M | Design-blocked. Mechanical swap once assets exist. |
| **P3** | [#7](https://github.com/variablefate/ridestr/issues/7) | Rider/driver block lists (Kind 30007 mute set) | enh | M | Established merge-with-existing pattern via ProfileSyncAdapter. |
| **P3** | [#9](https://github.com/variablefate/ridestr/issues/9) | Integrate tips with Cashu wallet | enh | M | Replace external-wallet stub in `TipScreen.kt` with HTLC/P2PK pattern. |
| **P3** | [#29](https://github.com/variablefate/ridestr/issues/29) | NUT-12 DLEQ verification | sec | L | Trust-min for mint. 7 unblinding sites in `CashuBackend.kt`. Self-contained. |
| **P3** | [#31](https://github.com/variablefate/ridestr/issues/31) | NUT-20 signed mint quotes | sec | M | MITM protection. Schnorr signing already in repo. Smaller than #29. |
| **P3** | [#32](https://github.com/variablefate/ridestr/issues/32) | Dev option: custom routing-tile NPUB | dev | S | Trivial settings parameterization. |
| **P3** | [#10](https://github.com/variablefate/ridestr/issues/10) | Blossom delete API for profile pictures | bug | S | Single signed-DELETE call. Currently leaks blobs server-side. |
| **P4** | [#30](https://github.com/variablefate/ridestr/issues/30) | NUT-21/22 mint authentication | sec | XL | Depends on #29. Useful only when targeting auth'd mint. |
| **P4** | [#25](https://github.com/variablefate/ridestr/issues/25) | TOR via Arti | priv | XL | APK-size + bootstrap-latency tradeoffs, niche use case. Research first. |
| **P4** | [#20](https://github.com/variablefate/ridestr/issues/20) | Bluetooth-mesh research for Nostr events | research | L | Pure research, no shipping commitment. |
| **P4** | [#34](https://github.com/variablefate/ridestr/issues/34) | Tile coverage expansion (blossom uploads) | ops | ongoing | Ops, self-tracking. 8 countries done. |
| **P4** | [#38](https://github.com/variablefate/ridestr/issues/38) | NIP-46 / Amber signer support | enh | L | Architectural — touches every signing site. Defer until SDK boundary work begins. |

## Recommended next work

1. **Resolve #74 first** (S effort): pick "extend mapper" or "won't-do + commit a rationale paragraph in the KDoc". Either choice closes it. Don't let decide-or-do issues age.
2. **Run the #41 enumeration spike**. ~2 hours to list every `notify()` site × state in a matrix; that converts the HIGH-labeled vague work into concrete tickets.
3. **Pair #19 (backup payment methods) with #6 (geocoder UX)** as a focused settings/UX sprint — both touch user-visible polish and use stable seams.
4. **Phase-2 prep**: when the production verification window for the #65/#66/#67 arc closes, the highest-leverage move is **#43 (ViewModel test harness)**. RiderVM (87 callers) and DriverVM (78 callers) remain the biggest seams; locking their behavior before further extraction prevents regression hunting later.

## Hotspot snapshot (after PR #75)

Top changes vs. the 04-18 table:

- `DriverModeScreenComponents.kt`: 1 → 2 commits, +520 LOC (the #71 perf fixes — `RelativeTimeText` leaf composable, `remember`-keyed derived math, hoisted `items{}` lambdas).
- `HistoryComponents.kt`: 4 → 5 commits, +43 LOC (#71 + #72 follow-throughs).
- `RideTabComponents.kt`: 2 → 3 commits, +10 LOC (#72 placeholder helper).
- `RiderModeScreen.kt` / `DriverModeScreen.kt`: each +1 commit, ~30 LOC (#75 follow-through).
- The two ViewModels did **not** churn this window — extraction is holding so far.

Coordinator caller counts (gitnexus, fresh):

| Class | Callers | Note |
|---|--:|---|
| SettingsRepository | 167 | Highest fan-in. SDK contract priority #1. |
| NostrService (facade) | 155 | SDK contract priority #2. |
| RiderViewModel | 87 | Down from 96. Still the largest VM seam. |
| DriverViewModel | 78 | Down from 80. |
| PaymentCoordinator | 33 | New (PR #70). Healthy fan-in for a payment seam. |
| DriverPresenceMapper | 26 | Validates the #58 design. |
| OfferCoordinator | 24 | New (PR #70). |
| AvailabilityCoordinator | 22 | New (PR #69). |
| RoadflareDriverCoordinator | 18 | New (PR #69). |
| AcceptanceCoordinator | 14 | New (PR #69). |
| RoadflareRiderCoordinator | 5 | New (PR #70). Lowest fan-in — watch whether it earns its keep. |

## Red flags

- **None new.** The 04-18 list is mostly resolved: #52/#58/#49/#59/#71/#72 closed, the gitnexus reindex unblocked (workaround: prebuilt `lbugjs.node` from `@ladybugdb/core-darwin-arm64` + `npm rebuild tree-sitter-kotlin` after `--ignore-scripts` install — should report upstream so future `npx gitnexus analyze` works in the harness sandbox).
- **One leftover from 04-18 still applies**: untracked `rider-app/.../viewmodels/AvailabilityMonitorPolicy.kt` + test in the working tree (PR #70 moved canonical copy to `:common/coordinator/`). Safe to delete after `git diff` confirms identical content.

---

*Generated 2026-04-27 against `main @ 87706d0`. GitNexus index `87706d0` (fresh, same SHA as HEAD). 21 open issues. No source files modified by this report.*
