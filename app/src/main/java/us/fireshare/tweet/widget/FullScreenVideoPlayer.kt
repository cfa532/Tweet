package us.fireshare.tweet.widget

import android.app.Activity
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId

@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(
    videoMid: MimeiId,
    videoUrl: String,
    onClose: () -> Unit,
    autoPlay: Boolean = true, // Auto-start playback when entering full screen
    enableImmersiveMode: Boolean = true,
    autoReplay: Boolean = true, // Auto-replay when video ends
    onHorizontalSwipe: ((direction: Int) -> Unit)? = null // -1 for left, 1 for right
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var showControls by remember { mutableStateOf(true) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Track if video was playing before full screen
    var wasPlayingBefore by remember { mutableStateOf(false) }
    
    // Track original mute state to restore when exiting full screen
    var originalMuteState by remember { mutableStateOf(false) }
    
    // Reuse the existing video player from VideoManager instead of creating a new one
    val exoPlayer = remember(videoMid) {
        // Get the existing player from VideoManager
        val existingPlayer = VideoManager.getVideoPlayer(context, videoMid, videoUrl)
        wasPlayingBefore = existingPlayer.playWhenReady
        
        // Store the original mute state (volume == 0f means muted)
        originalMuteState = existingPlayer.volume == 0f
        
        existingPlayer
    }

    // Create a single listener that will be properly managed
    val playerListener = remember {
        object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e("FullScreenVideoPlayer - Player error: ${error.message}")
                Timber.e("FullScreenVideoPlayer - Error cause: ${error.cause}")
                Timber.e("FullScreenVideoPlayer - Error code: ${error.errorCode}")
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                Timber.d("FullScreenVideoPlayer - Playback state changed: $playbackState")
                
                when (playbackState) {
                    Player.STATE_READY -> {
                        // Auto-start playback when ready
                        if (autoPlay) {
                            Timber.d("FullScreenVideoPlayer - Starting playback (autoPlay: $autoPlay)")
                            exoPlayer.play()
                        }
                    }
                    Player.STATE_ENDED -> {
                        // Handle video completion - auto-replay if enabled
                        if (autoReplay) {
                            Timber.d("FullScreenVideoPlayer - Video ended, auto-replaying")
                            exoPlayer.seekTo(0)
                            exoPlayer.playWhenReady = true
                            exoPlayer.play()
                        } else {
                            // Notify VideoManager about completion for sequential playback
                            VideoManager.onVideoCompleted(videoMid)
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        Timber.d("FullScreenVideoPlayer - Buffering")
                    }
                    Player.STATE_IDLE -> {
                        Timber.d("FullScreenVideoPlayer - Idle")
                    }
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Timber.d("FullScreenVideoPlayer - Is playing changed: $isPlaying")
                if (autoPlay && !isPlaying) {
                    Timber.d("FullScreenVideoPlayer - Player was paused, restarting...")
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }
            }
        }
    }

    // Add and remove listener properly
    DisposableEffect(exoPlayer) {
        exoPlayer.addListener(playerListener)
        onDispose {
            exoPlayer.removeListener(playerListener)
        }
    }

    // Autoplay when entering full screen - use LaunchedEffect(Unit) to ensure it only runs once
    LaunchedEffect(Unit) {
        // Set volume to 1f (unmuted) for full screen
        exoPlayer.volume = 1f
        
        // Add a longer delay to ensure any external interference has settled
        delay(200)
        
        // Force auto-play regardless of previous state - override VideoManager's reset
        if (autoPlay) {
            Timber.d("FullScreenVideoPlayer - Forcing auto-play (autoPlay: $autoPlay)")
            exoPlayer.playWhenReady = true
            exoPlayer.play()
            
            // Add another delay and check if we need to restart
            delay(100)
            if (!exoPlayer.isPlaying) {
                Timber.d("FullScreenVideoPlayer - Player was paused, restarting...")
                exoPlayer.playWhenReady = true
                exoPlayer.play()
            }
        } else if (wasPlayingBefore) {
            // If auto-play is disabled but video was playing before, restore that state
            Timber.d("FullScreenVideoPlayer - Restoring previous play state (wasPlayingBefore: $wasPlayingBefore)")
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            exoPlayer.playWhenReady = false
        }
    }
    
    // Add a periodic check to ensure the player stays playing if autoPlay is enabled
    // This coroutine will be automatically cancelled when the composable is disposed
    LaunchedEffect(autoPlay) {
        if (autoPlay) {
            while (isActive) {
                delay(500) // Check every 500ms
                if (!exoPlayer.isPlaying && exoPlayer.playbackState == androidx.media3.common.Player.STATE_READY) {
                    Timber.d("FullScreenVideoPlayer - Periodic check: Player not playing, restarting...")
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }
            }
        }
    }

    // Immersive full screen and audio control
    DisposableEffect(Unit) {
        // Unmute video and hide system bars on enter
        exoPlayer.volume = 1f
        if (enableImmersiveMode && activity != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                activity.window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
            }
        }
        onDispose {
            // Don't release the player - let VideoManager handle it
            // Just pause playback when leaving full screen
            exoPlayer.playWhenReady = false
            
            // Restore the original mute state when exiting full screen
            exoPlayer.volume = if (originalMuteState) 0f else 1f
            Timber.d("FullScreenVideoPlayer - Restored original mute state: $originalMuteState")
            
            // Restore system bars on exit
            if (enableImmersiveMode && activity != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.insetsController?.show(WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
        }
    }

    // Auto-hide controls after 1 second
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(1000)
            showControls = false
        }
    }

    // Animate the drag offset for smooth exit animation
    val animatedDragOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec = tween(if (isDragging) 0 else 300),
        label = "dragOffset"
    )

    // Calculate visual effects based on drag
    val maxDragDistance = 800f
    val dragProgress = (animatedDragOffset / maxDragDistance).coerceIn(0f, 1f)
    
    // Translation effect - content moves down with finger
    val translationY = animatedDragOffset * 1.5f // Increased from 0.5f to make it move faster with finger
    
    // Scale effect - content gets slightly smaller as it's dragged
    val scale = 1f - (dragProgress * 0.1f)
    
    // Alpha effect - content fades out as it's dragged
    val alpha = 1f - (dragProgress * 0.3f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        showControls = !showControls
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                    },
                    onDragEnd = {
                        isDragging = false
                        if (dragOffset > 200f) {
                            onClose()
                        } else {
                            dragOffset = 0f
                        }
                    }
                ) { _, dragAmount ->
                    // Handle vertical drag for swipe-down-to-exit
                    if (dragAmount.y > 0 && kotlin.math.abs(dragAmount.y) > kotlin.math.abs(dragAmount.x)) {
                        dragOffset += dragAmount.y
                    }
                    // Handle horizontal drag for pager navigation
                    else if (kotlin.math.abs(dragAmount.x) > kotlin.math.abs(dragAmount.y)) {
                        onHorizontalSwipe?.invoke(if (dragAmount.x > 0) -1 else 1)
                    }
                }
            }
    ) {
        // Video Player with enhanced visual effects
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true // Always enable controller, we'll control visibility manually
                    controllerShowTimeoutMs = 1000
                    controllerAutoShow = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // Set background color to dark gray (Material3 surface equivalent)
                    setBackgroundColor(android.graphics.Color.rgb(28, 28, 30))
                    // Show buffering indicator
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
            update = { playerView ->
                // Update controller visibility based on showControls state
                if (showControls) {
                    playerView.showController()
                } else {
                    playerView.hideController()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    translationY = translationY,
                    scaleX = scale,
                    scaleY = scale,
                    alpha = alpha
                )
        )

        // Close button - only show when controls are visible
        if (showControls) {
            IconButton(
                onClick = {
                    onClose()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .graphicsLayer(
                        translationY = translationY,
                        scaleX = scale,
                        scaleY = scale,
                        alpha = alpha
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