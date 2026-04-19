package com.drivestr.app.ui.screens.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.fiat.formatUsd
import com.ridestr.common.fiat.usdAmountOrNull
import com.ridestr.common.nostr.events.BroadcastRideOfferData
import com.ridestr.common.nostr.events.PaymentMethod
import com.ridestr.common.nostr.events.RideOfferData
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.DistanceUnit
import com.ridestr.common.ui.FareDisplay
import java.util.Locale

/**
 * Leaf composable that isolates the 1Hz relative-time ticker from the enclosing card.
 *
 * Before Issue #71 the ticker lived inside [RideOfferCard] / [BroadcastRideRequestCard]
 * and each 1s tick recomposed the whole card — re-running route maths, earnings
 * calculations, and payment-method lookups for every visible offer. Pulling the
 * ticker into this leaf means only the `Text` itself recomposes each second; the
 * surrounding card stays stable.
 */
@Composable
private fun RelativeTimeText(
    timestampSeconds: Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    var relativeTime by remember(timestampSeconds) {
        mutableStateOf(formatRelativeTime(timestampSeconds))
    }
    LaunchedEffect(timestampSeconds) {
        while (true) {
            relativeTime = formatRelativeTime(timestampSeconds)
            kotlinx.coroutines.delay(1000)
        }
    }
    Text(
        text = relativeTime,
        style = style,
        color = color,
        modifier = modifier
    )
}

private fun formatRelativeTime(timestampSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    val diffSeconds = nowSeconds - timestampSeconds

    return when {
        diffSeconds < 60 -> "${diffSeconds}s ago"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86400 -> "${diffSeconds / 3600}h ago"
        else -> "${diffSeconds / 86400}d ago"
    }
}

private fun formatDistance(distanceKm: Double, unit: DistanceUnit): String {
    return when (unit) {
        DistanceUnit.MILES -> {
            val miles = distanceKm * 0.621371
            if (miles < 0.1) {
                "${(miles * 5280).toInt()} ft"
            } else {
                String.format(Locale.US, "%.1f mi", miles)
            }
        }
        DistanceUnit.KILOMETERS -> {
            if (distanceKm < 1) {
                "${(distanceKm * 1000).toInt()} m"
            } else {
                String.format(Locale.US, "%.1f km", distanceKm)
            }
        }
    }
}

internal fun formatEarnings(
    satsPerUnit: Double,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    suffix: String,
    fiatAmountPerUnitUsd: Double? = null
): String {
    return when (displayCurrency) {
        DisplayCurrency.SATS -> {
            "${satsPerUnit.toInt()} sats$suffix"
        }
        DisplayCurrency.USD -> {
            if (fiatAmountPerUnitUsd != null) {
                "${fiatAmountPerUnitUsd.formatUsd()}$suffix"
            } else if (btcPriceUsd != null && btcPriceUsd > 0) {
                val usdValue = (satsPerUnit / 100_000_000.0) * btcPriceUsd
                "${usdValue.formatUsd()}$suffix"
            } else {
                "${satsPerUnit.toInt()} sats$suffix"
            }
        }
    }
}

private fun effectiveRoadflareDriverMethods(driverFiatMethods: List<String>): List<String> {
    val bitcoin = PaymentMethod.BITCOIN.value
    return (driverFiatMethods + bitcoin).distinctBy { it.trim().lowercase() }
}

/**
 * Intercept accept taps on fiat RoadFlare offers with no common payment method
 * (Issue #46). Routes to a warning dialog instead of accepting when the driver
 * can't actually settle in any method the rider prefers.
 */
private fun handleOfferAccept(
    offer: RideOfferData,
    driverFiatMethods: List<String>,
    onAcceptOffer: (RideOfferData) -> Unit,
    onSetNoMatchWarning: (String) -> Unit
) {
    val needsWarning = offer.isRoadflare &&
        offer.paymentMethod != "cashu" &&
        offer.fiatPaymentMethods.isNotEmpty() &&
        PaymentMethod.findBestCommonFiatMethod(
            offer.fiatPaymentMethods,
            effectiveRoadflareDriverMethods(driverFiatMethods)
        ) == null

    if (needsWarning) onSetNoMatchWarning(offer.eventId) else onAcceptOffer(offer)
}

