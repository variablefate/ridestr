package com.roadflare.rider.ui.screens.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.fiat.formatSatsOrPlaceholder
import com.ridestr.common.fiat.formatUsd
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.ui.FavoritesSection
import com.ridestr.common.ui.LocationSearchField
import com.ridestr.common.ui.RecentsSection
import com.roadflare.rider.state.RideStage
import com.roadflare.rider.viewmodels.ChatMessage
import com.roadflare.rider.viewmodels.DriverInfo
import com.roadflare.rider.viewmodels.RideSession
import com.roadflare.rider.viewmodels.RiderViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun formatFareAmount(
    fareUsd: Double,
    displayCurrency: DisplayCurrency,
    priceService: BitcoinPriceService
): String {
    // Subscribe so the string recomposes when the BTC price changes.
    val btcPrice by priceService.btcPriceUsd.collectAsState()
    return when (displayCurrency) {
        DisplayCurrency.USD -> fareUsd.formatUsd()
        DisplayCurrency.SATS -> formatSatsOrPlaceholder(
            btcPrice?.takeIf { it > 0 }?.let { priceService.usdToSats(fareUsd) }
        )
    }
}

/**
 * Takes [RiderViewModel] directly because this panel wires ~10 state flows and ~10 action
 * methods (pickup/dest search, GPS, saved-location management, swap). A narrow-props
 * signature would explode to 20+ parameters.
 */
