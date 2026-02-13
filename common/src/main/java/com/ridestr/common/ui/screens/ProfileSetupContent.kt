package com.ridestr.common.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridestr.common.ui.ProfilePictureEditor
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

/**
 * Shared content for profile setup screens.
 * Used by both rider and driver apps with different role-specific text.
 *
 * @param displayName Current display name value
 * @param picture Current profile picture URL
 * @param isSaving Whether the profile is currently being saved
 * @param error Optional error message to display
 * @param signer NostrSigner for Blossom upload authentication
 * @param onDisplayNameChange Callback when display name changes
 * @param onPictureChange Callback when picture URL changes
 * @param onSave Callback when save button is clicked
 */
@Composable
fun ProfileSetupContent(
    displayName: String,
    picture: String,
    isSaving: Boolean,
    error: String?,
    signer: NostrSigner?,
    onDisplayNameChange: (String) -> Unit,
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
            pictureUrl = picture,
            onPictureUrlChange = onPictureChange,
            signer = signer
        )

        Spacer(modifier = Modifier.height(24.dp))

        error?.let { errorText ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = errorText,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            label = { Text("Name") },
            placeholder = { Text("Satoshi Nakamoto") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onSave,
            enabled = !isSaving && displayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isSaving) "Saving..." else "Save Profile")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
