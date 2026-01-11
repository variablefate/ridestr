package com.ridestr.common.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridestr.common.location.GeocodingResult
import com.ridestr.common.nostr.events.Location
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search field for location input with autocomplete suggestions.
 *
 * @param value Current text value
 * @param onValueChange Called when text changes
 * @param selectedLocation Currently selected location (null if none)
 * @param onLocationSelected Called when a suggestion is selected
 * @param searchResults List of geocoding results to show as suggestions
 * @param isSearching Whether a search is in progress
 * @param onSearch Called to trigger a search with the current query
 * @param placeholder Placeholder text when empty
 * @param label Optional label for the field
 * @param showMyLocation Whether to show "Use My Location" button
 * @param onUseMyLocation Called when "Use My Location" is clicked
 * @param enabled Whether the field is enabled
 * @param modifier Modifier for the root composable
 */
@Composable
fun LocationSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    selectedLocation: Location?,
    onLocationSelected: (GeocodingResult) -> Unit,
    searchResults: List<GeocodingResult>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    placeholder: String = "Search for a location...",
    label: String? = null,
    showMyLocation: Boolean = false,
    onUseMyLocation: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showDropdown by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // Debounce search queries
    LaunchedEffect(value) {
        if (value.length >= 3 && selectedLocation == null) {
            delay(300) // Debounce 300ms
            onSearch(value)
        }
    }

    // Show dropdown when we have results and field is focused
    showDropdown = isFocused && (searchResults.isNotEmpty() || isSearching) && selectedLocation == null

    Column(modifier = modifier) {
        // Label if provided
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Search field with icon
        OutlinedTextField(
            value = if (selectedLocation != null) selectedLocation.getDisplayString() else value,
            onValueChange = { newValue ->
                onValueChange(newValue)
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    isFocused = focusState.isFocused
                },
            enabled = enabled,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            leadingIcon = {
                if (selectedLocation != null) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Selected location",
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    if (selectedLocation != null || value.isNotEmpty()) {
                        IconButton(onClick = {
                            onValueChange("")
                        }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear"
                            )
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // Dropdown with suggestions
        AnimatedVisibility(visible = showDropdown) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    // "Use My Location" option
                    if (showMyLocation && onUseMyLocation != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onUseMyLocation()
                                        focusManager.clearFocus()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Place,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Use My Location",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (searchResults.isNotEmpty()) {
                                HorizontalDivider()
                            }
                        }
                    }

                    // Search results
                    items(searchResults) { result ->
                        SuggestionItem(
                            result = result,
                            onClick = {
                                onLocationSelected(result)
                                focusManager.clearFocus()
                            }
                        )
                    }

                    // Loading indicator if searching and no results yet
                    if (isSearching && searchResults.isEmpty()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Searching...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionItem(
    result: GeocodingResult,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Feature name or first line of address
            Text(
                text = result.featureName ?: result.addressLine.split(",").firstOrNull() ?: result.addressLine,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Full address as secondary text
            if (result.featureName != null || result.addressLine.contains(",")) {
                Text(
                    text = result.addressLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Manual coordinate input field for debug mode.
 *
 * @param lat Current latitude value
 * @param lon Current longitude value
 * @param onLatChange Called when latitude changes
 * @param onLonChange Called when longitude changes
 * @param label Optional label for the field
 * @param enabled Whether the field is enabled
 * @param modifier Modifier for the root composable
 */
@Composable
fun ManualCoordinateInput(
    lat: String,
    lon: String,
    onLatChange: (String) -> Unit,
    onLonChange: (String) -> Unit,
    label: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Label if provided
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = lat,
                onValueChange = onLatChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                label = { Text("Latitude") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = lon,
                onValueChange = onLonChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                label = { Text("Longitude") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
