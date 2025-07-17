package us.fireshare.tweet.widget

import android.app.Activity
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.R
import us.fireshare.tweet.datamodel.MimeiId

@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayer(
    videoMid: MimeiId,
    videoUrl: String,
    onClose: () -> Unit,
    enableImmersiveMode: Boolean = true,
    autoPlay: Boolean = true,
    onHorizontalSwipe: ((direction: Int) -> Unit)? = null // -1 for left, 1 for right
) {
    Timber.d("FullScreenVideoPlayer - Composable called with videoMid: $videoMid, videoUrl: $videoUrl")
    val context = LocalContext.current
    val activity = context as? Activity
    var isMuted by remember { mutableStateOf(false) } // Always unmute in full screen
    var showControls by remember { mutableStateOf(true) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Get the existing player from VideoManager
    val exoPlayer = remember(videoMid) {
        Timber.d("FullScreenVideoPlayer - Getting ExoPlayer from VideoManager for video: $videoMid")
        val player = VideoManager.getVideoPlayer(context, videoMid, videoUrl)
        Timber.d("FullScreenVideoPlayer - Player obtained: ${player != null}")
        Timber.d("FullScreenVideoPlayer - Player state: ${player.playbackState}, isPlaying: ${player.isPlaying}")
        player
    }

    // Autoplay when entering full screen
    LaunchedEffect(exoPlayer) {
        Timber.d("FullScreenVideoPlayer - Starting autoplay for video: $videoMid, autoPlay: $autoPlay")
        Timber.d("FullScreenVideoPlayer - Video URL: $videoUrl")
        // Mark video as active in VideoManager
        VideoManager.markVideoActive(videoMid)
        if (autoPlay) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            exoPlayer.playWhenReady = false
        }
        
        // Add error listener to catch source errors
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e("FullScreenVideoPlayer - Player error: ${error.message}")
                Timber.e("FullScreenVideoPlayer - Error cause: ${error.cause}")
                Timber.e("FullScreenVideoPlayer - Error code: ${error.errorCode}")
            }
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                    androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                    androidx.media3.common.Player.STATE_READY -> "READY"
                    androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                Timber.d("FullScreenVideoPlayer - Playback state changed to: $stateName")
            }
        })
    }

    // Immersive full screen and audio control
    DisposableEffect(Unit) {
        // Unmute video and hide system bars on enter
        exoPlayer.volume = 1f
        Timber.d("FullScreenVideoPlayer - Unmuted video and set volume to 1.0")
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
            // Mute video and restore system bars on exit
            exoPlayer.volume = 0f
            Timber.d("FullScreenVideoPlayer - Muted video on exit")
            // Mark video as inactive in VideoManager
            VideoManager.markVideoInactive(videoMid)
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
    val translationY = animatedDragOffset * 0.5f
    
    // Scale effect - content gets slightly smaller as it's dragged
    val scale = 1f - (dragProgress * 0.1f)
    
    // Alpha effect - content fades out as it's dragged
    val alpha = 1f - (dragProgress * 0.3f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                            Timber.d("FullScreenVideoPlayer - Swipe down detected, closing player")
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
                Timber.d("FullScreenVideoPlayer - Creating PlayerView")
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true // Always enable controller, we'll control visibility manually
                    controllerShowTimeoutMs = 1000
                    controllerAutoShow = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    Timber.d("FullScreenVideoPlayer - PlayerView created with player: ${player != null}")
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
                    Timber.d("FullScreenVideoPlayer - Close button tapped")
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
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
} 