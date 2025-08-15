package us.fireshare.tweet.widget

import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.compose.ui.graphics.graphicsLayer
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
    var showControls by remember { mutableStateOf(false) } // Start with controls hidden
    var dragOffset by remember { mutableFloatStateOf(0f) }
    
    // Add player listener to handle state changes
    DisposableEffect(existingPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        // If player becomes idle, prepare it again
                        existingPlayer.prepare()
                    }
                    Player.STATE_READY -> {
                        // When ready, start playback
                        existingPlayer.playWhenReady = true
                    }
                    Player.STATE_ENDED -> {
                        // If video ends, restart it
                        existingPlayer.seekTo(0)
                        existingPlayer.playWhenReady = true
                    }

                    Player.STATE_BUFFERING -> {
                        TODO()
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

    // Immersive full screen and orientation handling
    DisposableEffect(Unit) {
        // Hide system bars on enter
        if (enableImmersiveMode) {
            activity?.let { act ->
                // Prevent activity recreation on configuration changes (like rotation)
                act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                
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
            // Show system bars on exit and restore normal orientation
            if (enableImmersiveMode) {
                activity?.let { act ->
                    // Restore normal orientation handling
                    act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    
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
            Player.STATE_IDLE -> {
                // If player is idle, prepare it
                existingPlayer.prepare()
            }
            Player.STATE_ENDED -> {
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
        
        // Add a retry mechanism - if player is still not ready after 1 second, try again
        delay(1000)
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
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        // Check if it's a downward swipe (positive Y value means downward)
                        if (dragOffset > 100f) { // Increased threshold - 100dp
                            onClose()
                        }
                        dragOffset = 0f
                    },
                    onDrag = { _, dragAmount ->
                        // Only allow downward dragging (positive Y values)
                        if (dragAmount.y > 0) {
                            dragOffset += dragAmount.y
                        }
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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragOffset
                    // Add some scaling effect as the video is dragged down
                    scaleX = 1f - (dragOffset / 1000f).coerceAtMost(0.1f)
                    scaleY = 1f - (dragOffset / 1000f).coerceAtMost(0.1f)
                    // Add alpha effect for fade out
                    alpha = 1f - (dragOffset / 500f).coerceAtMost(0.3f)
                },
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
    enableImmersiveMode: Boolean = true,
    autoReplay: Boolean = true, // Auto-replay when video ends
    onHorizontalSwipe: ((direction: Int) -> Unit)? = null // -1 for left, 1 for right
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showControls by remember { mutableStateOf(false) } // Start with controls hidden
    var dragOffset by remember { mutableFloatStateOf(0f) }

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

    // Immersive full screen and orientation handling
    DisposableEffect(Unit) {
        // Hide system bars on enter
        if (enableImmersiveMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.let { act ->
                // Prevent activity recreation on configuration changes (like rotation)
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                
                val windowInsetsController = act.window.insetsController
                windowInsetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }

        onDispose {
            // Show system bars on exit and restore normal orientation
            if (enableImmersiveMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity?.let { act ->
                    // Restore normal orientation handling
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    
                    val windowInsetsController = act.window.insetsController
                    windowInsetsController?.show(android.view.WindowInsets.Type.systemBars())
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
                    onDragEnd = {
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
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
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
                        exoPlayer.playWhenReady = !exoPlayer.isPlaying
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

