package com.roadflare.common.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadflare.common.routing.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileManagementScreen(
    tileManager: TileManager,
    downloadService: TileDownloadService,
    discoveryService: NostrTileDiscoveryService?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val downloadedRegions by tileManager.downloadedRegions.collectAsState()
    val downloadStatus by tileManager.downloadStatus.collectAsState()
    val discoveredRegions by tileManager.discoveredRegions.collectAsState()

    val knownRegions = remember(discoveredRegions) {
        tileManager.getKnownRegions()
    }

    val isDiscovering by discoveryService?.isDiscovering?.collectAsState() ?: remember { mutableStateOf(false) }
    val discoveryError by discoveryService?.discoveryError?.collectAsState() ?: remember { mutableStateOf<String?>(null) }

    var regionToDelete by remember { mutableStateOf<TileRegion?>(null) }

    LaunchedEffect(Unit) {
        discoveryService?.discoveredRegions?.collect { regions ->
            tileManager.updateDiscoveredRegions(regions)
        }
    }

    LaunchedEffect(Unit) {
        discoveryService?.startDiscovery()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Routing Tiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (discoveryService != null) {
                        IconButton(
                            onClick = { discoveryService.refreshDiscovery() },
                            enabled = !isDiscovering
                        ) {
                            if (isDiscovering) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        @OptIn(ExperimentalMaterial3Api::class)
        PullToRefreshBox(
            isRefreshing = isDiscovering,
            onRefresh = { discoveryService?.refreshDiscovery() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    StorageInfoCard(tileManager = tileManager, downloadedCount = downloadedRegions.size)
                }

                if (discoveryService != null && (isDiscovering || discoveryError != null)) {
                    item {
                        DiscoveryStatusCard(
                            isDiscovering = isDiscovering,
                            discoveredCount = discoveredRegions.size,
                            error = discoveryError
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Available Regions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (discoveredRegions.isNotEmpty()) {
                            Text("${discoveredRegions.size} from Nostr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                items(knownRegions) { region ->
                    TileRegionCard(
                        region = region,
                        isDownloaded = downloadedRegions.contains(region.id),
                        downloadStatus = downloadStatus[region.id],
                        onDownload = { scope.launch { downloadService.downloadRegion(region.id) } },
                        onDelete = { regionToDelete = region },
                        onCancel = { downloadService.cancelDownload(region.id) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Tiles enable offline turn-by-turn navigation. Download tiles for regions where you'll be driving. Pull down to refresh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    regionToDelete?.let { region ->
        AlertDialog(
            onDismissRequest = { regionToDelete = null },
            title = { Text("Delete Tile?") },
            text = { Text("Delete ${region.name}? You'll need to re-download it for offline routing in this area.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { tileManager.deleteTile(region.id) }
                    regionToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { regionToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StorageInfoCard(tileManager: TileManager, downloadedCount: Int, modifier: Modifier = Modifier) {
    val totalStorage = tileManager.getTotalStorageUsed()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Downloaded Tiles", style = MaterialTheme.typography.titleSmall)
                Text("$downloadedCount region${if (downloadedCount != 1) "s" else ""}", style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Storage Used", style = MaterialTheme.typography.titleSmall)
                Text(formatBytes(totalStorage), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DiscoveryStatusCard(isDiscovering: Boolean, discoveredCount: Int, error: String?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                error != null -> MaterialTheme.colorScheme.errorContainer
                isDiscovering -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                error != null -> {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text("Discovery error: $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                isDiscovering -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Discovering tiles from Nostr...", style = MaterialTheme.typography.bodySmall)
                }
                discoveredCount > 0 -> {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Found $discoveredCount tile${if (discoveredCount != 1) "s" else ""} available for download", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No additional tiles found on Nostr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TileRegionCard(
    region: TileRegion, isDownloaded: Boolean, downloadStatus: TileDownloadStatus?,
    onDownload: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier
) {
    val isDownloading = downloadStatus?.state == DownloadState.DOWNLOADING || downloadStatus?.state == DownloadState.VERIFYING

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(region.name, style = MaterialTheme.typography.titleMedium)
                        if (region.isBundled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            SuggestionChip(onClick = {}, label = { Text("Bundled") }, modifier = Modifier.height(24.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Size: ${region.getFormattedSize()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                when {
                    region.isBundled -> Icon(Icons.Default.CheckCircle, "Included", tint = MaterialTheme.colorScheme.primary)
                    isDownloading -> IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") }
                    isDownloaded -> IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    else -> IconButton(onClick = onDownload) { Icon(Icons.Default.Add, "Download") }
                }
            }

            AnimatedVisibility(visible = downloadStatus != null && !isDownloaded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    when (downloadStatus?.state) {
                        DownloadState.DOWNLOADING -> {
                            LinearProgressIndicator(progress = { downloadStatus.progress }, modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (downloadStatus.totalChunks > 1) "${downloadStatus.getStatusMessage()} - ${(downloadStatus.progress * 100).toInt()}%"
                                else "Downloading... ${(downloadStatus.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        DownloadState.VERIFYING -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Verifying...", style = MaterialTheme.typography.bodySmall)
                        }
                        DownloadState.FAILED -> Text("Download failed: ${downloadStatus.error ?: "Unknown error"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        DownloadState.PENDING -> Text("Waiting...", style = MaterialTheme.typography.bodySmall)
                        else -> {}
                    }
                }
            }

            if (isDownloaded && !region.isBundled) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Downloaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
    }
}
