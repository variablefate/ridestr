package com.roadflare.rider.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.roadflare.rider.state.RideStage
import com.roadflare.rider.ui.screens.components.CancelledContent
import com.roadflare.rider.ui.screens.components.ChoosingDriverContent
import com.roadflare.rider.ui.screens.components.CompletedContent
import com.roadflare.rider.ui.screens.components.IdleContent
import com.roadflare.rider.ui.screens.components.InRideContent
import com.roadflare.rider.ui.screens.components.MatchedContent
import com.roadflare.rider.ui.screens.components.RequestingContent
import com.roadflare.rider.viewmodels.RiderViewModel

/**
 * RoadFlare tab — the main ride request entry point.
 *
 * IDLE state shows the full geocoder with autocomplete and "Send RoadFlare" button.
 * Tapping "Send RoadFlare" opens a full-screen DriverSelectionScreen.
 * Other stages show ride progress (requesting, choosing, matched, etc.).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadFlareTab(
    viewModel: RiderViewModel,
    modifier: Modifier = Modifier
) {
    val rideStage by viewModel.rideStage.collectAsState()
    val currentRide by viewModel.currentRide.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val fareEstimate by viewModel.fareEstimate.collectAsState()
    val isCalculatingFare by viewModel.isCalculatingFare.collectAsState()
    val drivers by viewModel.drivers.collectAsState()
    val driverLocations by viewModel.driverLocations.collectAsState()
    val driverNames by viewModel.driverNames.collectAsState()
    val pickupLocation by viewModel.pickupLocation.collectAsState()
    val destLocation by viewModel.destLocation.collectAsState()
    val remoteConfig by viewModel.remoteConfig.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var showDriverSelection by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        when {
            showDriverSelection && pickupLocation != null && destLocation != null && fareEstimate != null -> {
                val driverQuotes by viewModel.driverQuoteCoordinator.driverQuotes.collectAsState()
                val routingComplete by viewModel.driverQuoteCoordinator.routingComplete.collectAsState()

                DriverSelectionScreen(
                    drivers = drivers,
                    driverLocations = driverLocations,
                    driverNames = driverNames,
                    driverQuotes = driverQuotes,
                    routingComplete = routingComplete,
                    pickupLocation = pickupLocation!!,
                    destLocation = destLocation!!,
                    rideMiles = fareEstimate!!.distanceMiles,
                    rideDurationMinutes = fareEstimate?.durationMinutes,
                    remoteConfig = remoteConfig,
                    displayCurrency = settings.displayCurrency,
                    priceService = BitcoinPriceService.getInstance(),
                    isSending = rideStage != RideStage.IDLE,
                    onSendToDriver = { driverOffer, rideMiles ->
                        showDriverSelection = false
                        viewModel.sendRoadflareToDriver(driverOffer, rideMiles)
                    },
                    onSendToAll = { driverOffers, rideMiles ->
                        showDriverSelection = false
                        viewModel.sendRoadflareToAll(driverOffers, rideMiles)
                    },
                    onBack = { showDriverSelection = false }
                )
            }

            rideStage == RideStage.IDLE -> IdleContent(
                viewModel = viewModel,
                sendRoadflareEnabled = fareEstimate != null && !isCalculatingFare
                    && drivers.isNotEmpty(),
                onSendRoadflare = { showDriverSelection = true }
            )
            rideStage == RideStage.REQUESTING -> RequestingContent(
                ride = currentRide,
                onCancel = { viewModel.rideSessionManager.cancelRide() }
            )
            rideStage == RideStage.CHOOSING_DRIVER -> ChoosingDriverContent(
                acceptedDrivers = viewModel.rideSessionManager.getAcceptedDrivers(),
                displayCurrency = settings.displayCurrency,
                priceService = BitcoinPriceService.getInstance(),
                onSelectDriver = { driver ->
                    viewModel.rideSessionManager.confirmDriver(driver)
                },
                onCancel = { viewModel.rideSessionManager.cancelRide() }
            )
            rideStage == RideStage.MATCHED ||
            rideStage == RideStage.DRIVER_EN_ROUTE ||
            rideStage == RideStage.DRIVER_ARRIVED -> MatchedContent(
                ride = currentRide,
                stage = rideStage,
                displayCurrency = settings.displayCurrency,
                priceService = BitcoinPriceService.getInstance(),
                onCancel = { viewModel.rideSessionManager.cancelRide() }
            )
            rideStage == RideStage.IN_RIDE -> InRideContent(
                ride = currentRide,
                messages = chatMessages
            )
            rideStage == RideStage.COMPLETED -> CompletedContent(
                ride = currentRide,
                displayCurrency = settings.displayCurrency,
                priceService = BitcoinPriceService.getInstance(),
                onDone = { viewModel.rideSessionManager.clearRide() }
            )
            rideStage == RideStage.CANCELLED -> CancelledContent(
                onDone = { viewModel.rideSessionManager.clearRide() }
            )
        }
    }
}
