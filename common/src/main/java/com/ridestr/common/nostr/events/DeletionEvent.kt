package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

/**
 * NIP-09: Event Deletion
 *
 * Kind 5 events request deletion of previous events.
 * Relays SHOULD delete or stop serving the referenced events.
 *
 * Note: Deletion is a request, not guaranteed. Some relays may ignore it.
 */
object DeletionEvent {

    /** Kind 5 is used for deletion requests per NIP-09 */
    const val KIND = 5

    /**
     * Create a deletion request for one or more events.
     *
     * @param signer The NostrSigner to sign the event
     * @param eventIds List of event IDs to request deletion of
     * @param reason Optional reason for deletion
     * @param kinds Optional list of kinds being deleted (for NIP-09 k-tags)
     * @return Signed deletion event
     */
    suspend fun create(
        signer: NostrSigner,
        eventIds: List<String>,
        reason: String = "",
        kinds: List<Int>? = null
    ): Event {
        val tags = mutableListOf<Array<String>>()

        // Each event to delete gets an "e" tag
        eventIds.forEach { eventId ->
            tags.add(arrayOf(RideshareTags.EVENT_REF, eventId))
        }

        // Add k-tags for each kind (NIP-09 recommendation for relay-side filtering)
        kinds?.distinct()?.forEach { kind ->
            tags.add(arrayOf("k", kind.toString()))
        }

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND,
            tags = tags.toTypedArray(),
            content = reason
        )
    }

    /**
     * Create a deletion request for a single event.
     *
     * @param signer The NostrSigner to sign the event
     * @param eventId The event ID to request deletion of
     * @param reason Optional reason for deletion
     * @param kind Optional kind of the event being deleted (for NIP-09 k-tag)
     * @return Signed deletion event
     */
    suspend fun createSingle(
        signer: NostrSigner,
        eventId: String,
        reason: String = "",
        kind: Int? = null
    ): Event {
        return create(signer, listOf(eventId), reason, kind?.let { listOf(it) })
    }
}