/**
 * Earnings strings rendered beneath an offer card. Packaged in a single value so
 * both fields are computed in one [remember] block — the math shares all inputs and
 * always runs together. See Issue #71: previously these recomputed on every 1Hz
 * ticker recomposition.
 */
private data class EarningsDisplay(val perHour: String?, val perDistance: String?) {
    companion object {
        val None = EarningsDisplay(perHour = null, perDistance = null)
    }
}

/**
 * Compute per-hour and per-distance earnings strings from raw offer inputs.
 *
 * [pickupDistanceKm] and [pickupDurationMin] are nullable to express "pickup
 * route not yet calculated". Both must be present to produce a meaningful
 * total-distance figure (driver only gets paid for ride miles but has to drive
 * pickup miles too — per-mile earnings must reflect the total).
 */
private fun computeEarningsDisplay(
    fareSats: Double,
    authoritativeUsdFare: Double?,
    pickupDistanceKm: Double?,
    pickupDurationMin: Double?,
    rideDistanceKm: Double?,
    rideDurationMin: Double?,
    displayCurrency: DisplayCurrency,
    distanceUnit: DistanceUnit,
    btcPriceUsd: Int?
): EarningsDisplay {
    if (pickupDurationMin == null || rideDurationMin == null) return EarningsDisplay.None

    val totalTimeHours = (pickupDurationMin + rideDurationMin) / 60.0
    val totalDistanceKm = (pickupDistanceKm ?: 0.0) + (rideDistanceKm ?: 0.0)
    val totalDistanceForEarnings = if (distanceUnit == DistanceUnit.MILES) {
        totalDistanceKm * 0.621371
    } else {
        totalDistanceKm
    }

    val perHour = if (totalTimeHours > 0) {
        formatEarnings(
            satsPerUnit = fareSats / totalTimeHours,
            displayCurrency = displayCurrency,
            btcPriceUsd = btcPriceUsd,
            suffix = "/hr",
            fiatAmountPerUnitUsd = authoritativeUsdFare?.div(totalTimeHours)
        )
    } else null

    val perDistance = if (totalDistanceForEarnings > 0) {
        val perDistanceUnit = if (distanceUnit == DistanceUnit.MILES) "/mi" else "/km"
        formatEarnings(
            satsPerUnit = fareSats / totalDistanceForEarnings,
            displayCurrency = displayCurrency,
            btcPriceUsd = btcPriceUsd,
            suffix = perDistanceUnit,
            fiatAmountPerUnitUsd = authoritativeUsdFare?.div(totalDistanceForEarnings)
        )
    } else null

    return EarningsDisplay(perHour = perHour, perDistance = perDistance)
}

/**
 * Pre-computed payment-method match display for a RoadFlare fiat offer. Lets the
 * card branch on the display variant without re-running `findBestCommonFiatMethod`
 * (which allocates a fresh list via [effectiveRoadflareDriverMethods] and scans
 * both sides for a common method) on every recomposition.
 */
private sealed class PaymentMatchDisplay {
    /** Cashu offer, or non-RoadFlare — no match row rendered. */
    object Hidden : PaymentMatchDisplay()

    /** Driver and rider share at least one fiat method. */
    data class BestMatch(val methodDisplay: String) : PaymentMatchDisplay()

    /** Rider advertised methods; driver has none in common — block acceptance. */
    object NoCommon : PaymentMatchDisplay()

    /** Legacy offer without `fiat_payment_methods` — show the single declared method. */
    data class LegacyMethod(val methodDisplay: String) : PaymentMatchDisplay()
}

