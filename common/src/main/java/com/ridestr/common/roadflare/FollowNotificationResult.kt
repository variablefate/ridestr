package com.ridestr.common.roadflare

/**
 * Result of [RoadflareKeyManager.handleFollowNotification].
 *
 * Kind 3187 follow notifications carry no semantics beyond "rider sent the
 * driver a follow signal" — they may be a first follow, a re-add after fresh
 * install, or a delivery-retry when the rider's local backup of the driver's
 * key share is unavailable. The driver-side handler interprets the rider's
 * current state in the followers list to decide whether to add as pending,
 * re-deliver the existing key to an approved follower, or no-op.
 *
 * Variants are returned to the caller so the UI layer can decide whether
 * to surface an OS notification (genuinely new follows only) or remain
 * silent (re-deliveries).
 */
sealed class FollowNotificationResult {

    /**
     * Rider was not in the followers list — added as a pending follower.
     * Driver must approve via the UI before any Kind 3186 key share is sent.
     * The call site should surface an OS notification so the driver knows.
     */
    object AddedAsPending : FollowNotificationResult()

    /**
     * Rider is already in the followers list as pending (driver hasn't
     * approved yet). No-op — driver still owes an approval via UI.
     */
    object AlreadyPending : FollowNotificationResult()

    /**
     * Rider is on the heavyweight muted list ([com.ridestr.common.nostr.events.MutedRider]).
     * No-op — the driver's explicit "Remove" decision is preserved.
     *
     * Auto-unmuting on Kind 3187 would silently bypass driver consent and
     * conflict with the cross-device sync invariant in
     * `RoadflareDriverCoordinator.mergeMutedLists` ("once muted, always muted —
     * auto-unmute via sync is intentionally blocked"). To restore a removed
     * rider the driver re-approves them through the RoadFlare tab.
     */
    object AlreadyMuted : FollowNotificationResult()

    /**
     * Rider is approved but lightweight-muted (issue #80,
     * [com.ridestr.common.nostr.events.RoadflareFollower.mutedAt] non-null). No-op —
     * re-delivering the current Kind 3186 key would defeat the lightweight mute.
     *
     * Distinct from [AlreadyMuted] (heavyweight `MutedRider` + key rotation) so the
     * call site can render different UI / log messages. Recovery: the driver clears
     * the lightweight mute via the RoadFlare tab's "Unmute" action, which re-sends
     * the current key.
     */
    object AlreadyLightMuted : FollowNotificationResult()

    /**
     * Rider was already approved. The current Kind 3186 key share was
     * re-delivered to recover from a transient delivery failure on the rider
     * side. No state change — the keyUpdatedAt and Kind 30012 metadata are
     * untouched.
     */
    object KeyResent : FollowNotificationResult()

    /**
     * Could not complete the requested action.
     *
     * @property reason Short human-readable description for logging.
     */
    data class Failed(val reason: String) : FollowNotificationResult()
}
