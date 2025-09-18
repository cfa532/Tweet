package us.fireshare.tweet.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import us.fireshare.tweet.utils.CameraXManager

@Composable
fun CameraXPreview(
    onImageCaptured: (Uri) -> Unit,
    onVideoRecorded: (Uri) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraManager = remember { CameraXManager(context, lifecycleOwner) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var isBackCamera by remember { mutableStateOf(true) }
    var isRecording by remember { mutableStateOf(false) }
    var captureMode by remember { mutableStateOf("photo") } // "photo" or "video"

    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && captureMode == "video" && !isRecording) {
            // Start recording after permission is granted
            cameraManager.startVideoRecording(onVideoRecorded)
            isRecording = true
        }
    }
    
    LaunchedEffect(Unit) {
        cameraManager.initialize()
    }
    
    DisposableEffect(Unit) {
        onDispose {
            cameraManager.cleanup()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Camera Preview
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { pv ->
                previewView = pv
                cameraManager.startCamera(pv, onImageCaptured)
            }
        )

        // Camera indicator at top
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isBackCamera) "Back Camera" else "Front Camera",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            
            if (isRecording) {
                Text(
                    text = "REC",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Camera Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Switch camera button
                IconButton(
                    onClick = {
                        previewView?.let { pv ->
                            cameraManager.switchCamera(pv, onImageCaptured)
                            isBackCamera = !isBackCamera
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = if (isBackCamera) "Switch to Front Camera" else "Switch to Back Camera",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Close button
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }

            // Mode toggle buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Photo mode button
                IconButton(
                    onClick = { captureMode = "photo" },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Photo Mode",
                        tint = if (captureMode == "photo") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Capture button
                FloatingActionButton(
                    onClick = {
                        if (captureMode == "photo") {
                            cameraManager.takePicture(onImageCaptured)
                        } else {
                            if (isRecording) {
                                cameraManager.stopVideoRecording()
                                isRecording = false
                            } else {
                                // Check for audio permission before starting video recording
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    cameraManager.startVideoRecording(onVideoRecorded)
                                    isRecording = true
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(72.dp),
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = if (captureMode == "photo") Icons.Default.CameraAlt else if (isRecording) Icons.Default.Stop else Icons.Default.Videocam,
                        contentDescription = if (captureMode == "photo") "Take Photo" else if (isRecording) "Stop Recording" else "Start Recording",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }

                // Video mode button
                IconButton(
                    onClick = { captureMode = "video" },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video Mode",
                        tint = if (captureMode == "video") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
