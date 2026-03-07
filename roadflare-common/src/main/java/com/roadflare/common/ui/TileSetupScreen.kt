package com.roadflare.common.ui

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
import com.roadflare.common.routing.*
import kotlinx.coroutines.launch

/**
 * Onboarding screen for downloading routing tiles based on user location.
 * Shows recommended tiles for current location + all other available tiles.
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

    val isDiscovering by discoveryService?.isDiscovering?.collectAsState() ?: remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { discoveryService?.startDiscovery() }

    LaunchedEffect(Unit) {
        discoveryService?.discoveredRegions?.collect { regions ->
            tileManager.updateDiscoveredRegions(regions)
        }
    }

    val allRegions = remember(discoveredRegions) { tileManager.getKnownRegions() }

    val (recommendedRegions, otherRegions) = remember(allRegions, currentLocation, discoveredRegions) {
        if (currentLocation != null) {
            allRegions.partition { it.boundingBox.contains(currentLocation.latitude, currentLocation.longitude) }
        } else {
            emptyList<TileRegion>() to allRegions
        }
    }

    val hasUserDownloadedTile = remember(downloadedRegions, allRegions) {
        allRegions.any { !it.isBundled && downloadedRegions.contains(it.id) }
    }

    val hasActiveDownload = remember(downloadStatus) {
        downloadStatus.values.any { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.VERIFYING }
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Text("Download Routing Data", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Download map tiles for offline turn-by-turn navigation in your area.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (isDiscovering) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Discovering available tiles...", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recommendedRegions.isNotEmpty()) {
                item {
                    Text("Recommended for Your Area", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                items(recommendedRegions) { region ->
                    TileSetupCard(
                        region = region,
                        isDownloaded = downloadedRegions.contains(region.id),
                        downloadStatus = downloadStatus[region.id],
                        isRecommended = true,
                        onDownload = { scope.launch { downloadService.downloadRegion(region.id) } }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            if (otherRegions.isNotEmpty()) {
                item {
                    Text(
                        if (recommendedRegions.isEmpty()) "Available Regions" else "Other Regions",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                items(otherRegions) { region ->
                    TileSetupCard(
                        region = region,
                        isDownloaded = downloadedRegions.contains(region.id),
                        downloadStatus = downloadStatus[region.id],
                        isRecommended = false,
                        onDownload = { scope.launch { downloadService.downloadRegion(region.id) } }
                    )
                }
            }

            if (allRegions.isEmpty() && !isDiscovering) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Info, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("No tiles available yet", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            Text("Tiles will appear here once discovered from Nostr.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth(), enabled = !hasActiveDownload) {
            if (hasActiveDownload) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
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
    region: TileRegion, isDownloaded: Boolean, downloadStatus: TileDownloadStatus?,
    isRecommended: Boolean, onDownload: () -> Unit, modifier: Modifier = Modifier
) {
    val isDownloading = downloadStatus?.state == DownloadState.DOWNLOADING || downloadStatus?.state == DownloadState.VERIFYING
    val isHighlighted = isRecommended && !isDownloaded && !region.isBundled

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        region.name, style = MaterialTheme.typography.titleSmall,
                        color = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    if (region.isBundled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SuggestionChip(onClick = {}, label = { Text("Bundled", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(20.dp))
                    }
                }
                Text(
                    region.getFormattedSize(), style = MaterialTheme.typography.bodySmall,
                    color = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                region.isBundled || isDownloaded -> Icon(Icons.Default.CheckCircle, "Downloaded", tint = MaterialTheme.colorScheme.primary)
                isDownloading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(progress = { downloadStatus?.progress ?: 0f }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("${((downloadStatus?.progress ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
                else -> {
                    val canDownload = region.blossomUrls.isNotEmpty() || region.chunks.isNotEmpty()
                    IconButton(onClick = onDownload, enabled = canDownload) {
                        Icon(Icons.Default.Add, "Download", tint = if (canDownload) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        AnimatedVisibility(visible = isDownloading && downloadStatus != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                LinearProgressIndicator(progress = { downloadStatus?.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                Text(downloadStatus?.getStatusMessage() ?: "Downloading...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (downloadStatus?.state == DownloadState.FAILED) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.width(4.dp))
                Text(downloadStatus.error ?: "Download failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDownload) { Text("Retry") }
            }
        }
    }
}
