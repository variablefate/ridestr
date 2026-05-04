package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the public Kind 30012 tag emission against the wall-clock fallback
 * hazard at the source. A null `keyUpdatedAt` MUST emit `"0"` (never the
 * current wall-clock time), because the public `key_updated_at` tag is what
 * riders compare against their stored value in `checkStaleKeys`. A wall-clock
 * fallback would silently advance the tag and cause riders to flap into
 * stale-key flagging until the next real publish corrects it.
 *
 * Companion to the Kind 3187 fix in PR #79: that PR pinned the invariant on
 * the handler path; this test pins it at the source so future publish callers
 * can't reintroduce the hazard.
 */
@RunWith(RobolectricTestRunner::class)
class DriverRoadflareStateEventTest {

    private fun mockSigner(): Pair<NostrSigner, CapturingSlot<Array<Array<String>>>> {
        val tagsSlot = slot<Array<Array<String>>>()
        val mockSigner = mockk<NostrSigner>(relaxed = true)
        val mockEvent = mockk<Event>(relaxed = true)
        every { mockSigner.pubKey } returns "d".repeat(64)
        coEvery { mockSigner.nip44Encrypt(any(), any()) } returns "encrypted_content"
        coEvery {
            mockSigner.sign<Event>(
                createdAt = any(),
                kind = any(),
                tags = capture(tagsSlot),
                content = any()
            )
        } returns mockEvent
        return Pair(mockSigner, tagsSlot)
    }

    @Test
    fun `null keyUpdatedAt emits 0 not wall-clock`() = runBlocking {
        val (signer, tagsSlot) = mockSigner()
        val state = DriverRoadflareState(
            roadflareKey = DriverRoadflareKey(
                privateKey = "p".repeat(64),
                publicKey = "k".repeat(64),
                version = 1,
                createdAt = 100L
            ),
            followers = emptyList(),
            muted = emptyList(),
            keyUpdatedAt = null,
            lastBroadcastAt = null
        )

        DriverRoadflareStateEvent.create(signer, state)

        val keyUpdatedAtTag = tagsSlot.captured.single { it[0] == "key_updated_at" }
        assertEquals("0", keyUpdatedAtTag[1])
        // Defense-in-depth: the wall-clock fallback would have produced a 10-digit
        // timestamp around 1.7e9. "0" is unambiguously not a wall-clock value.
        val nowSeconds = System.currentTimeMillis() / 1000
        assertNotEquals(nowSeconds.toString(), keyUpdatedAtTag[1])
    }

    @Test
    fun `non-null keyUpdatedAt is preserved verbatim`() = runBlocking {
        val (signer, tagsSlot) = mockSigner()
        val state = DriverRoadflareState(
            roadflareKey = DriverRoadflareKey(
                privateKey = "p".repeat(64),
                publicKey = "k".repeat(64),
                version = 2,
                createdAt = 100L
            ),
            followers = emptyList(),
            muted = emptyList(),
            keyUpdatedAt = 4_242L,
            lastBroadcastAt = null
        )

        DriverRoadflareStateEvent.create(signer, state)

        val keyUpdatedAtTag = tagsSlot.captured.single { it[0] == "key_updated_at" }
        assertEquals("4242", keyUpdatedAtTag[1])
    }

    @Test
    fun `null roadflareKey emits 0 for key_version`() = runBlocking {
        val (signer, tagsSlot) = mockSigner()
        val state = DriverRoadflareState(
            roadflareKey = null,
            followers = emptyList(),
            muted = emptyList(),
            keyUpdatedAt = null,
            lastBroadcastAt = null
        )

        DriverRoadflareStateEvent.create(signer, state)

        val keyVersionTag = tagsSlot.captured.single { it[0] == "key_version" }
        assertEquals("0", keyVersionTag[1])
    }
}
