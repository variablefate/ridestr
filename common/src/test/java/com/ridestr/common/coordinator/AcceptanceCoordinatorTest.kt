package com.ridestr.common.coordinator

import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.BroadcastRideOfferData
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PaymentMethod
import com.ridestr.common.nostr.events.PaymentPath
import com.ridestr.common.nostr.events.RideOfferData
import com.ridestr.common.payment.WalletService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for AcceptanceCoordinator — CAS gate semantics, PaymentPath derivation,
 * and the sealed AcceptBroadcastOutcome surface.
 * Robolectric runner provides android.util.Log stubs used by the coordinator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AcceptanceCoordinatorTest {

    private lateinit var nostrService: NostrService
    private lateinit var walletService: WalletService
    private lateinit var coordinator: AcceptanceCoordinator

    private val driverMint = "https://mint.driver.example"
    private val riderMintSame = driverMint
    private val riderMintDifferent = "https://mint.rider.example"

    @Before
    fun setUp() {
        nostrService = mockk(relaxed = true)
        walletService = mockk(relaxed = true)
        every { walletService.getWalletPubKey() } returns "driver_wallet_pk"
        every { walletService.getSavedMintUrl() } returns driverMint

        coordinator = AcceptanceCoordinator(
            nostrService = nostrService,
            walletServiceProvider = { walletService }
        )
    }

    private fun directOffer(
        mintUrl: String? = riderMintSame,
        paymentMethod: String = PaymentMethod.CASHU.value
    ) = RideOfferData(
        eventId = "offer_evt",
        riderPubKey = "rider_pk",
        driverEventId = "availability_evt",
        driverPubKey = "driver_pk",
        approxPickup = Location(37.0, -122.0),
        destination = Location(37.1, -122.1),
        fareEstimate = 10_000.0,
        createdAt = 1_700_000_000L,
        mintUrl = mintUrl,
        paymentMethod = paymentMethod
    )

    private fun broadcastRequest(
        mintUrl: String? = riderMintSame,
        paymentMethod: String = PaymentMethod.CASHU.value
    ) = BroadcastRideOfferData(
        eventId = "broadcast_evt",
        riderPubKey = "rider_pk",
        pickupArea = Location(37.0, -122.0),
        destinationArea = Location(37.1, -122.1),
        fareEstimate = 10_000.0,
        routeDistanceKm = 5.0,
        routeDurationMin = 15.0,
        createdAt = 1_700_000_000L,
        geohashes = listOf("9q9"),
        mintUrl = mintUrl,
        paymentMethod = paymentMethod
    )

    // ── acceptOffer ──────────────────────────────────────────────────────────

    @Test
    fun `acceptOffer returns Success carrying publish details for same-mint path`() = runTest {
        coEvery {
            nostrService.acceptRide(any(), any(), any(), any())
        } returns "acceptance_evt"

        val result = coordinator.acceptOffer(directOffer())

        assertNotNull(result)
        assertEquals("acceptance_evt", result!!.acceptanceEventId)
        assertEquals("driver_wallet_pk", result.walletPubKey)
        assertEquals(driverMint, result.driverMintUrl)
        assertEquals(PaymentPath.SAME_MINT, result.paymentPath)
    }

    @Test
    fun `acceptOffer derives CROSS_MINT when rider and driver mints differ`() = runTest {
        coEvery {
            nostrService.acceptRide(any(), any(), any(), any())
        } returns "acceptance_evt"

        val result = coordinator.acceptOffer(directOffer(mintUrl = riderMintDifferent))

        assertEquals(PaymentPath.CROSS_MINT, result!!.paymentPath)
    }

    @Test
    fun `acceptOffer derives FIAT_CASH for fiat payment method`() = runTest {
        coEvery {
            nostrService.acceptRide(any(), any(), any(), any())
        } returns "acceptance_evt"

        val result = coordinator.acceptOffer(
            directOffer(mintUrl = null, paymentMethod = PaymentMethod.FIAT_CASH.value)
        )

        assertEquals(PaymentPath.FIAT_CASH, result!!.paymentPath)
    }

    @Test
    fun `acceptOffer returns null when Nostr publish fails`() = runTest {
        coEvery {
            nostrService.acceptRide(any(), any(), any(), any())
        } returns null

        val result = coordinator.acceptOffer(directOffer())

        assertNull(result)
    }

    // ── acceptBroadcastRequest CAS gate ──────────────────────────────────────

    @Test
    fun `acceptBroadcastRequest first call succeeds and returns Success`() = runTest {
        coEvery {
            nostrService.acceptBroadcastRide(any(), any(), any(), any())
        } returns "acceptance_evt"

        val outcome = coordinator.acceptBroadcastRequest(broadcastRequest(), "driver_pk")

        assertTrue(outcome is AcceptBroadcastOutcome.Success)
        val success = outcome as AcceptBroadcastOutcome.Success
        assertEquals("acceptance_evt", success.result.acceptanceEventId)
        assertEquals(PaymentPath.SAME_MINT, success.result.paymentPath)
    }

    @Test
    fun `acceptBroadcastRequest second call returns DuplicateBlocked without publishing`() = runTest {
        coEvery {
            nostrService.acceptBroadcastRide(any(), any(), any(), any())
        } returns "acceptance_evt"

        coordinator.acceptBroadcastRequest(broadcastRequest(), "driver_pk")
        val second = coordinator.acceptBroadcastRequest(broadcastRequest(), "driver_pk")

        assertTrue(
            "second acceptance must be blocked by CAS gate",
            second is AcceptBroadcastOutcome.DuplicateBlocked
        )
        coVerify(exactly = 1) {
            nostrService.acceptBroadcastRide(any(), any(), any(), any())
        }
    }

    @Test
    fun `acceptBroadcastRequest returns PublishFailed and resets gate on null publish`() = runTest {
        coEvery {
            nostrService.acceptBroadcastRide(any(), any(), any(), any())
        } returns null

        val outcome = coordinator.acceptBroadcastRequest(broadcastRequest(), "driver_pk")
        assertTrue(outcome is AcceptBroadcastOutcome.PublishFailed)

        // Gate must be reset so a retry can proceed
        coEvery {
            nostrService.acceptBroadcastRide(any(), any(), any(), any())
        } returns "acceptance_evt"
        val retry = coordinator.acceptBroadcastRequest(broadcastRequest(), "driver_pk")
        assertTrue(
            "gate must reset after publish failure to allow retry",
            retry is AcceptBroadcastOutcome.Success
        )
    }

    @Test
    fun `acceptBroadcastRequest resets gate on CancellationException and rethrows`() = runTest {
        coEvery {
            nostrService.acceptBroadcastRide(any(), any(), any(), any())
        } throws CancellationException("cancelled mid-publish")

        try {
            coordinator.acceptBroadcastRequest(broadcastRequest(), "driver_pk")
            fail("expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // expected
        }

        // Gate should be reset so a retry after cancellation can proceed.
        coEvery {
            nostrService.acceptBroadcastRide(any(), any(), any(), any())
        } returns "acceptance_evt"
        val retry = coordinator.acceptBroadcastRequest(broadcastRequest(), "driver_pk")
        assertTrue(
            "gate must reset after CancellationException to allow retry",
            retry is AcceptBroadcastOutcome.Success
        )
    }

    @Test
    fun `resetBroadcastGate unblocks subsequent acceptance after Success`() = runTest {
        coEvery {
            nostrService.acceptBroadcastRide(any(), any(), any(), any())
        } returns "acceptance_evt"

        coordinator.acceptBroadcastRequest(broadcastRequest(), "driver_pk")
        coordinator.resetBroadcastGate()
        val afterReset = coordinator.acceptBroadcastRequest(broadcastRequest(), "driver_pk")

        assertTrue(
            "after resetBroadcastGate, acceptance must proceed",
            afterReset is AcceptBroadcastOutcome.Success
        )
    }

    // ── AcceptanceResult shape ───────────────────────────────────────────────

    @Test
    fun `acceptBroadcastRequest Success carries normalized compatibleOffer`() = runTest {
        coEvery {
            nostrService.acceptBroadcastRide(any(), any(), any(), any())
        } returns "acceptance_evt"

        val request = broadcastRequest()
        val outcome = coordinator.acceptBroadcastRequest(request, "driver_pk") as AcceptBroadcastOutcome.Success

        assertEquals(request.eventId, outcome.result.offer.eventId)
        assertEquals(request.riderPubKey, outcome.result.offer.riderPubKey)
        assertEquals("driver_pk", outcome.result.offer.driverPubKey)
        assertEquals(request, outcome.result.broadcastRequest)
    }
}
