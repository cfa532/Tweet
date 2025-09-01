package us.fireshare.tweet.widget

import android.app.Activity
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
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
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiFileType
import us.fireshare.tweet.service.OrientationManager
import kotlin.math.abs

/**
 * Simple full-screen video player wrapper that uses the unified VideoManager
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
    var dragOffset by remember { mutableFloatStateOf(0f) }
    
    // Add player listener to handle state changes
    DisposableEffect(existingPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        // Only prepare if not already preparing and no error occurred
                        if (!existingPlayer.isLoading) {
                            Timber.d("FullScreenVideoPlayer: Player idle, preparing")
                            existingPlayer.prepare()
                        }
                    }
                    Player.STATE_READY -> {
                        // When ready, start playback
                        Timber.d("FullScreenVideoPlayer: Player ready, starting playback")
                        existingPlayer.playWhenReady = true
                    }
                    Player.STATE_ENDED -> {
                        // If video ends, restart it
                        Timber.d("FullScreenVideoPlayer: Video ended, restarting")
                        existingPlayer.seekTo(0)
                        existingPlayer.playWhenReady = true
                    }
                    Player.STATE_BUFFERING -> {
                        // Video is buffering, this is normal - no action needed
                        Timber.d("FullScreenVideoPlayer: Video is buffering")
                    }
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e("FullScreenVideoPlayer: Player error: ${error.message}")
                // Don't automatically retry on error - let user handle it
                // This prevents endless retry loops
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
                // Allow rotation in full-screen mode
                Timber.d("FullScreenVideoPlayer: Entering full-screen, allowing rotation")
                OrientationManager.allowRotation(act)
                
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
            // Show system bars on exit and restore portrait orientation
            if (enableImmersiveMode) {
                activity?.let { act ->
                    // Restore portrait orientation
                    Timber.d("FullScreenVideoPlayer: Exiting full-screen, locking to portrait")
                    OrientationManager.lockToPortrait(act)
                    
                    val windowInsetsController = act.window.insetsController
                    windowInsetsController?.show(android.view.WindowInsets.Type.systemBars())
                }
            }
        }
    }

    // Handle configuration changes to keep video playing during rotation
    DisposableEffect(Unit) {
        val configurationChangeListener = object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                Timber.d("FullScreenVideoPlayer: Configuration changed, ensuring video continues playing")
                // Only resume if player is ready and was playing before
                if (existingPlayer.playbackState == Player.STATE_READY) {
                    existingPlayer.playWhenReady = true
                }
            }
            
            override fun onLowMemory() {
                // Handle low memory if needed
            }
            
            override fun onTrimMemory(level: Int) {
                // Handle memory trimming if needed
            }
        }
        
        context.registerComponentCallbacks(configurationChangeListener)
        
        onDispose {
            context.unregisterComponentCallbacks(configurationChangeListener)
        }
    }

    // Start playback and unmute when entering full screen - only run once
    LaunchedEffect(Unit) {
        // Set volume and start playback
        try {
            existingPlayer.volume = 1.0f // Unmute video
        } catch (e: Exception) {
            Timber.e("FullScreenVideoPlayer - Error setting volume: ${e.message}")
        }
        
        // Only prepare if player is idle and not already loading
        if (existingPlayer.playbackState == Player.STATE_IDLE && !existingPlayer.isLoading) {
            Timber.d("FullScreenVideoPlayer: Initial preparation")
            existingPlayer.prepare()
        }
        
        // Start playback if ready
        if (existingPlayer.playbackState == Player.STATE_READY) {
            existingPlayer.playWhenReady = true
        }
    }

    // Full screen video player UI - let ExoPlayer handle its own controls completely
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
    ) {
        // Video player view with native controls - NO interference
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = existingPlayer
                    useController = true // Use native controls
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // Let ExoPlayer handle its own control visibility
                    controllerShowTimeoutMs = 2000 // Auto-hide after 2 seconds
                    controllerHideOnTouch = true // Hide when tapping outside controls
                    // Start with controls hidden
                    hideController()
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
                }
        )

        // No close button overlay - let native controls handle everything
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
    var showCloseButton by remember { mutableStateOf(false) } // Start with close button hidden
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // Get the dedicated full screen player
    val exoPlayer = remember {
        VideoManager.getFullScreenPlayer(context)
    }

    // Load video into the full screen player
    LaunchedEffect(videoUrl) {
        VideoManager.loadVideo(context, videoUrl)
    }

    // Start playback when entering full screen
    LaunchedEffect(Unit) {
        // Set volume to 1f (unmuted) for full screen
        try {
            exoPlayer.volume = 1f
        } catch (e: Exception) {
            Timber.e("FullScreenVideoPlayer - Error setting volume: ${e.message}")
        }

        // Start playback with auto-replay
        VideoManager.startPlayback(autoReplay)

        // Auto-play the video
        exoPlayer.playWhenReady = true
    }

    // Immersive full screen and orientation handling
    DisposableEffect(Unit) {
        // Hide system bars on enter
        if (enableImmersiveMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.let { act ->
                // Allow rotation in full-screen mode
                Timber.d("FullScreenVideoPlayer (API 30+): Entering full-screen, allowing rotation")
                OrientationManager.allowRotation(act)

                val windowInsetsController = act.window.insetsController
                windowInsetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }

        onDispose {
            // Show system bars on exit and restore portrait orientation
            if (enableImmersiveMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity?.let { act ->
                    // Restore portrait orientation
                    Timber.d("FullScreenVideoPlayer (API 30+): Exiting full-screen, locking to portrait")
                    OrientationManager.lockToPortrait(act)

                    val windowInsetsController = act.window.insetsController
                    windowInsetsController?.show(android.view.WindowInsets.Type.systemBars())
                }
            }
        }
    }

    // Handle configuration changes to keep video playing during rotation
    DisposableEffect(Unit) {
        val configurationChangeListener = object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                Timber.d("FullScreenVideoPlayer (API 30+): Configuration changed, ensuring video continues playing")
                // Ensure video continues playing after configuration change
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    exoPlayer.playWhenReady = true
                } else if (exoPlayer.playbackState == Player.STATE_IDLE) {
                    // If player is idle, prepare it again
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            }

            override fun onLowMemory() {
                // Handle low memory if needed
            }

            override fun onTrimMemory(level: Int) {
                // Handle memory trimming if needed
            }
        }

        context.registerComponentCallbacks(configurationChangeListener)

        onDispose {
            context.unregisterComponentCallbacks(configurationChangeListener)
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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            Timber.d("FullScreenVideoPlayer (API 30+): AndroidView tapped, toggling controls")
                            showControls = !showControls
                            showCloseButton = !showCloseButton
                        }
                    )
                } // Toggle controls and close button on tap
        )

        // Custom controls overlay
        if (showControls) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                Timber.d("FullScreenVideoPlayer (API 30+): Controls overlay tapped, toggling controls")
                                showControls = !showControls
                                showCloseButton = !showCloseButton
                            }
                        )
                    }
            ) {
                // Close button
                if (showCloseButton) {
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
                        contentDescription = if (exoPlayer.isPlaying) stringResource(R.string.pause) else stringResource(
                            R.string.play
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // Auto-hide controls and close button after 2 seconds
        LaunchedEffect(showControls, showCloseButton) {
            if (showControls || showCloseButton) {
                delay(2000)
                showControls = false
                showCloseButton = false
            }

        }
    }
}



