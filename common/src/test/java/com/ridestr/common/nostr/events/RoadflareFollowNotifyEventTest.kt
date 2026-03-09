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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RoadflareFollowNotifyEventTest {

    private val testPubKey = "abc123riderPubKey"
    private val testTimestamp = 1700000000L

    private fun createMockEvent(kind: Int = RideshareEventKinds.ROADFLARE_FOLLOW_NOTIFY): Event {
        val event = mockk<Event>(relaxed = true)
        every { event.kind } returns kind
        every { event.pubKey } returns testPubKey
        every { event.content } returns "encrypted_content"
        return event
    }

    private fun validJson(): String = JSONObject().apply {
        put("action", "follow")
        put("riderName", "Alice")
        put("timestamp", testTimestamp)
    }.toString()

    // ==================
    // Suspend overload tests
    // ==================

    @Test
    fun `suspend parseAndDecrypt returns null for wrong event kind`() = runBlocking {
        val signer = mockk<NostrSigner>(relaxed = true)
        val event = createMockEvent(kind = 9999)

        val result = RoadflareFollowNotifyEvent.parseAndDecrypt(signer, event)
        assertNull(result)
    }

    @Test
    fun `suspend parseAndDecrypt returns null when decrypt throws`() = runBlocking {
        val signer = mockk<NostrSigner>(relaxed = true)
        coEvery { signer.nip44Decrypt(any(), any()) } throws Exception("Decryption failed")

        val event = createMockEvent()

        val result = RoadflareFollowNotifyEvent.parseAndDecrypt(signer, event)
        assertNull(result)
    }

    @Test
    fun `suspend parseAndDecrypt returns correct data for valid event`() = runBlocking {
        val signer = mockk<NostrSigner>(relaxed = true)
        coEvery { signer.nip44Decrypt(any(), any()) } returns validJson()

        val event = createMockEvent()

        val result = RoadflareFollowNotifyEvent.parseAndDecrypt(signer, event)
        assertNotNull(result)
        assertEquals(testPubKey, result!!.riderPubKey)
        assertEquals("Alice", result.riderName)
        assertEquals("follow", result.action)
        assertEquals(testTimestamp, result.timestamp)
    }

    // ==================
    // Parity test: both overloads produce same result
    // ==================

    @Test
    fun `both overloads return identical data for same input`() = runBlocking {
        val decryptedJson = validJson()
        val signer = mockk<NostrSigner>(relaxed = true)
        coEvery { signer.nip44Decrypt(any(), any()) } returns decryptedJson

        val event = createMockEvent()

        // Suspend overload
        val suspendResult = RoadflareFollowNotifyEvent.parseAndDecrypt(signer, event)

        // Sync lambda overload
        val syncResult = RoadflareFollowNotifyEvent.parseAndDecrypt(event) { _, _ -> decryptedJson }

        assertNotNull(suspendResult)
        assertNotNull(syncResult)
        assertEquals(suspendResult, syncResult)
    }
}
