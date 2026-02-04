package com.ridestr.rider.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.ridestr.common.ui.screens.ProfileSetupContent
import com.ridestr.rider.viewmodels.ProfileViewModel

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
            npub = uiState.npub,
            displayName = uiState.displayName,
            about = uiState.about,
            picture = uiState.picture,
            isSaving = uiState.isSaving,
            error = uiState.error,
            roleDescriptionText = "Tell drivers about yourself",
            aboutPlaceholderText = "Regular commuter, prefer quick rides...",
            signer = viewModel.getSigner(),
            onDisplayNameChange = viewModel::updateDisplayName,
            onAboutChange = viewModel::updateAbout,
            onPictureChange = viewModel::updatePicture,
            onSave = { viewModel.saveProfile(onComplete) },
            modifier = modifier.padding(padding)
        )
    }
}
