package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RoadflareDriverPingEventTest {

    // Fixed test inputs — chosen so fixedEpoch / 300 = 3333 exactly (no boundary ambiguity)
    private val driverPubKey        = "d" + "0".repeat(63)   // 64-char hex
    private val riderPubKey         = "a" + "0".repeat(63)   // 64-char hex
    private val roadflarePrivKeyHex = "bb".repeat(32)         // 64-char hex = 32 bytes
    private val fixedEpoch          = 1_000_000L              // window = 3333

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Compute HMAC-SHA256 using the same algorithm as the SUT.
     * Used to build expected auth-tag values in tests.
     */
    private fun computeHmac(privKeyHex: String, driverPub: String, riderPub: String, window: Long): String {
        val key = ByteArray(privKeyHex.length / 2) { i ->
            Integer.parseInt(privKeyHex.substring(i * 2, i * 2 + 2), 16).toByte()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val msg = driverPub + riderPub + window.toString()
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Build a mock Event with configurable kind, pubkey, auth tag, and expiration. */
    private fun makeEvent(
        kind: Int = RideshareEventKinds.ROADFLARE_DRIVER_PING,
        pubKey: String = riderPubKey,
        authTag: String? = null,
        expiration: Long? = fixedEpoch + 1800
    ): Event {
        val event = mockk<Event>(relaxed = true)
        every { event.kind } returns kind
        every { event.pubKey } returns pubKey
        every { event.content } returns "encrypted_content"
        val tagList = mutableListOf<Array<String>>()
        if (authTag != null)   tagList.add(arrayOf("auth",       authTag))
        if (expiration != null) tagList.add(arrayOf("expiration", expiration.toString()))
        every { event.tags } returns tagList.toTypedArray()
        return event
    }

    /** Convenience: valid auth hex for a given window (defaults to current). */
    private fun validAuthHex(window: Long = fixedEpoch / 300): String =
        computeHmac(roadflarePrivKeyHex, driverPubKey, riderPubKey, window)

    // ── isAuthValid ──────────────────────────────────────────────────────────

    @Test
    fun `isAuthValid returns true for HMAC at current window`() {
        val event = makeEvent(authTag = validAuthHex())
        assertTrue(
            RoadflareDriverPingEvent.isAuthValid(event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `isAuthValid returns true for HMAC at previous window`() {
        val event = makeEvent(authTag = validAuthHex(fixedEpoch / 300 - 1))
        assertTrue(
            RoadflareDriverPingEvent.isAuthValid(event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `isAuthValid returns true for HMAC at next window`() {
        val event = makeEvent(authTag = validAuthHex(fixedEpoch / 300 + 1))
        assertTrue(
            RoadflareDriverPingEvent.isAuthValid(event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `isAuthValid returns false for HMAC two windows back`() {
        val event = makeEvent(authTag = validAuthHex(fixedEpoch / 300 - 2))
        assertFalse(
            RoadflareDriverPingEvent.isAuthValid(event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `isAuthValid returns false for HMAC two windows ahead`() {
        val event = makeEvent(authTag = validAuthHex(fixedEpoch / 300 + 2))
        assertFalse(
            RoadflareDriverPingEvent.isAuthValid(event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `isAuthValid returns false for wrong roadflare key`() {
        val event = makeEvent(authTag = validAuthHex())
        val wrongKey = "cc".repeat(32)
        assertFalse(
            RoadflareDriverPingEvent.isAuthValid(event, driverPubKey, wrongKey, fixedEpoch)
        )
    }

    @Test
    fun `isAuthValid returns false when auth tag is absent`() {
        val event = makeEvent(authTag = null)
        assertFalse(
            RoadflareDriverPingEvent.isAuthValid(event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `isAuthValid returns false for wrong-length auth tag`() {
        // 63-char hex trips the authTag.length != 64 guard before HMAC is attempted
        val event = makeEvent(authTag = validAuthHex().take(63))
        assertFalse(
            RoadflareDriverPingEvent.isAuthValid(event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    // ── parseAndDecrypt ──────────────────────────────────────────────────────

    @Test
    fun `parseAndDecrypt returns null for wrong kind`() = runBlocking {
        val signer = mockk<NostrSigner>(relaxed = true)
        val event = makeEvent(kind = 9999)
        assertNull(
            RoadflareDriverPingEvent.parseAndDecrypt(signer, event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `parseAndDecrypt returns null for expired event`() = runBlocking {
        val signer = mockk<NostrSigner>(relaxed = true)
        val event = makeEvent(authTag = validAuthHex(), expiration = fixedEpoch - 1)
        assertNull(
            RoadflareDriverPingEvent.parseAndDecrypt(signer, event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `parseAndDecrypt returns null when expiration tag is absent`() = runBlocking {
        // Spec §1.6 requires every Kind 3189 event to carry a 30-min NIP-40 expiry tag.
        // Events without expiration are rejected — a missing tag signals a malformed or
        // unauthenticated sender rather than a benign omission.
        val signer = mockk<NostrSigner>(relaxed = true)
        val event = makeEvent(authTag = validAuthHex(), expiration = null)
        assertNull(
            RoadflareDriverPingEvent.parseAndDecrypt(signer, event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `parseAndDecrypt accepts event expiring in the future`() = runBlocking {
        // Positive-boundary mirror of the expired test: expiration = fixedEpoch + 1
        // verifies the strict-inequality check (expiration < nowEpoch) passes an event
        // that still has 1 second of TTL remaining.
        val signer = mockk<NostrSigner>(relaxed = true)
        val decryptedJson = JSONObject().apply {
            put("action",    "ping")
            put("riderName", "Bob")
            put("message",   "Bob is currently hoping you come online!")
            put("timestamp", fixedEpoch)
        }.toString()
        coEvery { signer.nip44Decrypt(any(), any()) } returns decryptedJson

        val event = makeEvent(authTag = validAuthHex(), expiration = fixedEpoch + 1)
        val result = RoadflareDriverPingEvent.parseAndDecrypt(
            signer, event, driverPubKey, roadflarePrivKeyHex, fixedEpoch
        )
        assertNotNull(result)
        assertEquals("Bob", result!!.riderName)
    }

    @Test
    fun `parseAndDecrypt returns null when HMAC auth fails`() = runBlocking {
        val signer = mockk<NostrSigner>(relaxed = true)
        val event = makeEvent(authTag = "00".repeat(32))  // wrong HMAC
        assertNull(
            RoadflareDriverPingEvent.parseAndDecrypt(signer, event, driverPubKey, roadflarePrivKeyHex, fixedEpoch)
        )
    }

    @Test
    fun `parseAndDecrypt returns data with message and riderName on success`() = runBlocking {
        val signer = mockk<NostrSigner>(relaxed = true)
        val decryptedJson = JSONObject().apply {
            put("action",    "ping")
            put("riderName", "Alice")
            put("message",   "Alice is currently hoping you come online!")
            put("timestamp", fixedEpoch)
        }.toString()
        coEvery { signer.nip44Decrypt(any(), any()) } returns decryptedJson

        val event = makeEvent(authTag = validAuthHex())
        val result = RoadflareDriverPingEvent.parseAndDecrypt(
            signer, event, driverPubKey, roadflarePrivKeyHex, fixedEpoch
        )

        assertNotNull(result)
        assertEquals("Alice is currently hoping you come online!", result!!.message)
        assertEquals("Alice",      result.riderName)
        assertEquals(riderPubKey,  result.riderPubKey)
        assertEquals(fixedEpoch,   result.timestamp)
    }
}
