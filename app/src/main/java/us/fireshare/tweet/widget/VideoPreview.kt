package us.fireshare.tweet.widget

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import us.fireshare.tweet.HproseInstance.preferenceHelper
import us.fireshare.tweet.datamodel.MediaType
import us.fireshare.tweet.datamodel.MimeiId
import us.fireshare.tweet.widget.Gadget.isElementVisible

/**
 * @param index: when there are multiple videos in a grid, the first one is played automatically.
 * @param inPreviewGrid: If the video is previewed in a Grid as part of tweet item in a list.
 *                       The aspect ratio shall be 1:1, otherwise use the video's real aspectRatio.
 * @param callback: callback function to be performed when video is closed.
 * **/
@OptIn(UnstableApi::class)
@Composable
fun VideoPreview(
    url: String,
    modifier: Modifier,
    index: Int,
    autoPlay: Boolean = false,
    inPreviewGrid: Boolean = true,
    callback: (Int) -> Unit,
    videoMid: MimeiId? = null,
    videoType: MediaType? = null,
    onLoadComplete: (() -> Unit)? = null,
    onVideoCompleted: (() -> Unit)? = null
) {
    val context = LocalContext.current

    // Use completely stable state that doesn't change during recompositions
    var isVideoVisible by remember(videoMid) { mutableStateOf(false) }
    var isMuted by remember(videoMid) { mutableStateOf(preferenceHelper.getSpeakerMute()) }
    var isLoading by remember(videoMid) {
        mutableStateOf(videoMid?.let { !VideoManager.isVideoPreloaded(it) } ?: true)
    }
    var hasError by remember(videoMid) { mutableStateOf(false) }
    var showTimeLabel by remember(videoMid) { mutableStateOf(false) }
    var remainingTime by remember(videoMid) { mutableLongStateOf(0L) }
    var retryCount by remember(videoMid) { mutableIntStateOf(0) }
    val maxRetries = 3

    // Use VideoLoadingManager to track visibility and manage loading
    videoMid?.let { mid ->
        rememberVideoLoadingManager(
            videoMid = mid,
            isVisible = isVideoVisible
        )
    }

    // Use videoMid as the only key to prevent ExoPlayer recreation
    val exoPlayer = remember(videoMid) {
        val player = if (videoMid != null) {
            VideoManager.getVideoPlayer(context, videoMid, url, videoType)
        } else {
            createExoPlayer(context, url, videoType ?: MediaType.Video)
        }

        // Explicitly disable repeat mode to prevent auto-replay
        player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF

        player
    }

    // Video preloading is handled by parent components (ChatScreen, MediaItemView, etc.)
    // This prevents conflicts and race conditions

    /**
     * Stop playing when screen is locked or closed. Resume play when unlocked.
     * Keep ExoPlayer in memory for better user experience.
     * */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        // Do not play video by default.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    // Pause video playback but keep player in memory
                    exoPlayer.playWhenReady = false
                }

                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> {
                    // Resume video playback if it was playing before
                    if (isVideoVisible && autoPlay) {
                        exoPlayer.playWhenReady = true
                    }
                }

                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Mark video as inactive in VideoManager instead of releasing
            videoMid?.let { mid ->
                VideoManager.markVideoInactive(mid)
            }
        }
    }

    LaunchedEffect(isVideoVisible) {
        if (isVideoVisible) {
            // Mark video as active in VideoManager
            videoMid?.let { mid ->
                VideoManager.markVideoActive(mid)
            }

            // Ensure repeat mode is disabled
            exoPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF

            // Handle different player states properly to avoid stuck states
            when (exoPlayer.playbackState) {
                androidx.media3.common.Player.STATE_READY -> {
                    // Player is ready, just start playing if needed
                    exoPlayer.playWhenReady = autoPlay
                    isLoading = false
                }
                androidx.media3.common.Player.STATE_IDLE -> {
                    // Player is idle, prepare it first
                    exoPlayer.prepare()
                    // Don't set playWhenReady yet, wait for STATE_READY
                }
                androidx.media3.common.Player.STATE_BUFFERING -> {
                    // Player is buffering, just set play state
                    exoPlayer.playWhenReady = autoPlay
                }
                androidx.media3.common.Player.STATE_ENDED -> {
                    // Video ended, seek to beginning and start if needed
                    exoPlayer.seekTo(0)
                    exoPlayer.playWhenReady = autoPlay
                }
                else -> {
                    // For other states, just set play state
                    exoPlayer.playWhenReady = autoPlay
                }
            }
        } else {
            // Only pause if this is the only active instance of this video
            // Don't pause if the video is being used in full screen
            videoMid?.let { mid ->
                val activeCount = VideoManager.getVideoActiveCount(mid)
                val isInFullScreen = VideoManager.isVideoInFullScreen(mid)
                if (activeCount <= 1 && !isInFullScreen) {
                    exoPlayer.playWhenReady = false
                }
            } ?: run {
                // If no videoMid, this is a standalone player, so pause it
                exoPlayer.playWhenReady = false
            }
        }
    }

    // React to changes in autoPlay while visible (for sequential playback)
    LaunchedEffect(autoPlay, isVideoVisible) {
        if (!isVideoVisible) return@LaunchedEffect
        try {
            // Always keep repeat mode off for previews
            exoPlayer.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
            if (autoPlay) {
                if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
                exoPlayer.playWhenReady = true
            } else {
                exoPlayer.playWhenReady = false
            }
        } catch (_: Exception) {
        }
    }

    // Note: Video loading issue monitoring removed as modern memory management
    // relies on system memory warnings and automatic cleanup

    LaunchedEffect(isMuted) {
        try {
            exoPlayer.volume = if (isMuted) 0f else 1f
            // Persist mute state to preferences
            preferenceHelper.setSpeakerMute(isMuted)
        } catch (e: Exception) {
            Timber.e("VideoPreview - Error setting volume: ${e.message}")
        }
    }

    // Watch for global mute state changes only when visible, at a relaxed cadence
    LaunchedEffect(isVideoVisible) {
        if (!isVideoVisible) return@LaunchedEffect
        while (isVideoVisible) {
            val globalMuteState = preferenceHelper.getSpeakerMute()
            if (isMuted != globalMuteState) {
                isMuted = globalMuteState
            }
            delay(500) // Check every 500ms while visible
        }
    }

    // Show time label when video starts playing and auto-hide after 3 seconds
    LaunchedEffect(exoPlayer.isPlaying) {
        if (exoPlayer.isPlaying) {
            showTimeLabel = true
            delay(3000)
            showTimeLabel = false
        }
    }

    // Update remaining time every second when time label is shown
    LaunchedEffect(showTimeLabel) {
        while (showTimeLabel) {
            remainingTime = exoPlayer.duration - exoPlayer.currentPosition
            delay(1000)
        }
    }

    // Create a single listener that will be properly managed
    val playerListener = remember {
        object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    androidx.media3.common.Player.STATE_READY -> {
                        isLoading = false
                        onLoadComplete?.invoke()
                    }

                    androidx.media3.common.Player.STATE_BUFFERING -> {
                        // Only show loading if not already cached/preloaded
                        if (videoMid != null && !VideoManager.isVideoPreloaded(videoMid)) {
                            isLoading = true
                        }
                    }

                    androidx.media3.common.Player.STATE_ENDED -> {
                        isLoading = false
                        // Ensure video doesn't restart by setting playWhenReady to false
                        exoPlayer.playWhenReady = false
                        videoMid?.let { mid ->
                            VideoManager.onVideoCompleted(mid)
                        }
                        // Call the completion callback for sequential playback
                        onVideoCompleted?.invoke()
                    }

                    androidx.media3.common.Player.STATE_IDLE -> {
                        // Only show loading if not already cached/preloaded
                        if (videoMid != null && !VideoManager.isVideoPreloaded(videoMid)) {
                            isLoading = true
                        }
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.tag("VideoPreview").e("Video loading error for $videoMid: ${error.message}")
                
                // Check if this is a recoverable error
                val isRecoverableError = error.cause?.message?.contains("network", ignoreCase = true) == true ||
                        error.cause?.message?.contains("timeout", ignoreCase = true) == true ||
                        error.cause?.message?.contains("connection", ignoreCase = true) == true ||
                        error.cause?.message?.contains("server", ignoreCase = true) == true
                
                // Only retry for recoverable errors and if we haven't exceeded max retries
                if (isRecoverableError && retryCount < maxRetries && videoMid != null) {
                    retryCount++
                    Timber.tag("VideoPreview").d("Attempting automatic retry $retryCount/$maxRetries for video: $videoMid")
                    
                    // Keep loading state during retry
                    isLoading = true
                    hasError = false
                    
                    // Retry after a short delay
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        delay(2000) // Wait 2 seconds before retry
                        if (isLoading && !hasError) {
                            VideoManager.attemptVideoRecovery(context, videoMid, url)
                        }
                    }
                } else {
                    // For non-recoverable errors or after max retries, show error state
                    isLoading = false
                    hasError = true
                    Timber.tag("VideoPreview").e("Final error for video: $videoMid - ${error.message} (retries: $retryCount, recoverable: $isRecoverableError)")
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

    // When previewing a single video, limit its height to show more content.
    Box(
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant) // Material3 surface variant for loading background
            .onGloballyPositioned { layoutCoordinates ->
                val newVisibility = isElementVisible(layoutCoordinates)
                if (isVideoVisible != newVisibility) {
                    isVideoVisible = newVisibility
                }
            }
            .clickable {
                // Auto-start video in full screen
                callback(index)
            }
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = false // No controls in preview mode
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    // Set background color to light gray (Material3 surface variant equivalent)
                    setBackgroundColor(android.graphics.Color.rgb(245, 245, 245))
                    // Keep last frame to avoid black flashes when resetting/pausing
                    setKeepContentOnPlayerReset(true)
                    // Only show buffering indicator when playing and buffering
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    // Force hardware acceleration and proper clipping for Media3 1.7.1
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds() // Ensure content is clipped to bounds
        )

        // Show loading indicator when video is loading
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        // Show error state when video fails to load
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = "Video Error",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Video unavailable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Show retry button for manual retry attempts
                    if (videoMid != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                retryCount++
                                hasError = false
                                isLoading = true
                                
                                Timber.tag("VideoPreview").d("Manual retry attempt $retryCount for video: $videoMid")
                                
                                // Attempt recovery on the main thread (required for ExoPlayer)
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    try {
                                        // Try gentle recovery first
                                        val success = VideoManager.attemptVideoRecovery(context, videoMid, url)
                                        if (!success) {
                                            // If gentle recovery failed, try a more thorough reset
                                            exoPlayer.stop()
                                            exoPlayer.clearMediaItems()
                                            
                                            // Wait a moment for cleanup
                                            delay(500)
                                            
                                            // Try recovery again after reset
                                            val retrySuccess = VideoManager.attemptVideoRecovery(context, videoMid, url)
                                            if (!retrySuccess) {
                                                // If recovery failed, show error again
                                                hasError = true
                                                isLoading = false
                                                Timber.tag("VideoPreview").w("Manual retry failed for video: $videoMid")
                                            } else {
                                                // Ensure playback resumes if visible and allowed
                                                if (isVideoVisible && autoPlay) {
                                                    exoPlayer.playWhenReady = true
                                                }
                                                Timber.tag("VideoPreview").d("Manual retry successful for video: $videoMid")
                                            }
                                        } else {
                                            // Gentle recovery succeeded
                                            if (isVideoVisible && autoPlay) {
                                                exoPlayer.playWhenReady = true
                                            }
                                            Timber.tag("VideoPreview").d("Manual retry successful for video: $videoMid")
                                        }
                                    } catch (e: Exception) {
                                        // Only log retry failures at debug level to avoid noise
                                        Timber.d("VideoPreview - Manual retry failed: ${e.message}")
                                        hasError = true
                                        isLoading = false
                                    }
                                }
                            },
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = if (retryCount > 0) "Retry Again" else "Retry",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        // Show retry count information
                        if (retryCount > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Attempts: $retryCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // Mute button in lower right corner
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.2f),
                        shape = CircleShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isMuted = !isMuted
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Time label in lower left corner
        if (showTimeLabel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = formatTime(remainingTime),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp
                    )
                }
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
