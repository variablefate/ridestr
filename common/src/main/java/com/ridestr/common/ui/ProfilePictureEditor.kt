package com.ridestr.common.ui

import android.content.Context
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
import com.ridestr.common.routing.BlobDescriptor
import com.ridestr.common.routing.BlossomTileService
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ProfilePictureEditor"
private const val PREFS_NAME = "profile_picture_prefs"
private const val KEY_PICTURE_SHA256 = "picture_sha256"

/**
 * Profile picture editor with upload/remove functionality via Blossom.
 *
 * @param pictureUrl Current profile picture URL (or empty string)
 * @param onPictureUrlChange Callback when picture URL changes
 * @param signer NostrSigner for Blossom authentication
 * @param modifier Modifier for the component
 */
@Composable
fun ProfilePictureEditor(
    pictureUrl: String,
    onPictureUrlChange: (String) -> Unit,
    signer: NostrSigner?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var isUploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            if (signer == null) {
                uploadError = "Cannot upload: No signing key available"
                return@let
            }

            scope.launch {
                isUploading = true
                uploadError = null

                try {
                    val result = uploadImage(context, selectedUri, signer)
                    if (result != null) {
                        // Store SHA256 for potential future deletion
                        prefs.edit().putString(KEY_PICTURE_SHA256, result.sha256).apply()
                        onPictureUrlChange(result.url)
                        Log.d(TAG, "Image uploaded successfully: ${result.url}")
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
                enabled = !isUploading && signer != null,
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
                    scope.launch {
                        // Attempt to delete from Blossom if we have SHA256
                        val sha256 = prefs.getString(KEY_PICTURE_SHA256, null)
                        if (sha256 != null && signer != null) {
                            try {
                                // TODO: Implement Blossom delete API call
                                // For now just clear locally
                                Log.d(TAG, "Would delete blob with SHA256: $sha256")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to delete from Blossom", e)
                            }
                        }

                        // Clear stored SHA256 and URL
                        prefs.edit().remove(KEY_PICTURE_SHA256).apply()
                        onPictureUrlChange("")
                    }
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
            text = if (signer == null) "Sign in to upload a picture" else "Tap + to upload a profile picture",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Upload an image to Blossom from a content Uri.
 */
private suspend fun uploadImage(
    context: Context,
    uri: Uri,
    signer: NostrSigner
): BlobDescriptor? = withContext(Dispatchers.IO) {
    try {
        // Copy image to temporary file
        val tempFile = File(context.cacheDir, "profile_upload_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Log.d(TAG, "Temp file created: ${tempFile.length()} bytes")

        // Upload to first available Blossom server
        val blossomService = BlossomTileService()
        val servers = BlossomTileService.DEFAULT_BLOSSOM_SERVERS
        for (server in servers) {
            try {
                val result = blossomService.upload(tempFile, server, signer)
                if (result != null) {
                    tempFile.delete()
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Upload to $server failed: ${e.message}")
            }
        }

        tempFile.delete()
        null
    } catch (e: Exception) {
        Log.e(TAG, "Error preparing image for upload", e)
        null
    }
}
