package com.ridestr.common.ui

import android.location.Location
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.common.routing.*
import kotlinx.coroutines.launch

/**
 * Onboarding screen for downloading routing tiles based on user location.
 *
 * Shows:
 * - Recommended tiles for the user's current location
 * - Other available tiles they can download
 * - Download progress and status
 *
 * @param tileManager TileManager instance
 * @param downloadService TileDownloadService instance
 * @param discoveryService NostrTileDiscoveryService for discovering available tiles
 * @param currentLocation User's current location (for recommendations)
 * @param onComplete Called when user finishes setup (Continue button)
 * @param onSkip Called if user wants to skip tile setup
 */
@Composable
fun TileSetupScreen(
    tileManager: TileManager,
    downloadService: TileDownloadService,
    discoveryService: NostrTileDiscoveryService?,
    currentLocation: Location?,
    onComplete: () -> Unit,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val downloadedRegions by tileManager.downloadedRegions.collectAsState()
    val downloadStatus by tileManager.downloadStatus.collectAsState()
    val discoveredRegions by tileManager.discoveredRegions.collectAsState()

    // Discovery state
    val isDiscovering by discoveryService?.isDiscovering?.collectAsState() ?: remember { mutableStateOf(false) }

    // Start discovery when screen opens
    LaunchedEffect(Unit) {
        discoveryService?.startDiscovery()
    }

    // Update TileManager when discovered regions change
    LaunchedEffect(Unit) {
        discoveryService?.discoveredRegions?.collect { regions ->
            tileManager.updateDiscoveredRegions(regions)
        }
    }

    // Get all known regions (reactive to discoveredRegions changes)
    val allRegions = remember(discoveredRegions) {
        tileManager.getKnownRegions()
    }

    // Split into recommended (for current location) and other
    val (recommendedRegions, otherRegions) = remember(allRegions, currentLocation, discoveredRegions) {
        if (currentLocation != null) {
            val lat = currentLocation.latitude
            val lon = currentLocation.longitude
            android.util.Log.d("TileSetupScreen", "User location: lat=$lat, lon=$lon")
            allRegions.forEach { region ->
                val bbox = region.boundingBox
                val contains = bbox.contains(lat, lon)
                android.util.Log.d("TileSetupScreen", "Region ${region.id}: bbox=(${bbox.west},${bbox.south},${bbox.east},${bbox.north}) contains=$contains")
            }
            allRegions.partition { it.boundingBox.contains(lat, lon) }
        } else {
            android.util.Log.d("TileSetupScreen", "No current location, showing all regions as 'other'")
            emptyList<TileRegion>() to allRegions
        }
    }

    // Check if user has downloaded at least one tile (non-bundled)
    val hasUserDownloadedTile = remember(downloadedRegions, allRegions) {
        allRegions.any { region ->
            !region.isBundled && downloadedRegions.contains(region.id)
        }
    }

    // Check if any downloads are in progress
    val hasActiveDownload = remember(downloadStatus) {
        downloadStatus.values.any { status ->
            status.state == DownloadState.DOWNLOADING || status.state == DownloadState.VERIFYING
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Header
        Text(
            text = "Download Routing Data",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Download map tiles for offline turn-by-turn navigation in your area.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Discovery status
        if (isDiscovering) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Discovering available tiles...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Tile list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Recommended section
            if (recommendedRegions.isNotEmpty()) {
                item {
                    Text(
                        text = "Recommended for Your Area",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                items(recommendedRegions) { region ->
                    TileSetupCard(
                        region = region,
                        isDownloaded = downloadedRegions.contains(region.id),
                        downloadStatus = downloadStatus[region.id],
                        isRecommended = true,
                        onDownload = {
                            scope.launch {
                                downloadService.downloadRegion(region.id)
                            }
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Other regions section
            if (otherRegions.isNotEmpty()) {
                item {
                    Text(
                        text = if (recommendedRegions.isEmpty()) "Available Regions" else "Other Regions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                items(otherRegions) { region ->
                    TileSetupCard(
                        region = region,
                        isDownloaded = downloadedRegions.contains(region.id),
                        downloadStatus = downloadStatus[region.id],
                        isRecommended = false,
                        onDownload = {
                            scope.launch {
                                downloadService.downloadRegion(region.id)
                            }
                        }
                    )
                }
            }

            // Empty state
            if (allRegions.isEmpty() && !isDiscovering) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No tiles available yet",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Bundled tiles for your region are already installed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Continue button
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth(),
            enabled = !hasActiveDownload
        ) {
            if (hasActiveDownload) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Downloading...")
            } else {
                Text(if (hasUserDownloadedTile) "Continue" else "Skip for now")
            }
        }
    }
}

@Composable
private fun TileSetupCard(
    region: TileRegion,
    isDownloaded: Boolean,
    downloadStatus: TileDownloadStatus?,
    isRecommended: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDownloading = downloadStatus?.state == DownloadState.DOWNLOADING ||
            downloadStatus?.state == DownloadState.VERIFYING

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRecommended && !isDownloaded && !region.isBundled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = region.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (region.isBundled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Bundled", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
                Text(
                    text = region.getFormattedSize(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Status/action
            when {
                region.isBundled || isDownloaded -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                isDownloading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            progress = { downloadStatus?.progress ?: 0f },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "${((downloadStatus?.progress ?: 0f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                else -> {
                    // Check if we can download (has URL or chunks)
                    val canDownload = region.blossomUrls.isNotEmpty() || region.chunks.isNotEmpty()
                    IconButton(
                        onClick = onDownload,
                        enabled = canDownload
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Download",
                            tint = if (canDownload) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Download progress details
        AnimatedVisibility(visible = isDownloading && downloadStatus != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp)
            ) {
                LinearProgressIndicator(
                    progress = { downloadStatus?.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = downloadStatus?.getStatusMessage() ?: "Downloading...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Error state
        if (downloadStatus?.state == DownloadState.FAILED) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = downloadStatus.error ?: "Download failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDownload) {
                    Text("Retry")
                }
            }
        }
    }
}
