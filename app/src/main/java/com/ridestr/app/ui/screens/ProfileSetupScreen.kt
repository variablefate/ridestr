package com.ridestr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.app.viewmodels.ProfileUiState
import com.ridestr.app.viewmodels.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    viewModel: ProfileViewModel,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set Up Your Profile") },
                actions = {
                    TextButton(onClick = { viewModel.skip(onComplete) }) {
                        Text("Skip")
                    }
                }
            )
        }
    ) { padding ->
        ProfileSetupContent(
            uiState = uiState,
            onNameChange = viewModel::updateName,
            onDisplayNameChange = viewModel::updateDisplayName,
            onAboutChange = viewModel::updateAbout,
            onPictureChange = viewModel::updatePicture,
            onLightningAddressChange = viewModel::updateLightningAddress,
            onSave = { viewModel.saveProfile(onComplete) },
            modifier = modifier.padding(padding)
        )
    }
}

@Composable
private fun ProfileSetupContent(
    uiState: ProfileUiState,
    onNameChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onPictureChange: (String) -> Unit,
    onLightningAddressChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Profile icon
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Profile",
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Show npub
        uiState.npub?.let { npub ->
            Text(
                text = npub.take(20) + "..." + npub.takeLast(8),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Tell others a bit about yourself",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Error message
        uiState.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Name field
        OutlinedTextField(
            value = uiState.name,
            onValueChange = onNameChange,
            label = { Text("Username") },
            placeholder = { Text("satoshi") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Display name field
        OutlinedTextField(
            value = uiState.displayName,
            onValueChange = onDisplayNameChange,
            label = { Text("Display Name") },
            placeholder = { Text("Satoshi Nakamoto") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // About field
        OutlinedTextField(
            value = uiState.about,
            onValueChange = onAboutChange,
            label = { Text("About") },
            placeholder = { Text("A few words about yourself...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Picture URL field
        OutlinedTextField(
            value = uiState.picture,
            onValueChange = onPictureChange,
            label = { Text("Profile Picture URL") },
            placeholder = { Text("https://example.com/photo.jpg") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Lightning address field
        OutlinedTextField(
            value = uiState.lightningAddress,
            onValueChange = onLightningAddressChange,
            label = { Text("Lightning Address") },
            placeholder = { Text("you@walletofsatoshi.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your lightning address allows riders and drivers to send you Bitcoin tips",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Save button
        Button(
            onClick = onSave,
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (uiState.isSaving) "Saving..." else "Save Profile")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
