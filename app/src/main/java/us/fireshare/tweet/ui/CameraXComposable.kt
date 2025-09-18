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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import kotlinx.coroutines.delay
import us.fireshare.tweet.utils.CameraXManager

/**
 * Format duration in seconds to MM:SS format
 */
private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%02d:%02d", minutes, remainingSeconds)
}

@Composable
fun CameraXPreview(
    onImageCaptured: (Uri) -> Unit,
    onVideoRecorded: (Uri) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    openedFromComposer: Boolean = true // Default to true since most camera usage is from composer
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val cameraManager = remember { CameraXManager(context, lifecycleOwner) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var isBackCamera by remember { mutableStateOf(true) }
    var isRecording by remember { mutableStateOf(false) }
    var captureMode by remember { mutableStateOf("photo") } // "photo" or "video"
    var recordingDuration by remember { mutableStateOf(0L) } // Recording duration in seconds

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
        
        // Force hide keyboard and system UI immediately when opening from composer
        val activity = context as androidx.activity.ComponentActivity
        val window = activity.window
        val windowInsetsController = WindowCompat.getInsetsController(window, view)
        
        // Aggressively hide keyboard using multiple methods
        windowInsetsController.hide(WindowInsetsCompat.Type.ime())
        
        // Hide keyboard using InputMethodManager with multiple approaches
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        inputMethodManager.hideSoftInputFromWindow(null, InputMethodManager.HIDE_NOT_ALWAYS)
        
        // If opened from composer, be extra aggressive with keyboard hiding
        if (openedFromComposer) {
            inputMethodManager.hideSoftInputFromWindow(null, InputMethodManager.HIDE_IMPLICIT_ONLY)
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
        }
        
        // Force clear focus to ensure keyboard doesn't reappear
        view.clearFocus()
        
        // Hide all system UI elements
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        
        // Set full screen flags
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        
        // Hide action bar and any app navigation
        activity.actionBar?.hide()
        
        // Additional delay to ensure keyboard is fully hidden
        delay(100)
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }
    
    // Recording timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isRecording) {
                delay(1000) // Update every second
                recordingDuration++
            }
        } else {
            recordingDuration = 0L // Reset timer when not recording
        }
    }
    
    // Handle full screen mode and cleanup
    DisposableEffect(Unit) {
        val activity = context as androidx.activity.ComponentActivity
        val window = activity.window
        val windowInsetsController = WindowCompat.getInsetsController(window, view)
        
        // Hide all system UI elements for true full screen
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        windowInsetsController.hide(WindowInsetsCompat.Type.ime()) // Hide keyboard
        
        // Aggressively hide keyboard using InputMethodManager with multiple approaches
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        inputMethodManager.hideSoftInputFromWindow(null, InputMethodManager.HIDE_NOT_ALWAYS)
        
        // If opened from composer, be extra aggressive with keyboard hiding
        if (openedFromComposer) {
            inputMethodManager.hideSoftInputFromWindow(null, InputMethodManager.HIDE_IMPLICIT_ONLY)
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
        }
        
        // Force clear focus to ensure keyboard doesn't reappear
        view.clearFocus()
        
        // Set behavior to prevent system bars from showing
        windowInsetsController.systemBarsBehavior = 
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // Additional window flags for full screen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        
        // Hide action bar if present
        activity.actionBar?.hide()
        
        onDispose {
            // Restore system bars when camera is dismissed
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
            
            // Clear full screen flags
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            
            // Show action bar if it was hidden
            activity.actionBar?.show()
            
            // Cleanup camera manager
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

        // Top controls row with camera switch and cancel buttons
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 32.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Switch camera button (moved to left) without background
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
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).alpha(0.8f)
                )
            }
            
            // Recording indicator with timer (when recording) - center
            if (isRecording) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    // Recording dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                MaterialTheme.colorScheme.error,
                                RoundedCornerShape(4.dp)
                            )
                    )
                    
                    // Timer text
                    Text(
                        text = formatDuration(recordingDuration),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
            
            // Cancel button (moved to right) without background
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).alpha(0.8f)
                )
            }
        }

        // Bottom controls - Mode toggle and capture button
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp, start = 24.dp, end = 24.dp, top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Photo mode button (smaller with further reduced opacity and background)
            IconButton(
                onClick = { captureMode = "photo" },
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        if (captureMode == "photo") 
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else 
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                        RoundedCornerShape(18.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Photo Mode",
                    tint = if (captureMode == "photo") 
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Capture button (gray with reduced opacity)
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
                containerColor = if (isRecording) 
                    MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                else 
                    androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.6f)
            ) {
                Icon(
                    imageVector = if (captureMode == "photo") Icons.Default.CameraAlt else if (isRecording) Icons.Default.Stop else Icons.Default.Videocam,
                    contentDescription = if (captureMode == "photo") "Take Photo" else if (isRecording) "Stop Recording" else "Start Recording",
                    modifier = Modifier.size(32.dp),
                    tint = if (isRecording) MaterialTheme.colorScheme.onError else androidx.compose.ui.graphics.Color.White
                )
            }

            // Video mode button (smaller with further reduced opacity and background)
            IconButton(
                onClick = { captureMode = "video" },
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        if (captureMode == "video") 
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else 
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                        RoundedCornerShape(18.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Video Mode",
                    tint = if (captureMode == "video") 
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
