# ridestr Triage — 2026-05-07

> Refresh against `main @ adc2400` (2026-05-06). 21 open issues. Scope: rider-app + drivestr + `:common`. roadflare-rider explicitly excluded per user direction (broken WIP). No source files modified by this report.

## Android — rider-app + drivestr (all on `main`)

- ✅ PR [#79](https://github.com/variablefate/ridestr/pull/79) — Kind 3187 delivery-retry fix (#78) → merged `54e3029`, no more cross-rider key invalidation on follower re-add
- ✅ PR [#81](https://github.com/variablefate/ridestr/pull/81) — per-follower lightweight mute (#80) → merged `f25612e`, `RoadflareFollower.mutedAt` + Kind 30177 `muted_pubkeys` backup + last-write-wins reconciliation
- ✅ PR [#85](https://github.com/variablefate/ridestr/pull/85) — silent mute + decoupled stale-key offer flow (#82, #83) → merged `adc2400`, receive-side `isAnyMuted(pubkey)` filter on Kind 3173/3189 + public `status`/`key_version` tags drive presence independent of key state, inline Kind 3188 stale-signal piggyback for inline key recovery
- 🟡 v0.2.7 release cut — still gated on the manual QA bake; HEAD is now 4 PRs ahead of the planned target SHA (`87706d0`) from 2026-04-18
- 🟡 Phase 2 (SDK extraction from `:common`) — hold remains correct, production verification still pending

## Issues

- ✅ #78 Kind 3187 follow-notification triggers full key rotation → closed by PR #79
- ✅ #80 driver-side per-follower lightweight mute → closed by PR #81
- ✅ #82 decouple ride-on-demand from key state + actually-filter mute → closed by PR #85
- ✅ #83 wire per-driver-fare fallback to also cover stale/missing key case → closed by PR #85
- ✅ #84 emit authoritative fiat fare in non-Bitcoin RoadFlare offers → closed (roadflare-rider scope; deferred per PR #85's "one big PR per app" plan, ridestr-side fallback is sufficient)
- 🟡 Open followups: #41 (notification audit, HIGH bug), #43 (VM test harness), #44 (withdrawal calculator UX), #76 (live ephemeral location, cross-platform), #26 (progressive location disclosure), #6 (geocoder detail), #13 (protocol versioning + ext_*), #42 (strings.xml extraction), #11 (app icons), #7 (block lists), #9 (Cashu tips integration), #29 (NUT-12 DLEQ), #31 (NUT-20 signed mint quotes), #30 (NUT-21/22 mint auth), #32 (custom routing-tile NPUB dev option), #10 (Blossom delete API), #38 (Amber/NIP-46 signer), #25 (TOR via Arti), #20 (Bluetooth-mesh research), #34 (tile coverage expansion), #77 (Nostr voice calls)

## Recent accomplishments

The week between 2026-05-01 and 2026-05-07 produced a coherent three-PR RoadFlare hardening arc that retook control of the follower lifecycle. PR #79 (Kind 3187 → delivery-retry semantics) closed a confirmed cross-platform bug where re-adding any previously-followed driver invalidated *every other* rider's stored key — iOS had already shipped a partial workaround, and the drivestr fix retired it. PR #81 introduced a second, lighter-weight mute concept (`RoadflareFollower.mutedAt` + Kind 30177 `muted_pubkeys` backup, last-write-wins reconciliation, `AlreadyLightMuted` guards on Kind 3187/3188, `mergeFollowerLists` union-merge, `isMuteReconciled` gate) so users can quietly stop seeing a follower without the disruption of a key rotation.

PR #85 then closed the loop with two related fixes shipped together. First, the lightweight mute *actually* filters now: silent receive-side drop on Kind 3173 offers and Kind 3189 driver pings via a unified `DriverRoadflareRepository.isAnyMuted(pubkey)` covering both the heavyweight `MutedRider` set and the lightweight `mutedAt` flag. Second, ride-on-demand is decoupled from key state — the protocol already exposed `status` and `key_version` as public tags on Kind 30014, so PR #85 added a `CachedDriverPresence` channel on `FollowedDriversRepository` and a `RoadflareDriverPresenceCoordinator` that subscribes to *all* followed drivers and updates presence even when decryption fails. A driver with a stale key now appears available; the rider can send an offer plus a Kind 3188 `status="stale"` signal in the same beat, and the existing #81 ack handler re-delivers Kind 3186 inline. ADR-0008's authoritative fiat fare is what makes this safe — fare calc no longer needs the driver's location, so a locationless-but-online driver is still useful.

Net effect: the follower system that was "fragile, key-coupled, with weak mute" two weeks ago is now "robust, decoupled, with two real mute semantics." Every change in the arc was iOS-cooperative — #78, #82, #83, and #84 each had a roadflare-ios counterpart issue, and the `ext_*` extension convention from #13 would have made the protocol coordination cheaper.

The arc also implicitly validates the #65/#66/#67 coordinator extraction. Three meaty PRs touched RiderViewModel, DriverViewModel, and `:common` simultaneously, but the coordinators themselves saw no structural churn — new code landed where it should (`FollowedDriversRepository`, `RoadflareDriverPresenceCoordinator`, `DriverRoadflareRepository`, plus first-time integration tests in `FollowedDriversRepositoryPresenceTest` and `RoadflareMuteTest`). This is the first non-refactor stress test of the post-#65/#66/#67 architecture and it held up.

## Best followups

1. **Run the v0.2.7 manual QA bake** ([`docs/qa/2026-04-18-release-manual-qa-plan.md`](docs/qa/2026-04-18-release-manual-qa-plan.md)). This was the outstanding gate at 2026-05-01 and the new RoadFlare arc has only widened the production gap (v0.2.6 from 2026-02-17 → `adc2400` 2026-05-06, ~80 days). The bake plan needs a small extension to cover Kind 3187 delivery-retry, two-tier mute filtering on Kind 3173/3189, and the stale-key offer + Kind 3188 piggyback flow, then run end-to-end on two physical devices. This is the hard prerequisite for both cutting v0.2.7 and lifting the phase-2 hold.

2. **Promote #43 (ViewModel test harness) to P2.** PR #85 modified `RiderViewModel` + `DriverViewModel` + `FollowedDriversRepository` simultaneously across both apps — the largest cross-cutting surface since the coordinator extraction. The new repository-level tests in PR #85 cover the data layer; ViewModel-level tests are now the largest verification gap before any further extraction. Build the harness immediately after QA passes, before the next protocol-touching change.

3. **Spike #41 (notification-layer audit), ~2h.** Still HIGH-labeled, still no concrete repro, independent of the QA bake. A grep for `notify()` call sites organized into a state-matrix (online / offline / on-ride / muted-rider-attempted-ping) converts the vague HIGH ticket into 3-5 concrete tickets and is the cheapest piece of cleanup in the queue.

4. **Re-scope #13 (protocol versioning + `ext_*` convention) and co-author with iOS.** Four of the last six merged PRs were iOS-cooperative protocol changes (#62/#61, then #79/#81/#85 with their #84 follow-up). The `ext_*` convention would have lowered the cost of every one. Worth ~half a day to write the spec doc to `docs/specs/` jointly with the iOS team and ship the implementation; pays off on the next protocol delta.

---

*Generated 2026-05-07 against `main @ adc2400`. 3 PRs merged + 5 issues closed since 2026-05-01 refresh. 21 open issues. roadflare-rider intentionally out of scope.*
