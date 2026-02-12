package com.ridestr.common.nostr

/**
 * Centralized manager for Nostr subscription ID lifecycle.
 * Replaces scattered nullable subscription ID variables with a type-safe registry.
 *
 * Supports singular subscriptions (one ID per key) and group subscriptions
 * (multiple IDs per key, e.g., profile subscriptions keyed by pubkey).
 *
 * Thread safety: Map operations are synchronized. closeSubscription() is called
 * OUTSIDE the lock to avoid holding it during relay I/O (RelayConnection.send()
 * acquires its own lock).
 *
 * @param closeSubscription Typically `nostrService::closeSubscription`
 */
class SubscriptionManager(
    private val closeSubscription: (String) -> Unit
) {
    private val lock = Any()
    private val subscriptions = mutableMapOf<String, String>()
    private val groups = mutableMapOf<String, MutableMap<String, String>>()

    /** Store subscription ID. Auto-closes previous for same key. Null = close only. */
    fun set(key: String, id: String?) {
        val old = synchronized(lock) {
            val prev = subscriptions.remove(key)
            if (id != null) subscriptions[key] = id
            prev?.takeIf { it != id }
        }
        old?.let { closeSubscription(it) }
    }

    /** Get current subscription ID for key. */
    fun get(key: String): String? = synchronized(lock) { subscriptions[key] }

    /** Close and remove subscription for key. */
    fun close(key: String) {
        val id = synchronized(lock) { subscriptions.remove(key) }
        id?.let { closeSubscription(it) }
    }

    /** Close subscriptions for all given keys. */
    fun closeAll(vararg keys: String) {
        val ids = synchronized(lock) { keys.mapNotNull { subscriptions.remove(it) } }
        ids.forEach { closeSubscription(it) }
    }

    /** Store ID in a named group (e.g., profiles keyed by pubkey). Auto-closes old. */
    fun setInGroup(groupKey: String, entityId: String, id: String) {
        val old = synchronized(lock) {
            val group = groups.getOrPut(groupKey) { mutableMapOf() }
            group.put(entityId, id)?.takeIf { it != id }
        }
        old?.let { closeSubscription(it) }
    }

    /** Close one entity's subscription from a group. */
    fun closeInGroup(groupKey: String, entityId: String) {
        val id = synchronized(lock) {
            val group = groups[groupKey] ?: return@synchronized null
            val removed = group.remove(entityId)
            if (group.isEmpty()) groups.remove(groupKey)
            removed
        }
        id?.let { closeSubscription(it) }
    }

    /** Close all subscriptions in a group. */
    fun closeGroup(groupKey: String) {
        val ids = synchronized(lock) {
            groups.remove(groupKey)?.values?.toList() ?: emptyList()
        }
        ids.forEach { closeSubscription(it) }
    }

    /** Check if group has a subscription for entity. */
    fun groupContains(groupKey: String, entityId: String): Boolean =
        synchronized(lock) { groups[groupKey]?.containsKey(entityId) == true }

    /** Nuclear: close ALL subscriptions (singular + all groups). For onCleared(). */
    fun closeAll() {
        val ids = synchronized(lock) {
            val all = mutableListOf<String>()
            all.addAll(subscriptions.values)
            subscriptions.clear()
            groups.values.forEach { all.addAll(it.values) }
            groups.clear()
            all
        }
        ids.forEach { closeSubscription(it) }
    }
}
