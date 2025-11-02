package us.fireshare.tweet.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import us.fireshare.tweet.R
import us.fireshare.tweet.ui.CameraXPreview

/**
 * Handles camera functionality with permission management
 */
@Composable
fun CameraHandler(
    showCamera: Boolean,
    onImageCaptured: (Uri) -> Unit,
    onVideoRecorded: (Uri) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, camera will be shown by parent state
        } else {
            Toast.makeText(context, context.getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
            onDismiss() // Dismiss if permission denied
        }
    }

    // Handle image capture
    val onImageCapturedInternal = { uri: Uri ->
        onImageCaptured(uri)
        onDismiss()
    }

    // Handle video recording
    val onVideoRecordedInternal = { uri: Uri ->
        onVideoRecorded(uri)
        onDismiss()
    }

    // Handle camera dismiss
    val onDismissInternal = {
        onDismiss()
    }

    // Show camera if requested and permission is granted
    if (showCamera) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            CameraXPreview(
                onImageCaptured = onImageCapturedInternal,
                onVideoRecorded = onVideoRecordedInternal,
                onDismiss = onDismissInternal,
                modifier = modifier
            )
        } else {
            // Request permission
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}

/**
 * Camera button click handler
 */
@Composable
fun rememberCameraClickHandler(
    onCameraClick: () -> Unit
): () -> Unit {
    val context = LocalContext.current
    
    return {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onCameraClick()
        } else {
            // Permission will be handled by CameraHandler
            onCameraClick()
        }
    }
}