@Composable
fun IdleContent(
    viewModel: RiderViewModel,
    sendRoadflareEnabled: Boolean,
    onSendRoadflare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickupLocation by viewModel.pickupLocation.collectAsState()
    val destLocation by viewModel.destLocation.collectAsState()
    val pickupSearchResults by viewModel.pickupSearchResults.collectAsState()
    val destSearchResults by viewModel.destSearchResults.collectAsState()
    val isSearchingPickup by viewModel.isSearchingPickup.collectAsState()
    val isSearchingDest by viewModel.isSearchingDest.collectAsState()
    val fareEstimate by viewModel.fareEstimate.collectAsState()
    val isCalculatingFare by viewModel.isCalculatingFare.collectAsState()

    var pickupQuery by remember { mutableStateOf("") }
    var destQuery by remember { mutableStateOf("") }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch {
                try {
                    val client = LocationServices.getFusedLocationProviderClient(context)
                    val location = client.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY, null
                    ).await()
                    if (location != null) {
                        viewModel.setPickupFromGps(location.latitude, location.longitude)
                        pickupQuery = ""
                    }
                } catch (_: Exception) { }
            }
        }
    }

    val hasLocationPermission = remember {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Where are you going?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pickup",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                LocationSearchField(
                    value = pickupQuery,
                    onValueChange = { newValue ->
                        pickupQuery = newValue
                        if (newValue.isEmpty()) viewModel.clearPickup()
                    },
                    selectedLocation = pickupLocation,
                    onLocationSelected = { result ->
                        viewModel.selectPickupFromSearch(result)
                        pickupQuery = ""
                    },
                    searchResults = pickupSearchResults,
                    isSearching = isSearchingPickup,
                    onSearch = { viewModel.searchPickupLocations(it) },
                    placeholder = "Search pickup address...",
                    showMyLocation = true,
                    onUseMyLocation = {
                        if (hasLocationPermission) {
                            scope.launch {
                                try {
                                    val client = LocationServices.getFusedLocationProviderClient(context)
                                    val location = client.getCurrentLocation(
                                        Priority.PRIORITY_HIGH_ACCURACY, null
                                    ).await()
                                    if (location != null) {
                                        viewModel.setPickupFromGps(location.latitude, location.longitude)
                                        pickupQuery = ""
                                    }
                                } catch (_: Exception) { }
                            }
                        } else {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (pickupLocation != null || destLocation != null) {
                        IconButton(onClick = { viewModel.swapLocations() }) {
                            Icon(
                                Icons.Default.SwapVert,
                                contentDescription = "Swap pickup and destination",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Destination",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                LocationSearchField(
                    value = destQuery,
                    onValueChange = { newValue ->
                        destQuery = newValue
                        if (newValue.isEmpty()) viewModel.clearDest()
                    },
                    selectedLocation = destLocation,
                    onLocationSelected = { result ->
                        viewModel.selectDestFromSearch(result)
                        destQuery = ""
                    },
                    searchResults = destSearchResults,
                    isSearching = isSearchingDest,
                    onSearch = { viewModel.searchDestLocations(it) },
                    placeholder = "Search destination...",
                    modifier = Modifier.fillMaxWidth()
                )

                if (pickupLocation == null || destLocation == null) {
                    val allSaved by viewModel.savedLocations.collectAsState()
                    val favorites = allSaved.filter { it.isPinned }.sortedBy { it.nickname ?: it.displayName }
                    val recents = allSaved.filter { !it.isPinned }.sortedByDescending { it.timestampMs }

                    if (favorites.isNotEmpty() || recents.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Saved Places",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val populatePickup = pickupLocation == null

                        FavoritesSection(
                            favorites = favorites,
                            onLocationSelected = { loc ->
                                val result = loc.toGeocodingResult()
                                if (populatePickup) {
                                    viewModel.selectPickupFromSearch(result)
                                    pickupQuery = ""
                                } else {
                                    viewModel.selectDestFromSearch(result)
                                    destQuery = ""
                                }
                            },
                            onUpdateNickname = { id, nick -> viewModel.pinWithNickname(id, nick) },
                            onUnpin = { id -> viewModel.unpinFavorite(id) },
                            onDelete = { id -> viewModel.removeSavedLocation(id) }
                        )

                        RecentsSection(
                            recents = recents,
                            onLocationSelected = { loc ->
                                val result = loc.toGeocodingResult()
                                if (populatePickup) {
                                    viewModel.selectPickupFromSearch(result)
                                    pickupQuery = ""
                                } else {
                                    viewModel.selectDestFromSearch(result)
                                    destQuery = ""
                                }
                            },
                            onPinWithNickname = { id, nick -> viewModel.pinWithNickname(id, nick) },
                            onDelete = { id -> viewModel.removeSavedLocation(id) }
                        )
                    }
                }

                if (pickupLocation != null && destLocation != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    fareEstimate?.let { route ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${String.format("%.1f", route.distanceMiles)} mi",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Distance",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${route.durationMinutes} min",
                                    style = MaterialTheme.typography.titleMedium,
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

                    if (isCalculatingFare) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Calculating route...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        val drivers by viewModel.drivers.collectAsState()
        if (drivers.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "No drivers in your network yet. Add drivers from the Driver Network tab first.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onSendRoadflare,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = sendRoadflareEnabled
        ) {
            Icon(Icons.Default.Groups, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Check Driver Availability", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun RequestingContent(
    ride: RideSession?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Contacting drivers...",
            style = MaterialTheme.typography.titleLarge
        )
        ride?.let {
            Text(
                text = "${it.pendingDrivers.size} drivers in your network",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Cancel")
        }
    }
}

@Composable
fun ChoosingDriverContent(
    acceptedDrivers: List<DriverInfo>,
    displayCurrency: DisplayCurrency,
    priceService: BitcoinPriceService,
    onSelectDriver: (DriverInfo) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Choose a Driver",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${acceptedDrivers.size} driver(s) accepted your request",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Quoted fares vary by driver",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        acceptedDrivers.forEach { driver ->
            Card(
                onClick = { onSelectDriver(driver) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = driver.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            driver.vehicleDescription?.let {
                                Text(text = it, style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                driver.etaMinutes?.let {
                                    Text(
                                        text = "$it min away",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                driver.distanceMiles?.let {
                                    Text(
                                        text = String.format("%.1f mi", it),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        driver.quotedFareUsd?.let { fareUsd ->
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatFareAmount(fareUsd, displayCurrency, priceService),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel Request")
        }
    }
}

@Composable
fun MatchedContent(
    ride: RideSession?,
    stage: RideStage,
    displayCurrency: DisplayCurrency,
    priceService: BitcoinPriceService,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val statusText = when (stage) {
            RideStage.MATCHED -> "Ride confirmed!"
            RideStage.DRIVER_EN_ROUTE -> "Driver is on the way"
            RideStage.DRIVER_ARRIVED -> "Driver has arrived"
            else -> ""
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        ride?.selectedDriver?.let { driver ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = driver.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    driver.vehicleDescription?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium)
                    }
                    driver.etaMinutes?.let {
                        Text(
                            text = "ETA: $it minutes",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        ride?.let {
            val fareUsd = it.selectedFareUsd ?: it.rideReferenceFareUsd
            Text(
                text = "Fare: ${formatFareAmount(fareUsd, displayCurrency, priceService)}",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Cancel Ride")
        }
    }
}

@Composable
fun InRideContent(
    ride: RideSession?,
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Ride in Progress",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        ride?.selectedDriver?.let { driver ->
            Text(
                text = "With ${driver.displayName}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (messages.isNotEmpty()) {
            Text(
                text = "${messages.size} messages",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun CompletedContent(
    ride: RideSession?,
    displayCurrency: DisplayCurrency,
    priceService: BitcoinPriceService,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Ride Complete",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        ride?.let {
            val fareUsd = it.selectedFareUsd ?: it.rideReferenceFareUsd
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Total: ${formatFareAmount(fareUsd, displayCurrency, priceService)}",
                style = MaterialTheme.typography.titleLarge
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text("Done")
        }
    }
}

/** Pure-presentation cancellation screen — no ride state bindings. */
@Composable
fun CancelledContent(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Ride Cancelled",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text("Done")
        }
    }
}
