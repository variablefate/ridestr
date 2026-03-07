package com.roadflare.common.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

private const val TAG = "ProfilePictureEditor"

/**
 * Profile picture editor with upload/remove functionality.
 *
 * Upload and remove operations are delegated to the caller via callbacks,
 * keeping this composable free of Blossom or signer dependencies.
 *
 * @param pictureUrl Current profile picture URL (or empty string)
 * @param onPictureUrlChange Callback when picture URL changes
 * @param onUploadImage Suspend callback that uploads the selected [Uri] and returns the URL, or null on failure
 * @param canUpload Whether uploading is available (e.g. signer present)
 * @param modifier Modifier for the component
 */
@Composable
fun ProfilePictureEditor(
    pictureUrl: String,
    onPictureUrlChange: (String) -> Unit,
    onUploadImage: (suspend (Uri) -> String?)? = null,
    canUpload: Boolean = onUploadImage != null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isUploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            if (onUploadImage == null) {
                uploadError = "Cannot upload: No upload handler available"
                return@let
            }

            scope.launch {
                isUploading = true
                uploadError = null

                try {
                    val resultUrl = onUploadImage(selectedUri)
                    if (resultUrl != null) {
                        onPictureUrlChange(resultUrl)
                        Log.d(TAG, "Image uploaded successfully: $resultUrl")
                    } else {
                        uploadError = "Failed to upload image"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Upload error", e)
                    uploadError = "Upload failed: ${e.message}"
                } finally {
                    isUploading = false
                }
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Picture preview with action buttons
        Box(contentAlignment = Alignment.Center) {
            // Profile picture or placeholder
            if (pictureUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(pictureUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Profile picture",
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "No picture",
                        modifier = Modifier.size(60.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Upload progress indicator
            if (isUploading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(100.dp),
                    strokeWidth = 4.dp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action buttons row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Upload button (+)
            FilledIconButton(
                onClick = { imagePickerLauncher.launch("image/*") },
                enabled = !isUploading && canUpload,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Upload picture"
                )
            }

            // Remove button (-)
            FilledIconButton(
                onClick = {
                    onPictureUrlChange("")
                },
                enabled = !isUploading && pictureUrl.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove picture"
                )
            }
        }

        // Error message
        uploadError?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Helper text
        Text(
            text = if (!canUpload) "Sign in to upload a picture" else "Tap + to upload a profile picture",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