private fun computePaymentMatch(
    offer: RideOfferData,
    driverFiatMethods: List<String>
): PaymentMatchDisplay {
    val bestMatch = if (offer.fiatPaymentMethods.isNotEmpty()) {
        PaymentMethod.findBestCommonFiatMethod(
            offer.fiatPaymentMethods,
            effectiveRoadflareDriverMethods(driverFiatMethods)
        )
    } else null

    return when {
        bestMatch != null -> PaymentMatchDisplay.BestMatch(
            methodDisplay = PaymentMethod.fromString(bestMatch)?.displayName ?: bestMatch
        )
        offer.fiatPaymentMethods.isNotEmpty() -> PaymentMatchDisplay.NoCommon
        else -> PaymentMatchDisplay.LegacyMethod(
            methodDisplay = PaymentMethod.fromString(offer.paymentMethod)?.displayName ?: offer.paymentMethod
        )
    }
}

@Composable
private fun RideOfferCard(
    offer: RideOfferData,
    pickupRoute: RouteResult?,
    rideRoute: RouteResult?,
    isProcessing: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    displayCurrency: DisplayCurrency,
    distanceUnit: DistanceUnit,
    onToggleCurrency: () -> Unit,
    priceService: BitcoinPriceService,
    driverFiatMethods: List<String>
) {
    val btcPrice by priceService.btcPriceUsd.collectAsState()

    // Route info — cached so we don't re-unbox nullable fields on every recomposition
    // (the full card still recomposes on btcPrice changes, but not on the 1Hz ticker).
    val pickupDistanceKm = remember(pickupRoute) { pickupRoute?.distanceKm }
    val pickupDurationMin = remember(pickupRoute) { pickupRoute?.let { it.durationSeconds / 60.0 } }
    val rideDistanceKm = remember(rideRoute) { rideRoute?.distanceKm }
    val rideDurationMin = remember(rideRoute) { rideRoute?.let { it.durationSeconds / 60.0 } }
    val authoritativeUsdFare = remember(offer.fiatFare) { offer.fiatFare?.usdAmountOrNull() }

    // Earnings math — only recomputes when routes, currency, distance unit, or BTC price change.
    val earnings = remember(
        offer.fareEstimate,
        authoritativeUsdFare,
        pickupDistanceKm,
        pickupDurationMin,
        rideDistanceKm,
        rideDurationMin,
        displayCurrency,
        distanceUnit,
        btcPrice
    ) {
        computeEarningsDisplay(
            fareSats = offer.fareEstimate,
            authoritativeUsdFare = authoritativeUsdFare,
            pickupDistanceKm = pickupDistanceKm,
            pickupDurationMin = pickupDurationMin,
            rideDistanceKm = rideDistanceKm,
            rideDurationMin = rideDurationMin,
            displayCurrency = displayCurrency,
            distanceUnit = distanceUnit,
            btcPriceUsd = btcPrice
        )
    }
    val earningsPerHour = earnings.perHour
    val earningsPerDistance = earnings.perDistance

    // Fiat payment-method best match (RoadFlare offers only). Wrapped in remember so the
    // allocation-heavy list merge + `findBestCommonFiatMethod` scan doesn't re-run each
    // recomposition — the inputs only change when the offer or driver methods change.
    val paymentMatch = remember(
        offer.isRoadflare,
        offer.paymentMethod,
        offer.fiatPaymentMethods,
        driverFiatMethods
    ) {
        if (!offer.isRoadflare || offer.paymentMethod == "cashu") {
            PaymentMatchDisplay.Hidden
        } else {
            computePaymentMatch(offer, driverFiatMethods)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Fare + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FareDisplay(
                            satsAmount = offer.fareEstimate,
                            displayCurrency = displayCurrency,
                            onToggleCurrency = onToggleCurrency,
                            priceService = priceService,
                            fiatFare = offer.fiatFare,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    Text(
                        text = if (offer.isRoadflare) "RoadFlare" else "Direct Request",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (offer.isRoadflare) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                    )
                    // Show fiat payment match status for RoadFlare offers (Issue #46)
                    when (paymentMatch) {
                        PaymentMatchDisplay.Hidden -> Unit
                        is PaymentMatchDisplay.BestMatch -> Text(
                            text = "Pay via: ${paymentMatch.methodDisplay}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32) // Green
                        )
                        PaymentMatchDisplay.NoCommon -> Text(
                            text = "No Common Payment Method",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        is PaymentMatchDisplay.LegacyMethod -> Text(
                            text = "Payment: ${paymentMatch.methodDisplay}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                RelativeTimeText(
                    timestampSeconds = offer.createdAt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pickup route info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (pickupDistanceKm != null && pickupDurationMin != null) {
                    Text(
                        text = "${formatDistance(pickupDistanceKm, distanceUnit)} away",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " • ${pickupDurationMin.toInt()} min to pickup",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Calculating route...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Ride route info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (rideDistanceKm != null && rideDurationMin != null) {
                    Text(
                        text = "${formatDistance(rideDistanceKm, distanceUnit)} ride",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " • ${rideDurationMin.toInt()} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Calculating...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Earnings metrics (if available)
            if (earningsPerHour != null || earningsPerDistance != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    earningsPerHour?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    earningsPerDistance?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Decline")
                }
                Button(
                    onClick = onAccept,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Accept")
                    }
                }
            }
        }
    }
}

/**
 * Card for displaying a broadcast ride request.
 * Shows fare prominently, route info, earnings metrics, and request age.
 */
@Composable
private fun BroadcastRideRequestCard(
    request: BroadcastRideOfferData,
    pickupRoute: RouteResult?,
    isProcessing: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    displayCurrency: DisplayCurrency,
    distanceUnit: DistanceUnit,
    onToggleCurrency: () -> Unit,
    priceService: BitcoinPriceService
) {
    val btcPrice by priceService.btcPriceUsd.collectAsState()

    val rideDistanceKm = request.routeDistanceKm
    val rideDurationMin = request.routeDurationMin
    val rideDistanceStr = remember(rideDistanceKm, distanceUnit) {
        formatDistance(rideDistanceKm, distanceUnit)
    }

    val pickupDistanceKm = remember(pickupRoute) { pickupRoute?.distanceKm }
    val pickupDurationMin = remember(pickupRoute) { pickupRoute?.let { it.durationSeconds / 60.0 } }
    val pickupDistanceStr = remember(pickupDistanceKm, distanceUnit) {
        pickupDistanceKm?.let { formatDistance(it, distanceUnit) }
    }
    val authoritativeUsdFare = remember(request.fiatFare) { request.fiatFare?.usdAmountOrNull() }

    // Earnings: only meaningful when we have the pickup route (without pickup time,
    // $/hr would be misleadingly high). Remember so the math doesn't run per-tick.
    val earnings = remember(
        request.fareEstimate,
        authoritativeUsdFare,
        pickupDistanceKm,
        pickupDurationMin,
        rideDistanceKm,
        rideDurationMin,
        displayCurrency,
        distanceUnit,
        btcPrice
    ) {
        if (pickupDistanceKm == null || pickupDurationMin == null) {
            EarningsDisplay.None
        } else {
            computeEarningsDisplay(
                fareSats = request.fareEstimate,
                authoritativeUsdFare = authoritativeUsdFare,
                pickupDistanceKm = pickupDistanceKm,
                pickupDurationMin = pickupDurationMin,
                rideDistanceKm = rideDistanceKm,
                rideDurationMin = rideDurationMin,
                displayCurrency = displayCurrency,
                distanceUnit = distanceUnit,
                btcPriceUsd = btcPrice
            )
        }
    }
    val earningsPerHour = earnings.perHour
    val earningsPerDistance = earnings.perDistance

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Fare + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FareDisplay(
                            satsAmount = request.fareEstimate,
                            displayCurrency = displayCurrency,
                            onToggleCurrency = onToggleCurrency,
                            priceService = priceService,
                            fiatFare = request.fiatFare,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    if (request.isRoadflare) {
                        Text(
                            text = "RoadFlare",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                RelativeTimeText(
                    timestampSeconds = request.createdAt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pickup info - distance/time from driver's location
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (pickupDistanceStr != null && pickupDurationMin != null) {
                    Text(
                        text = "$pickupDistanceStr away",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " • ${pickupDurationMin.toInt()} min to pickup",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Calculating route...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Ride info - distance/time of the trip
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$rideDistanceStr ride",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = " • ${rideDurationMin.toInt()} min trip",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Earnings metrics row
            if (earningsPerHour != null || earningsPerDistance != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // $/hr metric
                    earningsPerHour?.let { perHour ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = perHour,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    // $/mi or $/km metric
                    earningsPerDistance?.let { perDistance ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Straighten,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = perDistance,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Pass")
                }
                Button(
                    onClick = onAccept,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Accept")
                    }
                }
            }
        }
    }
}

/**
 * Warning dialog shown when driver accepts a fiat offer with no common payment method.
 * Shows rider's top preferences; "Decline Ride" closes dialog but keeps offer visible.
 */
@Composable
fun NoCommonPaymentMethodDialog(
    riderFiatMethods: List<String>,
    onAcceptAnyway: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("No Common Payment Method") },
        text = {
            Column {
                Text(
                    "You and this rider don't share a payment method.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Rider's preferred methods:",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                riderFiatMethods.take(3).forEach { method ->
                    val displayName = com.ridestr.common.nostr.events.PaymentMethod.fromString(method)?.displayName ?: method
                    Text(
                        text = "  \u2022 $displayName",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (riderFiatMethods.size > 3) {
                    Text(
                        text = "  + ${riderFiatMethods.size - 3} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onAcceptAnyway) {
                Text("Accept Anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("Decline Ride")
            }
        }
    )
}

@Composable
fun AvailabilityControls(
    onGoFullyOnline: () -> Unit,
    onGoOffline: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RoadFlare Only",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Broadcasting to followers, receiving RoadFlare requests",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onGoFullyOnline,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Power, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Go Fully Online")
            }
            OutlinedButton(
                onClick = onGoOffline,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PowerOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Go Offline")
            }
        }
    }
}

@Composable
fun RoadflareFollowerList(
    pendingOffers: List<RideOfferData>,
    isProcessingOffer: Boolean,
    directOfferPickupRoutes: Map<String, RouteResult>,
    directOfferRideRoutes: Map<String, RouteResult>,
    displayCurrency: DisplayCurrency,
    distanceUnit: DistanceUnit,
    driverFiatMethods: List<String>,
    onToggleCurrency: () -> Unit,
    priceService: BitcoinPriceService,
    onAcceptOffer: (RideOfferData) -> Unit,
    onDeclineOffer: (RideOfferData) -> Unit,
    onSetNoMatchWarning: (String) -> Unit
) {
    if (pendingOffers.isNotEmpty()) {
        Text(
            text = "RoadFlare Requests",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        // N is small (personal follower network) so a non-lazy Column is fine — the
        // per-card ticker has been hoisted to [RelativeTimeText] so the cost no longer
        // compounds with list length.
        pendingOffers.forEach { offer ->
            key(offer.eventId) {
                val pickupRoute = directOfferPickupRoutes[offer.eventId]
                val rideRoute = directOfferRideRoutes[offer.eventId]
                val onAccept = remember(
                    offer, driverFiatMethods, onAcceptOffer, onSetNoMatchWarning
                ) {
                    { handleOfferAccept(offer, driverFiatMethods, onAcceptOffer, onSetNoMatchWarning) }
                }
                val onDecline = remember(offer, onDeclineOffer) {
                    { onDeclineOffer(offer) }
                }
                RideOfferCard(
                    offer = offer,
                    pickupRoute = pickupRoute,
                    rideRoute = rideRoute,
                    isProcessing = isProcessingOffer,
                    onAccept = onAccept,
                    onDecline = onDecline,
                    displayCurrency = displayCurrency,
                    distanceUnit = distanceUnit,
                    onToggleCurrency = onToggleCurrency,
                    priceService = priceService,
                    driverFiatMethods = driverFiatMethods
                )
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Waiting for RoadFlare requests...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun OfferInbox(
    expandedSearch: Boolean,
    freshBroadcastRequests: List<BroadcastRideOfferData>,
    pendingOffers: List<RideOfferData>,
    isProcessingOffer: Boolean,
    pickupRoutes: Map<String, RouteResult>,
    directOfferPickupRoutes: Map<String, RouteResult>,
    directOfferRideRoutes: Map<String, RouteResult>,
    displayCurrency: DisplayCurrency,
    distanceUnit: DistanceUnit,
    driverFiatMethods: List<String>,
    onToggleCurrency: () -> Unit,
    priceService: BitcoinPriceService,
    onAcceptBroadcastRequest: (BroadcastRideOfferData) -> Unit,
    onDeclineBroadcastRequest: (BroadcastRideOfferData) -> Unit,
    onAcceptOffer: (RideOfferData) -> Unit,
    onDeclineOffer: (RideOfferData) -> Unit,
    onSetNoMatchWarning: (String) -> Unit,
    onToggleExpandedSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalRequests = freshBroadcastRequests.size + pendingOffers.size

    Column(modifier = modifier) {
    // Search area toggle and ride requests header
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Ride Requests ($totalRequests)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        FilterChip(
            selected = expandedSearch,
            onClick = onToggleExpandedSearch,
            label = {
                Text(if (expandedSearch) "Wide Area" else "Expand Search")
            },
            leadingIcon = {
                Icon(
                    imageVector = if (expandedSearch)
                        Icons.Default.ZoomOutMap
                    else
                        Icons.Default.ZoomIn,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (totalRequests == 0) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Waiting for ride requests...",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Requests from nearby riders will appear here",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Show broadcast requests first (sorted by fare, highest first)
            // Using freshBroadcastRequests which filters out stale requests.
            items(
                items = freshBroadcastRequests,
                key = { it.eventId }
            ) { request ->
                val pickupRoute = pickupRoutes[request.eventId]
                val onAccept = remember(request, onAcceptBroadcastRequest) {
                    { onAcceptBroadcastRequest(request) }
                }
                val onDecline = remember(request, onDeclineBroadcastRequest) {
                    { onDeclineBroadcastRequest(request) }
                }
                BroadcastRideRequestCard(
                    request = request,
                    pickupRoute = pickupRoute,
                    isProcessing = isProcessingOffer,
                    onAccept = onAccept,
                    onDecline = onDecline,
                    displayCurrency = displayCurrency,
                    distanceUnit = distanceUnit,
                    onToggleCurrency = onToggleCurrency,
                    priceService = priceService
                )
            }

            // Show direct offers (legacy/advanced) if any
            if (pendingOffers.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Direct Requests (${pendingOffers.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(
                    items = pendingOffers,
                    key = { it.eventId }
                ) { offer ->
                    val pickupRoute = directOfferPickupRoutes[offer.eventId]
                    val rideRoute = directOfferRideRoutes[offer.eventId]
                    val onAccept = remember(
                        offer, driverFiatMethods, onAcceptOffer, onSetNoMatchWarning
                    ) {
                        { handleOfferAccept(offer, driverFiatMethods, onAcceptOffer, onSetNoMatchWarning) }
                    }
                    val onDecline = remember(offer, onDeclineOffer) {
                        { onDeclineOffer(offer) }
                    }
                    RideOfferCard(
                        offer = offer,
                        pickupRoute = pickupRoute,
                        rideRoute = rideRoute,
                        isProcessing = isProcessingOffer,
                        onAccept = onAccept,
                        onDecline = onDecline,
                        displayCurrency = displayCurrency,
                        distanceUnit = distanceUnit,
                        onToggleCurrency = onToggleCurrency,
                        priceService = priceService,
                        driverFiatMethods = driverFiatMethods
                    )
                }
            }
        }
    }
    }
}
