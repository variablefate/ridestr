package com.ridestr.common.nostr

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SubscriptionManagerTest {

    private val closedIds = mutableListOf<String>()
    private lateinit var manager: SubscriptionManager

    @Before
    fun setup() {
        closedIds.clear()
        manager = SubscriptionManager { closedIds.add(it) }
    }

    // ==================== set() / get() ====================

    @Test
    fun `set stores ID and get returns it`() {
        manager.set("key1", "sub-1")
        assertEquals("sub-1", manager.get("key1"))
    }

    @Test
    fun `get returns null for unknown key`() {
        assertNull(manager.get("unknown"))
    }

    @Test
    fun `set with new value auto-closes old subscription`() {
        manager.set("key1", "sub-old")
        manager.set("key1", "sub-new")

        assertEquals("sub-new", manager.get("key1"))
        assertEquals(listOf("sub-old"), closedIds)
    }

    @Test
    fun `set with same value does not close`() {
        manager.set("key1", "sub-1")
        manager.set("key1", "sub-1")

        assertEquals("sub-1", manager.get("key1"))
        assertTrue("Should not close when setting same ID", closedIds.isEmpty())
    }

    @Test
    fun `set with null closes existing and removes`() {
        manager.set("key1", "sub-1")
        manager.set("key1", null)

        assertNull(manager.get("key1"))
        assertEquals(listOf("sub-1"), closedIds)
    }

    @Test
    fun `set with null on missing key is no-op`() {
        manager.set("key1", null)
        assertNull(manager.get("key1"))
        assertTrue(closedIds.isEmpty())
    }

    // ==================== close() ====================

    @Test
    fun `close removes and closes subscription`() {
        manager.set("key1", "sub-1")
        manager.close("key1")

        assertNull(manager.get("key1"))
        assertEquals(listOf("sub-1"), closedIds)
    }

    @Test
    fun `close is no-op for missing key`() {
        manager.close("missing")
        assertTrue(closedIds.isEmpty())
    }

    // ==================== closeAll(vararg) ====================

    @Test
    fun `closeAll vararg closes only named keys`() {
        manager.set("a", "sub-a")
        manager.set("b", "sub-b")
        manager.set("c", "sub-c")

        manager.closeAll("a", "b")

        assertNull(manager.get("a"))
        assertNull(manager.get("b"))
        assertEquals("sub-c", manager.get("c"))
        assertEquals(2, closedIds.size)
        assertTrue(closedIds.contains("sub-a"))
        assertTrue(closedIds.contains("sub-b"))
    }

    @Test
    fun `closeAll vararg ignores missing keys`() {
        manager.set("a", "sub-a")
        manager.closeAll("a", "missing")

        assertEquals(listOf("sub-a"), closedIds)
    }

    // ==================== Group operations ====================

    @Test
    fun `setInGroup stores and closeInGroup removes`() {
        manager.setInGroup("profiles", "pk1", "sub-1")
        assertTrue(manager.groupContains("profiles", "pk1"))

        manager.closeInGroup("profiles", "pk1")
        assertFalse(manager.groupContains("profiles", "pk1"))
        assertEquals(listOf("sub-1"), closedIds)
    }

    @Test
    fun `setInGroup auto-closes old subscription for same entity`() {
        manager.setInGroup("profiles", "pk1", "sub-old")
        manager.setInGroup("profiles", "pk1", "sub-new")

        assertTrue(manager.groupContains("profiles", "pk1"))
        assertEquals(listOf("sub-old"), closedIds)
    }

    @Test
    fun `setInGroup with same ID does not close`() {
        manager.setInGroup("profiles", "pk1", "sub-1")
        manager.setInGroup("profiles", "pk1", "sub-1")

        assertTrue(closedIds.isEmpty())
    }

    @Test
    fun `closeGroup closes all entities in group`() {
        manager.setInGroup("profiles", "pk1", "sub-1")
        manager.setInGroup("profiles", "pk2", "sub-2")
        manager.setInGroup("profiles", "pk3", "sub-3")

        manager.closeGroup("profiles")

        assertFalse(manager.groupContains("profiles", "pk1"))
        assertFalse(manager.groupContains("profiles", "pk2"))
        assertFalse(manager.groupContains("profiles", "pk3"))
        assertEquals(3, closedIds.size)
    }

    @Test
    fun `closeGroup is no-op for missing group`() {
        manager.closeGroup("missing")
        assertTrue(closedIds.isEmpty())
    }

    @Test
    fun `closeInGroup is no-op for missing entity`() {
        manager.setInGroup("profiles", "pk1", "sub-1")
        manager.closeInGroup("profiles", "pk-missing")
        assertTrue(closedIds.isEmpty())
    }

    @Test
    fun `closeInGroup is no-op for missing group`() {
        manager.closeInGroup("missing-group", "pk1")
        assertTrue(closedIds.isEmpty())
    }

    @Test
    fun `groupContains returns false for missing group`() {
        assertFalse(manager.groupContains("missing", "pk1"))
    }

    // ==================== closeAll() (nuclear) ====================

    @Test
    fun `closeAll nuclear closes singular and group subscriptions`() {
        manager.set("a", "sub-a")
        manager.set("b", "sub-b")
        manager.setInGroup("profiles", "pk1", "sub-p1")
        manager.setInGroup("profiles", "pk2", "sub-p2")
        manager.setInGroup("other", "x", "sub-x")

        manager.closeAll()

        assertNull(manager.get("a"))
        assertNull(manager.get("b"))
        assertFalse(manager.groupContains("profiles", "pk1"))
        assertFalse(manager.groupContains("other", "x"))
        assertEquals(5, closedIds.size)
    }

    @Test
    fun `closeAll nuclear is safe when empty`() {
        manager.closeAll()
        assertTrue(closedIds.isEmpty())
    }

    // ==================== Create-before-close ordering ====================

    @Test
    fun `set stores new ID before closing old - create-before-close ordering`() {
        // Verify that during the close callback, the new ID is already stored
        var idDuringClose: String? = null
        lateinit var mgr: SubscriptionManager
        mgr = SubscriptionManager { closedId ->
            closedIds.add(closedId)
            idDuringClose = mgr.get("chat")
        }

        mgr.set("chat", "old-sub")
        mgr.set("chat", "new-sub")

        assertEquals("old-sub", closedIds.single())
        assertEquals("new-sub", idDuringClose)
    }
}
