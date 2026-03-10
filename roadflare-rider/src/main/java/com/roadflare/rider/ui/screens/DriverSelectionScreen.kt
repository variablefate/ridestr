package com.roadflare.rider.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.data.CachedDriverLocation
import com.ridestr.common.nostr.events.AdminConfig
import com.ridestr.common.nostr.events.FollowedDriver
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import com.ridestr.common.roadflare.FareState
import com.ridestr.common.roadflare.RoadflareDriverQuote
import com.ridestr.common.roadflare.RoadflareDriverUiModel
import com.ridestr.common.roadflare.RoadflareDriverUiModel.DriverStatus
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.ui.RoadflareDriverCard
import com.roadflare.rider.viewmodels.DriverOfferData
import kotlin.math.roundToLong

/**
 * Full-screen driver selection for roadflare-rider.
 *
 * Pure UI — reads pre-computed [driverQuotes] and [routingComplete] from the
 * DriverQuoteCoordinator. No routing or tile initialization done here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverSelectionScreen(
    drivers: List<FollowedDriver>,
    driverLocations: Map<String, CachedDriverLocation>,
    driverNames: Map<String, String>,
    driverQuotes: Map<String, RoadflareDriverQuote>,
    routingComplete: Boolean,
    pickupLocation: Location,
    destLocation: Location,
    rideMiles: Double,
    rideDurationMinutes: Int?,
    remoteConfig: AdminConfig,
    displayCurrency: DisplayCurrency,
    priceService: BitcoinPriceService,
    isSending: Boolean,
    onSendToDriver: (DriverOfferData, rideMiles: Double) -> Unit,
    onSendToAll: (List<DriverOfferData>, rideMiles: Double) -> Unit,
    onBack: () -> Unit
) {
    val now = System.currentTimeMillis() / 1000
    val staleThresholdSec = 5 * 60

    // Map drivers to UI models
    val driverModels = remember(drivers, driverLocations, driverNames, driverQuotes, isSending, now) {
        drivers.map { driver ->
            val cached = driverLocations[driver.pubkey]
            val quote = driverQuotes[driver.pubkey]
            val hasKey = driver.roadflareKey != null
            val isOnline = cached != null &&
                cached.status == RoadflareLocationEvent.Status.ONLINE &&
                (now - cached.timestamp) < staleThresholdSec
            val isOnRide = cached?.status == RoadflareLocationEvent.Status.ON_RIDE

            val status = when {
                !hasKey -> DriverStatus.PENDING_APPROVAL
                isOnRide -> DriverStatus.ON_RIDE
                !isOnline -> DriverStatus.OFFLINE
                quote?.isTooFar == true -> DriverStatus.TOO_FAR
                else -> DriverStatus.AVAILABLE
            }

            val fareState = quote?.fareState ?: FareState.CALCULATING
            val isTooFar = quote?.isTooFar == true

            val formattedFare = if (quote != null && fareState != FareState.CALCULATING) {
                formatFareUsd(quote.fareUsd, displayCurrency, priceService)
            } else null

            val isBroadcastEligible = hasKey && isOnline && !isTooFar &&
                fareState != FareState.CALCULATING &&
                cached != null && (now - cached.timestamp) < staleThresholdSec

            val isDirectSelectable = hasKey && !isSending &&
                fareState != FareState.CALCULATING

            RoadflareDriverUiModel(
                pubkey = driver.pubkey,
                displayName = driverNames[driver.pubkey] ?: driver.pubkey.take(8) + "...",
                status = status,
                formattedFare = formattedFare,
                pickupMiles = if (fareState != FareState.CALCULATING) quote?.pickupMiles else null,
                fareState = fareState,
                isTooFar = isTooFar,
                isBroadcastEligible = isBroadcastEligible,
                isDirectSelectable = isDirectSelectable
            )
        }
    }

    val broadcastCount = driverModels.count { it.isBroadcastEligible }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top bar: back + title + private badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Select Driver",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            // Privacy badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Private",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Ride summary card (compact — distance + duration only, no fare)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${pickupLocation.addressLabel ?: pickupLocation.getDisplayString()}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "to ${destLocation.addressLabel ?: destLocation.getDisplayString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${String.format("%.1f", rideMiles)} mi",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Distance",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    rideDurationMinutes?.let { duration ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "~$duration min",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Duration",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Tap a driver to send request directly",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Driver list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(driverModels, key = { it.pubkey }) { model ->
                RoadflareDriverCard(
                    model = model,
                    onClick = {
                        val quote = driverQuotes[model.pubkey] ?: return@RoadflareDriverCard
                        onSendToDriver(
                            DriverOfferData(
                                pubkey = model.pubkey,
                                displayName = model.displayName,
                                pickupMiles = quote.pickupMiles,
                                fareUsd = quote.fareUsd,
                                fareSats = quote.toEventFareSats(priceService)
                            ),
                            rideMiles
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Broadcast button
        Button(
            onClick = {
                val eligible = driverModels.filter { it.isBroadcastEligible }
                val offerData = eligible.mapNotNull { model ->
                    val quote = driverQuotes[model.pubkey] ?: return@mapNotNull null
                    DriverOfferData(
                        pubkey = model.pubkey,
                        displayName = model.displayName,
                        pickupMiles = quote.pickupMiles,
                        fareUsd = quote.fareUsd,
                        fareSats = quote.toEventFareSats(priceService)
                    )
                }
                onSendToAll(offerData, rideMiles)
            },
            enabled = routingComplete && broadcastCount > 0 && !isSending,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(
                Icons.Default.Flare,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (!routingComplete) {
                Text("Calculating fares...")
            } else {
                Text("Send RoadFlare ($broadcastCount)")
            }
        }

        if (isSending) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sending request...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Format a USD fare amount respecting the display currency preference.
 * When displayCurrency is SATS, converts USD to sats using the price service.
 */
private fun formatFareUsd(
    fareUsd: Double,
    displayCurrency: DisplayCurrency,
    priceService: BitcoinPriceService
): String {
    return when (displayCurrency) {
        DisplayCurrency.USD -> "$${String.format("%.2f", fareUsd)}"
        DisplayCurrency.SATS -> {
            val sats = priceService.usdToSats(fareUsd)
            if (sats != null) "${String.format("%,d", sats)} sats"
            else "$${String.format("%.2f", fareUsd)}"
        }
    }
}

private fun RoadflareDriverQuote.toEventFareSats(priceService: BitcoinPriceService): Long {
    return fareSats
        ?: priceService.usdToSats(fareUsd)
        ?: maxOf((fareUsd * 1000.0).roundToLong(), 1L)
}
