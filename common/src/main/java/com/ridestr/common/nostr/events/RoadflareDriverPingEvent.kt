package com.ridestr.common.nostr.events

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Kind 3189: RoadFlare Driver Ping Request (Regular)
 *
 * Sent by a rider to nudge an offline trusted driver to come online.
 * Content is NIP-44 encrypted to the driver's Nostr identity pubkey.
 *
 * Auth proof: HMAC-SHA256(key = driver's RoadFlare privateKey bytes,
 *   msg = driverPubkey + riderPubkey + str(floor(epochSeconds / 300)))
 * Validated against currentWindow ± 1 (5-minute buckets, clock-skew tolerance).
 *
 * Protocol spec: roadflare-ios plan docs/superpowers/plans/2026-04-14-issue-4-driver-ping.md §1
 */
object RoadflareDriverPingEvent {
    private const val TAG = "DriverPingEvent"
    const val T_TAG = "roadflare-ping"
    private const val HMAC_WINDOW_SECONDS = 300L  // 5-minute bucket
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Validate the HMAC auth tag on a Kind 3189 event.
     *
     * Checks three consecutive 5-minute windows (currentWindow - 1, currentWindow,
     * currentWindow + 1) to tolerate clock skew and window boundary crossings.
     *
     * @param event               The raw Nostr event (event.pubKey = rider's Nostr pubkey)
     * @param driverPubKey        Driver's Nostr identity pubkey (hex)
     * @param roadflarePrivKeyHex Driver's RoadFlare private key (64-char lowercase hex = 32 bytes)
     * @param nowEpoch            Current unix timestamp in seconds (injectable for testing)
     * @return true if any of the three windows produces a matching HMAC
     */
    fun isAuthValid(
        event: Event,
        driverPubKey: String,
        roadflarePrivKeyHex: String,
        nowEpoch: Long = System.currentTimeMillis() / 1000
    ): Boolean {
        val authTag = event.tags.find { it.getOrNull(0) == RideshareTags.AUTH }
            ?.getOrNull(1)?.lowercase() ?: return false
        if (authTag.length != 64) return false  // SHA256 hex is exactly 64 chars

        return try {
            val keyBytes = roadflarePrivKeyHex.hexToBytes()
            val riderPubKey = event.pubKey
            val currentWindow = nowEpoch / HMAC_WINDOW_SECONDS

            listOf(currentWindow - 1L, currentWindow, currentWindow + 1L).any { window ->
                val msg = driverPubKey + riderPubKey + window.toString()
                computeHmac(keyBytes, msg).toHexString() == authTag
            }
        } catch (e: Exception) {
            Log.w(TAG, "HMAC validation error: ${e.message}")
            false
        }
    }

    /**
     * Full validation and decryption pipeline for a Kind 3189 event.
     *
     * Validation order (each failure returns null — silent from the user's perspective;
     * DEBUG-level log lines remain for local troubleshooting):
     * 1. Wrong kind
     * 2. NIP-40 expiry exceeded
     * 3. HMAC auth mismatch
     * 4. NIP-44 decrypt or JSON parse failure
     *
     * @param signer              Driver's Nostr signer (for NIP-44 decryption)
     * @param event               The raw Kind 3189 event
     * @param driverPubKey        Driver's Nostr identity pubkey (hex)
     * @param roadflarePrivKeyHex Driver's RoadFlare private key (64-char lowercase hex)
     * @param nowEpoch            Current unix timestamp in seconds (injectable for testing)
     * @return Parsed data on success, null on any validation failure
     */
    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event,
        driverPubKey: String,
        roadflarePrivKeyHex: String,
        nowEpoch: Long = System.currentTimeMillis() / 1000
    ): RoadflareDriverPingData? {
        if (event.kind != RideshareEventKinds.ROADFLARE_DRIVER_PING) {
            Log.w(TAG, "Wrong event kind: ${event.kind}")
            return null
        }

        // NIP-40: relays should enforce expiry, but driver app must not trust relays.
        // Missing expiration tag is also rejected — spec §1.6 requires a 30-min TTL on every event.
        val expiration = event.tags.find { it.getOrNull(0) == RideshareTags.EXPIRATION }
            ?.getOrNull(1)?.toLongOrNull()
        if (expiration == null || expiration < nowEpoch) {
            Log.d(TAG, "Dropping driver ping — expiry absent or exceeded (exp=$expiration, now=$nowEpoch)")
            return null
        }

        // HMAC auth (silent drop on failure — no error response to sender)
        if (!isAuthValid(event, driverPubKey, roadflarePrivKeyHex, nowEpoch)) {
            Log.d(TAG, "HMAC auth failed for ping from ${event.pubKey.take(8)}")
            return null
        }

        return try {
            val decrypted = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decrypted)

            // Require action == "ping"; reject anything else as malformed.
            // Silent drop — same failure path as HMAC failure.
            val action = json.optString("action", "")
            if (action != "ping") {
                Log.d(TAG, "Invalid action in driver ping payload: '$action'")
                return null
            }

            // "message" field is intentionally not parsed. The notification body is
            // constructed locally in the service from riderName only — the sender's
            // message field is untrusted and must never reach the notification copy.
            // riderName is sanitised here (truncate + strip control chars) to prevent
            // injection even through the weaker sender-controlled display name.
            val safeRiderName = json.optString("riderName", "")
                .take(64)
                .filter { it >= ' ' }

            RoadflareDriverPingData(
                riderPubKey = event.pubKey,
                riderName   = safeRiderName,
                timestamp   = json.optLong("timestamp", nowEpoch)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt driver ping from ${event.pubKey.take(8)}", e)
            null
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun computeHmac(key: ByteArray, message: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    /** Decode a lowercase hex string to bytes. Throws if length is odd. */
    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length, got $length" }
        return ByteArray(length / 2) { i ->
            Integer.parseInt(substring(i * 2, i * 2 + 2), 16).toByte()
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Parsed content from a validated Kind 3189 driver ping event.
 *
 * Security note: the raw payload's `message` field is intentionally absent here.
 * Any receiver that needs to display a notification MUST build the body locally
 * from [riderName] — never from a sender-supplied string. [riderName] is already
 * sanitised (64-char truncation, control-char strip) at parse time.
 */
data class RoadflareDriverPingData(
    val riderPubKey: String,  // event.pubKey — Nostr sender
    val riderName: String,    // rider's display name (sanitised); use to build notification body locally
    val timestamp: Long       // unix epoch from "timestamp" field
)
