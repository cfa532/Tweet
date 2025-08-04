package us.fireshare.tweet.widget

import android.app.Activity
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.datamodel.MimeiId
import kotlin.math.abs

/**
 * Simple full-screen video player wrapper that uses an existing player without FullScreenVideoManager
 * This prevents conflicts when multiple videos are opened in full-screen
 */
@RequiresApi(Build.VERSION_CODES.R)
@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(
    existingPlayer: ExoPlayer,
    videoItem: MimeiFileType,
    onClose: () -> Unit,
    enableImmersiveMode: Boolean = true
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var isLandscape by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) } // Start with controls hidden
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Check if video is landscape and set rotation
    LaunchedEffect(Unit) {
        videoItem.aspectRatio?.let { 
            isLandscape = it > 1
        }
    }
    
    // Add player listener to handle state changes
    DisposableEffect(existingPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_IDLE -> {
                        // If player becomes idle, prepare it again
                        existingPlayer.prepare()
                    }
                    androidx.media3.common.Player.STATE_READY -> {
                        // When ready, start playback
                        existingPlayer.playWhenReady = true
                    }
                    androidx.media3.common.Player.STATE_ENDED -> {
                        // If video ends, restart it
                        existingPlayer.seekTo(0)
                        existingPlayer.playWhenReady = true
                    }
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // If there's an error, try to prepare again
                existingPlayer.prepare()
            }
        }
        
        existingPlayer.addListener(listener)
        
        onDispose {
            existingPlayer.removeListener(listener)
        }
    }

    // Immersive full screen
    DisposableEffect(Unit) {
        // Hide system bars on enter
        if (enableImmersiveMode) {
            activity?.let { act ->
                val windowInsetsController = act.window.insetsController
                windowInsetsController?.let { controller ->
                    // Hide system bars
                    controller.hide(android.view.WindowInsets.Type.systemBars())
                    // Set immersive sticky behavior
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }

        onDispose {
            // Show system bars on exit
            if (enableImmersiveMode) {
                activity?.let { act ->
                    val windowInsetsController = act.window.insetsController
                    windowInsetsController?.show(android.view.WindowInsets.Type.systemBars())
                    

                }
            }
        }
    }

    // Start playback and unmute when entering full screen
    LaunchedEffect(Unit) {
        // More aggressive player state handling
        when (existingPlayer.playbackState) {
            androidx.media3.common.Player.STATE_IDLE -> {
                // If player is idle, prepare it
                existingPlayer.prepare()
            }
            androidx.media3.common.Player.STATE_ENDED -> {
                // If player has ended, seek to beginning and prepare
                existingPlayer.seekTo(0)
                existingPlayer.prepare()
            }
            else -> {
                // For other states, just ensure it's ready to play
                if (!existingPlayer.playWhenReady) {
                    existingPlayer.playWhenReady = true
                }
            }
        }
        
        // Set volume and start playback
        existingPlayer.volume = 1.0f // Unmute video
        existingPlayer.playWhenReady = true
        
        // For landscape videos, try to set video rotation
        if (isLandscape) {
            // Try to set video rotation to 90 degrees
            existingPlayer.videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
        
        // Add a retry mechanism - if player is still not ready after 1 second, try again
        kotlinx.coroutines.delay(1000)
        if (existingPlayer.playbackState == androidx.media3.common.Player.STATE_IDLE) {
            existingPlayer.prepare()
            existingPlayer.playWhenReady = true
        }
    }

    // Full screen video player UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { 
                        isDragging = true
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        isDragging = false
                        // Check if it's a downward swipe (positive Y value means downward)
                        if (dragOffset > 30f) { // Very low threshold - 30dp
                            onClose()
                        }
                        dragOffset = 0f
                    },
                    onDrag = { _, dragAmount ->
                        dragOffset += dragAmount.y
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls }
                )
            }
    ) {
        // Video player view with native controls
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = existingPlayer
                    useController = true // Re-enable native controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { playerView ->
                // Control the visibility of the native controller
                if (showControls) {
                    playerView.showController()
                } else {
                    playerView.hideController()
                }
            }
        )

        // Auto-hide controls after 3 seconds
        LaunchedEffect(showControls) {
            if (showControls) {
                kotlinx.coroutines.delay(2000)
                showControls = false
            }
        }

        // Close button overlay (since native controls don't have a close button)
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(
    videoUrl: String,
    onClose: () -> Unit,
    autoPlay: Boolean = true, // Auto-start playback when entering full screen
    enableImmersiveMode: Boolean = true,
    autoReplay: Boolean = true, // Auto-replay when video ends
    onHorizontalSwipe: ((direction: Int) -> Unit)? = null // -1 for left, 1 for right
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showControls by remember { mutableStateOf(false) } // Start with controls hidden
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Get the dedicated full screen player
    val exoPlayer = remember {
        FullScreenVideoManager.getFullScreenPlayer(context)
    }

    // Load video into the full screen player
    LaunchedEffect(videoUrl) {
        FullScreenVideoManager.loadVideo(context, videoUrl)
    }

    // Start playback when entering full screen
    LaunchedEffect(Unit) {
        // Set volume to 1f (unmuted) for full screen
        FullScreenVideoManager.setVolume(1f)

        // Start playback with auto-replay
        FullScreenVideoManager.startPlayback(autoReplay)
        
        // Auto-play the video
        exoPlayer.playWhenReady = true
    }

    // Immersive full screen and audio control
    DisposableEffect(Unit) {
        // Hide system bars on enter
        if (enableImmersiveMode) {
            activity?.let { act ->
                val decorView = act.window.decorView
                val flags = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                decorView.systemUiVisibility = flags
            }
        }

        onDispose {
            // Show system bars on exit
            if (enableImmersiveMode) {
                activity?.let { act ->
                    val decorView = act.window.decorView
                    decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
    }

    // Handle auto-replay
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && autoReplay) {
                    exoPlayer.seekTo(0)
                    exoPlayer.play()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Full screen video player UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        if (abs(dragOffset) > 100f) {
                            onHorizontalSwipe?.invoke(if (dragOffset > 0) 1 else -1)
                        }
                        dragOffset = 0f
                    },
                    onDrag = { _, dragAmount ->
                        dragOffset += dragAmount.x
                    }
                )
            }
    ) {
        // Video player view
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false // We'll implement custom controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable { showControls = !showControls } // Toggle controls on tap
        )

        // Custom controls overlay
        if (showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showControls = !showControls }
            ) {
                // Close button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .size(48.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Play/Pause button
                IconButton(
                    onClick = {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.playWhenReady = false
                        } else {
                            exoPlayer.playWhenReady = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (exoPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (exoPlayer.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Auto-hide controls after 3 seconds
        LaunchedEffect(showControls) {
            if (showControls) {
                delay(3000)
                showControls = false
            }
        }
    }
}

/**
 * FullScreenVideoPlayer that uses an existing player instance
 * This allows seamless transition from preview to full-screen without losing position
 */
@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(
    existingPlayer: ExoPlayer,
    videoMid: MimeiId,
    onClose: () -> Unit,
    enableImmersiveMode: Boolean = true,
    onHorizontalSwipe: ((direction: Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showControls by remember { mutableStateOf(false) } // Start with controls hidden
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Use the existing player for full-screen mode
    LaunchedEffect(Unit) {
        FullScreenVideoManager.useExistingPlayer(existingPlayer, videoMid)
        // Set volume to 1f (unmuted) for full screen
        FullScreenVideoManager.setVolume(1f)
        
        // Auto-play the video
        existingPlayer.playWhenReady = true
    }

    // Immersive full screen and audio control
    DisposableEffect(Unit) {
        // Hide system bars on enter
        if (enableImmersiveMode) {
            activity?.let { act ->
                val decorView = act.window.decorView
                val flags = (android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                decorView.systemUiVisibility = flags
            }
        }

        onDispose {
            // Show system bars on exit
            if (enableImmersiveMode) {
                activity?.let { act ->
                    val decorView = act.window.decorView
                    decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
            // Return player to preview mode
            VideoManager.returnFromFullScreen(videoMid)
        }
    }

    // Full screen video player UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        if (abs(dragOffset) > 100f) {
                            onHorizontalSwipe?.invoke(if (dragOffset > 0) 1 else -1)
                        }
                        dragOffset = 0f
                    },
                    onDrag = { _, dragAmount ->
                        dragOffset += dragAmount.x
                    }
                )
            }
    ) {
        // Video player view with native controls
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = existingPlayer
                    useController = true // Use native ExoPlayer controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Close button overlay (since native controls don't have a close button)
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Format time in milliseconds to MM:SS format
 */
private fun formatTime(timeMs: Long): String {
    if (timeMs <= 0) return "0:00"
    
    val totalSeconds = timeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    
    return if (minutes > 0) {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    } else {
        "0:${seconds.toString().padStart(2, '0')}"
    }
}
