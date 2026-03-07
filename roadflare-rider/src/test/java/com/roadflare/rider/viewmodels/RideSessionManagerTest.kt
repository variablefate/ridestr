package com.roadflare.rider.viewmodels

import com.ridestr.common.nostr.NostrService
import com.roadflare.rider.harness.MainDispatcherRule
import com.roadflare.rider.state.RideStage
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RideSessionManagerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `destroy resets state to IDLE and nulls currentRide`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockNostrService = mockk<NostrService>(relaxed = true)
        val manager = RideSessionManager(mockNostrService)

        manager.destroy()

        assertEquals(RideStage.IDLE, manager.rideStage.value)
        assertNull(manager.currentRide.value)
    }

    @Test
    fun `destroy cancels the coroutine scope`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockNostrService = mockk<NostrService>(relaxed = true)
        val manager = RideSessionManager(mockNostrService)

        assertTrue(manager.isScopeActive())
        manager.destroy()
        assertFalse(manager.isScopeActive())
    }

    @Test
    fun `destroy closes active subscriptions and resets count to zero`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockNostrService = mockk<NostrService>(relaxed = true)
        val manager = RideSessionManager(mockNostrService)

        // Inject test subscription IDs via @VisibleForTesting accessor
        manager.addTestSubscriptionId("sub-1")
        manager.addTestSubscriptionId("sub-2")
        assertEquals(2, manager.activeSubscriptionCount())

        manager.destroy()

        assertEquals(0, manager.activeSubscriptionCount())
        verify { mockNostrService.closeSubscription("sub-1") }
        verify { mockNostrService.closeSubscription("sub-2") }
    }
}
