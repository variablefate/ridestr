package com.drivestr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.drivestr.app.viewmodels.DriverStage
import com.ridestr.common.data.Vehicle

/**
 * Screen for managing driver's vehicles.
 * Supports multiple vehicles with one being the primary.
 *
 * Display mode depends on alwaysAskVehicle setting:
 * - When ON (ask mode): Shows "Last used" for activeVehicle, hides "Set Primary" button
 * - When OFF (auto-select mode): Shows "Currently in use" for primary, shows "Set Primary" button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclesScreen(
    vehicles: List<Vehicle>,
    alwaysAskVehicle: Boolean,
    activeVehicleId: String?,
    driverStage: DriverStage? = null,
    onAddVehicle: (Vehicle) -> Unit,
    onUpdateVehicle: (Vehicle) -> Unit,
    onDeleteVehicle: (String) -> Unit,
    onSetPrimary: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Block vehicle changes during an active ride
    val isRideActive = driverStage in listOf(
        DriverStage.RIDE_ACCEPTED,
        DriverStage.EN_ROUTE_TO_PICKUP,
        DriverStage.ARRIVED_AT_PICKUP,
        DriverStage.IN_RIDE,
        DriverStage.RIDE_COMPLETED
    )
    var showAddDialog by remember { mutableStateOf(false) }
    var editingVehicle by remember { mutableStateOf<Vehicle?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (vehicles.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No vehicles added",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add your vehicle to start driving",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Vehicle")
                }
            }
        } else {
            // Vehicle list
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(vehicles, key = { it.id }) { vehicle ->
                    VehicleCard(
                        vehicle = vehicle,
                        alwaysAskVehicle = alwaysAskVehicle,
                        activeVehicleId = activeVehicleId,
                        isRideActive = isRideActive,
                        onEdit = { editingVehicle = vehicle },
                        onDelete = { onDeleteVehicle(vehicle.id) },
                        onSetPrimary = { onSetPrimary(vehicle.id) }
                    )
                }

                // Add more button at bottom
                item {
                    OutlinedCard(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Add Another Vehicle",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // FAB for adding when list is not empty
        if (vehicles.isNotEmpty()) {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Vehicle")
            }
        }
    }

    // Add Vehicle Dialog
    if (showAddDialog) {
        VehicleDialog(
            title = "Add Vehicle",
            initialVehicle = null,
            onDismiss = { showAddDialog = false },
            onSave = { vehicle ->
                onAddVehicle(vehicle)
                showAddDialog = false
            }
        )
    }

    // Edit Vehicle Dialog
    editingVehicle?.let { vehicle ->
        VehicleDialog(
            title = "Edit Vehicle",
            initialVehicle = vehicle,
            onDismiss = { editingVehicle = null },
            onSave = { updated ->
                onUpdateVehicle(updated)
                editingVehicle = null
            }
        )
    }
}

@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    alwaysAskVehicle: Boolean,
    activeVehicleId: String?,
    isRideActive: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetPrimary: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Determine badge text and highlight based on mode
    val isActiveInAskMode = alwaysAskVehicle && vehicle.id == activeVehicleId
    val isHighlighted = if (alwaysAskVehicle) isActiveInAskMode else vehicle.isPrimary
    val badgeText = when {
        alwaysAskVehicle && isActiveInAskMode -> "Last used"
        !alwaysAskVehicle && vehicle.isPrimary -> "Currently in use"
        else -> null
    }
    // Only show "Set Primary" button in auto-select mode AND when no ride is active
    val showSetPrimaryButton = !alwaysAskVehicle && !vehicle.isPrimary && !isRideActive

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top row: Vehicle icon and info
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = if (isHighlighted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = vehicle.displayName(),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (vehicle.licensePlate.isNotBlank()) {
                        Text(
                            text = vehicle.licensePlate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom row: Badge on left, action buttons on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Badge on left (or empty space if no badge)
                if (badgeText != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // Action buttons on right
                Row {
                    if (showSetPrimaryButton) {
                        TextButton(onClick = onSetPrimary) {
                            Text("Set Primary")
                        }
                    }
                    TextButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit")
                    }
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Vehicle?") },
            text = { Text("Are you sure you want to remove ${vehicle.shortName()}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun VehicleDialog(
    title: String,
    initialVehicle: Vehicle?,
    onDismiss: () -> Unit,
    onSave: (Vehicle) -> Unit
) {
    var make by remember { mutableStateOf(initialVehicle?.make ?: "") }
    var model by remember { mutableStateOf(initialVehicle?.model ?: "") }
    var year by remember { mutableStateOf(initialVehicle?.year?.toString() ?: "") }
    var color by remember { mutableStateOf(initialVehicle?.color ?: "") }
    var licensePlate by remember { mutableStateOf(initialVehicle?.licensePlate ?: "") }

    val isValid = make.isNotBlank() && model.isNotBlank() &&
            year.toIntOrNull()?.let { it in 1900..2030 } == true &&
            color.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = make,
                    onValueChange = { make = it },
                    label = { Text("Make") },
                    placeholder = { Text("e.g., Toyota") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    placeholder = { Text("e.g., Camry") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = year,
                        onValueChange = { if (it.length <= 4) year = it.filter { c -> c.isDigit() } },
                        label = { Text("Year") },
                        placeholder = { Text("2024") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = color,
                        onValueChange = { color = it },
                        label = { Text("Color") },
                        placeholder = { Text("White") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = licensePlate,
                    onValueChange = { licensePlate = it.uppercase() },
                    label = { Text("License Plate (Optional)") },
                    placeholder = { Text("ABC 1234") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val vehicle = if (initialVehicle != null) {
                        initialVehicle.copy(
                            make = make.trim(),
                            model = model.trim(),
                            year = year.toInt(),
                            color = color.trim(),
                            licensePlate = licensePlate.trim()
                        )
                    } else {
                        Vehicle(
                            make = make.trim(),
                            model = model.trim(),
                            year = year.toInt(),
                            color = color.trim(),
                            licensePlate = licensePlate.trim()
                        )
                    }
                    onSave(vehicle)
                },
                enabled = isValid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
