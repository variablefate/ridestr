package com.drivestr.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.drivestr.app.viewmodels.ProfileViewModel
import com.ridestr.common.ui.screens.ProfileSetupContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    viewModel: ProfileViewModel,
    onComplete: () -> Unit,
    isEditMode: Boolean = false,
    canSkip: Boolean = true,
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
                    if (!isEditMode && canSkip) {
                        TextButton(onClick = { viewModel.skip(onComplete) }) {
                            Text("Skip")
                        }
                    }
                }
            )
        }
    ) { padding ->
        ProfileSetupContent(
            displayName = uiState.displayName,
            picture = uiState.picture,
            isSaving = uiState.isSaving,
            error = uiState.error,
            signer = viewModel.getSigner(),
            onDisplayNameChange = viewModel::updateDisplayName,
            onPictureChange = viewModel::updatePicture,
            onSave = { viewModel.saveProfile(onComplete) },
            modifier = modifier.padding(padding)
        )
    }
}
