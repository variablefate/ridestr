package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Schema-level tests for issue #80's `muted_pubkeys` field on Kind 30177.
 *
 * The encryption layer is mocked to identity (encrypt(x, _) → x, decrypt(x, _) → x) so the
 * tests exercise the JSON serialization / parsing without needing a real NIP-44 implementation.
 *
 * Robolectric is required because `org.json.JSONObject` ships as part of the Android runtime
 * (a stub-only class on the host JVM) — without Robolectric it throws "not mocked".
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProfileBackupEventMutedPubkeysTest {

    private val pubKey = "deadbeef".repeat(8)

    /**
     * Build a signer mock whose `nip44Encrypt`/`nip44Decrypt` are identity functions,
     * and whose `sign<Event>` returns an Event with the supplied content / kind / pubKey.
     * Lets us inspect the JSON payload that would be encrypted, then parse it back through
     * the real `parseAndDecrypt` path.
     */
    private fun identitySigner(): NostrSigner {
        val signer = mockk<NostrSigner>(relaxed = true)
        every { signer.pubKey } returns pubKey
        coEvery { signer.nip44Encrypt(any(), any()) } answers { firstArg() }
        coEvery { signer.nip44Decrypt(any(), any()) } answers { firstArg() }

        // sign<Event>: emit a minimal Event capturing the content + kind we care about.
        coEvery {
            signer.sign<Event>(any(), any(), any(), any())
        } answers {
            val createdAt = firstArg<Long>()
            val kind = secondArg<Int>()
            val tags = thirdArg<Array<Array<String>>>()
            val content = arg<String>(3)
            Event(
                id = "test-event-id",
                pubKey = pubKey,
                createdAt = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = "sig"
            )
        }
        return signer
    }

    // ── Acceptance criterion 5: missing muted_pubkeys parses gracefully ───────

    @Test
    fun `parseAndDecrypt yields empty mutedFollowerPubkeys when field absent`() = runTest {
        val signer = identitySigner()

        // Hand-build a payload that intentionally omits `muted_pubkeys` — mimics events
        // produced before issue #80 shipped. `parseAndDecrypt` must default the field to
        // an empty list rather than throwing.
        val plaintext = JSONObject().apply {
            put("vehicles", org.json.JSONArray())
            put("savedLocations", org.json.JSONArray())
            put("settings", SettingsBackup().toJson())
            put("updated_at", 12_345L)
        }.toString()

        val event = Event(
            id = "evt-no-mute",
            pubKey = pubKey,
            createdAt = 1_000L,
            kind = RideshareEventKinds.PROFILE_BACKUP,
            tags = arrayOf(arrayOf("d", ProfileBackupEvent.D_TAG)),
            content = plaintext,
            sig = "sig"
        )

        val parsed = ProfileBackupEvent.parseAndDecrypt(signer, event)
        assertNotNull(parsed)
        assertTrue("expected empty mutedFollowerPubkeys when field absent", parsed!!.mutedFollowerPubkeys.isEmpty())
    }

    @Test
    fun `parseAndDecrypt round-trips muted_pubkeys field`() = runTest {
        val signer = identitySigner()
        val muted = listOf("a".repeat(64), "b".repeat(64))

        val event = ProfileBackupEvent.create(
            signer = signer,
            vehicles = emptyList(),
            savedLocations = emptyList(),
            settings = SettingsBackup(),
            mutedFollowerPubkeys = muted
        )!!

        val parsed = ProfileBackupEvent.parseAndDecrypt(signer, event)
        assertNotNull(parsed)
        assertEquals(muted, parsed!!.mutedFollowerPubkeys)
    }

    @Test
    fun `create omits muted_pubkeys field entirely when list is empty`() = runTest {
        // Wire-format invariant: rider apps and pre-#80 driver flows must produce identical
        // payloads. The `muted_pubkeys` key MUST NOT appear in the JSON when the list is empty.
        val signer = identitySigner()
        val captured = slot<String>()
        coEvery { signer.nip44Encrypt(capture(captured), any()) } answers { firstArg() }

        ProfileBackupEvent.create(
            signer = signer,
            vehicles = emptyList(),
            savedLocations = emptyList(),
            settings = SettingsBackup(),
            mutedFollowerPubkeys = emptyList()
        )

        val json = JSONObject(captured.captured)
        assertTrue(
            "muted_pubkeys should NOT appear in payload when list is empty (wire-compat)",
            !json.has("muted_pubkeys")
        )
    }

    @Test
    fun `parseAndDecrypt rejects non-hex and wrong-length entries in muted_pubkeys array`() = runTest {
        // The parser must filter junk entries (numbers, short strings, non-hex chars) so a
        // malformed remote event cannot grow the local mute list unboundedly across backup
        // cycles. Only canonical 32-byte hex pubkeys (64 lowercase hex chars) are accepted.
        val signer = identitySigner()
        val payload = JSONObject().apply {
            put("vehicles", org.json.JSONArray())
            put("savedLocations", org.json.JSONArray())
            put("settings", SettingsBackup().toJson())
            put("muted_pubkeys", org.json.JSONArray().apply {
                put("c".repeat(64))                  // valid hex
                put(42)                              // numeric — coerced to "42" by optString
                put("d".repeat(64))                  // valid hex
                put("not-hex-but-64-chars-long-______________________________________") // 64 chars, non-hex
                put("a".repeat(63))                  // wrong length (off-by-one short)
                put("a".repeat(65))                  // wrong length (off-by-one long)
                put("")                              // empty
                put("AABBCCDDEEFFAABBCCDDEEFFAABBCCDDEEFFAABBCCDDEEFFAABBCCDDEEFFAABB") // upper-case hex (accepted)
            })
            put("updated_at", 1L)
        }.toString()

        val event = Event(
            id = "evt-mixed",
            pubKey = pubKey,
            createdAt = 1L,
            kind = RideshareEventKinds.PROFILE_BACKUP,
            tags = arrayOf(arrayOf("d", ProfileBackupEvent.D_TAG)),
            content = payload,
            sig = "sig"
        )

        val parsed = ProfileBackupEvent.parseAndDecrypt(signer, event)
        assertNotNull(parsed)
        val muted = parsed!!.mutedFollowerPubkeys
        // Valid entries survive (lower- and upper-case hex both accepted).
        assertTrue("expected valid lower-hex c…", muted.contains("c".repeat(64)))
        assertTrue("expected valid lower-hex d…", muted.contains("d".repeat(64)))
        assertTrue("expected upper-case hex accepted", muted.contains("AABBCCDDEEFFAABBCCDDEEFFAABBCCDDEEFFAABBCCDDEEFFAABBCCDDEEFFAABB"))
        // Junk entries are rejected.
        assertFalse("numeric '42' must NOT pass the hex filter", muted.contains("42"))
        assertFalse("63-char entry must NOT pass length check", muted.contains("a".repeat(63)))
        assertFalse("65-char entry must NOT pass length check", muted.contains("a".repeat(65)))
        assertFalse("empty string must NOT pass length check", muted.contains(""))
        assertEquals("only the 3 valid entries survive", 3, muted.size)
    }
}
