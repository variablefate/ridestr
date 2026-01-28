package com.drivestr.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivestr.app.viewmodels.ProfileUiState
import com.drivestr.app.viewmodels.ProfileViewModel
import com.ridestr.common.ui.ProfilePictureEditor
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    viewModel: ProfileViewModel,
    onComplete: () -> Unit,
    isEditMode: Boolean = false,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Enable back handler for edit mode
    if (isEditMode && onBack != null) {
        BackHandler(onBack = onBack)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "Edit Profile" else "Set Up Your Profile") },
                navigationIcon = if (isEditMode && onBack != null) {
                    {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                } else {
                    {}
                },
                actions = {
                    if (!isEditMode) {
                        TextButton(onClick = { viewModel.skip(onComplete) }) {
                            Text("Skip")
                        }
                    }
                }
            )
        }
    ) { padding ->
        ProfileSetupContent(
            uiState = uiState,
            signer = viewModel.getSigner(),
            onDisplayNameChange = viewModel::updateDisplayName,
            onAboutChange = viewModel::updateAbout,
            onPictureChange = viewModel::updatePicture,
            onSave = { viewModel.saveProfile(onComplete) },
            modifier = modifier.padding(padding)
        )
    }
}

@Composable
private fun ProfileSetupContent(
    uiState: ProfileUiState,
    signer: NostrSigner?,
    onDisplayNameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onPictureChange: (String) -> Unit,
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
        // Profile picture editor with Blossom upload
        ProfilePictureEditor(
            pictureUrl = uiState.picture,
            onPictureUrlChange = onPictureChange,
            signer = signer
        )

        Spacer(modifier = Modifier.height(8.dp))

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
            text = "Tell riders about yourself",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

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

        OutlinedTextField(
            value = uiState.displayName,
            onValueChange = onDisplayNameChange,
            label = { Text("Name") },
            placeholder = { Text("Satoshi Nakamoto") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.about,
            onValueChange = onAboutChange,
            label = { Text("About") },
            placeholder = { Text("Professional driver, 5+ years experience...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )

        Spacer(modifier = Modifier.height(32.dp))

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
